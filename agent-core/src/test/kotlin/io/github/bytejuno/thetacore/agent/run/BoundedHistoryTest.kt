package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.asFlow
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.net.URLClassLoader
import kotlin.test.*

class BoundedHistoryTest {
    private fun user(s: String) = message(Role.USER, textPart(s))
    @Test fun lazySnapshotPreservesBoundariesDigestAndOriginalPaging() {
        TestRunHistory.create(Files.createTempDirectory("bounded-history"), "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            h.append(0, listOf(user("before")))
            val binding = callBinding("p", "req", "resp", 0, "wire")
            h.append(1, listOf(message(Role.ASSISTANT, toolCallPart(binding, "tool", "{}")),
                message(Role.TOOL, toolResultPart(binding.callId, "result", false))))
            h.append(2, listOf(user("after")))
            val view = h.runSnapshot()
            assertEquals(sourceDigest(h.snapshot().messages), sourceDigest(view.messages))
            validateHistory(view.messages)
            h.append(3, listOf(user("new")))
            assertEquals(4, view.messages.size)
            assertEquals("before", (view.messages[0].parts.single() as ContentPart.Text).text)
            assertEquals("after", (view.messages[3].parts.single() as ContentPart.Text).text)
            val page = h.page(h.recent(2).older, 1)
            assertEquals(listOf(Role.ASSISTANT, Role.TOOL), page.messages.map { it.role })
            assertEquals(5, h.snapshot().messages.size)
        }
    }
    @Test fun memoryLimitPausesWithoutSubmittingOrTruncating() = runBlocking {
        TestRunHistory.create(Files.createTempDirectory("bounded-pause"), "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            repeat(20) { h.append(it.toLong(), listOf(user("$it".padEnd(1024, 'x')))) }
            val f = RunFixture(); val provider = ScriptProvider(listOf(answer()))
            val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                ModelTarget("provider", provider, Credentials { "test" }), f.request(), h,
                LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                contextPolicy = ContextPolicy("goal"), requestViewLimit = RequestViewLimit(10, 2048)).run()
            assertEquals(RunState.PAUSED, result.state); assertTrue(provider.requests.isEmpty())
            assertEquals("BUDGET", h.loadCheckpoint()!!.pauseClass)
            assertEquals(20, h.snapshot().messages.size)
        }
    }
    @Test fun summaryViewCanRunWithinMemoryCeilingAndKeepsRawHistory() = runBlocking {
        TestRunHistory.create(Files.createTempDirectory("bounded-summary"), "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            repeat(8) { h.append(it.toLong(), listOf(user("old-$it".padEnd(300, 'x')))) }
            h.append(8, listOf(user("goal")))
            val f = RunFixture(); val provider = ScriptProvider(listOf(answer()))
            val summarizer = ScriptProvider(listOf(answer("old work completed")))
            val cost = ContextBudget({ r -> r.messages.sumOf { m -> m.parts.sumOf { (it as ContentPart.Text).text.length.toLong() } } }, { 10000 })
            val summary = SummaryModel(ModelTarget("summary", summarizer, Credentials { "test" }),
                chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100 }, cost)
            val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                ModelTarget("provider", provider, Credentials { "test" }), f.request(), h,
                LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                budget = cost, contextPolicy = ContextPolicy("goal", 8, summary),
                requestViewLimit = RequestViewLimit(4, 2048)).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertEquals(2, provider.requests.single().messages.size)
            assertEquals(10, h.snapshot().messages.size)
            assertEquals(8, h.loadView()!!.sourceCount)
        }
    }
    @Test fun oversizedLegacyTransactionPausesBeforeRuntimeDecode() = runBlocking {
        TestRunHistory.create(Files.createTempDirectory("bounded-legacy"), "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            h.append(0, listOf(user("x".repeat(MAX_RUNTIME_BATCH_BYTES.toInt()))))
            val before = h.io().messagesDecoded
            val provider = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, RunFixture().loop(provider, history = h).run().state)
            assertEquals(before, h.io().messagesDecoded)
            assertTrue(provider.requests.isEmpty()); assertEquals(1, h.recent(1).messages.size)
        }
    }
    @Test fun corruptedOldBatchStillFailsClosed() {
        val path = Files.createTempDirectory("bounded-corrupt")
        TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use {
            it.append(0, listOf(user("old"))); it.append(1, listOf(user("new")))
        }
        TestRunHistory.corrupt(path, "batches/1")
        TestRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { assertFails { it.runSnapshot() } }
    }
    @Test fun exportMeasurementClasspath() {
        val classes = listOf(BoundedHistoryMeasurement::class.java, SqliteRunHistory::class.java,
            kotlin.Unit::class.java, androidx.sqlite.SQLiteDriver::class.java,
            androidx.sqlite.driver.bundled.BundledSQLiteDriver::class.java, kotlinx.serialization.json.Json::class.java,
            kotlinx.serialization.SerializationException::class.java, kotlinx.coroutines.Job::class.java)
        val cp = (classes.map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath } +
            generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).absolutePath }.toList()).distinct().joinToString(File.pathSeparator)
        println("ISSUE63_CLASSPATH=$cp")
    }
}

/** Run separately with -Xmx96m. Heap samples are JVM observations, not Android/RSS claims. */
object BoundedHistoryMeasurement {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val n = args[0].toInt()
        val path = Files.createTempDirectory("history-memory-$n")
        val started = System.nanoTime()
        TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            repeat(n) { i -> h.append(i.toLong(), listOf(message(Role.USER, textPart(i.toString().padEnd(4096, 'x'))))) }
        }
        System.gc()
        val bean = java.lang.management.ManagementFactory.getMemoryMXBean()
        val baseline = bean.heapMemoryUsage.used
        var peak = baseline
        TestRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
            h.afterReadChunk = { peak = maxOf(peak, bean.heapMemoryUsage.used) }
            val f = RunFixture()
            var requests = 0
            val p = object : ModelProvider {
                override fun chat(request: ChatRequest, auth: Credentials): kotlinx.coroutines.flow.Flow<ModelEvent> {
                    requests++; return answer().asFlow()
                }
            }
            val r = AgentRunLoop(RunIdentity("chain", "session", "run"), ModelTarget("provider", p, Credentials { "test" }),
                f.request(), h, LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                contextPolicy = ContextPolicy("goal")).run()
            check(r.state == RunState.PAUSED && requests == 0)
            check(h.loadCheckpoint()!!.pauseClass == "BUDGET")
            var count = 0; var cursor: HistoryCursor? = null
            do { val page = h.page(cursor, 50); count += page.messages.size; cursor = page.older } while (cursor != null)
            check(count == n)
            System.gc()
            println("ISSUE63_MEMORY messages=$n payloadChars=${n.toLong()*4096} maxHeap=${Runtime.getRuntime().maxMemory()} baselineHeap=$baseline sampledPeakHeap=$peak retainedHeap=${bean.heapMemoryUsage.used} pagesRecovered=$count modelRequests=$requests seconds=${(System.nanoTime()-started)/1e9}")
        }
    }
}
