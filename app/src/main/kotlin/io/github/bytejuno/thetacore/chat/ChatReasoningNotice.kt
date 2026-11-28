package io.github.bytejuno.thetacore.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.provider.openai.ReasoningLevelAdjustment
import io.github.bytejuno.thetacore.provider.AnthropicEffort
import io.github.bytejuno.thetacore.uiStrings

internal fun ChatState.withReasoningAdjustment(snapshot: OpenAiRunSnapshot?): ChatState =
    if (snapshot == null) this else copy(reasoningAdjustment = snapshot.reasoningAdjustment)

@Composable
internal fun ChatReasoningNotice(adjustment: ReasoningLevelAdjustment) {
    val strings = uiStrings()
    fun level(value: String?): String = AnthropicEffort.entries.firstOrNull { it.wire == value }
        ?.let { strings.get(it.label) } ?: io.github.bytejuno.thetacore.provider.GeminiEffort.entries.firstOrNull { it.wire == value }
        ?.let { strings.get(it.label) } ?: value.orEmpty()
    Text(strings.get(R.string.reasoning_level_adjusted, level(adjustment.requested), level(adjustment.effective)),
        style = MaterialTheme.typography.bodyMedium)
}
