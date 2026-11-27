package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiParameters
import io.github.bytejuno.thetacore.plugin.ProviderTemplate

internal fun anthropicTemplateModel(template: ProviderTemplate?, id: String): EnabledModel {
    val model = template?.takeIf { it.protocol.wire == "anthropic" }?.models?.firstOrNull { it.id == id }
    return EnabledModel(id, alias = model?.displayName, parameters = model?.parameters() ?: OpenAiParameters())
}

internal fun anthropicParameters(value: OpenAiParameters, maxTokens: Int? = value.maxOutputTokens,
    effort: String? = value.reasoningLevel) = OpenAiParameters(maxTokens, value.temperature, value.topP,
    effort, value.parallelToolCalls, value.stopSequences)

internal enum class AnthropicEffort(val wire: String?, val label: Int) {
    DEFAULT(null, R.string.anthropic_effort_default),
    LOW("low", R.string.anthropic_effort_low),
    MEDIUM("medium", R.string.anthropic_effort_medium),
    HIGH("high", R.string.anthropic_effort_high),
    MAX("max", R.string.anthropic_effort_max),
}
