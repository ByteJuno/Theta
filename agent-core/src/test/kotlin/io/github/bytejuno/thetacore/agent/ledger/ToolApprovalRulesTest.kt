package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import kotlin.test.*

class ToolApprovalRulesTest {
    @Test fun revocationPreservesAuditSurvivesReopenAndAllowsNewExplicitGrant() {
        val revoked = mutableSetOf<String>()
        Fixture(ApprovalPolicy(grantRevoked = { it.request.id in revoked })).use { f ->
            val d = definition(Risk.DEVICE_CONTROL)
            val granted = f.approve(f.prepare(f.input(d)), Decision.ALLOW_ALWAYS)
            val receipt = granted.receipts.single()
            assertEquals(Status.READY, f.prepare(f.input(d)).state)
            revoked += receipt.request.id
            assertEquals(Status.WAITING_APPROVAL, f.claim(granted).record.state)
            f.reopen()
            assertEquals(listOf(receipt), f.ledger.get(granted.callId)!!.receipts)
            assertTrue(f.ledger.permanentGrants().isEmpty())
            val next = f.approve(f.prepare(f.input(d)), Decision.ALLOW_ALWAYS)
            assertNotEquals(receipt.request.id, next.receipts.single().request.id)
            assertEquals(Status.READY, f.prepare(f.input(d)).state)
            assertEquals(listOf(receipt), f.ledger.get(granted.callId)!!.receipts)
        }
    }

    @Test fun denyWinsOverPermanentGrantsInEveryModeAndUnattended() {
        for (mode in ApprovalMode.entries) for (unattended in listOf(false, true)) {
            var deny = false
            var activeMode = ApprovalMode.STRICT
            Fixture(ApprovalPolicy(denied = { deny }, mode = { activeMode })).use { f ->
                val d = definition(Risk.DEVICE_CONTROL)
                f.approve(f.prepare(f.input(d)), Decision.ALLOW_ALWAYS)
                activeMode = mode
                val ready = f.prepare(f.input(d, unattended = unattended))
                assertEquals(Status.READY, ready.state)
                deny = true
                val rejected = f.claim(ready).record
                assertEquals(Status.REJECTED, rejected.state)
                assertEquals(Effect.NONE, rejected.result!!.effect)
                val fresh = f.prepare(f.input(d, unattended = unattended))
                assertEquals(Status.REJECTED, fresh.state)
                assertEquals(Effect.NONE, fresh.result!!.effect)
            }
        }
    }

    @Test fun frozenRiskCannotLowerDeclarationAndControlsFailureAndTimeoutEffects() {
        var override: Risk? = Risk.DEVICE_CONTROL
        val policy = ApprovalPolicy(mode = { ApprovalMode.BALANCED }, riskOverride = { override })
        Fixture(policy).use { f ->
            val raw = definition(Risk.READ)
            val frozen = policy.freezeRisk(raw)
            assertEquals(raw.digest, frozen.digest)
            assertTrue(raw.sameRegistrationAs(frozen))
            override = Risk.READ
            assertEquals(Risk.DEVICE_CONTROL, frozen.initialRisk)
            for (status in listOf(Status.FAILED, Status.TIMED_OUT)) {
                val input = f.input(raw, chain = status.name).copy(definition = frozen, initialRisk = frozen.initialRisk)
                f.gate.releaseRun(input.runId)
                f.gate.freezeRun(input.runId, listOf(frozen))
                val prepared = f.prepare(input)
                assertEquals(Risk.DEVICE_CONTROL, prepared.pending!!.risk)
                val claimed = f.claim(f.approve(prepared))
                val result = assertIs<Mutation.Applied>(if (status == Status.TIMED_OUT) f.ledger.timeout(claimed.record.callId) else f.ledger.complete(
                    claimed.record.callId, claimed.ticket!!.attemptId, status, "failed",
                )).record
                assertEquals(Risk.DEVICE_CONTROL, result.effectiveRisk)
                assertEquals(Effect.UNKNOWN, result.result!!.effect)
            }
            assertEquals(Risk.ROOT, policy.freezeRisk(definition(Risk.ROOT)).initialRisk)
        }
    }

    @Test fun revokedRuntimeReceiptCannotBeConsumed() {
        var revoked = false
        Fixture(ApprovalPolicy(grantRevoked = { revoked })).use { f ->
            val claimed = f.claim(f.ready())
            val request = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(
                claimed.record.callId, claimed.ticket!!.attemptId, Risk.DEVICE_CONTROL, "control",
            )).record.pending!!
            f.ledger.decideApproval(claimed.record.callId, request.id, Decision.ALLOW_ALWAYS)
            revoked = true
            assertIs<Mutation.Blocked>(f.ledger.checkRiskApproval(claimed.record.callId, claimed.ticket!!.attemptId, request.id))
        }
    }
}
