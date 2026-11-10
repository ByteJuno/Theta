package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

/** Deterministic production-path regressions for the four issue37 review boundaries. */
class UnknownBoundaryTest {
    @Test fun runningSiblingInternalReadAndWriteRespectExternalIsolation() {
        for (risk in listOf(Risk.READ, Risk.WRITE)) Fixture().use { f ->
            val a = f.claim(f.ready(f.input(definition(Risk.WRITE, resource = "screen"))))
            val b = f.claim(f.ready(f.input(definition().copy(toolKey = "reader/tool", owner = "reader"))))
            // Both legally run in parallel; a live sibling alone is not isolation.
            assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(b.record.callId, b.ticket!!.attemptId, Risk.READ))
            f.ledger.complete(a.record.callId, a.ticket!!.attemptId, Status.FAILED, "uncertain")
            assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(b.record.callId, b.ticket.attemptId, risk))
            assertNull(f.ledger.get(b.record.callId)!!.pending)
        }
    }

    @Test fun approvedInternalStepCannotConsumePermissionThroughIsolation() {
        Fixture().use { f ->
            val a = f.claim(f.ready(f.input(definition(Risk.WRITE, resource = "screen"))))
            val b = f.claim(f.ready(f.input(definition().copy(toolKey = "reader/tool", owner = "reader"))))
            val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(b.record.callId, b.ticket!!.attemptId, Risk.WRITE)).record.pending!!
            f.ledger.complete(a.record.callId, a.ticket!!.attemptId, Status.FAILED, "uncertain")
            // Evidence renewal requires the current request, not the obsolete one.
            val renewed = assertIs<Mutation.Applied>(f.ledger.resolveApproval(b.record.callId, pending, Decision.ALLOW_ONCE)).record.pending!!
            assertIs<Mutation.Applied>(f.ledger.resolveApproval(b.record.callId, renewed, Decision.ALLOW_ONCE))
            assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(b.record.callId, b.ticket.attemptId, renewed.id))
            assertNull(f.ledger.get(a.record.callId)!!.isolation!!.resolution)
            f.ledger.resolveRecovery(a.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
            assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(b.record.callId, b.ticket.attemptId, renewed.id))
            f.ledger.resolveRecovery(a.record.callId, RecoveryResolution.VERIFIED_CLEAR)
            // Clearing changes the evidence set; the old receipt still is not reusable.
            assertNotNull(assertIs<Mutation.Applied>(f.ledger.checkRiskApproval(b.record.callId, b.ticket.attemptId, renewed.id)).record.pending)
        }
    }

    @Test fun serialClaimRenewalAsksAfterWaitingParallelUnknown() = runTest {
        val f = RunFixture(listOf(testDefinition("a", parallel = true, risk = Risk.WRITE), testDefinition("b", risk = Risk.WRITE)))
        f.approve = { Decision.ALLOW_ALWAYS }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("b"), answer()))).run().state)
        val asked = mutableListOf<ApprovalRequest>()
        f.approve = { asked += it; Decision.ALLOW_ONCE }
        f.onSubmit = { t -> if (t.definition.name == "b") f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done") }
        f.onAwait = { id ->
            val r = f.ledger.get(id)!!
            if (!r.state.terminal) f.ledger.complete(id, r.instance!!.attemptId, Status.FAILED, "uncertain")
        }
        val provider = ScriptProvider(listOf(calls("a", "b"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(provider).run().state)
        assertEquals(listOf("b", "a", "b"), f.submitted.map { it.definition.name })
        assertEquals(listOf(f.submitted[1].callId), asked.last().unknownCalls)
        assertEquals("b", f.ledger.get(asked.last().callId)!!.input.modelName)
        assertEquals(2, provider.requests.size)
    }

    @Test fun approvalBoundaryRenewalAsksCurrentRequestWithoutStaleReceipt() = runTest {
        val f = RunFixture(listOf(testDefinition("a", parallel = true, risk = Risk.WRITE), testDefinition("b", risk = Risk.WRITE)))
        val asked = mutableListOf<ApprovalRequest>()
        f.onSubmit = { t -> if (t.definition.name == "b") f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done") }
        f.approve = { r ->
            asked += r
            if (f.ledger.get(r.callId)!!.input.modelName == "b" && r.unknownCalls.isEmpty()) {
                val a = f.submitted.single()
                f.ledger.complete(a.callId, a.attemptId, Status.FAILED, "uncertain")
            }
            Decision.ALLOW_ONCE
        }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("a", "b"), answer()))).run().state)
        assertEquals(3, asked.size)
        val b = f.ledger.get(f.submitted.last().callId)!!
        assertEquals(listOf(asked.last().id), b.receipts.map { it.request.id })
        assertNotEquals(asked[1].id, asked[2].id)
    }

    @Test fun continuouslyRenewedApprovalStopsWithoutSubmitting() = runTest {
        val f = RunFixture(listOf(testDefinition("write", risk = Risk.WRITE)))
        var prompts = 0
        f.approve = {
            prompts++
            val d = testDefinition("external-$prompts", parallel = true)
            f.gate.install(d)
            f.gate.freezeRun("external-$prompts", listOf(d))
            val binding = BindingValue.copyOf(callBinding("p", "r", null, 0, "w"))
            f.ledger.recordInvocation(InvocationInput(binding, "chain", "session", "external-$prompts", "step", 0, "{}", d, false, Risk.READ))
            val ready = assertIs<Mutation.Applied>(f.ledger.validateAndPrepare(binding.callId)).record
            val claim = assertIs<Mutation.Applied>(f.ledger.claimExecution(binding.callId, ready.revision))
            f.ledger.complete(binding.callId, claim.ticket!!.attemptId, Status.FAILED, "new uncertainty", Effect.UNKNOWN)
            Decision.ALLOW_ONCE
        }
        val p = ScriptProvider(listOf(calls("write"), answer()))
        assertEquals(RunState.PAUSED, f.loop(p).run().state)
        assertEquals(8, prompts)
        assertTrue(f.submitted.isEmpty())
        assertEquals(1, p.requests.size)
        assertTrue(f.store.records().first().receipts.isEmpty())
    }

    @Test fun renewedApprovalDenialDoesNotSubmitSerialWrite() = runTest {
        val f = RunFixture(listOf(testDefinition("a", parallel = true, risk = Risk.WRITE), testDefinition("b", risk = Risk.WRITE)))
        f.approve = { Decision.ALLOW_ALWAYS }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("b"), answer()))).run().state)
        f.onSubmit = {}
        f.onAwait = { id ->
            val r = f.ledger.get(id)!!
            f.ledger.complete(id, r.instance!!.attemptId, Status.FAILED, "uncertain")
        }
        f.approve = { if (it.unknownCalls.isEmpty()) Decision.ALLOW_ONCE else Decision.DENY }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("a", "b"), answer()))).run().state)
        assertEquals(listOf("b", "a"), f.submitted.map { it.definition.name })
        assertEquals(Status.REJECTED, f.store.records().last().state)
        assertNull(f.store.records().last().instance)
    }

    @Test fun unattendedOuterDenialRecoveryDoesNotRequireFabricatedInstance() = runTest {
        Fixture().use { f ->
            val old = f.claim(f.ready(f.input(definition(Risk.WRITE))))
            f.ledger.complete(old.record.callId, old.ticket!!.attemptId, Status.FAILED, "uncertain")
            val rejected = f.prepare(f.input(definition(Risk.WRITE), args = "{\"new\":true}", unattended = true))
            assertEquals(Status.REJECTED, rejected.state)
            assertNull(rejected.instance)
            f.reopen()
            val p = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, emptyLoop(f, MemoryRunHistory(), p).run().state)
            assertTrue(p.requests.isEmpty())
            val recovery = f.ledger.pendingRecoveries().single { it.callId == rejected.callId }
            assertIs<Mutation.Applied>(f.ledger.decideRecovery(rejected.callId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY))
            assertNull(f.ledger.get(rejected.callId)!!.instance)
            assertEquals(rejected.receipts, f.ledger.get(rejected.callId)!!.receipts)
            assertEquals(RunState.COMPLETED, emptyLoop(f, MemoryRunHistory(), ScriptProvider(listOf(answer()))).run().state)
        }
    }

    private fun assertSupplement(request: ChatRequest, id: String) {
        val result = request.messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().single { it.callId == id }
        assertTrue(result.text.contains("\"status\":\"timed_out\""))
        assertTrue(result.text.contains("\"effect\":\"none\""))
        val notices = request.messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
            .filter { it.text.contains(id) && it.text.contains("Side effects are unknown") }
        assertEquals(1, notices.size, "Late UNKNOWN must reach the actual provider as supplementary evidence")
        assertTrue(notices.single().text.toByteArray().size <= ModelResultText.LIMIT)
        validateHistory(request.messages)
    }

    @Test fun lateReadUnknownReachesActualNextModelRequest() = runTest {
        val f = RunFixture(listOf(testDefinition("read")))
        f.onSubmit = { t ->
            f.ledger.timeout(t.callId)
            f.ledger.complete(t.callId, t.attemptId, Status.FAILED, "late unknown", Effect.UNKNOWN)
        }
        val p = ScriptProvider(listOf(calls("read"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertSupplement(p.requests[1], f.submitted.single().callId)
        assertEquals(Effect.NONE, f.ledger.get(f.submitted.single().callId)!!.result!!.effect)
    }

    @Test fun lateEvidenceAfterCommittedPairAndStalePendingReopensWithoutMismatch() = runTest {
        Fixture().use { f ->
            val input = f.input(definition())
            val a = f.claim(f.ready(input))
            f.ledger.timeout(a.record.callId)
            f.ledger.recordActualExit(a.record.callId, a.ticket!!.attemptId, "exit")
            val canonical = f.ledger.get(a.record.callId)!!.result
            val path = Files.createTempDirectory("unknown-paired-history")
            val assistant = message(Role.ASSISTANT, toolCallPart(input.binding, input.modelName, input.argsJson))
            val identity = RunIdentity("chain", "session", input.runId)
            FileRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).close()
            FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h -> h.prepareStep(0, identity, "step", assistant) }
            FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY) {
                if (it.file == "head.json" && it.stage == HistoryWriteStage.AFTER_REPLACE) error("stop after commit before pending removal")
            }.use { h -> assertFails { h.append(0, listOf(assistant, f.ledger.resultsForStep("session", input.runId, "step", listOf(a.record.callId))!!)) } }
            f.ledger.complete(a.record.callId, a.ticket.attemptId, Status.FAILED, "late unknown", Effect.UNKNOWN)
            f.reopen()
            FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                assertTrue(h.recoverPending(f.ledger).recovered)
                assertEquals(1, h.revision())
                val p = ScriptProvider(listOf(answer()))
                assertEquals(RunState.COMPLETED, emptyLoop(f, h, p).run().state)
                assertSupplement(p.requests.single(), a.record.callId)
                assertEquals(canonical, f.ledger.get(a.record.callId)!!.result)
            }
            FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                val p = ScriptProvider(listOf(answer()))
                assertEquals(RunState.COMPLETED, emptyLoop(f, h, p).run().state)
                assertSupplement(p.requests.single(), a.record.callId)
            }
            path.toFile().deleteRecursively()
        }
    }

    private fun emptyLoop(f: Fixture, history: RunHistory, p: ScriptProvider, unattended: Boolean = false) = AgentRunLoop(
        RunIdentity("chain", "session", "resumed", unattended), ModelTarget("provider", p, Credentials { "fake" }),
        chatRequest("m") {}, history, LedgerToolExecutor(f.ledger, f.gate, emptyList(), object : ToolExecutionBridge {
            override fun submit(ticket: ExecutionTicket) = error("No submission")
            override suspend fun awaitTerminal(callId: String) = error("No await")
            override fun requestCancel(callId: String) = error("No cancellation")
        }))

    @Test fun unattendedInternalDenialSurvivesFileReopenAndHasExplicitRecovery() = runTest {
        for (committed in listOf(false, true)) Fixture().use { f ->
            val old = f.claim(f.ready(f.input(definition(Risk.WRITE))))
            f.ledger.complete(old.record.callId, old.ticket!!.attemptId, Status.FAILED, "uncertain")
            val input = f.input(definition().copy(toolKey = "reader/tool"), unattended = true)
            val a = f.claim(f.ready(input))
            val path = Files.createTempDirectory("unknown-denial-history")
            FileRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
                val assistant = message(Role.ASSISTANT, toolCallPart(input.binding, input.modelName, input.argsJson))
                h.prepareStep(0, RunIdentity("chain", "session", input.runId, true), "step", assistant)
                assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(a.record.callId, a.ticket!!.attemptId, Risk.WRITE))
                f.ledger.complete(a.record.callId, a.ticket.attemptId, Status.SUCCEEDED, "read complete", Effect.NONE)
                if (committed) h.append(0, listOf(assistant, f.ledger.resultsForStep("session", input.runId, "step", listOf(a.record.callId))!!))
            }
            val receipt = f.ledger.get(a.record.callId)!!.receipts.single()
            f.reopen()
            FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                h.recoverPending(f.ledger)
                for (unattended in listOf(true, false)) {
                    val p = ScriptProvider(listOf(answer()))
                    assertEquals(RunState.PAUSED, emptyLoop(f, h, p, unattended).run().state)
                    assertTrue(p.requests.isEmpty(), "A fresh loop is not explicit recovery")
                }
                val recovery = f.ledger.pendingRecoveries().single { it.callId == a.record.callId }
                assertIs<Mutation.Applied>(f.ledger.decideRecovery(a.record.callId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY))
                assertEquals(receipt, f.ledger.get(a.record.callId)!!.receipts.single())
                assertTrue(f.ledger.rejectedUnknownUnattended(a.record.callId))
                assertEquals(Effect.NONE, f.ledger.get(a.record.callId)!!.result!!.effect)
                assertEquals(RunState.COMPLETED, emptyLoop(f, h, ScriptProvider(listOf(answer()))).run().state)
            }
            f.reopen("epoch-3")
            FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                assertEquals(RunState.COMPLETED, emptyLoop(f, h, ScriptProvider(listOf(answer()))).run().state)
            }
            path.toFile().deleteRecursively()
        }
    }
}
