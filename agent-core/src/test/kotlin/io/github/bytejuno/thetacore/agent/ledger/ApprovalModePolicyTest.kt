package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApprovalModePolicyTest {
    private fun sourceDefinition(
        source: Source,
        risk: Risk,
        key: String = "${source.name.lowercase()}/${risk.name.lowercase()}",
    ): FrozenDefinition = definition(risk = risk, source = source).copy(
        toolKey = key,
        modelName = key.replace('/', '_'),
        owner = source.name.lowercase(),
    )

    @Test
    fun attendedModeMatrixCoversStrictBalancedAndFullAutoAcrossAllSourcesAndRisks() {
        for (mode in ApprovalMode.entries) {
            for (source in Source.entries) {
                for (risk in Risk.entries) {
                    Fixture(ApprovalPolicy(mode = { mode })).use { f ->
                        val def = sourceDefinition(source, risk)
                        val prepared = f.prepare(f.input(def))
                        // InvocationInput elevates MCP initialRisk to at least WRITE.
                        val effectiveRisk = if (source == Source.MCP && risk == Risk.READ) Risk.WRITE else risk
                        val expectedAllow = when (mode) {
                            ApprovalMode.STRICT -> effectiveRisk == Risk.READ && source == Source.FIRST_PARTY
                            ApprovalMode.BALANCED -> effectiveRisk == Risk.READ || effectiveRisk == Risk.WRITE
                            ApprovalMode.FULL_AUTO -> true
                        }
                        if (expectedAllow) {
                            assertEquals(Status.READY, prepared.state, "mode=$mode source=$source risk=$risk")
                            assertNull(prepared.pending)
                        } else {
                            assertEquals(Status.WAITING_APPROVAL, prepared.state, "mode=$mode source=$source risk=$risk")
                            assertNotNull(prepared.pending)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun denyRuleUnknownSideEffectsAndUnattendedCallsNeverRelaxInBalancedOrFullAuto() {
        for (mode in listOf(ApprovalMode.BALANCED, ApprovalMode.FULL_AUTO)) {
            // 1. Explicit deny rule always wins even in FULL_AUTO.
            Fixture(ApprovalPolicy(denied = { it.definition?.toolKey == "blocked/tool" }, mode = { mode })).use { f ->
                val blocked = sourceDefinition(Source.FIRST_PARTY, Risk.READ, "blocked/tool")
                val rejected = f.prepare(f.input(blocked))
                assertEquals(Status.REJECTED, rejected.state)
            }

            // 2. Unattended calls NEVER inherit BALANCED or FULL_AUTO relaxations.
            Fixture(ApprovalPolicy(mode = { mode })).use { f ->
                val fpRead = sourceDefinition(Source.FIRST_PARTY, Risk.READ, "fp/read")
                val fpWrite = sourceDefinition(Source.FIRST_PARTY, Risk.WRITE, "fp/write")
                val tpRead = sourceDefinition(Source.THIRD_PARTY, Risk.READ, "tp/read")
                val devCtrl = sourceDefinition(Source.FIRST_PARTY, Risk.DEVICE_CONTROL, "fp/dev")

                assertEquals(Status.READY, f.prepare(f.input(fpRead, unattended = true)).state)
                assertEquals(Status.REJECTED, f.prepare(f.input(fpWrite, unattended = true)).state)
                assertEquals(Status.REJECTED, f.prepare(f.input(tpRead, unattended = true)).state)
                assertEquals(Status.REJECTED, f.prepare(f.input(devCtrl, unattended = true)).state)

                // A valid attended ALLOW_ALWAYS grant still permits unattended execution when permitsLongTerm allows.
                Fixture(ApprovalPolicy(mode = { ApprovalMode.STRICT })).use { strictFixture ->
                    val asked = strictFixture.prepare(strictFixture.input(fpWrite))
                    strictFixture.approve(asked, Decision.ALLOW_ALWAYS)
                    assertEquals(Status.READY, strictFixture.prepare(strictFixture.input(fpWrite, unattended = true)).state)
                }
            }

            // 3. Unverified UNKNOWN side effects on the same task chain still force ASK for writes/retries.
            Fixture(ApprovalPolicy(mode = { mode })).use { f ->
                val writer = sourceDefinition(Source.FIRST_PARTY, Risk.WRITE, "fp/writer")
                val followUp = sourceDefinition(Source.FIRST_PARTY, Risk.WRITE, "fp/followup")

                val claimed = f.claim(f.ready(f.input(writer)))
                f.ledger.complete(claimed.record.callId, claimed.ticket!!.attemptId, Status.FAILED, "uncertain", Effect.UNKNOWN)

                val next = f.prepare(f.input(followUp))
                assertEquals(Status.WAITING_APPROVAL, next.state, "mode=$mode must still ASK after UNKNOWN")
                assertEquals(listOf(claimed.record.callId), next.pending?.unknownCalls)
            }
        }
    }

    @Test
    fun balancedModeAutoAllowsWriteUpgradesButStillPromptsForDeviceControlAndRootUpgrades() {
        var allowRootLongTerm = false
        Fixture(ApprovalPolicy(allowRootLongTerm = { allowRootLongTerm }, mode = { ApprovalMode.BALANCED })).use { f ->
            val reader = sourceDefinition(Source.THIRD_PARTY, Risk.READ, "plugin/reader")

            val claimed = f.claim(f.ready(f.input(reader)))
            val callId = claimed.record.callId
            val attemptId = claimed.ticket!!.attemptId

            // Upgrading READ -> WRITE in BALANCED succeeds immediately without pending prompt.
            val writeUpgraded = assertIs<Mutation.Applied>(
                f.ledger.requestRiskUpgrade(callId, attemptId, Risk.WRITE, "save cache"),
            ).record
            assertNull(writeUpgraded.pending)
            assertEquals(Risk.WRITE, writeUpgraded.effectiveRisk)
            assertIs<Mutation.AlreadyApplied>(f.ledger.checkImmediateRiskApproval(callId, attemptId, Risk.WRITE, "save cache"))

            // Upgrading -> DEVICE_CONTROL in BALANCED still prompts for human approval.
            val devPrompt = assertIs<Mutation.Applied>(
                f.ledger.requestRiskUpgrade(callId, attemptId, Risk.DEVICE_CONTROL, "tap screen"),
            ).record.pending
            assertNotNull(devPrompt)
            assertEquals(Risk.DEVICE_CONTROL, devPrompt.risk)
            assertIs<Mutation.Applied>(f.ledger.decideApproval(callId, devPrompt.id, Decision.ALLOW_SESSION))
            assertIs<Mutation.AlreadyApplied>(f.ledger.checkRiskApproval(callId, attemptId, devPrompt.id))

            // Upgrading -> ROOT in BALANCED prompts, and blocks ALLOW_ALWAYS while allowRootLongTerm == false.
            val rootPrompt = assertIs<Mutation.Applied>(
                f.ledger.requestRiskUpgrade(callId, attemptId, Risk.ROOT, "run root cmd"),
            ).record.pending
            assertNotNull(rootPrompt)
            assertEquals(Risk.ROOT, rootPrompt.risk)
            assertIs<Mutation.Blocked>(f.ledger.decideApproval(callId, rootPrompt.id, Decision.ALLOW_ALWAYS))
            allowRootLongTerm = true
            assertIs<Mutation.Applied>(f.ledger.decideApproval(callId, rootPrompt.id, Decision.ALLOW_ALWAYS))
            assertIs<Mutation.AlreadyApplied>(f.ledger.checkRiskApproval(callId, attemptId, rootPrompt.id))
        }
    }
}
