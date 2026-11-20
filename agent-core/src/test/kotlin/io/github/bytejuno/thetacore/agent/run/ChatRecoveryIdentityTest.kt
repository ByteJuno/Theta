package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.Durability
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class ChatRecoveryIdentityTest {
    private val durability = Durability.PROCESS_CRASH_ONLY

    @Test fun acceptedInputAndTrustedIdentityCommitAtomically() {
        for (stage in HistoryWriteStage.entries) {
            val root = Files.createTempDirectory("chat-admission-atomic")
            val identity = RunIdentity("session", "session", "accepted")
            TestRunHistory.create(root, "session", durability).use { disk ->
                disk.saveChatContext(ChatTaskContext("session", "goal", 0))
                disk.saveChatResult(RunResult(RunState.COMPLETED, "previous task", 0, 0))
            }
            TestRunHistory.open(root, durability) {
                val file = if (stage in setOf(HistoryWriteStage.AFTER_CHUNK, HistoryWriteStage.AFTER_VALUE))
                    "chat-result" else "append"
                if (it.file == file && it.stage == stage) throw java.io.IOException("admission fault")
            }.use { disk ->
                assertFailsWith<HistoryStorageException> {
                    disk.appendChatInput(0, message(Role.USER, textPart("goal")), identity)
                }
            }
            TestRunHistory.open(root, durability).use { disk ->
                val committed = stage == HistoryWriteStage.AFTER_COMMIT
                assertEquals(if (committed) 1L else 0L, disk.revision(), stage.name)
                assertEquals(if (committed) identity else null, disk.chatRecoveryIdentity(), stage.name)
                assertEquals(if (committed) RunState.PAUSED else RunState.COMPLETED, disk.chatResult()?.state, stage.name)
            }
        }
    }

    @Test fun inputCannotPromoteUnattendedOrLegacyIdentityAndKeepsCounters() {
        for (attendance in listOf(null, false, true)) {
            val root = Files.createTempDirectory("chat-admission-attendance")
            TestRunHistory.create(root, "session", durability).use { disk ->
                disk.saveChatContext(ChatTaskContext("session", "goal", 0))
                val prior = RunAccounting("session", "session", "original", 7, 3, attendance)
                disk.saveAccounting(prior)
                val input = message(Role.USER, textPart("continue"))
                val identity = RunIdentity("session", "session", "new")
                assertFailsWith<IllegalArgumentException> { disk.appendChatInput(0, input, identity.copy(sessionId = "other")) }
                assertFailsWith<IllegalArgumentException> { disk.appendChatInput(0, input, identity.copy(taskChainId = "other")) }
                if (attendance == false) disk.appendChatInput(0, input, identity)
                else assertFailsWith<IllegalArgumentException> { disk.appendChatInput(0, input, identity) }
                assertEquals(if (attendance == false) 1L else 0L, disk.revision())
                assertEquals(prior, disk.loadAccounting("session"))
            }
        }
    }

    @Test fun identitySurvivesAccountingCodecAndColdOpenWithoutAttendancePromotion() {
        for (unattended in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-recovery-identity")
            TestRunHistory.create(root, "session", durability).use { disk ->
                disk.saveAccounting(RunAccounting("session", "session", "run", 2, 1, unattended))
                disk.saveBarrier(ExecutionBarrier("session", "session", "run", "uncertain", 2, 1))
            }
            TestRunHistory.open(root, durability).use { disk ->
                assertEquals(RunIdentity("session", "session", "run", unattended), disk.chatRecoveryIdentity())
            }
        }
    }
    @Test fun legacyAndMismatchedAccountingCannotEstablishAttendedIdentity() {
        val root = Files.createTempDirectory("chat-legacy-identity")
        TestRunHistory.create(root, "session", durability).use { disk ->
            disk.saveAccounting(RunAccounting("session", "session", "run", 2, 1))
            disk.saveBarrier(ExecutionBarrier("session", "session", "run", "uncertain", 2, 1))
            assertNull(disk.chatRecoveryIdentity())
            disk.saveAccounting(RunAccounting("session", "session", "other", 2, 1, false))
            assertNull(disk.chatRecoveryIdentity())
        }
    }
    @Test fun oldSummarySettingsRetainWindowAndNewReadmissionDoesNotEnableSummary() {
        val legacy = HistoryCodec.json.decodeFromString<ChatTaskContext>(
            """{"taskChainId":"session","goal":"goal","priorMessageCount":0,"summaryWindowTokens":8192}""")
        assertEquals(8192L, legacy.contextWindowTokens)
        val raw = legacy.copy(summaryWindowTokens = null, contextWindowTokens = 16384)
        assertEquals(raw, HistoryCodec.json.decodeFromString<ChatTaskContext>(HistoryCodec.json.encodeToString(raw)))
        val accounting = RunAccounting("session", "session", "run", 1, 0, true)
        val bytes = java.io.ByteArrayOutputStream().also { HistoryCodec.writeValue(accounting, it) }.toByteArray()
        assertEquals(HistoryCodec.json.encodeToString(accounting), bytes.toString(Charsets.UTF_8))
    }
}
