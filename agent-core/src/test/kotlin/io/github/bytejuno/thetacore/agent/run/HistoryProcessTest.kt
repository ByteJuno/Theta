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

/** A separate JVM is killed by the parent while holding the actual SQLite transactions and database writer lock. No shutdown hooks,
 * close(), catch/finally cleanup, or simulated exceptions execute in the child after the checkpoint. */
object HistoryCrashChild {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Paths.get(args[0])
        val file = args[1]
        val stage = HistoryWriteStage.valueOf(args[2])
        val fault: (HistoryWritePoint) -> Unit = { point ->
            if ((point.file == file || file == "append" && point.file == "batches/2" && stage in setOf(HistoryWriteStage.AFTER_CHUNK, HistoryWriteStage.AFTER_VALUE)) && point.stage == stage) {
                println("KILL_READY"); System.out.flush(); CountDownLatch(1).await()
            }
        }
        val id = UUID.randomUUID().toString()
        val assistant = message(Role.ASSISTANT,
            toolCallPart(callBinding("p", "req", "resp", 0, "wire", id), "tool", "{}"))
        AgentDatabase.open(directory.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
            if (file == "create") {
                SqliteRunHistory.create(db, "session", fault).close()
            } else SqliteRunHistory.open(db, "session", fault).use { history ->
                if (file == "pending") history.prepareStep(1, RunIdentity("chain", "session", "run"), "step", assistant)
                else SqliteInvocationStore.open(db).use { store ->
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
        val opened = runCatching { TestRunHistory.open(Paths.get(args[0]), Durability.PROCESS_CRASH_ONLY).close() }.isSuccess
        println(if (opened) "OPENED" else "BLOCKED")
    }
}

class HistoryProcessTest {
    // Gradle 9 may use a non-URL application loader. Code sources work with both loaders.
    private val classpath get() = (listOf(HistoryCrashChild::class.java, HistoryLockChild::class.java, SqliteRunHistory::class.java,
        kotlin.Unit::class.java, androidx.sqlite.SQLiteDriver::class.java,
        androidx.sqlite.driver.bundled.BundledSQLiteDriver::class.java, kotlinx.serialization.json.Json::class.java,
        kotlinx.serialization.SerializationException::class.java, kotlinx.coroutines.Job::class.java)
        .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath } +
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).absolutePath }.toList())
        .distinct().joinToString(File.pathSeparator)
    private val java get() = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")

    private fun otherProcessCanOpen(path: java.nio.file.Path): Boolean {
        val child = ProcessBuilder(java.absolutePath, "--enable-native-access=ALL-UNNAMED", "-cp", classpath, HistoryLockChild::class.java.name, path.toString())
            .redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText().trim()
        assertTrue(child.waitFor(30, TimeUnit.SECONDS))
        assertTrue(output == "OPENED" || output == "BLOCKED", output)
        return output == "OPENED"
    }

    @Test fun rejectedSameProcessOpenKeepsTheWriterLockForOtherProcesses() {
        val path = Files.createTempDirectory("history-lock")
        TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
            assertFalse(otherProcessCanOpen(path))
            assertFails { TestRunHistory.open(path, Durability.PROCESS_CRASH_ONLY) }
            assertFalse(otherProcessCanOpen(path), "a rejected duplicate must not release the OS lock")
            assertEquals(0, h.revision())
        }
        assertTrue(otherProcessCanOpen(path))
        TestRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).close()
    }

    @Test fun actualSubprocessKillAtEveryWriteStageRecoversCoherentHistoryAndLedger() {
        assertTrue(classpath.isNotBlank())
        var killed = 0
        for (file in listOf("create", "pending", "append")) {
            for (stage in HistoryWriteStage.entries) {
                val path = Files.createTempDirectory("history-real-kill")
                if (file != "create") TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use {
                    it.append(0, listOf(message(Role.USER, textPart("base"))))
                }
                val child = ProcessBuilder(java.absolutePath, "--enable-native-access=ALL-UNNAMED", "-cp", classpath, HistoryCrashChild::class.java.name,
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
                if (file == "create") {
                    // Inspect the killed transaction BEFORE idempotent create can repair absence.
                    AgentDatabase.open(path.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
                        val committed = stage == HistoryWriteStage.AFTER_COMMIT
                        assertEquals(if (committed) 1L else 0L,
                            db.connection.longValue("SELECT count(*) FROM sessions WHERE session_id='session'"), stage.name)
                        if (committed) SqliteRunHistory.open(db, "session").use {
                            assertEquals(0, it.revision()); assertTrue(it.snapshot().messages.isEmpty())
                        } else {
                            assertFails { SqliteRunHistory.open(db, "session") }
                            assertEquals(0L, db.connection.longValue("SELECT count(*) FROM history_values"))
                            assertEquals(0L, db.connection.longValue("SELECT count(*) FROM history_chunks"))
                        }
                        println("ISSUE78_CREATE_KILL stage=$stage visible=$committed inspected_before_retry=true")
                    }
                    TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
                        assertEquals(0, h.revision()); h.append(0, listOf(message(Role.USER, textPart("retry"))))
                    }
                    TestRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { assertEquals(1, it.revision()) }
                    continue
                }
                AgentDatabase.open(path.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.open(db, "session").use { h ->
                    val visible = h.recent().messages
                    validateHistory(visible)
                    if (file == "pending") {
                        assertEquals(1, visible.size)
                        val ledger = InvocationLedger(TestStore(), DefinitionGate(), ArgumentsValidator { _, _ -> true })
                        ledger.recoverColdStart("parent")
                        val recovery = h.recoverPending(ledger)
                        assertEquals(stage in setOf(HistoryWriteStage.AFTER_COMMIT), recovery.recovered)
                    } else {
                        assertEquals(if (file == "append" && stage in setOf(HistoryWriteStage.AFTER_COMMIT)) 3 else 1, visible.size)
                        SqliteInvocationStore.open(db).use { store ->
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
        }
        println("ISSUE78_SQLITE_REAL_KILLS=$killed; method=Process.destroyForcibly; power_loss_tested=false")
        assertEquals(15, killed)
    }
}
