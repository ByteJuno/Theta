package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class LedgerRegressionTest {
    @Test fun rootLongTermSwitchIsRecheckedForOwnReceipt(): Unit {
        for (decision in listOf(Decision.ALLOW_ALWAYS, Decision.ALLOW_SESSION)) {
            var enabled = true
            Fixture(ApprovalPolicy(allowRootLongTerm = { enabled })).use { f ->
                val ready = f.approve(f.prepare(f.input(definition(Risk.ROOT))), decision)
                enabled = false
                val result = assertIs<Mutation.Applied>(f.ledger.claimExecution(ready.callId, ready.revision))
                assertNull(result.ticket)
                assertEquals(Status.WAITING_APPROVAL, result.record.state)
                f.claim(f.approve(result.record, Decision.ALLOW_ONCE)).also { assertNotNull(it.ticket) }
            }
        }
    }

    @Test fun exitedInstanceCannotAcceptPendingInternalApproval(): Unit = Fixture().use { f ->
        val active = f.claim(f.ready())
        val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(active.record.callId, active.ticket!!.attemptId, Risk.WRITE)).record
        f.ledger.recordActualExit(pending.callId, active.ticket.attemptId, "worker exited")
        assertIs<Mutation.Conflict>(f.ledger.resolveApproval(pending.callId, pending.pending!!, Decision.ALLOW_ALWAYS))
        assertEquals(Risk.READ, f.ledger.get(pending.callId)!!.effectiveRisk)
        assertTrue(f.ledger.get(pending.callId)!!.receipts.isEmpty())
    }

    @Test fun newUnknownEvidenceInvalidatesEarlierResourceClearance(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE, resource = "screen")
        val active = f.claim(f.ready(f.input(d)))
        f.ledger.timeout(active.record.callId)
        f.ledger.recordActualExit(active.record.callId, active.ticket!!.attemptId, "exit")
        f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.VERIFIED_CLEAR)
        val late = assertIs<Mutation.Applied>(f.ledger.complete(active.record.callId, active.ticket.attemptId,
            Status.FAILED, "new external uncertainty", Effect.UNKNOWN)).record
        assertNull(late.isolation!!.resolution)
        val queued = f.ready(f.input(d, chain = "other"))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(queued.callId, queued.revision))
        f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.VERIFIED_CLEAR)
        assertIs<Mutation.AlreadyApplied>(f.ledger.complete(active.record.callId, active.ticket.attemptId,
            Status.FAILED, "new external uncertainty", Effect.UNKNOWN))
        assertNotNull(f.claim(queued).ticket)
    }

    @Test fun renewedEarlierApprovalBlocksLaterApproval(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE)
        val old = f.claim(f.ready(f.input(d)))
        val a = f.input(d)
        val first = f.ready(a)
        val second = f.prepare(f.input(d, run = a.runId, order = 1))
        f.ledger.timeout(old.record.callId)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(first.callId, first.revision))
        f.ledger.recordActualExit(old.record.callId, old.ticket!!.attemptId, "worker exited")
        val renewed = assertIs<Mutation.Applied>(f.ledger.claimExecution(first.callId, first.revision)).record
        assertEquals(Status.WAITING_APPROVAL, renewed.state)
        assertIs<Mutation.Blocked>(f.ledger.resolveApproval(second.callId, second.pending!!, Decision.ALLOW_ONCE))
        assertEquals(second, f.ledger.get(second.callId))
    }

    @Test fun coldRecoveryRequiresDecisionBeforeNewTaskChainClaims(): Unit {
        for (terminal in listOf<Status?>(null, Status.CANCELLED, Status.TIMED_OUT)) Fixture().use { f ->
            val active = f.claim(f.ready()) // READ with no resource still pauses on host loss.
            if (terminal == Status.CANCELLED) f.ledger.cancel(active.record.callId)
            if (terminal == Status.TIMED_OUT) f.ledger.timeout(active.record.callId)
            val ready = f.ready()
            f.reopen()
            assertIs<Mutation.Blocked>(f.ledger.claimExecution(ready.callId, ready.revision))
            assertNotNull(f.claim(f.ready(f.input(chain = "unrelated"))).ticket)
            assertIs<Mutation.Applied>(f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
            assertNotNull(f.claim(ready).ticket)
        }
    }

    @Test fun recoveryDecisionSurvivesReopenAndFailedWrite(): Unit = Fixture().use { f ->
        val active = f.claim(f.ready())
        val ready = f.ready()
        f.reopen()
        f.stage = SqliteWriteStage.BEFORE_COMMIT
        // Reopened fixture has no injector; reopen explicitly with the failing writer.
        f.store.close()
        f.store = TestInvocationStore.open(f.directory) {
            if (it == f.stage) error("injected recovery failure")
        }
        f.ledger = InvocationLedger(f.store, f.gate, ArgumentsValidator { _, _ -> true })
        f.ledger.recoverColdStart("epoch-3")
        assertIs<Mutation.StorageFailure>(f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
        assertTrue(f.ledger.get(active.record.callId)!!.recoveryPending)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(ready.callId, ready.revision))
        f.stage = null
        f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
        f.reopen("epoch-4")
        assertFalse(f.ledger.get(active.record.callId)!!.recoveryPending)
        assertNotNull(f.claim(ready).ticket)
    }

    @Test fun automaticInternalGrantPersistsBeforeReturning(): Unit = Fixture().use { f ->
        val d = definition()
        val grantSource = f.claim(f.ready(f.input(d)))
        val asked = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(grantSource.record.callId,
            grantSource.ticket!!.attemptId, Risk.WRITE)).record
        f.approve(asked, Decision.ALLOW_ALWAYS)
        f.ledger.complete(grantSource.record.callId, grantSource.ticket.attemptId, Status.SUCCEEDED, "done")
        val active = f.claim(f.ready(f.input(d, unattended = true)))
        f.stage = SqliteWriteStage.BEFORE_COMMIT
        assertIs<Mutation.StorageFailure>(f.ledger.requestRiskUpgrade(active.record.callId, active.ticket!!.attemptId, Risk.WRITE))
        assertEquals(Risk.READ, f.ledger.get(active.record.callId)!!.effectiveRisk)
        f.stage = null
        val allowed = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(active.record.callId, active.ticket.attemptId, Risk.WRITE)).record
        assertEquals(Risk.WRITE, allowed.effectiveRisk)
        assertNull(allowed.pending)
        f.reopen()
        assertEquals(Effect.UNKNOWN, f.ledger.get(active.record.callId)!!.result!!.effect)
    }

    @Test fun crossCallResourceRaceHasOneWinner(): Unit = Fixture().use { f ->
        val d = definition(resource = "screen")
        val a = f.ready(f.input(d))
        val b = f.ready(f.input(d, session = "other"))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            val results = listOf(a, b).map { r -> pool.submit(java.util.concurrent.Callable {
                latch.await(); f.ledger.claimExecution(r.callId, r.revision)
            }) }
            latch.countDown()
            val replies = results.map { it.get() }
            assertEquals(1, replies.count { it is Mutation.Applied && it.ticket != null })
            assertEquals(1, replies.count { it is Mutation.Blocked })
            assertEquals(1, f.store.records().count { it.instance?.state == InstanceState.UNCONFIRMED })
        } finally { pool.shutdownNow() }
    }

    private fun rejectsCorruption(transform: (InvocationRecord) -> InvocationRecord, running: Boolean = false) {
        Fixture().use { f ->
            val ready = f.ready(f.input(definition(Risk.WRITE, resource = "screen"), args = "{\"value\":1}"))
            val record = if (running) {
                val active = f.claim(ready)
                assertIs<Mutation.Applied>(f.ledger.complete(ready.callId, active.ticket!!.attemptId, Status.FAILED, "unknown")).record
            } else ready
            f.store.close()
            replacePayload(f.directory, record.callId,
                Json { encodeDefaults = true }.encodeToString(transform(record)).toByteArray())
            assertFailsWith<StoreException> { TestInvocationStore.open(f.directory).close() }
        }
    }

    @Test fun alteredExecutionArgumentsFailClosed(): Unit = rejectsCorruption({ it.copy(executionArgs = "{\"value\":2}") })
    @Test fun inflatedApprovalRiskFailsClosed(): Unit = rejectsCorruption({ r ->
        r.copy(receipts = r.receipts.map { it.copy(request = it.request.copy(risk = Risk.ROOT), decision = Decision.ALLOW_ALWAYS) })
    })
    @Test fun missingUnknownIsolationFailsClosed(): Unit = rejectsCorruption({ it.copy(isolation = null) }, running = true)
}
