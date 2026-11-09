package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.*

class UnknownToolTest {
    private fun unknown(f: Fixture, args: String = "{", cancelled: Boolean = false) =
        f.input(args = args, cancelled = cancelled).copy(definition = null, modelName = "missing_tool")

    @Test fun unknownIsDurablyTerminalOnRegistrationAndCannotExecute(): Unit = Fixture(
        validator = ArgumentsValidator { _, _ -> error("Unknown tools must not validate arguments") },
        policy = ApprovalPolicy(denied = { error("Unknown tools must not request approval") }),
    ).use { f ->
        val input = unknown(f)
        val record = f.record(input)
        assertEquals(Status.REJECTED, record.state)
        assertEquals(Effect.NONE, record.result!!.effect)
        assertNull(record.input.definition)
        assertNull(record.definitionDigest)
        assertNull(record.instance)
        assertNull(record.executionArgs)
        assertNull(record.pending)
        assertEquals("{", record.input.argsJson)
        assertEquals("missing_tool", record.input.modelName)
        assertEquals(1, record.revision)
        assertIs<Mutation.AlreadyApplied>(f.ledger.validateAndPrepare(record.callId))
        assertIs<Mutation.Conflict>(f.ledger.claimExecution(record.callId, record.revision))
        assertIs<Mutation.Conflict>(f.ledger.recordInvocation(input.copy(modelName = "different")))
        assertIs<Mutation.Conflict>(f.ledger.recordInvocation(input.copy(argsJson = "{}")))
        f.reopen()
        assertEquals(record, f.ledger.get(record.callId))
        assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input))
        assertIs<Mutation.AlreadyApplied>(f.ledger.cancel(record.callId))
        assertIs<Mutation.Conflict>(f.ledger.claimExecution(record.callId, record.revision))
        val message = f.ledger.resultsForStep(input.sessionId, input.runId, input.stepId, listOf(record.callId))!!
        val part = message.parts.single() as ContentPart.ToolResult
        assertEquals(record.callId, part.callId)
        assertTrue(part.isError)
        val result = Json.parseToJsonElement(part.text).jsonObject
        assertEquals("rejected", result["status"]!!.jsonPrimitive.content)
        assertEquals("none", result["effect"]!!.jsonPrimitive.content)
        assertEquals(1, f.store.records().size)
    }

    @Test fun unresolvedRecordsCannotBeForgedIntoExecutableStates(): Unit = Fixture().use { f ->
        val r = f.record(unknown(f))
        for (state in listOf(Status.RECEIVED, Status.READY, Status.WAITING_APPROVAL, Status.RUNNING)) {
            assertFailsWith<IllegalArgumentException> { r.copy(state = state, result = null).validate() }
        }
        assertFailsWith<IllegalArgumentException> { r.copy(executionArgs = "{}").validate() }
        assertFailsWith<IllegalArgumentException> { r.copy(definitionDigest = "invented").validate() }
        assertFailsWith<IllegalArgumentException> { r.copy(effectiveRisk = Risk.WRITE).validate() }
    }

    @Test fun truncatedUnknownCallsRetainCancelledNoneSemantics(): Unit = Fixture().use { f ->
        val input = unknown(f, args = "{}", cancelled = true)
        val r = f.record(input)
        assertEquals(Status.CANCELLED, r.state)
        assertEquals(Effect.NONE, r.result!!.effect)
        assertNull(r.instance)
        f.reopen()
        assertEquals(r, f.ledger.get(r.callId))
    }

    @Test fun existingKnownRecordsWithoutModelNameStillReopen(): Unit = Fixture().use { f ->
        val record = f.record()
        f.store.close()
        val file = f.directory.resolve("${record.callId}.json")
        val json = Json.parseToJsonElement(String(java.nio.file.Files.readAllBytes(file), Charsets.UTF_8)).jsonObject
        val input = kotlinx.serialization.json.JsonObject(json["input"]!!.jsonObject - "modelName")
        val legacy = kotlinx.serialization.json.JsonObject(json + ("input" to input))
        java.nio.file.Files.write(file, legacy.toString().toByteArray(Charsets.UTF_8))
        f.reopen()
        assertEquals(record, f.ledger.get(record.callId))
    }

    @Test fun uncertainUnknownWriteReopensToOneRejectionWithoutExecution(): Unit = Fixture().use { f ->
        val input = unknown(f)
        f.stage = WriteStage.AFTER_REPLACE
        assertTrue(assertIs<Mutation.StorageFailure>(f.ledger.recordInvocation(input)).uncertain)
        f.reopen()
        val record = assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input)).record
        assertEquals(Status.REJECTED, record.state)
        assertEquals(Effect.NONE, record.result!!.effect)
        assertNull(record.instance)
        assertEquals(1, f.store.records().size)
        assertIs<Mutation.Conflict>(f.ledger.claimExecution(record.callId, record.revision))
    }

    @Test fun failedUnknownWriteDoesNotAcknowledgeResultAndCanRetry(): Unit = Fixture().use { f ->
        val input = unknown(f)
        f.stage = WriteStage.BEFORE_REPLACE
        assertIs<Mutation.StorageFailure>(f.ledger.recordInvocation(input))
        assertNull(f.ledger.get(input.binding.callId))
        assertNull(f.ledger.resultsForStep(input.sessionId, input.runId, input.stepId, listOf(input.binding.callId)))
        f.stage = null
        assertEquals(Status.REJECTED, f.record(input).state)
    }
}
