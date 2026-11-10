package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.plugin.tools.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostScreenToolTest {
    private val info = ScreenInfo(1080, 2400, 1, "on")

    /** Observe acknowledged disk writes without polling or taking over the production coordinator. */
    private class ObservedStore(private val delegate: InvocationStore) : InvocationStore by delegate {
        val writes = Channel<InvocationRecord>(Channel.UNLIMITED)
        override fun save(record: InvocationRecord, expectedRevision: Long?) {
            delegate.save(record, expectedRevision)
            writes.trySend(record)
        }
        override fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) {
            delegate.saveWithBlobs(record, expectedRevision, blobs)
            writes.trySend(record)
        }
        suspend fun await(id: String, matches: (InvocationRecord) -> Boolean): InvocationRecord = withTimeout(20_000) {
            while (true) {
                val record = writes.receive()
                if (record.callId == id && matches(record)) return@withTimeout record
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun input(service: HostToolExecutionService, key: String = "host/screen_info", args: String = "{}",
        session: String = UUID.randomUUID().toString()): InvocationInput {
        val run = UUID.randomUUID().toString()
        val definition = service.catalog.freeze(run).values.single { it.toolKey == key }
        return InvocationInput(BindingValue.copyOf(callBinding("screen-test", run, null, 0, "wire")),
            run, session, run, "step", 0, args, definition, false, definition.declaredRisk)
    }

    @Test fun registeredScreenUsesSchemaAndBackendAndPersistsStructuredResult() = runBlocking {
        val dir = Files.createTempDirectory("screen-success").toFile()
        val budget = PluginExecutionBudget()
        val calls = AtomicInteger()
        val backend = ScreenBackend { calls.incrementAndGet(); info }
        try {
            openTestStore(dir).use { store ->
                val service = HostToolBridgeRoot(budget).initialize(backend) { store }
                val bad = input(service, args = "{\"capture\":true}")
                assertEquals(Status.REJECTED, (service.prepare(bad) as Mutation.Applied).record.state)
                assertEquals(0, calls.get())
                val good = input(service)
                assertEquals("screen", good.definition!!.exclusiveResource)
                assertEquals(Risk.READ, good.definition!!.declaredRisk)
                service.prepare(good)
                assertNotNull((service.dispatch(good.binding.callId) as Mutation.Applied).ticket)
                withTimeout(20_000) { service.awaitObservedExit(good.binding.callId) }
                val record = service.ledger.get(good.binding.callId)!!
                assertEquals(Status.SUCCEEDED, record.state)
                assertEquals(Effect.NONE, record.result!!.effect)
                assertTrue(record.result!!.text.contains("\"modeWidthPixels\":1080"))
                assertTrue(record.result!!.text.contains("\"rotationQuarterTurns\":1"))
                assertEquals(1, calls.get())
                assertEquals(0, budget.occupiedSlots())
                assertEquals(InstanceState.EXIT_CONFIRMED, record.instance!!.state)
            }
        } finally { budget.close(); dir.deleteRecursively() }
    }

    @Test fun unavailableDisplayIsExplicitReadFailureWithoutPixelsOrSideEffects() = runBlocking {
        val tool = HostScreenTool(ScreenBackend { null })
        val result = tool.call(JsonObject(emptyMap()), ToolEnv("run", "session", "call", false, { _, _ -> }))
        assertTrue(result.isError)
        assertEquals("Default display unavailable", result.text)
        assertEquals(ToolEffect.NONE, result.effect)
        assertTrue(result.content.isEmpty())
    }

    @Test fun noncooperativeScreenTimeoutRetainsLeaseAcrossSessionsUntilActualExit() = runBlocking {
        exerciseTimeout(unknown = false)
    }

    @Test fun unknownScreenIsolationSurvivesActualExitAndDiskColdRestart() = runBlocking {
        exerciseTimeout(unknown = true)
    }

    private suspend fun exerciseTimeout(unknown: Boolean) {
        val dir = Files.createTempDirectory("screen-timeout").toFile()
        val budget = PluginExecutionBudget(processSlots = 4, perPluginSlots = 2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timerHeld = CountDownLatch(1)
        val releaseTimer = CountDownLatch(1)
        val calls = AtomicInteger()
        val backend = ScreenBackend {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                // Intentionally noncooperative: cancellation must not free this worker or lease.
                release.await()
            }
            info
        }
        val store = ObservedStore(openTestStore(dir))
        var service: HostToolExecutionService? = null
        var firstId: String? = null
        try {
            service = HostToolBridgeRoot(budget).initialize(backend) { store }
            val first = input(service, session = "session-a")
            firstId = first.binding.callId
            // A conservative host risk override supplies the unknown-external-state branch. The
            // production backend remains READ/NONE; no fake screen control is claimed by this test.
            val effective = if (unknown) first.copy(initialRisk = Risk.DEVICE_CONTROL) else first
            service.prepare(effective)
            service.ledger.get(firstId)!!.pending?.let {
                assertTrue(service.decide(firstId, it.id, Decision.ALLOW_ONCE) is Mutation.Applied)
            }
            val other = OtherOwner(service, first.definition!!, "session-b")
            val unrelated = OtherOwner(service, first.definition!!, "session-c", resource = "other-screen-test")
            // Existing scheduling seam: hold only the deadline executor until the actual backend
            // has entered. Keep the production 5s timeout, not a millisecond race against startup.
            budget.schedule(0) { timerHeld.countDown(); releaseTimer.await() }
            assertTrue(timerHeld.await(10, TimeUnit.SECONDS))
            assertNotNull((service.dispatch(firstId) as Mutation.Applied).ticket)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            releaseTimer.countDown()
            val timed = store.await(firstId) { it.state == Status.TIMED_OUT && it.restartRequiredUntilExit }
            assertEquals(if (unknown) Effect.UNKNOWN else Effect.NONE, timed.result!!.effect)
            assertEquals(InstanceState.UNCONFIRMED, timed.instance!!.state)
            assertEquals(1, budget.occupiedSlots())
            assertEquals("Resource occupied or externally isolated", (other.claim() as Mutation.Blocked).reason)
            assertNull(service.ledger.get(other.input.binding.callId)!!.instance)
            assertEquals(0, other.executions.get())
            // Different owner avoids the host restart gate; different resource is still runnable.
            unrelated.execute(budget)
            assertEquals(1, unrelated.executions.get())
            assertEquals(1, budget.occupiedSlots())
            assertEquals(1, calls.get())
            val projected = service.ledger.resultsForStep("session-a", first.runId, "step", listOf(firstId))
            release.countDown()
            withTimeout(20_000) { service.awaitObservedExit(firstId) }
            val exited = service.ledger.get(firstId)!!
            assertEquals(InstanceState.EXIT_CONFIRMED, exited.instance!!.state)
            assertEquals(0, budget.occupiedSlots())
            assertFalse(exited.restartRequired)
            assertEquals(timed.result, exited.result)
            assertEquals(projected, service.ledger.resultsForStep("session-a", first.runId, "step", listOf(firstId)))
            assertTrue(exited.evidence.any { it.outcome?.status == Status.SUCCEEDED })
            if (!unknown) {
                assertNull(exited.isolation)
                other.execute(budget)
                assertEquals(1, other.executions.get())
            } else {
                assertEquals("screen", exited.isolation!!.resource)
                assertNull(exited.isolation!!.resolution)
                assertTrue(other.claim() is Mutation.Blocked)
            }
            // All workers/observers have exited before closing disk storage. Reopen real files,
            // construct a NEW ledger/composition and recover; never reuse an in-memory snapshot.
            store.close()
            openTestStore(dir).use { reopened ->
                val cold = HostToolBridgeRoot(budget).initialize(backend) { reopened }
                val recovered = cold.ledger.get(firstId)!!
                assertEquals(exited, recovered)
                val next = input(cold, session = "session-d")
                cold.prepare(next)
                if (unknown) {
                    assertEquals("Resource occupied or externally isolated", (cold.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                    assertEquals(0, budget.occupiedSlots())
                    assertEquals(1, calls.get())
                    val recovery = cold.ledger.pendingRecoveries().single { it.callId == firstId }
                    cold.decideRecovery(firstId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY)
                    assertTrue(cold.dispatch(next.binding.callId) is Mutation.Blocked)
                    assertEquals(Effect.UNKNOWN, cold.ledger.get(firstId)!!.result!!.effect)
                } else {
                    assertNotNull((cold.dispatch(next.binding.callId) as Mutation.Applied).ticket)
                    withTimeout(20_000) { cold.awaitObservedExit(next.binding.callId) }
                    assertEquals(Status.SUCCEEDED, cold.ledger.get(next.binding.callId)!!.state)
                    assertEquals(2, calls.get())
                }
            }
        } finally {
            releaseTimer.countDown()
            release.countDown()
            try { firstId?.let { id -> withTimeout(20_000) { service?.awaitObservedExit(id) } } }
            finally { store.close(); budget.close(); dir.deleteRecursively() }
        }
    }

    /** Synthetic second plugin owner at the ledger/budget integration boundary. Production host
     * catalog intentionally cannot register plugins yet. Claims share the SAME process ledger;
     * successful claims really execute a bounded worker, not merely mutate a fixture record.
     */
    private class OtherOwner(private val service: HostToolExecutionService, screen: FrozenDefinition,
        session: String, resource: String = "screen") {
        val executions = AtomicInteger()
        private val run = UUID.randomUUID().toString()
        private val definition = screen.copy(toolKey = "test-$session/read", owner = "test-$session",
            modelName = "test_$session", exclusiveResource = resource)
        val input = InvocationInput(BindingValue.copyOf(callBinding("screen-test", run, null, 0, "wire")),
            run, session, run, "step", 0, "{}", definition, false, Risk.READ)
        init {
            service.catalog.gate.install(definition)
            service.catalog.gate.freezeRun(run, listOf(definition))
            check(service.ledger.recordInvocation(input) is Mutation.Applied)
            check(service.ledger.validateAndPrepare(input.binding.callId) is Mutation.Applied)
        }
        fun claim(): Mutation = service.ledger.claimExecution(input.binding.callId,
            service.ledger.get(input.binding.callId)!!.revision)
        suspend fun execute(budget: PluginExecutionBudget) {
            val ticket = (claim() as Mutation.Applied).ticket!!
            val owner = CoroutineScope(SupervisorJob())
            val tool = HostScreenTool(ScreenBackend {
                executions.incrementAndGet()
                ScreenInfo(800, 600, 0, "on")
            })
            val invocation = PluginInvocation(budget, definition.owner, owner, tool, JsonObject(emptyMap()),
                ToolInvocationRequest(input.runId, input.sessionId, ticket.callId),
                frozenSpec = tool.spec.copy(exclusiveResource = definition.exclusiveResource)).start()
            try {
                val result = withTimeout(20_000) { invocation.terminal.await() }
                assertEquals(PluginInvocationStatus.SUCCEEDED, result.status)
                withTimeout(20_000) { invocation.actualExited.await() }
                assertTrue(service.ledger.recordOutcome(ticket.callId, ticket.attemptId, Status.SUCCEEDED,
                    result.result!!.text, Effect.NONE) is Mutation.Applied)
                assertTrue(service.ledger.recordActualExit(ticket.callId, ticket.attemptId, "budget worker exited") is Mutation.Applied)
            } finally { invocation.close(); owner.cancel() }
        }
    }
}
