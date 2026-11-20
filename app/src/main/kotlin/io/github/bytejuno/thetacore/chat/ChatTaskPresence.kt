package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord
import io.github.bytejuno.thetacore.agent.ledger.Status

internal sealed interface ChatTaskProgress {
    data object Model : ChatTaskProgress
    data class Tool(val name: String) : ChatTaskProgress
    data object Approval : ChatTaskProgress
    data object Recovery : ChatTaskProgress
}

internal fun taskProgress(records: Collection<InvocationRecord>): ChatTaskProgress = when {
    records.any { it.state == Status.RUNNING && it.pending == null } -> ChatTaskProgress.Tool(
        records.first { it.state == Status.RUNNING && it.pending == null }.input.definition?.modelName.orEmpty())
    records.any { it.state == Status.READY } -> ChatTaskProgress.Model
    records.any { it.pending != null } -> ChatTaskProgress.Approval
    records.any { it.recoveryPending } -> ChatTaskProgress.Recovery
    else -> ChatTaskProgress.Model
}

internal interface ChatTaskPresence {
    suspend fun update(executionId: String, progress: ChatTaskProgress)
    suspend fun finish(executionId: String, waiting: ChatTaskProgress?)

    object None : ChatTaskPresence {
        override suspend fun update(executionId: String, progress: ChatTaskProgress) = Unit
        override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) = Unit
    }
}

internal class ChatTaskPresenceException(cause: Throwable) : Exception(cause)
