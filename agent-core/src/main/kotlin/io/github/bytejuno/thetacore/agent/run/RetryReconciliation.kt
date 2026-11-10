package io.github.bytejuno.thetacore.agent.run

import kotlinx.serialization.Serializable
import io.github.bytejuno.thetacore.agent.protocol.ModelEvent
import io.github.bytejuno.thetacore.agent.protocol.ModelErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Closure is necessary, not proof of remote completion: adapters can catch transport errors
 * and close normally with Failed. Only a validated finish or definite request rejection can
 * release an admission/reservation; ambiguous failures require attended reconciliation. */
internal class ResponseEvidence {
    @Volatile private var terminal: ModelEvent? = null
    @Volatile private var closed = false
    fun observe(source: Flow<ModelEvent>): Flow<ModelEvent> = flow {
        source.collect { event ->
            if (event is ModelEvent.Failed || event is ModelEvent.Finished) terminal = event
            emit(event)
        }
        closed = true
    }
    fun confirms(event: ModelEvent?): Boolean {
        if (!closed || event == null || terminal !== event) return false
        return when (event) {
            is ModelEvent.Finished -> true
            is ModelEvent.Failed -> when (event.error.code) {
                ModelErrorCode.AUTH,
                ModelErrorCode.INVALID_REQUEST,
                ModelErrorCode.MODEL_NOT_FOUND,
                ModelErrorCode.CONTEXT_TOO_LONG,
                ModelErrorCode.UNSUPPORTED_PARAMETER,
                ModelErrorCode.RATE_LIMIT,
                ModelErrorCode.QUOTA,
                ModelErrorCode.CONTENT_BLOCKED -> true
                else -> false // NETWORK/TIMEOUT/PROTOCOL/UNKNOWN and server execution failures
            }
            else -> false
        }
    }
}
internal class UncertainSummaryResponse : IllegalStateException("Summary response not confirmed closed")

/** Host-only inspection, not a model/tool capability. Confirmation permits a new attempt;
 * it does not assert remote cancellation, deduplication, or exactly-once execution. */
data class RetryReconciliation(val id: String, val sessionId: String, val reason: String)

/** One atomic receipt retains the exact evidence acknowledged by the attended host. */
@Serializable
internal data class RetryReceipt(
    val id: String,
    val sessionId: String,
    val callerRunId: String,
    val note: String,
    val barrier: ExecutionBarrier?,
    val checkpoint: ContextCheckpoint?,
)
