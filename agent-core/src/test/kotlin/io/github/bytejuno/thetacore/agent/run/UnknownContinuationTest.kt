package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class UnknownContinuationTest {
    private fun RunFixture.failUnknown(ticket: ExecutionTicket) {
        // The real submission seam must see the durable claim BEFORE any effect.
        assertEquals(Status.RUNNING, ledger.get(ticket.callId)!!.state)
        assertEquals(ticket.attemptId, store.records().single { it.callId == ticket.callId }.instance!!.attemptId)
        assertIs<Mutation.Applied>(ledger.complete(ticket.callId, ticket.attemptId, Status.FAILED, "ordinary failure"))
    }

    @Test fun serialIsolationStopsDifferentResourceReadAndPairsRemainder() = runTest {
        for (parallel in listOf(false, true)) for (live in listOf(false, true)) {
            val f = RunFixture(listOf(
                testDefinition("write", parallel = parallel, risk = Risk.WRITE).copy(exclusiveResource = "screen"),
                testDefinition("read", parallel = parallel).copy(exclusiveResource = "files")))
            f.onSubmit = { t ->
                if (live) f.ledger.recordOutcome(t.callId, t.attemptId, Status.FAILED, "uncertain")
                else f.failUnknown(t)
            }
            val p = ScriptProvider(listOf(calls("write", "read"), answer()))
            assertEquals(RunState.PAUSED, f.loop(p).run().state)
            assertEquals(listOf("write"), f.submitted.map { it.definition.name })
            assertEquals(1, p.requests.size)
            val remaining = f.store.records().last()
            assertEquals(Status.CANCELLED, remaining.state)
            assertEquals(Effect.NONE, remaining.result!!.effect)
            assertNull(remaining.instance)
            assertEquals(2, f.history.snapshot().messages.last().parts.size)
            validateHistory(f.history.snapshot().messages)
        }
    }

    @Test fun invalidArgumentsCannotAcquireFutureUnknownDenialReason() = runTest {
        val f = RunFixture(listOf(testDefinition("write", risk = Risk.WRITE)))
        f.approve = { Decision.ALLOW_ALWAYS }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("write"), answer()))).run().state)
        f.onSubmit = { f.failUnknown(it) }
        val batch = listOf(Events.started("r", "m"),
            Events.toolStart(0, "bad", "write"), Events.toolDelta(0, "[]"), Events.end(0),
            Events.toolStart(1, "valid", "write"), Events.toolDelta(1, "{\"new\":true}"), Events.end(1),
            Events.finished(FinishReason.TOOL_CALLS))
        val p = ScriptProvider(listOf(batch, answer()))
        val history = MemoryRunHistory()
        val loop = AgentRunLoop(RunIdentity("chain", "session", "unattended", true),
            ModelTarget("provider", p, Credentials { "fake" }), f.request(), history,
            LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { fail("No prompt") }))
        assertEquals(RunState.COMPLETED, loop.run().state)
        assertEquals(2, p.requests.size)
        val rejected = f.store.records().single { it.state == Status.REJECTED }
        assertTrue(rejected.result!!.text.contains("Invalid arguments"))
        assertFalse(f.ledger.rejectedUnknownUnattended(rejected.callId))
        validateHistory(history.snapshot().messages)
    }

    @Test fun exitedUnknownContinuesToReadAndExplicitModelNotice() = runTest {
        val f = RunFixture(listOf(testDefinition("write", risk = Risk.WRITE), testDefinition("read")))
        val approvals = mutableListOf<ApprovalRequest>()
        f.approve = { approvals += it; Decision.ALLOW_ONCE }
        f.onSubmit = { t ->
            if (t.definition.name == "write") f.failUnknown(t)
            else f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "observed")
        }
        val p = ScriptProvider(listOf(calls("write"), calls("read"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertEquals(listOf("write", "read"), f.submitted.map { it.definition.name })
        assertEquals(1, approvals.size)
        val result = p.requests[1].messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().single()
        assertTrue(result.text.contains("\"effect\":\"unknown\""))
        val notices = p.requests[1].messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
            .filter { it.text.contains("Side effects are unknown") }
        assertEquals(1, notices.size)
        assertTrue(notices.single().text.contains(result.callId))
        assertTrue(notices.single().text.contains("canonical result reports UNKNOWN"))
        assertEquals(1, p.requests[2].messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
            .count { it.text == notices.single().text })
        assertTrue(result.isError)
        assertNull(f.ledger.get(f.submitted.first().callId)!!.isolation!!.resolution)
        validateHistory(f.history.snapshot().messages)
    }

    @Test fun everyHigherRiskAsksDespiteStoredGrantsAndOneApprovalDoesNotReconcile() = runTest {
        for (risk in listOf(Risk.WRITE, Risk.DEVICE_CONTROL, Risk.ROOT)) {
            val f = RunFixture(listOf(testDefinition("uncertain", risk = Risk.WRITE), testDefinition("next", risk = risk)))
            val asked = mutableListOf<ApprovalRequest>()
            f.approve = { asked += it; if (it.risk == Risk.ROOT) Decision.ALLOW_ONCE else Decision.ALLOW_ALWAYS }
            f.onSubmit = { t ->
                if (t.definition.name == "uncertain") f.failUnknown(t)
                else f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done")
            }
            val p = ScriptProvider(listOf(calls("next"), calls("uncertain"), calls("next", args = "{\"n\":1}"),
                calls("next", args = "{\"n\":2}"), answer()))
            assertEquals(RunState.COMPLETED, f.loop(p).run().state)
            val id = f.submitted[1].callId
            assertEquals(listOf(emptyList(), emptyList(), listOf(id), listOf(id)), asked.map { it.unknownCalls })
            assertNull(f.ledger.get(id)!!.isolation!!.resolution)
            assertEquals(4, f.submitted.size)
        }
    }

    @Test fun onlyVerifiedRecoveryRestoresNormalGrantsAndSameArgumentRetryStillAsks() = runTest {
        val f = RunFixture(listOf(testDefinition("uncertain", risk = Risk.WRITE), testDefinition("next", risk = Risk.WRITE)))
        val asked = mutableListOf<ApprovalRequest>()
        f.approve = { asked += it; Decision.ALLOW_ALWAYS }
        var unknownId = ""
        f.onSubmit = { t ->
            if (t.definition.name == "uncertain") { f.failUnknown(t); unknownId = t.callId }
            else f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done")
        }
        val first = ScriptProvider(listOf(calls("next"), calls("uncertain"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(first).run().state)
        fun recover(resolution: RecoveryResolution) {
            val request = f.ledger.pendingRecoveries().single { it.callId == unknownId }
            assertIs<Mutation.Applied>(f.ledger.decideRecovery(unknownId, request.id, resolution))
        }
        recover(RecoveryResolution.ACCEPTED_UNCERTAINTY)
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("next", args = "{\"n\":1}"), answer()))).run().state)
        assertEquals(listOf(unknownId), asked.last().unknownCalls)
        recover(RecoveryResolution.VERIFIED_CLEAR)
        val count = asked.size
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("next", args = "{\"n\":2}"), answer()))).run().state)
        assertEquals(count, asked.size)
        // Protocol 3.6 same-tool/argument retry remains separate from 3.8 reconciliation.
        val reconciledId = unknownId
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("uncertain"), answer()))).run().state)
        assertEquals(listOf(reconciledId), asked.last().unknownCalls)
    }

    @Test fun actualExitResourceIsolationAndStickyRestartRemainPauseConditions() = runTest {
        for (kind in listOf("live", "resource", "restart")) {
            val d = testDefinition("write", risk = Risk.WRITE).let {
                if (kind == "resource") it.copy(exclusiveResource = "screen") else it
            }
            val f = RunFixture(listOf(d))
            f.onSubmit = { t ->
                f.ledger.recordOutcome(t.callId, t.attemptId, Status.FAILED, "failed")
                if (kind == "restart") f.ledger.markRestartRequired(t.callId, t.attemptId)
                if (kind != "live") f.ledger.recordActualExit(t.callId, t.attemptId, "worker exited")
            }
            val p = ScriptProvider(listOf(calls("write"), answer()))
            assertEquals(RunState.PAUSED, f.loop(p).run().state, kind)
            assertEquals(1, p.requests.size, kind)
            val resumed = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, f.loop(resumed).run().state, kind)
            assertTrue(resumed.requests.isEmpty(), kind)
        }
    }

    @Test fun unattendedUnknownRestrictedCallRejectsAndPausesDespiteAlwaysGrant() = runTest {
        val f = RunFixture(listOf(testDefinition("write", risk = Risk.WRITE)))
        f.approve = { Decision.ALLOW_ALWAYS }
        f.onSubmit = { f.failUnknown(it) }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("write"), answer()))).run().state)
        val unknown = f.submitted.single().callId
        val p = ScriptProvider(listOf(calls("write", args = "{\"new\":true}"), answer()))
        val history = MemoryRunHistory(listOf(message(Role.USER, textPart("checkpoint continuation"))))
        val executor = LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { fail("Unattended prompt") })
        val loop = AgentRunLoop(RunIdentity("chain", "session", "resumed", true),
            ModelTarget("provider", p, Credentials { "fake" }), f.request(), history, executor)
        assertEquals(RunState.PAUSED, loop.run().state)
        assertEquals(1, p.requests.size)
        assertEquals(1, f.submitted.size)
        val rejected = f.store.records().last()
        assertEquals(Status.REJECTED, rejected.state)
        assertEquals(Effect.NONE, rejected.result!!.effect)
        assertNull(f.ledger.get(unknown)!!.isolation!!.resolution)
        validateHistory(history.snapshot().messages)
    }

    @Test fun coldRecoveryRequiresDecisionBeforeProviderAndAcceptingIsNotResourceClearance() = runTest {
        for (resource in listOf(null, "screen")) Fixture().use { f ->
            val d = testDefinition("write", risk = Risk.WRITE).copy(exclusiveResource = resource)
            val active = f.claim(f.ready(f.input(d)))
            f.ledger.timeout(active.record.callId)
            f.reopen()
            assertTrue(f.ledger.get(active.record.callId)!!.recoveryPending)
            fun loop(p: ScriptProvider) = AgentRunLoop(RunIdentity("chain", "session", "resume"),
                ModelTarget("provider", p, Credentials { "fake" }), chatRequest("m") {}, MemoryRunHistory(),
                LedgerToolExecutor(f.ledger, f.gate, emptyList(), object : ToolExecutionBridge {
                    override fun submit(ticket: ExecutionTicket) = error("No tool expected")
                    override suspend fun awaitTerminal(callId: String) = error("No tool expected")
                    override fun requestCancel(callId: String) = error("No tool expected")
                }))
            val blocked = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, loop(blocked).run().state)
            assertTrue(blocked.requests.isEmpty())
            val recovery = f.ledger.pendingRecoveries().single()
            assertIs<Mutation.Applied>(f.ledger.decideRecovery(active.record.callId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY))
            val resumed = ScriptProvider(listOf(answer()))
            assertEquals(if (resource == null) RunState.COMPLETED else RunState.PAUSED, loop(resumed).run().state)
            assertEquals(if (resource == null) 1 else 0, resumed.requests.size)
            if (resource != null) {
                val current = f.ledger.pendingRecoveries().single()
                assertIs<Mutation.Applied>(f.ledger.decideRecovery(active.record.callId, current.id, RecoveryResolution.VERIFIED_CLEAR))
                assertEquals(RunState.COMPLETED, loop(ScriptProvider(listOf(answer()))).run().state)
            }
        }
    }

    @Test fun internalWriteAfterUnknownAsksDespiteReadDeclarationAndStoredGrant() = runTest {
        val f = RunFixture(listOf(testDefinition("uncertain", risk = Risk.WRITE), testDefinition("upgrade")))
        val internal = mutableListOf<ApprovalRequest>()
        f.onSubmit = { t ->
            if (t.definition.name == "uncertain") f.failUnknown(t)
            else {
                val r = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(t.callId, t.attemptId, Risk.DEVICE_CONTROL)).record
                val request = r.pending!!
                internal += request
                assertIs<Mutation.Applied>(f.ledger.decideApproval(t.callId, request.id, Decision.ALLOW_ALWAYS))
                assertIs<Mutation.AlreadyApplied>(f.ledger.checkRiskApproval(t.callId, t.attemptId, request.id))
                assertEquals(Risk.DEVICE_CONTROL, f.store.records().single { it.callId == t.callId }.effectiveRisk)
                f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done")
            }
        }
        val p = ScriptProvider(listOf(calls("upgrade"), calls("uncertain"), calls("upgrade", args = "{\"n\":1}"),
            calls("upgrade", args = "{\"n\":2}"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        val unknown = f.submitted[1].callId
        assertEquals(listOf(emptyList(), listOf(unknown), listOf(unknown)), internal.map { it.unknownCalls })
        assertTrue(internal.all { it.internal && it.risk == Risk.DEVICE_CONTROL })
    }

    @Test fun lateEvidenceKeepsSingleTerminalResultAndRearmsClearedUnknown() = runTest {
        val f = RunFixture(listOf(testDefinition("write", risk = Risk.WRITE), testDefinition("next", risk = Risk.WRITE)))
        val asked = mutableListOf<ApprovalRequest>()
        f.approve = { asked += it; Decision.ALLOW_ALWAYS }
        f.onSubmit = { t ->
            if (t.definition.name == "write") {
                f.ledger.timeout(t.callId)
                f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "late success", Effect.APPLIED)
            } else f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done")
        }
        val p = ScriptProvider(listOf(calls("next"), calls("write"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        val t = f.submitted.last()
        val before = f.ledger.get(t.callId)!!
        assertEquals(Status.TIMED_OUT, before.state)
        assertEquals(Effect.UNKNOWN, before.result!!.effect)
        assertEquals(Effect.APPLIED, before.evidence.single().outcome!!.effect)
        val recovery = f.ledger.pendingRecoveries().single()
        f.ledger.decideRecovery(t.callId, recovery.id, RecoveryResolution.VERIFIED_CLEAR)
        f.ledger.complete(t.callId, t.attemptId, Status.FAILED, "new uncertainty", Effect.UNKNOWN)
        val after = f.ledger.get(t.callId)!!
        assertNull(after.isolation!!.resolution)
        assertEquals(before.result, after.result)
        assertEquals(2, after.evidence.size)
        assertEquals(1, f.history.snapshot().messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().count { it.callId == t.callId })
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("next", args = "{\"new\":true}"), answer()))).run().state)
        assertEquals(listOf(t.callId), asked.last().unknownCalls)
    }

    @Test fun changedDefinitionDuringUnknownApprovalNeverSubmits() = runTest {
        val d = testDefinition("next", risk = Risk.WRITE)
        val f = RunFixture(listOf(testDefinition("write", risk = Risk.WRITE), d))
        f.onSubmit = { f.failUnknown(it) }
        f.approve = { request ->
            if (request.unknownCalls.isNotEmpty()) f.gate.install(d.copy(version = "2"))
            Decision.ALLOW_ONCE
        }
        val p = ScriptProvider(listOf(calls("write"), calls("next"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertEquals(1, f.submitted.size)
        assertEquals(Status.REJECTED, f.store.records().last().state)
        assertTrue(f.store.records().last().result!!.text.contains("STALE"))
    }

    @Test fun effectiveUpgradeAndNewRunWithCheckpointHistoryRetainRestrictions() = runTest {
        val f = RunFixture(listOf(testDefinition("upgrade"), testDefinition("read"), testDefinition("write", risk = Risk.WRITE)))
        val asked = mutableListOf<ApprovalRequest>()
        f.approve = { asked += it; Decision.ALLOW_ALWAYS }
        f.onSubmit = { t ->
            if (t.definition.name == "upgrade") {
                val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(t.callId, t.attemptId, Risk.WRITE)).record.pending!!
                assertIs<Mutation.Applied>(f.ledger.decideApproval(t.callId, pending.id, Decision.ALLOW_ONCE))
                assertEquals(Risk.WRITE, f.ledger.get(t.callId)!!.effectiveRisk)
                f.failUnknown(t)
            } else f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done")
        }
        assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(calls("write"), calls("upgrade"), answer()))).run().state)
        val unknown = f.submitted.last().callId
        val history = MemoryRunHistory(listOf(message(Role.USER, textPart("checkpoint summary; continue same task"))))
        val p = ScriptProvider(listOf(calls("read"), calls("write", args = "{\"new\":true}"), answer()))
        val executor = LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { asked += it; Decision.ALLOW_ONCE })
        val loop = AgentRunLoop(RunIdentity("chain", "session", "continuation"),
            ModelTarget("provider", p, Credentials { "fake" }), f.request(), history, executor, initialSteps = 3)
        assertEquals(RunState.COMPLETED, loop.run().state)
        assertEquals(listOf(emptyList(), listOf(unknown)), asked.map { it.unknownCalls })
        assertEquals(listOf("write", "upgrade", "read", "write"), f.submitted.map { it.definition.name })
    }
}
