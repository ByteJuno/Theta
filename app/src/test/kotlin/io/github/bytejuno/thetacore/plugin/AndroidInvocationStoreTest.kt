package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.plugin.tools.HostClockTool
import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolSpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidInvocationStoreTest {
    private fun input(service: HostToolExecutionService, args: String = "{}", run: String = UUID.randomUUID().toString()): InvocationInput {
        val definition = service.catalog.freeze(run).values.single { it.toolKey == "host/clock" }
        return InvocationInput(BindingValue.copyOf(callBinding("android-test", run, null, 0, "clock")),
            run, "session", run, "step", 0, args, definition, false, definition.declaredRisk)
    }
    private suspend fun exited(service: HostToolExecutionService, id: String): InvocationRecord = withTimeout(10_000) {
        while (service.ledger.get(id)?.instance?.state != InstanceState.EXIT_CONFIRMED) delay(5)
        checkNotNull(service.ledger.get(id))
    }
    private fun <T> temporary(block: (File) -> T): T {
        val root = Files.createTempDirectory("issue17-").toFile()
        return try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun clockValidatesExecutesPersistsAndColdReopensWithoutReplay() = runBlocking {
        temporary { root -> runBlocking {
            val budget = PluginExecutionBudget()
            val store = openTestStore(root)
            try {
                val bridge = HostToolBridgeRoot(budget)
                val service = bridge.initialize { store }
                assertSame(service, bridge.initialize { error("Must not reopen a live writer") })
                assertTrue(File(root, AgentDatabase.FILE_NAME).isFile)
                val bad = input(service, "{\"extra\":1}")
                assertEquals(Status.REJECTED, (service.prepare(bad) as Mutation.Applied).record.state)
                assertNull(service.ledger.get(bad.binding.callId)!!.instance)
                val good = input(service)
                assertEquals(Status.READY, (service.prepare(good) as Mutation.Applied).record.state)
                assertTrue(service.dispatch(good.binding.callId) is Mutation.Applied)
                val completed = exited(service, good.binding.callId)
                assertEquals(Status.SUCCEEDED, completed.state)
                assertTrue(completed.result!!.text.contains("epochMillis"))
                assertEquals(Effect.NONE, completed.result!!.effect)
                assertTrue(appPayload(root, completed.callId).isNotEmpty())
                assertFalse(File(root, "host-invocations").exists())
                store.close()
                openTestStore(root).use { reopened ->
                    val fresh = HostToolBridgeRoot(budget).initialize { reopened }
                    assertEquals(completed, fresh.ledger.get(completed.callId))
                    assertEquals(2, reopened.records().size)
                    assertTrue(fresh.dispatch(completed.callId) is Mutation.Conflict)
                    assertEquals(completed, fresh.ledger.get(completed.callId))
                }
            } finally { store.close(); budget.close() }
        } }
    }

    @Test fun lostCommitAcknowledgementBlocksEveryWriteUntilReopen() = temporary { root ->
        var fail = false
        AndroidInvocationStore.open(root, androidx.sqlite.driver.bundled.BundledSQLiteDriver()) {
            if (fail && it == SqliteWriteStage.AFTER_COMMIT) throw IOException("ack lost")
        }.use { store ->
            val gate = DefinitionGate()
            val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            ledger.recoverColdStart("first")
            val d = FrozenDefinition("host/test", "host_test", "test", "test", "{}", Risk.READ,
                Source.FIRST_PARTY, "host", "1", 1, true, null, false, null)
            gate.install(d); gate.freezeRun("run", listOf(d))
            val input = InvocationInput(BindingValue.copyOf(callBinding("test", "req", null, 0, "wire")),
                "chain", "session", "run", "step", 0, "{}", d, false, Risk.READ)
            fail = true
            val result = ledger.recordInvocation(input) as Mutation.StorageFailure
            assertTrue(result.uncertain)
            assertTrue(store.records().isEmpty())
            fail = false
            assertTrue(ledger.recordInvocation(input) is Mutation.Blocked)
        }
        openTestStore(root).use { assertEquals(1, it.records().size); assertNull(it.records().single().instance) }
    }

    @Test fun historyBorrowsTheSameNoBackupDatabaseAndDoesNotCloseLedger() = temporary { root ->
        AndroidInvocationStore.open(root, androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { store ->
            store.createHistory("session").use { h ->
                h.append(0, listOf(io.github.bytejuno.thetacore.agent.protocol.message(
                    io.github.bytejuno.thetacore.agent.protocol.Role.USER,
                    io.github.bytejuno.thetacore.agent.protocol.textPart("goal"))))
            }
            assertTrue(store.records().isEmpty())
            store.openHistory("session").use { assertEquals(1L, it.revision()) }
        }
        AndroidInvocationStore.open(root, androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { store ->
            store.openHistory("session").use { assertEquals(1, it.snapshot().messages.size) }
        }
    }

    @Test fun databaseLivesInNoBackupRootAndReopens() = temporary { root ->
        // The schema is a SQLite transaction, not a published directory marker.
        openTestStore(root).close()
        assertTrue(File(root, AgentDatabase.FILE_NAME).isFile)
        assertFalse(File(root, "host-invocations").exists())
        openTestStore(root).use { assertTrue(it.records().isEmpty()) }
    }

    private class BlockedSpecTool(private val release: CountDownLatch) : Tool {
        val entered = CountDownLatch(1)
        private val delegate = HostClockTool()
        override val id = "blocked"
        override val spec: ToolSpec get() { entered.countDown(); release.await(); return delegate.spec }
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = delegate.call(args, env)
    }

    @Test fun pluginLeasesCannotFailOneShotStartupButStillLimitExecution() = runBlocking {
        temporary { root -> runBlocking {
            val budget = PluginExecutionBudget(processSlots = 2, perPluginSlots = 2)
            val plugins = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val hold = CompletableDeferred<Unit>()
            try {
                val leases = List(2) { checkNotNull(budget.submit("plugin", plugins, {}, {}) { hold.await() }) }
                openTestStore(root).use { store ->
                    val service = HostToolBridgeRoot(budget).initialize { store }
                    val call = input(service)
                    assertEquals(Status.READY, (service.prepare(call) as Mutation.Applied).record.state)
                    assertEquals("Execution capacity unavailable", (service.dispatch(call.binding.callId) as Mutation.Blocked).reason)
                    hold.complete(Unit)
                    leases.forEach { it.actualExited.await() }
                    assertTrue(service.dispatch(call.binding.callId) is Mutation.Applied)
                    assertEquals(Status.SUCCEEDED, exited(service, call.binding.callId).state)
                }
            } finally { hold.complete(Unit); budget.close(); plugins.cancel() }
        } }
    }

    @Test fun metadataTimeoutIsAStartupFailureNotCallerCancellation() = runBlocking {
        temporary { root -> runBlocking {
            val budget = PluginExecutionBudget()
            val release = CountDownLatch(1)
            try {
                val failure = runCatching {
                    HostToolBridgeRoot(budget, BlockedSpecTool(release)).initialize { openTestStore(root) }
                }.exceptionOrNull()
                assertTrue("$failure", failure is IllegalStateException && failure !is CancellationException)
                // Coroutine stack-trace recovery may wrap the rethrown exception in a copy.
                assertTrue(generateSequence(failure) { it.cause }.any { it is TimeoutCancellationException })
                openTestStore(root).close() // The failed startup released its writer.
            } finally { release.countDown(); budget.close() }
        } }
    }

    @Test fun cancellingTheInitializerStaysCancellation() = runBlocking {
        temporary { root -> runBlocking {
            val budget = PluginExecutionBudget()
            val release = CountDownLatch(1)
            val tool = BlockedSpecTool(release)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val startup = scope.async { HostToolBridgeRoot(budget, tool).initialize { openTestStore(root) } }
                withContext(Dispatchers.IO) { assertTrue(tool.entered.await(5, TimeUnit.SECONDS)) }
                startup.cancel()
                withTimeout(3_000) { startup.join() }
                assertTrue(startup.isCancelled)
            } finally { release.countDown(); budget.close(); scope.cancel() }
        } }
    }

    @Test fun helperOnCreateOrderingCannotReinitializeEstablishedEvidence() = temporary { root ->
        // Real SQLite with the SupportSQLiteDriver contract: helper creates schema inside
        // driver.open BEFORE AgentDatabase can inspect pragmas. Not an Android device test.
        var creates = 0
        var opens = 0
        val driver = object : androidx.sqlite.SQLiteDriver {
            override val hasConnectionPool = true
            override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
                opens++
                val db = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(fileName)
                fun exec(sql: String) { db.prepare(sql).use { it.step() } }
                exec("PRAGMA journal_mode=WAL"); exec("PRAGMA synchronous=FULL"); exec("PRAGMA foreign_keys=ON")
                val version = db.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
                if (version == 0L) {
                    exec("BEGIN IMMEDIATE")
                    AgentDatabase.createSchema(db); creates++
                    exec("COMMIT")
                }
                return db
            }
        }
        AndroidInvocationStore.open(root, driver).close()
        assertEquals(1, creates)
        for (deleted in listOf(false, true)) {
            val file = File(root, AgentDatabase.FILE_NAME)
            if (deleted) file.delete() else file.writeBytes(byteArrayOf())
            assertThrows(StoreException::class.java) { AndroidInvocationStore.open(root, driver).close() }
            assertEquals(1, opens); assertEquals(1, creates)
            assertEquals(!deleted, file.exists())
            if (!deleted) assertEquals(0L, file.length())
        }
    }

    @Test fun secondWriterRejectedAndFailedOpenReleasesLocks() = temporary { root ->
        openTestStore(root).use {
            assertThrows(StoreException::class.java) { openTestStore(root) }
        }
        openTestStore(root).close()
    }

    @Test fun malformedRecordBlocksStartupWithoutDeletingEvidence() = runBlocking {
        temporary { root -> runBlocking {
            openTestStore(root).close()
            val corrupt = File(root, AgentDatabase.FILE_NAME)
            androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(corrupt.path).use { db ->
                db.prepare("INSERT INTO invocations VALUES('broken',1,1,'invalid-checksum')").use { it.step() }
            }
            val budget = PluginExecutionBudget()
            try {
                try { HostToolBridgeRoot(budget).initialize { openTestStore(root) }; fail("Expected failure") }
                catch (_: StoreException) { }
                assertTrue(corrupt.isFile)
            } finally { budget.close() }
        } }
    }
}
