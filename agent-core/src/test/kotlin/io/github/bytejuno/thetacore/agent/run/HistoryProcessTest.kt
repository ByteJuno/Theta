package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** A separate JVM is killed by the parent while holding the actual file locks. No shutdown hooks,
 * close(), catch/finally cleanup, or simulated exceptions execute in the child after the checkpoint. */
object HistoryCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Paths.get(args[0])
        val file = args[1]
        val stage = HistoryWriteStage.valueOf(args[2])
        val fault: (HistoryWritePoint) -> Unit = { point ->
            if ((point.file == file || file == "calls/" && point.file.startsWith(file)) && point.stage == stage) {
                println("KILL_READY"); System.out.flush(); CountDownLatch(1).await()
            }
        }
        val id = UUID.randomUUID().toString()
        val assistant = message(Role.ASSISTANT,
            toolCallPart(callBinding("p", "req", "resp", 0, "wire", id), "tool", "{}"))
        FileRunHistory.open(directory, Durability.PROCESS_CRASH_ONLY, fault).use { history ->
            if (file == "pending.json") {
                history.prepareStep(1, RunIdentity("chain", "session", "run"), "step", assistant)
            } else {
                // Persist actual RUNNING WRITE state, then recover it before the history write.
                // If killed before HEAD, pending intent survives and the new parent reconstructs LOST.
                val ledgerDir = directory.resolve("ledger")
                JsonInvocationStore.create(ledgerDir, Durability.PROCESS_CRASH_ONLY).use { store ->
                    val d = testDefinition(risk = Risk.WRITE)
                    val gate = DefinitionGate().apply { install(d); freezeRun("run", listOf(d)) }
                    val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                    ledger.recoverColdStart("child-old")
                    history.prepareStep(1, RunIdentity("chain", "session", "run"), "step", assistant)
                    val call = assistant.parts.single() as ContentPart.ToolCall
                    ledger.recordInvocation(InvocationInput(BindingValue.copyOf(call.binding), "chain", "session", "run", "step", 0,
                        "{}", d, false, Risk.WRITE, false, "tool"))
                    ledger.validateAndPrepare(id)
                    ledger.resolveApproval(id, ledger.get(id)!!.pending!!, Decision.ALLOW_ONCE)
                    check(ledger.claimExecution(id, ledger.get(id)!!.revision) is Mutation.Applied)
                    // Logical cancellation is UNKNOWN and instance exit remains unconfirmed.
                    ledger.cancel(id)
                    val result = requireNotNull(ledger.resultsForStep("session", "run", "step", listOf(id)))
                    history.append(1, listOf(assistant, result))
                }
            }
        }
        error("Requested kill checkpoint not reached")
    }
}

/** Reports whether a genuinely separate process can become a writer of the session. */
object HistoryLockChild {
    @JvmStatic fun main(args: Array<String>) {
        val opened = runCatching { FileRunHistory.open(Paths.get(args[0]), Durability.PROCESS_CRASH_ONLY).close() }.isSuccess
        println(if (opened) "OPENED" else "BLOCKED")
    }
}

