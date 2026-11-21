package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.R

internal enum class OutputTokenField(val wire: String, val label: Int) {
    COMPLETION("max_completion_tokens", R.string.chat_compat_completion_tokens),
    LEGACY("max_tokens", R.string.chat_compat_max_tokens),
}

/** Non-secret editable state. Saved keys never enter this object or Compose state. */
internal data class ProviderSettingsForm(
    val endpoint: String = "",
    val model: String = "",
    val saved: OpenAiProviderSettings? = null,
    val explicitCacheControl: Boolean = false,
    val maxTokensField: String = "max_completion_tokens",
    val streamUsage: Boolean = true,
    val reasoningField: String = "",
    val replayReasoning: Boolean = false,
) {
    val canReuseKey: Boolean get() = saved != null && endpoint.trim() == saved.baseUrl

    fun withReasoningField(value: String) = copy(reasoningField = value,
        replayReasoning = replayReasoning && value.isNotBlank())

    fun settings(providerId: String, image: Boolean): OpenAiProviderSettings {
        val compat = if (image) saved?.compat ?: OpenAiChatConfig.Compat() else OpenAiChatConfig.Compat(
            maxTokensField = maxTokensField,
            reasoningField = reasoningField.trim().ifEmpty { null },
            replayReasoning = replayReasoning,
            streamUsage = streamUsage,
            schemaProfile = saved?.compat?.schemaProfile ?: SchemaProfile.FULL,
            explicitCacheControl = explicitCacheControl,
        )
        return OpenAiProviderSettings(providerId, endpoint.trim(), model.trim(),
            saved?.parameters ?: OpenAiParameters(), compat)
    }

    companion object {
        fun loaded(settings: OpenAiProviderSettings) = ProviderSettingsForm(
            settings.baseUrl, settings.model, settings, settings.compat.explicitCacheControl,
            settings.compat.maxTokensField, settings.compat.streamUsage,
            settings.compat.reasoningField.orEmpty(), settings.compat.replayReasoning)
    }
}
