package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.provider.openai.ReasoningLevelAdjustment
import io.github.bytejuno.thetacore.agent.run.ReasoningCapabilityObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun chatReasoningRecovery(snapshot: OpenAiRunSnapshot, state: MutableStateFlow<ChatState>) =
    ReasoningCapabilityObserver { _, request ->
        snapshot.recordUnsupportedReasoning()
        state.update { it.copy(reasoningAdjustment = ReasoningLevelAdjustment(requireNotNull(request.reasoningLevel), null)) }
    }