class HistoryProcessTest {
    // Gradle 9 may use a non-URL application loader. Code sources work with both loaders.
    private val classpath get() = (listOf(HistoryCrashChild::class.java, HistoryLockChild::class.java, FileRunHistory::class.java,
        kotlin.Unit::class.java, kotlinx.serialization.json.Json::class.java,
        kotlinx.serialization.SerializationException::class.java, kotlinx.coroutines.Job::class.java)
        .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath } +
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).absolutePath }.toList())
        .distinct().joinToString(File.pathSeparator)
    private val java get() = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")

    private fun otherProcessCanOpen(path: java.nio.file.Path): Boolean {
        val child = ProcessBuilder(java.absolutePath, "-cp", classpath, HistoryLockChild::class.java.name, path.toString())
            .redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText().trim()
        assertTrue(child.waitFor(30, TimeUnit.SECONDS))
        assertTrue(output == "OPENED" || output == "BLOCKED", output)
        return output == "OPENED"
    }

    @Test fun rejectedSameProcessOpenKeepsTheWriterLockForOtherProcesses() {
        val path = Files.createTempDirectory("history-lock")
        FileRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            assertFalse(otherProcessCanOpen(path))
            assertFailsWith<HistoryStorageException> { FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY) }
            assertFalse(otherProcessCanOpen(path), "a rejected duplicate must not release the OS lock")
            assertEquals(0, h.revision())
        }
        assertTrue(otherProcessCanOpen(path))
        FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).close()
    }

    @Test fun actualSubprocessKillAtEveryWriteStageRecoversCoherentHistoryAndLedger() {
        assertTrue(classpath.isNotBlank())
        var killed = 0
        for (file in listOf("pending.json", "batches/2.json", "calls/", "head.json")) {
            for (stage in HistoryWriteStage.entries) {
                val path = Files.createTempDirectory("history-real-kill")
                FileRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use {
                    it.append(0, listOf(message(Role.USER, textPart("base"))))
                }
                val child = ProcessBuilder(java.absolutePath, "-cp", classpath, HistoryCrashChild::class.java.name,
                    path.toString(), file, stage.name).redirectErrorStream(true).start()
                val reader = Executors.newSingleThreadExecutor()
                try {
                    val ready = reader.submit<String> {
                        val output = StringBuilder()
                        child.inputStream.bufferedReader().use { input ->
                            while (true) {
                                val line = input.readLine() ?: break
                                output.appendLine(line)
                                if (line == "KILL_READY") return@submit line
                            }
                        }
                        output.toString()
                    }
                    assertEquals("KILL_READY", ready.get(30, TimeUnit.SECONDS), "$file $stage")
                    child.destroyForcibly()
                    assertTrue(child.waitFor(15, TimeUnit.SECONDS))
                    assertNotEquals(0, child.exitValue())
                    killed++
                } finally {
                    if (child.isAlive) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS) }
                    reader.shutdownNow()
                }
                FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                    val visible = h.recent().messages
                    validateHistory(visible)
                    if (file == "pending.json") {
                        assertEquals(1, visible.size)
                        val ledger = InvocationLedger(TestStore(), DefinitionGate(), ArgumentsValidator { _, _ -> true })
                        ledger.recoverColdStart("parent")
                        val recovery = h.recoverPending(ledger)
                        assertEquals(stage in setOf(HistoryWriteStage.AFTER_REPLACE, HistoryWriteStage.AFTER_DIRECTORY_SYNC), recovery.recovered)
                    } else {
                        assertEquals(if (file == "head.json" && stage in setOf(HistoryWriteStage.AFTER_REPLACE,
                            HistoryWriteStage.AFTER_DIRECTORY_SYNC)) 3 else 1, visible.size)
                        JsonInvocationStore.open(path.resolve("ledger"), Durability.PROCESS_CRASH_ONLY).use { store ->
                            val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                            ledger.recoverColdStart("parent")
                            assertTrue(h.recoverPending(ledger).requiresDecision)
                            assertEquals(2, h.revision())
                            val result = h.snapshot().messages.last().parts.single() as ContentPart.ToolResult
                            assertTrue(result.text.contains("\"effect\":\"unknown\""))
                            assertTrue(result.text.contains("\"status\":\"cancelled\""))
                            assertTrue(ledger.get(result.callId)!!.recoveryPending)
                        }
                    }
                    validateHistory(h.snapshot().messages)
                    Files.walk(path).use { paths ->
                        assertEquals(0L, paths.filter { it.fileName.toString().endsWith(".json.tmp") }.count(),
                            "Killed writer temporary files must be reclaimed: $file $stage")
                    }
                }
            }
        }
        println("ISSUE19_REAL_KILLS=$killed; method=Process.destroyForcibly; power_loss_tested=false")
        assertEquals(28, killed)
    }
}
