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
        val definition = service.catalog.freeze(run).values.single()
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
            val syncs = mutableListOf<String>()
            val sync: (File) -> Unit = { syncs += it.name }
            val budget = PluginExecutionBudget()
            val store = AndroidInvocationStore.open(root, sync)
            try {
                val bridge = HostToolBridgeRoot(budget)
                val service = bridge.initialize { store }
                assertSame(service, bridge.initialize { error("Must not reopen a live writer") })
                assertEquals(listOf(".${AndroidInvocationStore.DIRECTORY}.initializing", root.name, AndroidInvocationStore.DIRECTORY), syncs)
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
                assertTrue(syncs.size > 5)
                assertTrue(File(root, "${AndroidInvocationStore.DIRECTORY}/${completed.callId}.json").isFile)
                store.close()
                AndroidInvocationStore.open(root, sync).use { reopened ->
                    val fresh = HostToolBridgeRoot(budget).initialize { reopened }
                    assertEquals(completed, fresh.ledger.get(completed.callId))
                    assertEquals(2, reopened.records().size)
                    assertTrue(fresh.dispatch(completed.callId) is Mutation.Conflict)
                    assertEquals(completed, fresh.ledger.get(completed.callId))
                }
            } finally { store.close(); budget.close() }
        } }
    }

    @Test fun directorySyncFailureNeverAcknowledgesOrAllowsAnotherSubmission() = runBlocking {
        temporary { root -> runBlocking {
            var fail = false
            val budget = PluginExecutionBudget()
            AndroidInvocationStore.open(root) { if (fail) throw IOException("fsync failed") }.use { store ->
                try {
                    val service = HostToolBridgeRoot(budget).initialize { store }
                    val call = input(service)
                    fail = true
                    val result = service.prepare(call) as Mutation.StorageFailure
                    assertTrue(result.uncertain)
                    assertNull(service.ledger.get(call.binding.callId))
                    fail = false
                    assertTrue(service.prepare(input(service)) is Mutation.Blocked)
                    assertTrue(service.dispatch(call.binding.callId) is Mutation.Blocked)
                    assertTrue(store.records().isEmpty())
                } finally { budget.close() }
            }
            // Atomic replacement may have happened, but no instance was ever claimed.
            AndroidInvocationStore.open(root) {}.use { reopened ->
                assertEquals(1, reopened.records().size)
                assertNull(reopened.records().single().instance)
            }
        } }
    }

    @Test fun everyInitializationCutpointCanRetryWithoutPublishingAnIncompleteStore() {
        for (stage in InitializationStage.entries) temporary { root ->
            assertThrows(StoreException::class.java) {
                AndroidInvocationStore.open(root, { if (it == stage) throw IOException("crash at $stage") }) {}
            }
            val directory = File(root, AndroidInvocationStore.DIRECTORY)
            if (stage < InitializationStage.AFTER_RENAME) assertFalse(directory.exists())
            else assertEquals("theta-invocation-ledger-v1", File(directory, "ledger.identity").readText())
            AndroidInvocationStore.open(root) {}.use { assertTrue(it.records().isEmpty()) }
            assertFalse(File(root, ".${AndroidInvocationStore.DIRECTORY}.initializing").exists())
        }
    }

    @Test fun everyInitialDirectorySyncFailureCanRetry() {
        for (failureAt in 1..3) temporary { root ->
            var calls = 0
            assertThrows(StoreException::class.java) {
                AndroidInvocationStore.open(root) { if (++calls == failureAt) throw IOException("sync $failureAt") }
            }
            AndroidInvocationStore.open(root) {}.use { assertTrue(it.records().isEmpty()) }
        }
    }

    @Test fun corruptFormalDirectoryWinsOverAnAbandonedStage() = temporary { root ->
        val stage = File(root, ".${AndroidInvocationStore.DIRECTORY}.initializing").apply { mkdir() }
        File(stage, "ledger.identity").writeText("theta-invocation-ledger-v1")
        val directory = File(root, AndroidInvocationStore.DIRECTORY).apply { mkdir() }
        assertThrows(StoreException::class.java) { AndroidInvocationStore.open(root) {} }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertTrue(stage.isDirectory)
    }

    @Test fun existingEmptyOrCorruptDirectoryDoesNotBecomeFresh() = temporary { root ->
        val directory = File(root, AndroidInvocationStore.DIRECTORY).apply { mkdir() }
        assertThrows(StoreException::class.java) { AndroidInvocationStore.open(root) {} }
        File(directory, "ledger.identity").writeText("bad identity")
        assertThrows(StoreException::class.java) { AndroidInvocationStore.open(root) {} }
        assertEquals("bad identity", File(directory, "ledger.identity").readText())
    }

    @Test fun initialSyncFailureIsClosedAndBridgeNeverRetriesOpen() = runBlocking {
        temporary { root -> runBlocking {
            val budget = PluginExecutionBudget()
            try {
                val bridge = HostToolBridgeRoot(budget)
                var attempts = 0
                try {
                    bridge.initialize { attempts++; AndroidInvocationStore.open(root) { throw IOException("fsync") } }
                    fail("Expected storage failure")
                } catch (_: StoreException) { }
                try { bridge.initialize { attempts++; AndroidInvocationStore.open(root) {} }; fail("Expected closed bridge") }
                catch (_: IllegalStateException) { }
                assertEquals(1, attempts)
                assertFalse(File(root, "${AndroidInvocationStore.DIRECTORY}/ledger.identity").exists())
            } finally { budget.close() }
        } }
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
                AndroidInvocationStore.open(root) {}.use { store ->
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
                    HostToolBridgeRoot(budget, BlockedSpecTool(release)).initialize { AndroidInvocationStore.open(root) {} }
                }.exceptionOrNull()
                assertTrue("$failure", failure is IllegalStateException && failure !is CancellationException)
                // Coroutine stack-trace recovery may wrap the rethrown exception in a copy.
                assertTrue(generateSequence(failure) { it.cause }.any { it is TimeoutCancellationException })
                AndroidInvocationStore.open(root) {}.close() // The failed startup released its writer.
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
                val startup = scope.async { HostToolBridgeRoot(budget, tool).initialize { AndroidInvocationStore.open(root) {} } }
                withContext(Dispatchers.IO) { assertTrue(tool.entered.await(5, TimeUnit.SECONDS)) }
                startup.cancel()
                withTimeout(3_000) { startup.join() }
                assertTrue(startup.isCancelled)
            } finally { release.countDown(); budget.close(); scope.cancel() }
        } }
    }

    @Test fun secondWriterRejectedAndFailedOpenReleasesLocks() = temporary { root ->
        AndroidInvocationStore.open(root) {}.use {
            assertThrows(StoreException::class.java) { AndroidInvocationStore.open(root) {} }
        }
        var count = 0
        assertThrows(StoreException::class.java) {
            AndroidInvocationStore.open(root) { if (++count == 2) throw IOException("post-open sync") }
        }
        AndroidInvocationStore.open(root) {}.close()
    }

    @Test fun malformedRecordBlocksStartupWithoutDeletingEvidence() = runBlocking {
        temporary { root -> runBlocking {
            AndroidInvocationStore.open(root) {}.close()
            val corrupt = File(root, "${AndroidInvocationStore.DIRECTORY}/${UUID.randomUUID()}.json")
            corrupt.writeText("{broken")
            val budget = PluginExecutionBudget()
            try {
                try { HostToolBridgeRoot(budget).initialize { AndroidInvocationStore.open(root) {} }; fail("Expected failure") }
                catch (_: StoreException) { }
                assertEquals("{broken", corrupt.readText())
            } finally { budget.close() }
        } }
    }
}
