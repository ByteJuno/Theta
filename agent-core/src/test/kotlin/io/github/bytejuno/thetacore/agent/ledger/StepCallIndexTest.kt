package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import org.junit.Test
import java.util.UUID
import kotlin.test.*

class StepCallIndexTest {
    @Test fun lookupVisitsOnlyTheCompleteStepRegardlessOfUnrelatedHistory() = Fixture().use { f ->
        val template = f.record(f.input(cancelled = true))
        fun row(session: String, run: String, step: String, order: Int = 0) = template.copy(
            input = template.input.copy(sessionId = session, runId = run, stepId = step, callOrder = order,
                binding = template.input.binding.copy(callId = UUID.randomUUID().toString())))
        val target = (0..2).map { row("session", "run", "step", it) }
        for (size in listOf(0, 4000)) {
            val unrelated = (0 until size).map { row("session", "run", "other-$it") } + listOf(
                row("other-session", "run", "step"), row("session", "other-run", "step"))
            val store = object : InvocationStore {
                var reads = 0
                override fun bindCoordinator() = Unit
                override fun records(): List<InvocationRecord> { reads++; return unrelated + target.reversed() }
                override fun save(record: InvocationRecord, expectedRevision: Long?) = error("read-only fixture")
                override fun close() = Unit
            }
            val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
            val ids = target.map { it.callId }
            val before = ledger.stepCallVisits
            repeat(20) {
                val result = assertNotNull(ledger.resultsForStep("session", "run", "step", ids))
                assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                assertNull(ledger.resultsForStep("session", "run", "missing", ids))
            }
            assertEquals(60L, ledger.stepCallVisits - before)
            assertEquals(1, store.reads)
        }
    }

    @Test fun fullMembershipOrderAndScopeSurviveReopen() = Fixture().use { f ->
        val a = f.input(run = "run", cancelled = true)
        val b = f.input(run = "run", order = 1, cancelled = true)
        val ids = listOf(a.binding.callId, b.binding.callId)
        f.record(a); f.record(b)
        fun checkStep() {
            val result = assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids))
            assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
            assertNull(f.ledger.resultsForStep("session", "run", "step", ids.reversed()))
            assertNull(f.ledger.resultsForStep("session", "run", "step", ids.take(1))) // extra stored call must not disappear
            assertNull(f.ledger.resultsForStep("session", "run", "step", ids + UUID.randomUUID().toString()))
            assertNull(f.ledger.resultsForStep("other", "run", "step", ids))
            assertNull(f.ledger.resultsForStep("session", "other", "step", ids))
            assertNull(f.ledger.resultsForStep("session", "run", "other", ids))
            assertNull(f.ledger.resultsForStep("session", "run", "missing", emptyList()))
        }
        checkStep()
        f.reopen()
        checkStep()
    }

    @Test fun registrationIntoSparseLoadedStepPreservesCallOrderRatherThanInsertionOrder(): Unit = Fixture().use { f ->
        val original = f.record(f.input(run = "run", cancelled = true))
        // Store fixtures may be sparse; preserve the previous stable sortedBy projection semantics.
        val sparse = original.copy(revision = original.revision + 1, input = original.input.copy(callOrder = 2))
        f.store.save(sparse, original.revision)
        f.reopen()
        val next = f.record(f.input(run = "run", order = 1, cancelled = true))
        val ids = listOf(next.callId, original.callId)
        assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids))
        assertNull(f.ledger.resultsForStep("session", "run", "step", ids.reversed()))
        f.reopen("epoch-3")
        assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids))
    }

    @Test fun failedAndUncertainRegistrationNeverPublishPhantomMembership() {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) Fixture().use { f ->
            val first = f.record(f.input(run = "run", cancelled = true))
            val second = f.input(run = "run", order = 1, cancelled = true)
            val ids = listOf(first.callId, second.binding.callId)
            f.stage = stage
            val failure = assertIs<Mutation.StorageFailure>(f.ledger.recordInvocation(second))
            assertEquals(stage == SqliteWriteStage.AFTER_COMMIT, failure.uncertain)
            assertNull(f.ledger.get(second.binding.callId))
            assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids.take(1)))
            assertNull(f.ledger.resultsForStep("session", "run", "step", ids))
            f.stage = null
            if (failure.uncertain) {
                assertIs<Mutation.Blocked>(f.ledger.recordInvocation(second))
            } else {
                assertIs<Mutation.Applied>(f.ledger.recordInvocation(second))
                assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(second))
                assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids))
            }
            f.reopen()
            assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids))
            assertNull(f.ledger.resultsForStep("session", "run", "step", ids.take(1)))
        }
    }

    @Test fun failedAndUncertainTerminalWritesKeepAcknowledgedProjectionUntilReopen() {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) Fixture().use { f ->
            val r = f.record(f.input(run = "run"))
            val ids = listOf(r.callId)
            f.stage = stage
            val failure = assertIs<Mutation.StorageFailure>(f.ledger.cancel(r.callId))
            assertEquals(stage == SqliteWriteStage.AFTER_COMMIT, failure.uncertain)
            assertEquals(r, f.ledger.get(r.callId))
            assertNull(f.ledger.resultsForStep("session", "run", "step", ids))
            f.reopen()
            assertEquals(failure.uncertain, f.ledger.resultsForStep("session", "run", "step", ids) != null)
            if (!failure.uncertain) {
                assertIs<Mutation.Applied>(f.ledger.cancel(r.callId))
                assertNotNull(f.ledger.resultsForStep("session", "run", "step", ids))
            }
        }
    }

    @Test fun projectionUsesCurrentTerminalAndRetainsLateEvidenceWithoutDuplicateMembers() = Fixture().use { f ->
        val active = f.claim(f.ready(f.input(run = "run")))
        val id = active.record.callId
        fun result() = f.ledger.resultsForStep("session", "run", "step", listOf(id))
        assertNull(result())
        assertIs<Mutation.Applied>(f.ledger.timeout(id))
        val terminal = assertNotNull(result())
        val late = assertIs<Mutation.Applied>(f.ledger.complete(id, active.ticket!!.attemptId,
            Status.SUCCEEDED, "late outcome", Effect.UNKNOWN)).record
        assertEquals(terminal, result())
        assertEquals("late outcome", f.ledger.get(id)!!.evidence.single().text)
        assertEquals(Effect.UNKNOWN, late.evidence.single().outcome!!.effect)
        assertIs<Mutation.Applied>(f.ledger.resolveRecovery(id, RecoveryResolution.VERIFIED_CLEAR))
        val current = f.ledger.get(id)
        f.reopen()
        assertEquals(current, f.ledger.get(id))
        assertEquals(terminal, result())
    }
}
