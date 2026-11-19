package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.Serializable

data class JournalReply(val sequence: Long, val requestId: String, val partial: Message,
    val reason: String, val historyRevision: Long)
data class JournalPage(val replies: List<JournalReply>, val older: Long?)

@Serializable
internal data class StoredAttempt(val sequence: Long, val requestId: String, val targetId: String,
    val mode: ModelExecutionMode, val historyRevision: Long, val partial: StoredMessage? = null,
    val finish: FinishReason? = null, val error: ModelErrorCode? = null, val cancelled: Boolean = false,
    val complete: Boolean = false, val usage: List<Long?> = emptyList()) {
    fun reply(): JournalReply? {
        val reason = when {
            !complete || cancelled || finish == null && error == null -> "INTERRUPTED"
            error != null -> error.name
            finish == FinishReason.REFUSAL || finish == FinishReason.CONTENT_FILTER -> finish.name
            else -> return null
        }
        return JournalReply(sequence, requestId, partial?.let(HistoryCodec::decodeMessage)
            ?: message(Role.ASSISTANT), reason, historyRevision)
    }
}

class StoredRunJournal(private val history: SqliteRunHistory) : RunJournal {
    override fun submitted(requestId: String, targetId: String, mode: ModelExecutionMode) =
        history.journalSubmitted(requestId, targetId, mode)
    override fun finished(attempt: ModelAttempt) = history.journalFinished(attempt)
}

@Serializable
data class ChatTaskContext(val taskChainId: String, val goal: String, val priorMessageCount: Int,
    val summaryWindowTokens: Long? = null)
