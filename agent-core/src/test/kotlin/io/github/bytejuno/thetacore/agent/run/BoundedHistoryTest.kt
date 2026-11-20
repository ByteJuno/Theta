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
    @Test fun oversizedLatestTransactionReopensWithoutDecodingAndKeepsOriginal() = runBlocking {
        val path = Files.createTempDirectory("bounded-reopen")
        val original = "x".repeat(MAX_RUNTIME_BATCH_BYTES.toInt())
        TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use {
            it.append(0, listOf(user(original)))
        }
        TestRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
            assertEquals(0, h.io().messagesDecoded)
            val provider = ScriptProvider(listOf(answer()))
            val result = RunFixture().loop(provider, history = h).run()
            assertEquals(RunState.PAUSED, result.state)
            assertTrue(result.reason.contains("memory budget"))
            assertEquals(0, h.io().messagesDecoded)
            assertTrue(provider.requests.isEmpty())
            assertEquals(original, (h.recent(1).messages.single().parts.single() as ContentPart.Text).text)
        }
        TestRunHistory.corrupt(path, "batches/1")
        assertFailsWith<HistoryStorageException> { TestRunHistory.open(path, Durability.PROCESS_CRASH_ONLY) }
        Unit
    }

    @Test fun fourLargeTransactionsRespectIndependentSummaryMemoryBudget() = runBlocking {
        val path = Files.createTempDirectory("bounded-large-summary")
        TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            repeat(4) { h.append(it.toLong(), listOf(user("$it".padEnd(700_000, 'x')))) }
            h.append(4, listOf(user("goal")))
            val f = RunFixture(); val provider = ScriptProvider(listOf(answer()))
            val summarizer = ScriptProvider(List(20) { answer("completed work retained") })
            val cost = ContextBudget({ r -> r.messages.sumOf { m -> m.parts.sumOf { (it as ContentPart.Text).text.length.toLong() } } }, { 20_000_000 })
            val summary = SummaryModel(ModelTarget("summary", summarizer, Credentials { "test" }),
                chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100 }, cost)
            val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                ModelTarget("provider", provider, Credentials { "test" }), f.request(), h,
                LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                budget = cost, contextPolicy = ContextPolicy("goal", 4, summary)).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertTrue(summarizer.requests.size >= 3) // multiple map calls plus a reduction
            summarizer.requests.forEach { request ->
                assertTrue(summary.memoryLimit.admits(request.messages))
                assertTrue(cost.tokens(request)!! <= cost.limit(request.model)!!)
            }
            for (i in 0..3) assertEquals("$i".padEnd(700_000, 'x'),
                (h.snapshot().messages[i].parts.single() as ContentPart.Text).text)
        }
    }

    @Test fun summaryFragmentsChargePromptReferencesAndReduceOverhead() = runBlocking {
        TestRunHistory.create(Files.createTempDirectory("bounded-fragments"), "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            h.append(0, listOf(user("x".repeat(5000))))
            val provider = ScriptProvider(List(100) { answer("saved") })
            val limit = RequestViewLimit(8, 1400)
            val cost = ContextBudget({ r -> (r.system?.length ?: 0).toLong() +
                r.messages.sumOf { m -> m.parts.sumOf { (it as ContentPart.Text).text.length.toLong() } } }, { 1300 })
            val model = SummaryModel(ModelTarget("summary", provider, Credentials { "test" }),
                chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100; system = "policy".repeat(30) }, cost, limit)
            val compacted = ContextSummarizer(model, RunFixture().journal, EventTimeouts(1000, 1000), h::createSummarySpool) { true }
                .compact(h.runSnapshot().messages, "session/reference")
            assertNotNull(compacted)
            assertTrue(provider.requests.size > 2)
            assertTrue(provider.requests.any { (it.messages.single().parts.single() as ContentPart.Text).text.contains("UTF-16 offset") })
            provider.requests.forEach {
                val chars = (it.messages.single().parts.single() as ContentPart.Text).text.length.toLong() +
                    128 + (it.system?.length ?: 0) + it.model.length
                assertTrue(chars <= limit.maxChars)
                assertTrue(cost.tokens(it)!! <= cost.limit(it.model)!!)
            }
            assertEquals(5000, (h.recent(1).messages.single().parts.single() as ContentPart.Text).text.length)
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

    @Test fun repeatedCheckpointOperationsAtImmutableRevisionAvoidFullRescans() {
        val path = Files.createTempDirectory("bounded-checkpoint-cache")
        TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            repeat(20) { h.append(it.toLong(), listOf(user("msg-$it".padEnd(1024, 'x')))) }
            val source = h.runSnapshot()
            val key = settingsKey("provider", RunFixture().request())
            val digest = h.verifiedSourceDigest(source, source.messages.size)
            val saved = ContextCheckpoint(id = "cp-1", sessionId = "session", taskChainId = "chain", runId = "run",
                nextRunId = null, unattended = false, sourceRevision = source.revision, sourceCount = source.messages.size,
                sourceDigest = digest, settingsKey = key, goal = "goal", steps = 1, retries = 0, kind = "PAUSED",
                summary = null, reason = "budget", serverUnresolved = false, ledger = emptyList(), catalog = emptyList(),
                pauseClass = "BUDGET")
            h.saveCheckpoint(saved)
            assertEquals("cp-1", h.checkpointInfo()!!.id)
            val decodedAfterWarmup = h.io().messagesDecoded
            repeat(5) {
                val expectedId = if (it == 0) "cp-1" else "cp-${it + 1}"
                assertEquals(expectedId, h.checkpointInfo()!!.id)
                h.saveCheckpoint(saved.copy(id = "cp-${it + 2}", steps = it + 2))
            }
            assertEquals(decodedAfterWarmup, h.io().messagesDecoded)
            h.append(h.revision(), listOf(user("newer")))
            assertNull(h.checkpointInfo())
        }
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
