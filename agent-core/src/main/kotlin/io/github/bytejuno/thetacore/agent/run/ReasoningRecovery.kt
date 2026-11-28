package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*

internal fun isReasoningParameter(param: String?) = param in setOf(
    "reasoning_effort", "reasoning", "thinking_level", "thinkingConfig", "thinkingBudget")

/** Ratified first-step exception: a confirmed rejection, no output/replay or tool loop. */
internal fun canRemoveReasoning(request: ChatRequest, error: ModelError, mode: ModelExecutionMode,
    steps: Int, partial: Message, confirmed: Boolean, alreadyRemoved: Boolean): Boolean =
    error.code == ModelErrorCode.UNSUPPORTED_PARAMETER && isReasoningParameter(error.param) &&
        request.reasoningLevel != null && mode == ModelExecutionMode.GENERATION_ONLY && steps == 0 &&
        partial.parts.isEmpty() && confirmed && !alreadyRemoved &&
        request.messages.none { m -> m.parts.any { it.replay != null || it is ContentPart.Reasoning } }

fun interface ReasoningCapabilityObserver {
    /** Persist capability information before retry. A failed write must not report a saved capability. */
    fun unsupported(target: ModelTarget, request: ChatRequest)
}
