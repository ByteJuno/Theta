package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.io.StringWriter
import java.util.UUID
import kotlin.test.*

class HistorySchemaTest {
    private inline fun <reified T : Any> same(value: T) {
        var largestWrite = 0
        val writer = object : StringWriter() {
            override fun write(str: String, off: Int, len: Int) {
                largestWrite = maxOf(largestWrite, len)
                super.write(str, off, len)
            }
        }
        HistoryCodec.writeValue(value, writer)
        assertTrue(largestWrite <= 8192)
        assertEquals(HistoryCodec.json.encodeToString(value), writer.toString())
    }

    @Test fun `streaming nested checkpoint and receipt schema equals canonical with every field populated`() {
        val id = UUID.randomUUID().toString()
        val definition = testDefinition(risk = Risk.WRITE).copy(parallelSafe = true,
            exclusiveResource = "resource", idempotent = true, outputSchema = "{}", timeoutMillis = 37)
        val approval = ApprovalRequest(UUID.randomUUID().toString(), id, definition.digest, "1", 1,
            Risk.ROOT, listOf("older-unknown"), true, "summary\n\"\\".repeat(3000))
        val blob = BlobWrite("raw contents".toByteArray(), "text/plain").ref
        assertNotNull(blob.chunksHash)
        assertNotEquals(blob.id, blob.contentHash)
        val result = LogicalResult(Status.TIMED_OUT, Effect.UNKNOWN, "result", true,
            StoredToolOutput(blob, listOf(blob), "warning"))
        val record = InvocationRecord(2, 17,
            InvocationInput(BindingValue("provider", "request", "response", 3, "wire", id),
                "chain", "session", "run", "step", 4, "{}", definition, true, Risk.WRITE, true, "tool"),
            definition.digest, Status.TIMED_OUT, Risk.ROOT, "{}", approval,
            listOf(ApprovalReceipt(approval, Decision.DENY, ApprovalDenialReason.UNATTENDED_UNKNOWN)),
            ExecutionInstance(UUID.randomUUID().toString(), "epoch", InstanceState.UNCONFIRMED), result,
            listOf(ExitEvidence("attempt", "late", result)), Isolation("unknown", "resource", RecoveryResolution.ACCEPTED_UNCERTAINTY),
            true, true, true, approval.id)
        // Wire-shape fixtures intentionally exercise optional fields together; transition validity
        // is tested separately against the real ledger, not relaxed by this serializer test.
        val checkpoint = ContextCheckpoint(1, "checkpoint", "session", "chain", "run", "next", true,
            7, 4, "digest", "settings", "goal", 8, 9, "CONTINUATION", "summary", "reason", true,
            listOf(record), listOf(definition), "SUMMARY_RESERVED", "budget")
        same(approval); same(record); same(checkpoint)
        same(RetryReceipt("receipt", "session", "caller", "note",
            ExecutionBarrier("session", "chain", "run", "reason", 8, 9, "barrier"), checkpoint))
        // Includes every serialized field even if its default is added later.
        val encoded = HistoryCodec.json.parseToJsonElement(HistoryCodec.json.encodeToString(approval))
        assertEquals(ApprovalRequest.serializer().descriptor.elementsCount,
            (encoded as kotlinx.serialization.json.JsonObject).size)
    }
}
