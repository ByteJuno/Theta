package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.R
import androidx.compose.runtime.*
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** State shared by the profile UI and its load/recovery actions; contains no saved key. */
internal class ProviderSettingsEditor {
    var form by mutableStateOf(ProviderSettingsForm())
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<Int?>(null)
        private set
    var replacingUnreadable by mutableStateOf(false)
        private set
    val editable: Boolean get() = !loading && loadError == null

    suspend fun load(store: OpenAiSettingsStore) {
        loading = true; loadError = null; replacingUnreadable = false
        try {
            form = withContext(Dispatchers.IO) { ProviderSettingsForm.loaded(store.readSettings()) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ProviderSettingsException) {
            if (failure.failure == ProviderSettingsException.Failure.NOT_CONFIGURED) form = ProviderSettingsForm()
            else loadError = providerFailureText(failure.failure)
        } catch (_: Exception) { loadError = R.string.chat_provider_read_failed }
        finally { loading = false }
    }

    /** Explicit UI action only. Disk is untouched until a new key is entered and saved. */
    fun beginReplacement() {
        if (loading || loadError == null) return
        form = ProviderSettingsForm()
        replacingUnreadable = true
        loadError = null
    }
}

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
