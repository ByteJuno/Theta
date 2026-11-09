package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*

/** Policy actions requiring real host infrastructure are explicit; there is no pretend refresh,
 * token estimation, parameter support cache, checkpoint summarizer or alternate credential here. */
enum class RecoveryAction { RETRY, REFRESH_CREDENTIALS, ROTATE_CREDENTIALS, FALLBACK, SHRINK_REQUEST, REMOVE_PARAMETER, INCREASE_OUTPUT }
data class ModelTarget(val id: String, val provider: ModelProvider, val credentials: Credentials)
data class RecoveryProposal(val target: ModelTarget, val request: ChatRequest)
fun interface RecoveryStrategy {
    suspend fun recover(action: RecoveryAction, error: ModelError?, target: ModelTarget, request: ChatRequest): RecoveryProposal?
}

data class RecoveryDecision(val action: RecoveryAction?, val delayMs: Long = 0, val serverUnknown: Boolean = false)

/** Pure protocol 2.8 table. ordinal is the number of previous recoveries for THIS cause in a step.
 * The server-effect gate is intentionally conservative even for an early HTTP error: the present
 * protocol has no trusted 'definitely not submitted' evidence field. */
fun recoveryDecision(error: ModelError, mode: ModelExecutionMode, ordinal: Int): RecoveryDecision {
    if (mode != ModelExecutionMode.GENERATION_ONLY) return RecoveryDecision(null, serverUnknown = true)
    require(ordinal >= 0)
    return when (error.code) {
        ModelErrorCode.AUTH -> RecoveryDecision(if (ordinal == 0) RecoveryAction.REFRESH_CREDENTIALS else null)
        ModelErrorCode.RATE_LIMIT -> RecoveryDecision(if (ordinal < 3) RecoveryAction.ROTATE_CREDENTIALS else null,
            (error.retryAfterMs ?: 1_000L).coerceAtLeast(0))
        ModelErrorCode.QUOTA, ModelErrorCode.CONTENT_BLOCKED -> RecoveryDecision(if (ordinal == 0) RecoveryAction.FALLBACK else null)
        ModelErrorCode.OVERLOADED, ModelErrorCode.SERVER, ModelErrorCode.NETWORK, ModelErrorCode.TIMEOUT ->
            if (ordinal < 3) RecoveryDecision(RecoveryAction.RETRY, 1_000L shl ordinal)
            else RecoveryDecision(if (ordinal == 3) RecoveryAction.FALLBACK else null)
        ModelErrorCode.CONTEXT_TOO_LONG -> RecoveryDecision(if (ordinal == 0) RecoveryAction.SHRINK_REQUEST else null)
        ModelErrorCode.UNSUPPORTED_PARAMETER -> RecoveryDecision(if (ordinal == 0 && error.param != null) RecoveryAction.REMOVE_PARAMETER else null)
        ModelErrorCode.INVALID_REQUEST, ModelErrorCode.MODEL_NOT_FOUND -> RecoveryDecision(null)
        ModelErrorCode.PROTOCOL, ModelErrorCode.UNKNOWN -> RecoveryDecision(if (ordinal == 0) RecoveryAction.RETRY else null)
    }
}

/** Transport evidence is separate from replayable history. Failed partial messages and usage are
 * retained here. A durable host may inject a journal, with the same fail-closed acknowledgement rule
 * as RunHistory. Event payloads are already bounded by validated(); keep-alives need not be stored. */
interface RunJournal {
    fun submitted(requestId: String, targetId: String, mode: ModelExecutionMode)
    fun finished(attempt: ModelAttempt)
}
data class ModelAttempt(val requestId: String, val targetId: String, val mode: ModelExecutionMode,
    val partial: Message, val terminal: ModelEvent?, val usage: TokenUsage?, val cancelled: Boolean)
class MemoryRunJournal : RunJournal {
    private val starts = mutableListOf<Triple<String, String, ModelExecutionMode>>()
    private val ends = mutableListOf<ModelAttempt>()
    @Synchronized override fun submitted(requestId: String, targetId: String, mode: ModelExecutionMode) {
        starts += Triple(requestId, targetId, mode)
    }
    @Synchronized override fun finished(attempt: ModelAttempt) { ends += attempt }
    @Synchronized fun attempts(): List<ModelAttempt> = ends.toList()
    @Synchronized fun submissions(): List<Triple<String, String, ModelExecutionMode>> = starts.toList()
}
