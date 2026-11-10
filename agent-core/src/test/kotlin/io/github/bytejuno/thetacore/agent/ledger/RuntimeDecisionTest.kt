package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class RuntimeDecisionTest {
    @Test fun currentDenialSurvivesDefinitionPolicyAndUnknownChangesForInitialAndInternalRequests(): Unit {
        for (internal in listOf(false, true)) for (stale in listOf(false, true)) {
            var denied = false
            Fixture(ApprovalPolicy(denied = { denied })).use { f ->
                val d = definition(Risk.WRITE)
                val initial = f.prepare(f.input(d))
                val running = if (internal) f.claim(f.approve(initial)) else null
                val ask = if (internal) assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(
                    initial.callId, running!!.ticket!!.attemptId, Risk.ROOT)).record.pending!! else initial.pending!!
                val other = f.claim(f.ready(f.input(definition().copy(toolKey = "other/tool", owner = "other"))))
                f.ledger.complete(other.record.callId, other.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
                denied = true
                if (stale) f.gate.install(d.copy(description = "changed")) else f.gate.beginRefresh(d.toolKey)
                val before = f.ledger.get(initial.callId)!!
                assertIs<Mutation.Conflict>(f.ledger.resolveApproval(initial.callId, ask.copy(summary = "forged"), Decision.DENY))
                assertIs<Mutation.Conflict>(f.ledger.decideApproval(initial.callId, "obsolete", Decision.DENY))
                val result = assertIs<Mutation.Applied>(f.ledger.decideApproval(initial.callId, ask.id, Decision.DENY)).record
                assertNull(result.pending)
                assertEquals(ApprovalReceipt(ask, Decision.DENY), result.receipts.last())
                assertEquals(before.effectiveRisk, result.effectiveRisk)
                assertEquals(before.instance, result.instance)
                assertEquals(if (internal) Status.RUNNING else Status.REJECTED, result.state)
                if (internal) assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(
                    result.callId, running!!.ticket!!.attemptId, ask.id))
                else assertEquals(Effect.NONE, result.result!!.effect)
                assertIs<Mutation.Conflict>(f.ledger.decideApproval(result.callId, ask.id, Decision.ALLOW_ONCE))
                f.reopen()
                assertEquals(ApprovalReceipt(ask, Decision.DENY), f.ledger.get(result.callId)!!.receipts.last())
            }
        }
    }

    @Test fun runtimeConsumptionRechecksRootLongTermSwitchButPreservesOnceAndNonRoot(): Unit {
        for (risk in listOf(Risk.ROOT, Risk.WRITE)) {
            for (decision in listOf(Decision.ALLOW_SESSION, Decision.ALLOW_ALWAYS, Decision.ALLOW_ONCE)) {
                var longTerm = true
                Fixture(ApprovalPolicy(allowRootLongTerm = { longTerm })).use { f ->
                    val running = f.claim(f.ready())
                    val id = running.record.callId; val attempt = running.ticket!!.attemptId
                    val ask = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, risk)).record.pending!!
                    val approved = assertIs<Mutation.Applied>(f.ledger.decideApproval(id, ask.id, decision)).record
                    longTerm = false
                    val consumed = f.ledger.checkRiskApproval(id, attempt, ask.id)
                    if (risk == Risk.ROOT && decision != Decision.ALLOW_ONCE)
                        assertEquals("ROOT long-term grants disabled", assertIs<Mutation.Blocked>(consumed).reason)
                    else assertIs<Mutation.AlreadyApplied>(consumed)
                    assertEquals(approved, f.ledger.get(id))
                }
            }
        }
    }

    @Test fun reachedRiskRechecksDefinitionPolicyAndEachDecisionInsteadOfWatermark(): Unit {
        var denied = false
        Fixture(ApprovalPolicy(denied = { denied })).use { f ->
            val d = definition(Risk.WRITE)
            val running = f.claim(f.ready(f.input(d)))
            val id = running.record.callId; val attempt = running.ticket!!.attemptId
            val again = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE)).record
            assertEquals(Risk.WRITE, again.effectiveRisk)
            val request = again.pending!!
            f.ledger.decideApproval(id, request.id, Decision.DENY)
            assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(id, attempt, request.id))
            assertEquals(Risk.WRITE, f.ledger.get(id)!!.effectiveRisk)
            assertIs<Mutation.Conflict>(f.ledger.decideApproval(id, request.id, Decision.ALLOW_ONCE))
            denied = true
            assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE))
            denied = false
            f.gate.beginRefresh(d.toolKey)
            assertEquals("REFRESH_PENDING", assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE)).reason)
            f.gate.install(d.copy(description = "changed"))
            assertEquals("STALE definition on submitted instance", assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE)).reason)
            f.gate.install(d)
            val fresh = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE)).record.pending!!
            assertNotEquals(request.id, fresh.id)
            f.ledger.decideApproval(id, fresh.id, Decision.ALLOW_ALWAYS)
            assertIs<Mutation.AlreadyApplied>(f.ledger.checkRiskApproval(id, attempt, fresh.id))
            val allowed = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.READ)).record
            assertNull(allowed.pending)
            assertEquals(Risk.WRITE, allowed.effectiveRisk)
            denied = true
            assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(id, attempt, fresh.id))
            assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE))
        }
    }

    @Test fun reachedRiskWithAlwaysGrantStillReasksForNewUnknownAndUnattendedNeverAsks(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE)
        val initial = f.approve(f.prepare(f.input(d)), Decision.ALLOW_ALWAYS)
        val running = f.claim(initial); val id = initial.callId; val attempt = running.ticket!!.attemptId
        val unattended = f.claim(f.ready(f.input(d, unattended = true)))
        val other = f.claim(f.ready(f.input(definition().copy(toolKey = "other/tool", owner = "other"))))
        f.ledger.complete(other.record.callId, other.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
        val asked = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE)).record.pending!!
        assertEquals(listOf(other.record.callId), asked.unknownCalls)
        f.ledger.decideApproval(id, asked.id, Decision.ALLOW_ONCE)
        assertIs<Mutation.AlreadyApplied>(f.ledger.checkRiskApproval(id, attempt, asked.id))
        // Neither the initial ALWAYS nor this ONCE resolves the UNKNOWN for the next internal action.
        val next = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.READ)).record.pending!!
        assertNotEquals(asked.id, next.id)
        assertEquals(Risk.WRITE, next.risk)
        f.ledger.decideApproval(id, next.id, Decision.DENY)
        assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(id, attempt, next.id))
        assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(unattended.record.callId, unattended.ticket!!.attemptId, Risk.WRITE))
        assertNull(f.ledger.get(unattended.record.callId)!!.pending)
        f.reopen()
        assertEquals(Decision.DENY, f.ledger.get(id)!!.receipts.last().decision)
        assertEquals(Risk.WRITE, f.ledger.get(id)!!.effectiveRisk)
    }

    @Test fun evidenceArrivingAfterAllowButBeforeConsumptionRenewsSameRuntimeDecision(): Unit = Fixture().use { f ->
        val running = f.claim(f.ready())
        val id = running.record.callId; val attempt = running.ticket!!.attemptId
        val ask = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.WRITE)).record.pending!!
        f.ledger.decideApproval(id, ask.id, Decision.ALLOW_ONCE)
        val other = f.claim(f.ready(f.input(definition().copy(toolKey = "other/tool", owner = "other"))))
        f.ledger.complete(other.record.callId, other.ticket!!.attemptId, Status.FAILED, "new evidence", Effect.UNKNOWN)
        val renewed = assertIs<Mutation.Applied>(f.ledger.checkRiskApproval(id, attempt, ask.id)).record.pending!!
        assertNotEquals(ask.id, renewed.id)
        assertEquals(listOf(other.record.callId), renewed.unknownCalls)
        assertIs<Mutation.Conflict>(f.ledger.decideApproval(id, ask.id, Decision.ALLOW_ONCE))
        f.ledger.decideApproval(id, renewed.id, Decision.DENY)
        assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(id, attempt, renewed.id))
    }

    @Test fun unconfirmedUnknownPausesOnlyItsChainAcrossStepsOwnersAndRuntimeActions(): Unit {
        for (cancel in listOf(false, true)) Fixture().use { f ->
            val a = f.claim(f.ready(f.input(definition(Risk.WRITE))))
            val bDefinition = definition(Risk.WRITE).copy(toolKey = "b/tool", owner = "b")
            val b = f.ready(f.input(bDefinition).copy(stepId = "distinct-step"))
            val runningSibling = f.claim(f.ready(f.input(definition().copy(toolKey = "c/tool", owner = "c"))))
            if (cancel) f.ledger.cancel(a.record.callId) else f.ledger.timeout(a.record.callId)
            val aBefore = f.ledger.get(a.record.callId)!!
            assertNull(aBefore.input.definition!!.exclusiveResource)
            assertFalse(aBefore.restartRequired)
            assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(runningSibling.record.callId,
                runningSibling.ticket!!.attemptId, Risk.READ))
            assertEquals("Task chain awaits unknown instance actual exit",
                assertIs<Mutation.Blocked>(f.ledger.claimExecution(b.callId, b.revision)).reason)
            val approved = f.ready(f.input(bDefinition).copy(stepId = "approved-after-unknown"))
            assertIs<Mutation.Blocked>(f.ledger.claimExecution(approved.callId, approved.revision))
            val unrelated = f.ready(f.input(bDefinition, chain = "unrelated"))
            f.claim(unrelated)
            assertIs<Mutation.Blocked>(f.ledger.resolveRecovery(a.record.callId, RecoveryResolution.VERIFIED_CLEAR))
            assertEquals(aBefore, f.ledger.get(a.record.callId))
            f.ledger.recordActualExit(a.record.callId, a.ticket!!.attemptId, "worker exited")
            // Actual exit releases occupancy, not UNKNOWN consent. An old approval must be refreshed.
            val reask = assertIs<Mutation.Applied>(f.ledger.claimExecution(b.callId, b.revision)).record
            assertEquals(listOf(a.record.callId), reask.pending!!.unknownCalls)
            f.claim(f.approve(reask))
            f.claim(approved)
            assertNull(assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(runningSibling.record.callId,
                runningSibling.ticket.attemptId, Risk.READ)).record.pending)
            val writeAsk = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(runningSibling.record.callId,
                runningSibling.ticket.attemptId, Risk.WRITE)).record.pending!!
            assertEquals(listOf(a.record.callId), writeAsk.unknownCalls)
        }
    }

    @Test fun coldRecoveryPreservesUnknownChainPauseWithoutResourceAndExternalIsolationWithResource(): Unit {
        for (resource in listOf(null, "external")) Fixture().use { f ->
            val a = f.claim(f.ready(f.input(definition(Risk.WRITE, resource = resource))))
            f.ledger.cancel(a.record.callId)
            val bDefinition = definition(Risk.WRITE, resource = resource).copy(toolKey = "b/tool", owner = "b")
            val b = f.ready(f.input(bDefinition).copy(stepId = "next"))
            f.reopen()
            assertEquals(Status.CANCELLED, f.ledger.get(a.record.callId)!!.state)
            assertEquals("Task chain awaits explicit cold-recovery decision",
                assertIs<Mutation.Blocked>(f.ledger.claimExecution(b.callId, b.revision)).reason)
            val recovery = f.ledger.pendingRecoveries().single()
            f.ledger.decideRecovery(a.record.callId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY)
            if (resource == null) f.claim(b) else {
                assertEquals("Resource occupied or externally isolated",
                    assertIs<Mutation.Blocked>(f.ledger.claimExecution(b.callId, b.revision)).reason)
                val verify = f.ledger.pendingRecoveries().single()
                f.ledger.decideRecovery(a.record.callId, verify.id, RecoveryResolution.VERIFIED_CLEAR)
                val reask = assertIs<Mutation.Applied>(f.ledger.claimExecution(b.callId, b.revision)).record
                f.claim(f.approve(reask))
            }
            assertEquals(Effect.UNKNOWN, f.ledger.get(a.record.callId)!!.result!!.effect)
        }
    }

    @Test fun coldPendingUpgradeBecomesLostAndRecoveryIdsSurviveReopenButNotEvidence(): Unit = Fixture().use { f ->
        val claimed = f.claim(f.ready(f.input(definition(Risk.WRITE, resource = "screen"))))
        val id = claimed.record.callId
        val attempt = claimed.ticket!!.attemptId
        val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, attempt, Risk.ROOT, "root")).record.pending!!
        f.reopen()
        val lost = f.ledger.get(id)!!
        assertEquals(Status.LOST, lost.state)
        assertEquals(Effect.UNKNOWN, lost.result!!.effect)
        assertTrue(lost.recoveryPending)
        assertIs<Mutation.Conflict>(f.ledger.decideApproval(id, pending.id, Decision.ALLOW_ONCE))
        val recovery = f.ledger.pendingRecoveries().single()
        f.reopen("epoch-3")
        assertEquals(recovery, f.ledger.pendingRecoveries().single())
        f.ledger.recordOutcome(id, attempt, Status.FAILED, "late remote evidence", Effect.UNKNOWN)
        assertIs<Mutation.Conflict>(f.ledger.decideRecovery(id, recovery.id, RecoveryResolution.VERIFIED_CLEAR))
        val fresh = f.ledger.pendingRecoveries().single()
        val accepted = assertIs<Mutation.Applied>(f.ledger.decideRecovery(id, fresh.id, RecoveryResolution.ACCEPTED_UNCERTAINTY)).record
        assertFalse(accepted.recoveryPending)
        assertEquals(lost.result, accepted.result)
        assertEquals(1, accepted.evidence.size)
        assertIs<Mutation.Conflict>(f.ledger.decideRecovery(id, fresh.id, RecoveryResolution.ACCEPTED_UNCERTAINTY))
        val next = f.ready(f.input(definition(Risk.WRITE, resource = "screen")))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        val verify = f.ledger.pendingRecoveries().single()
        val cleared = assertIs<Mutation.Applied>(f.ledger.decideRecovery(id, verify.id, RecoveryResolution.VERIFIED_CLEAR)).record
        assertEquals(accepted.result, cleared.result)
        assertEquals(accepted.evidence, cleared.evidence)
        assertIs<Mutation.Applied>(f.ledger.claimExecution(next.callId, next.revision))
        f.reopen("epoch-4")
        assertEquals(cleared, f.ledger.get(id))
    }

    @Test fun duplicateRecoveryRacesHaveOneWinnerAndNeverReleaseLiveInstance(): Unit = Fixture().use { f ->
        val running = f.claim(f.ready(f.input(definition(Risk.WRITE))))
        val id = running.record.callId
        f.ledger.timeout(id)
        assertTrue(f.ledger.pendingRecoveries().isEmpty())
        assertIs<Mutation.Conflict>(f.ledger.decideRecovery(id, "recovery:$id:1", RecoveryResolution.VERIFIED_CLEAR))
        f.reopen()
        val request = f.ledger.pendingRecoveries().single()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = List(2) { pool.submit(Callable {
                start.await(); f.ledger.decideRecovery(id, request.id, RecoveryResolution.VERIFIED_CLEAR)
            }) }
            start.countDown()
            val replies = results.map { it.get() }
            assertEquals(1, replies.count { it is Mutation.Applied })
            assertEquals(1, replies.count { it is Mutation.Conflict })
            assertEquals(Effect.UNKNOWN, f.ledger.get(id)!!.result!!.effect)
        } finally { pool.shutdownNow() }
    }

    @Test fun unrelatedUnknownForcesAllWritesAndRefreshesStaleRuntimeDecision(): Unit = Fixture().use { f ->
        val d = definition()
        val running = f.claim(f.ready(f.input(d)))
        val id = running.record.callId
        val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, running.ticket!!.attemptId, Risk.WRITE)).record.pending!!
        val otherDefinition = definition().copy(toolKey = "other/tool", owner = "other")
        val other = f.claim(f.ready(f.input(otherDefinition, args = "{\"different\":true}")))
        f.ledger.complete(other.record.callId, other.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
        val replaced = assertIs<Mutation.Applied>(f.ledger.decideApproval(id, pending.id, Decision.ALLOW_ONCE)).record
        assertEquals(Risk.READ, replaced.effectiveRisk)
        assertNotEquals(pending.id, replaced.pending!!.id)
        assertEquals(listOf(other.record.callId), replaced.pending.unknownCalls)
        assertIs<Mutation.Conflict>(f.ledger.decideApproval(id, pending.id, Decision.ALLOW_ONCE))
        assertIs<Mutation.Applied>(f.ledger.decideApproval(id, replaced.pending.id, Decision.ALLOW_ONCE))
        f.ledger.complete(id, running.ticket.attemptId, Status.SUCCEEDED, "done", Effect.NONE)
        val write = definition(Risk.WRITE).copy(toolKey = "third/tool")
        assertEquals(Status.REJECTED, f.prepare(f.input(write, unattended = true)).state)
        val decision = f.ledger.pendingRecoveries().single { it.callId == other.record.callId }
        f.ledger.decideRecovery(other.record.callId, decision.id, RecoveryResolution.VERIFIED_CLEAR)
        val normal = f.prepare(f.input(write))
        assertTrue(normal.pending!!.unknownCalls.isEmpty())
        // Historical UNKNOWN same-tool/same-args retries still ask, even after verification.
        val retry = f.prepare(f.input(otherDefinition, args = "{\"different\":true}"))
        assertEquals(listOf(other.record.callId), retry.pending!!.unknownCalls)
    }

    @Test fun storageFailureDoesNotConsumeRecoveryIdOrChangeEvidence(): Unit = Fixture().use { f ->
        val running = f.claim(f.ready(f.input(definition(Risk.WRITE))))
        f.reopen()
        val id = running.record.callId
        val before = f.ledger.get(id)!!
        val request = f.ledger.pendingRecoveries().single()
        f.store.close()
        var fail = true
        f.store = JsonInvocationStore.open(f.directory, Durability.PROCESS_CRASH_ONLY) {
            if (fail && it == WriteStage.BEFORE_REPLACE) error("injected")
        }
        f.ledger = InvocationLedger(f.store, f.gate, ArgumentsValidator { _, _ -> true })
        f.ledger.recoverColdStart("epoch-3")
        assertIs<Mutation.StorageFailure>(f.ledger.decideRecovery(id, request.id, RecoveryResolution.VERIFIED_CLEAR))
        assertEquals(before, f.ledger.get(id))
        assertEquals(request, f.ledger.pendingRecoveries().single())
        fail = false
        assertIs<Mutation.Applied>(f.ledger.decideRecovery(id, request.id, RecoveryResolution.VERIFIED_CLEAR))
        f.reopen("epoch-4")
        assertEquals(before.result, f.ledger.get(id)!!.result)
        assertFalse(f.ledger.get(id)!!.recoveryPending)
    }

    @Test fun timeoutAndApprovalRacePersistOneConsistentRisk(): Unit = Fixture().use { f ->
        val running = f.claim(f.ready())
        val id = running.record.callId
        val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(id, running.ticket!!.attemptId, Risk.WRITE)).record.pending!!
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val approval = pool.submit(Callable { start.await(); f.ledger.decideApproval(id, pending.id, Decision.ALLOW_ONCE) })
            val timeout = pool.submit(Callable { start.await(); f.ledger.timeout(id) })
            start.countDown(); approval.get(); timeout.get()
            val r = f.ledger.get(id)!!
            assertEquals(Status.TIMED_OUT, r.state)
            assertEquals(if (r.effectiveRisk == Risk.WRITE) Effect.UNKNOWN else Effect.NONE, r.result!!.effect)
            assertNull(r.pending)
            assertEquals(InstanceState.UNCONFIRMED, r.instance!!.state)
            assertIs<Mutation.Conflict>(f.ledger.decideApproval(id, pending.id, Decision.ALLOW_ONCE))
        } finally { pool.shutdownNow() }
    }
}
