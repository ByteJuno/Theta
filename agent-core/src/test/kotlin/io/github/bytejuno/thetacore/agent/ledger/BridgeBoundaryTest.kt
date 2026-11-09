package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import org.junit.Test
import kotlin.test.*

class BridgeBoundaryTest {
    private val validator = Schema202012Validator()
    @Test fun returnEvidenceDoesNotReleaseActualExitBarrierAndLateUnknownSurvivesReopen() {
        Fixture().use { f ->
            val input = f.input(definition(resource = "shared", parallel = false))
            val ready = f.ready(input)
            val ticket = f.claim(ready).ticket!!
            f.ledger.timeout(ready.callId)
            assertIs<Mutation.Applied>(f.ledger.recordOutcome(ready.callId, ticket.attemptId,
                Status.SUCCEEDED, "late unknown", Effect.UNKNOWN))
            val r = f.ledger.get(ready.callId)!!
            assertEquals(Status.TIMED_OUT, r.state)
            assertEquals(InstanceState.UNCONFIRMED, r.instance!!.state)
            assertNotNull(r.isolation)
            val next = f.ready(f.input(requireNotNull(input.definition), session = "other", chain = "other-chain"))
            assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
            f.reopen()
            val recovered = f.ledger.get(ready.callId)!!
            assertEquals(Status.TIMED_OUT, recovered.state)
            assertEquals(InstanceState.EXIT_CONFIRMED, recovered.instance!!.state)
            assertTrue(recovered.recoveryPending)
            assertTrue(recovered.evidence.any { it.outcome?.effect == Effect.UNKNOWN })
            assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        }
    }
    @Test fun completeAfterIdenticalReturnEvidenceStillConfirmsExit() {
        Fixture().use { f ->
            val ready = f.ready(); val ticket = f.claim(ready).ticket!!
            f.ledger.recordOutcome(ready.callId, ticket.attemptId, Status.SUCCEEDED, "returned", Effect.NONE)
            f.ledger.complete(ready.callId, ticket.attemptId, Status.SUCCEEDED, "returned", Effect.NONE)
            val record = f.ledger.get(ready.callId)!!
            assertEquals(InstanceState.EXIT_CONFIRMED, record.instance!!.state)
            assertEquals(1, record.evidence.size)
            assertIs<Mutation.AlreadyApplied>(f.ledger.complete(ready.callId, ticket.attemptId, Status.SUCCEEDED, "returned", Effect.NONE))
        }
    }
    @Test fun outcomeWriteFailureDoesNotPretendWorkerExited() {
        Fixture().use { f ->
            val ready = f.ready(); val ticket = f.claim(ready).ticket!!
            f.stage = WriteStage.BEFORE_TEMP
            assertIs<Mutation.StorageFailure>(f.ledger.recordOutcome(ready.callId, ticket.attemptId, Status.SUCCEEDED, "returned"))
            assertEquals(Status.RUNNING, f.ledger.get(ready.callId)!!.state)
            assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(ready.callId)!!.instance!!.state)
        }
    }
    @Test fun real202012ValidationPreservesTypesAndComposition() {
        val schema = """{"type":"object","properties":{"n":{"type":"integer","minimum":2}},"required":["n"],"additionalProperties":false,"oneOf":[{"properties":{"n":{"const":2}}},{"properties":{"n":{"const":3}}}]}"""
        assertTrue(validator.supported(schema))
        assertTrue(validator.valid(schema, "{\"n\":2}"))
        listOf("{}", "{\"n\":\"2\"}", "{\"n\":1}", "{\"n\":4}", "{\"n\":2,\"x\":0}").forEach { assertFalse(validator.valid(schema, it)) }
    }
    @Test fun referenceAndUnsupportedSchemaCannotCauseNetworkLookupOrSilentDowngrade() {
        assertFalse(validator.supported("""{"${'$'}ref":"https://example.invalid/schema"}"""))
        assertFalse(validator.supported("""{"${'$'}ref":"#"}"""))
        assertFalse(validator.supported("""{"format":"email"}"""))
        assertFalse(validator.supported("{"))
        assertTrue(validator.supported("""{"type":"object","additionalProperties":false}"""))
    }
}
