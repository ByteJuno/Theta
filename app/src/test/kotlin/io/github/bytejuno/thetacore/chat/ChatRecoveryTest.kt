package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.run.*
import org.junit.Assert.*
import org.junit.Test

class ChatRecoveryTest {
    private val identity = RunIdentity("chain", "session", "run")
    private val checkpoint = CheckpointInfo("checkpoint", "run", null, 3, 1, "budget", false, pauseClass = "BUDGET")
    private val token = ChatRecoveryToken(identity, 1, checkpoint, null, ChatTaskContext("chain", "goal", 0, null, 4096))
    private fun state(value: ChatRecoveryToken? = token) = ChatState(loading = false, sessionId = "session", recovery = value)

    @Test fun budgetOffersDistinctWindowAndSummaryPaths() {
        assertEquals(setOf(ChatRecoveryAction.READMIT, ChatRecoveryAction.SUMMARIZE), recoveryActions(state()))
        assertFalse(validRecoveryWindow(ChatRecoveryAction.READMIT, 4096, 4096))
        assertFalse(validRecoveryWindow(ChatRecoveryAction.READMIT, 2048, 4096))
        assertTrue(validRecoveryWindow(ChatRecoveryAction.READMIT, 8192, 4096))
        assertTrue(validRecoveryWindow(ChatRecoveryAction.SUMMARIZE, 4096, 4096))
        for (window in listOf(null, 0L, 1023L, 1_000_001L))
            assertFalse(validRecoveryWindow(ChatRecoveryAction.READMIT, window, null))
    }
    @Test fun reconciliationTakesPrecedenceAndSummaryUsesKernelIdentity() {
        assertEquals(setOf(ChatRecoveryAction.RETRY), recoveryActions(state(token.copy(
            reconciliation = RetryReconciliation("receipt", "session", "uncertain")))))
        assertEquals(setOf(ChatRecoveryAction.CONTINUE_SUMMARY), recoveryActions(state(token.copy(
            checkpoint = checkpoint.copy(kind = "CONTINUATION", canContinue = true, nextIdentity = identity.copy(runId = "next"))))))
    }
    @Test fun unattendedMissingBusyAndPendingStatesExposeNoConfirmation() {
        assertTrue(recoveryActions(state(null)).isEmpty())
        assertTrue(recoveryActions(state(token.copy(identity = identity.copy(unattended = true)))).isEmpty())
        assertTrue(recoveryActions(state().copy(active = true)).isEmpty())
        assertTrue(recoveryActions(state().copy(pending = true)).isEmpty())
        assertTrue(recoveryActions(state().copy(loading = true)).isEmpty())
        assertTrue(recoveryActions(state().copy(sessionId = "other")).isEmpty())
        assertTrue(recoveryActions(state(token.copy(checkpoint = checkpoint.copy(canContinue = true,
            nextIdentity = identity.copy(unattended = true))))).isEmpty())
    }
}
