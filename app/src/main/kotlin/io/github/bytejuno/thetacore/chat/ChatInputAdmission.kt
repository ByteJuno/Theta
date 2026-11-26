package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.plugin.HostToolExecutionService
import java.util.UUID

internal fun SqliteRunHistory.checkChatAdmission(host: HostToolExecutionService) {
    check(!hasPendingIntent() && pendingStep(host.ledger) == null)
    val previous = chatRecoveryIdentity()
    check(previous?.unattended != true)
    if (retryReconciliation() != null || checkpointInfo()?.kind in setOf("PAUSED", "CONTINUATION"))
        check(previous != null)
}

internal fun SqliteRunHistory.appendUserInput(text: String, media: List<ContentPart>, draft: ChatDraftReceipt? = null): RunIdentity {
    val parts = buildList {
        if (text.isNotBlank()) add(textPart(text))
        addAll(media)
    }
    val chain = chatTaskChainId()
    if (chatContext() == null) saveChatContext(ChatTaskContext(chain, text.trim().ifEmpty { IMAGE_ONLY_GOAL_FALLBACK },
        Math.toIntExact(page(null, 1).totalMessages), chatSummaryWindow(), chatContextWindow()))
    val identity = RunIdentity(chain, sessionId, UUID.randomUUID().toString())
    appendChatInput(revision(), message(Role.USER, *parts.toTypedArray()), identity, draft?.consumption())
    return identity
}
