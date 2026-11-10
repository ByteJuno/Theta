package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class UnknownDenialReceiptTest {
    private fun unknown(f: Fixture): String {
        val claim = f.claim(f.ready(f.input(definition(Risk.WRITE))))
        f.ledger.complete(claim.record.callId, claim.ticket!!.attemptId, Status.FAILED, "unknown")
        return claim.record.callId
    }

    @Test fun internalDenialPersistsWithoutRiskOrOutcomeForgeryAndSurvivesReopen() = Fixture().use { f ->
        val old = unknown(f)
        val d = definition().copy(toolKey = "reader/tool")
        val claim = f.claim(f.ready(f.input(d, unattended = true)))
        val id = claim.record.callId
        assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(id, claim.ticket!!.attemptId, Risk.WRITE, "write"))
        val denied = f.ledger.get(id)!!
        assertEquals(Status.RUNNING, denied.state)
        assertEquals(Risk.READ, denied.effectiveRisk)
        assertNull(denied.pending)
        assertNull(denied.result)
        val receipt = denied.receipts.single()
        assertEquals(ApprovalDenialReason.UNATTENDED_UNKNOWN, receipt.reason)
        assertEquals(listOf(old), receipt.request.unknownCalls)
        assertTrue(receipt.request.internal)
        assertEquals(denied, f.store.records().single { it.callId == id })
        f.ledger.complete(id, claim.ticket.attemptId, Status.SUCCEEDED, "read ran", Effect.NONE)
        f.reopen()
        assertTrue(f.ledger.rejectedUnknownUnattended(id))
        assertEquals(receipt, f.ledger.get(id)!!.receipts.single())
        assertEquals(Effect.NONE, f.ledger.get(id)!!.result!!.effect)
        assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(id)!!.instance!!.state)
        f.ledger.resolveRecovery(old, RecoveryResolution.VERIFIED_CLEAR)
        assertTrue(f.ledger.rejectedUnknownUnattended(id)) // Historical cause is not rewritten.
    }

    @Test fun refusalWriteFailureCannotMasqueradeAsApproval() = Fixture().use { f ->
        unknown(f)
        val claim = f.claim(f.ready(f.input(definition().copy(toolKey = "reader/tool"), unattended = true)))
        f.stage = WriteStage.BEFORE_TEMP
        assertIs<Mutation.StorageFailure>(f.ledger.requestRiskUpgrade(claim.record.callId, claim.ticket!!.attemptId, Risk.WRITE))
        assertEquals(claim.record, f.ledger.get(claim.record.callId))
    }

    @Test fun codecKeepsReasonAndEvidenceWhileLegacyReceiptDefaultsToNull() = Fixture().use { f ->
        unknown(f)
        val denied = f.prepare(f.input(definition(Risk.WRITE), args = "{\"new\":true}", unattended = true))
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val codec = InvocationRecordCodec(json)
        assertEquals(denied, codec.decode(codec.encode(denied)))
        val encoded = json.encodeToJsonElement(denied).jsonObject
        val legacy = JsonObject(encoded + ("receipts" to JsonArray(encoded.getValue("receipts").jsonArray.map {
            JsonObject(it.jsonObject - "reason")
        })))
        val decoded = codec.decode(legacy.toString())
        assertNull(decoded.receipts.single().reason)
        assertEquals(denied.receipts.single().request, decoded.receipts.single().request)
        decoded.validate()
    }

    @Test fun explicitDenyWinsOverUnknownAndDoesNotInventUnknownRefusal() = Fixture(
        policy = ApprovalPolicy(denied = { it.unattended })
    ).use { f ->
        unknown(f)
        val denied = f.prepare(f.input(definition(Risk.WRITE), unattended = true))
        assertEquals(ApprovalDenialReason.POLICY, denied.receipts.single().reason)
        assertFalse(f.ledger.rejectedUnknownUnattended(denied.callId))
    }
}
