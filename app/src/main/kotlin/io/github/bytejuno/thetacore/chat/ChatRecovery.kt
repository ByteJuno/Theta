package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.run.*

internal enum class ChatRecoveryAction { RETRY, READMIT, SUMMARIZE, CONTINUE_SUMMARY, CONTINUE }

internal data class ChatRecoveryToken(
    val identity: RunIdentity,
    val revision: Long,
    val checkpoint: CheckpointInfo?,
    val reconciliation: RetryReconciliation?,
    val context: ChatTaskContext,
)

internal fun recoveryActions(state: ChatState): Set<ChatRecoveryAction> {
    val token = state.recovery ?: return emptySet()
    if (state.loading || state.active || state.pending || token.identity.unattended) return emptySet()
    if (token.identity.sessionId != state.sessionId || token.identity.taskChainId != token.context.taskChainId) return emptySet()
    if (token.reconciliation != null) return setOf(ChatRecoveryAction.RETRY)
    if (token.checkpoint?.canContinue == true) {
        val next = token.checkpoint.nextIdentity ?: return emptySet()
        return if (!next.unattended && next.sessionId == token.identity.sessionId && next.taskChainId == token.identity.taskChainId)
            setOf(ChatRecoveryAction.CONTINUE_SUMMARY) else emptySet()
    }
    if (needsBudgetReadmission(token.checkpoint)) return setOf(ChatRecoveryAction.READMIT, ChatRecoveryAction.SUMMARIZE)
    return setOf(ChatRecoveryAction.CONTINUE, ChatRecoveryAction.SUMMARIZE)
}

internal fun validRecoveryWindow(action: ChatRecoveryAction, window: Long?, current: Long?): Boolean =
    window != null && window in 1024..1_000_000 && (action != ChatRecoveryAction.READMIT || current == null || window > current)
