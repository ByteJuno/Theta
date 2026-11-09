package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

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
