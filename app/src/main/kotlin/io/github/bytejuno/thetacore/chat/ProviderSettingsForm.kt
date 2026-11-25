package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.provider.MAX_PROFILE_NAME_CHARS
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.provider.ProviderProfileSummary
import io.github.bytejuno.thetacore.provider.ProviderProfilesCatalog
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun ProviderProfileSummary.headerLabel(): String = "$displayName · ${settings.model}"

/** State shared by the profile UI and its load/recovery actions; contains no saved key. */
internal class ProviderSettingsEditor {
    var form by mutableStateOf(ProviderSettingsForm())
    var profiles by mutableStateOf<List<ProviderProfileSummary>>(emptyList())
        private set
    var activeProfileId by mutableStateOf<String?>(null)
        private set
    var creatingNew by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<Int?>(null)
        private set
    var replacingUnreadable by mutableStateOf(false)
        private set
    val editable: Boolean get() = !loading && loadError == null

    suspend fun load(
        store: OpenAiSettingsStore,
        imageProfile: Boolean = false,
        preferredProfileId: String? = null,
    ) {
        loading = true; loadError = null; replacingUnreadable = false; creatingNew = false
        try {
            if (imageProfile) {
                profiles = emptyList()
                activeProfileId = null
                form = withContext(Dispatchers.IO) { ProviderSettingsForm.loaded(store.readSettings()) }
            } else {
                val catalog = withContext(Dispatchers.IO) { store.readProfiles() }
                profiles = catalog.profiles
                activeProfileId = catalog.activeProfileId
                val target = preferredProfileId?.let { id -> catalog.profiles.firstOrNull { it.id == id } }
                    ?: catalog.activeProfile
                    ?: catalog.profiles.firstOrNull()
                form = if (target != null) ProviderSettingsForm.loadedProfile(target) else ProviderSettingsForm()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ProviderSettingsException) {
            profiles = emptyList()
            activeProfileId = null
            if (failure.failure == ProviderSettingsException.Failure.NOT_CONFIGURED) form = ProviderSettingsForm()
            else loadError = providerFailureText(failure.failure)
        } catch (_: Exception) {
            profiles = emptyList()
            activeProfileId = null
            loadError = R.string.chat_provider_read_failed
        } finally {
            loading = false
        }
    }

    fun selectProfileForEdit(profileId: String) {
        val target = profiles.firstOrNull { it.id == profileId } ?: return
        creatingNew = false
        replacingUnreadable = false
        form = ProviderSettingsForm.loadedProfile(target)
    }

    fun startNewProfile() {
        if (!editable) return
        creatingNew = true
        replacingUnreadable = false
        form = ProviderSettingsForm()
    }

    /** Explicit UI action only. Disk is untouched until a new key is entered and saved. */
    fun beginReplacement() {
        if (loading || loadError == null) return
        profiles = emptyList()
        activeProfileId = null
        creatingNew = false
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
    val supportsImageInput: Boolean = true,
    val profileId: String? = null,
    val profileName: String = "",
    val systemPrompt: String = "",
    val noAuth: Boolean = false,
    val savedNoAuth: Boolean = false,
) {
    val canReuseKey: Boolean
        get() = saved != null && !noAuth && !savedNoAuth && endpoint.trim() == saved.baseUrl

    fun withProfileName(value: String): ProviderSettingsForm =
        copy(profileName = value.take(MAX_PROFILE_NAME_CHARS))

    fun withSystemPrompt(value: String): ProviderSettingsForm =
        if (value.length <= MAX_SYSTEM_PROMPT_CHARS) copy(systemPrompt = value) else this

    fun withReasoningField(value: String) = copy(
        reasoningField = value,
        replayReasoning = replayReasoning && value.isNotBlank(),
    )

    fun settings(providerId: String, image: Boolean): OpenAiProviderSettings {
        val compat = if (image) saved?.compat ?: OpenAiChatConfig.Compat() else OpenAiChatConfig.Compat(
            maxTokensField = maxTokensField,
            reasoningField = reasoningField.trim().ifEmpty { null },
            replayReasoning = replayReasoning,
            streamUsage = streamUsage,
            schemaProfile = saved?.compat?.schemaProfile ?: SchemaProfile.FULL,
            explicitCacheControl = explicitCacheControl,
            supportsImageInput = supportsImageInput,
        )
        return OpenAiProviderSettings(
            providerId = providerId,
            baseUrl = endpoint.trim(),
            model = model.trim(),
            parameters = saved?.parameters ?: OpenAiParameters(),
            compat = compat,
            systemPrompt = if (image) null else systemPrompt,
        )
    }

    companion object {
        fun loaded(settings: OpenAiProviderSettings) = ProviderSettingsForm(
            endpoint = settings.baseUrl,
            model = settings.model,
            saved = settings,
            explicitCacheControl = settings.compat.explicitCacheControl,
            maxTokensField = settings.compat.maxTokensField,
            streamUsage = settings.compat.streamUsage,
            reasoningField = settings.compat.reasoningField.orEmpty(),
            replayReasoning = settings.compat.replayReasoning,
            supportsImageInput = settings.compat.supportsImageInput,
            systemPrompt = settings.systemPrompt.orEmpty(),
        )

        fun loadedProfile(profile: ProviderProfileSummary) = loaded(profile.settings).copy(
            profileId = profile.id,
            profileName = profile.displayName,
            noAuth = profile.noAuth,
            savedNoAuth = profile.noAuth,
        )
    }
}
