package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.Durability
import kotlinx.serialization.encodeToString
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class ChatRecoveryIdentityTest {
    private val durability = Durability.PROCESS_CRASH_ONLY

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
