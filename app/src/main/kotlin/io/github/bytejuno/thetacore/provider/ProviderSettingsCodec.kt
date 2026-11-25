package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.serialization.json.*

internal const val DEFAULT_PROFILE_ID = "default"
internal const val MAX_PROFILES = 64
internal const val MAX_PROFILE_NAME_CHARS = 128
private val PROFILE_ID_REGEX = Regex("[A-Za-z0-9._-]{1,64}")

internal fun validProfileId(id: String): Boolean = PROFILE_ID_REGEX.matches(id)

internal fun validProfileDisplayName(name: String): Boolean =
    name.isNotBlank() && name.length <= MAX_PROFILE_NAME_CHARS && name.none { it < ' ' || it.isISOControl() }

/**
 * Generates a valid profile display name fallback from a model identifier without modifying
 * the model identifier itself, parameters, or credentials.
 */
internal fun fallbackProfileDisplayName(model: String): String {
    val sanitized = buildString {
        model.forEach { ch ->
            if (ch >= ' ' && !ch.isISOControl()) append(ch)
        }
    }.trim().take(MAX_PROFILE_NAME_CHARS).trim()
    return if (validProfileDisplayName(sanitized)) sanitized else DEFAULT_PROFILE_ID
}

data class ProviderProfileSummary(
    val id: String,
    val displayName: String,
    val settings: OpenAiProviderSettings,
    val noAuth: Boolean = false,
) {
    init {
        require(validProfileId(id)) { "Invalid profile identifier" }
        require(validProfileDisplayName(displayName)) { "Invalid profile display name" }
    }
    override fun toString() = "ProviderProfileSummary(id=$id, [configuration])"
}

data class ProviderProfilesCatalog(
    val activeProfileId: String?,
    val profiles: List<ProviderProfileSummary>,
) {
    val activeProfile: ProviderProfileSummary?
        get() = profiles.firstOrNull { it.id == activeProfileId }
}

/** Private encrypted payload only. Never pass this encoding to a journal or diagnostic sink. */
internal object ProviderSettingsCodec {
    internal class Record(
        val settings: OpenAiProviderSettings,
        val key: String,
        val noAuth: Boolean = false,
    ) {
        fun toSnapshot(): OpenAiRunSnapshot = OpenAiRunSnapshot(
            settings,
            if (noAuth) NoAuthCredentials else ApiKeyCredentials(key),
        )
        override fun toString() = "ProviderRecord([REDACTED])"
    }

    internal class ProfileRecord(
        val id: String,
        val displayName: String,
        val settings: OpenAiProviderSettings,
        val key: String,
        val noAuth: Boolean = false,
    ) {
        init {
            require(validProfileId(id)) { "Invalid profile identifier" }
            require(validProfileDisplayName(displayName)) { "Invalid profile display name" }
            if (noAuth) {
                require(key.isEmpty()) { "Unauthenticated profile must not retain an API key" }
            } else {
                require(validApiKey(key)) { "Invalid API key" }
            }
        }
        fun summary() = ProviderProfileSummary(id, displayName, settings, noAuth)
        fun toRecord() = Record(settings, key, noAuth)
        override fun toString() = "ProviderProfileRecord([REDACTED])"
    }

    internal class CatalogRecord(
        val activeProfileId: String,
        val profiles: List<ProfileRecord>,
    ) {
        init {
            require(profiles.size in 1..MAX_PROFILES) { "Invalid profile count" }
            val ids = profiles.map { it.id }
            require(ids.distinct().size == ids.size) { "Duplicate profile ID" }
            require(activeProfileId in ids) { "Active profile missing from catalog" }
        }
        val active: ProfileRecord
            get() = profiles.first { it.id == activeProfileId }
        fun toSummaryCatalog() = ProviderProfilesCatalog(activeProfileId, profiles.map { it.summary() })
        override fun toString() = "ProviderCatalogRecord([REDACTED])"
    }

    fun encode(settings: OpenAiProviderSettings, token: String): ByteArray {
        if (settings.systemPrompt != null) {
            return encodeCatalog(
                CatalogRecord(
                    activeProfileId = DEFAULT_PROFILE_ID,
                    profiles = listOf(
                        ProfileRecord(
                            DEFAULT_PROFILE_ID,
                            fallbackProfileDisplayName(settings.model),
                            settings,
                            token,
                            noAuth = false,
                        ),
                    ),
                ),
            )
        }
        require(validApiKey(token)) { "Invalid API key" }
        return checkEncodedSize(buildJsonObject {
            put("version", 1)
            encodeSettingsFields(settings)
            put("key", token)
        })
    }

    fun encodeCatalog(catalog: CatalogRecord): ByteArray = checkEncodedSize(buildJsonObject {
        put("version", 2)
        put("active", catalog.activeProfileId)
        putJsonArray("profiles") {
            catalog.profiles.forEach { profile ->
                addJsonObject {
                    put("id", profile.id)
                    put("name", profile.displayName)
                    encodeSettingsFields(profile.settings)
                    put("noAuth", profile.noAuth)
                    if (!profile.noAuth) put("key", profile.key)
                    profile.settings.systemPrompt?.let { put("system", it) }
                }
            }
        }
    })

    private fun JsonObjectBuilder.encodeSettingsFields(settings: OpenAiProviderSettings) {
        put("provider", settings.providerId)
        put("base", settings.baseUrl)
        put("model", settings.model)
        putJsonObject("parameters") {
            val p = settings.parameters
            p.maxOutputTokens?.let { put("maxOutput", it) }
            p.temperature?.let { put("temperature", it) }
            p.topP?.let { put("topP", it) }
            p.reasoningLevel?.let { put("reasoning", it) }
            p.parallelToolCalls?.let { put("parallel", it) }
            put("stop", JsonArray(p.stopSequences.map(::JsonPrimitive)))
        }
        putJsonObject("compat") {
            val c = settings.compat
            put("maxTokensField", c.maxTokensField)
            c.reasoningField?.let { put("reasoningField", it) }
            put("replayReasoning", c.replayReasoning)
            put("streamUsage", c.streamUsage)
            put("explicitCacheControl", c.explicitCacheControl)
            put("schema", c.schemaProfile.name)
        }
    }

    private fun checkEncodedSize(json: JsonObject): ByteArray =
        json.toString().toByteArray(Charsets.UTF_8).also {
            if (it.size > OpenAiSettingsStore.MAX_BYTES - 32) {
                it.fill(0)
                throw IllegalArgumentException("Provider settings exceed size limit")
            }
        }

    fun decode(bytes: ByteArray): OpenAiRunSnapshot = decodeRecord(bytes).toSnapshot()

    fun decodeRecord(bytes: ByteArray): Record = decodeCatalog(bytes).active.toRecord()

    fun decodeCatalog(bytes: ByteArray): CatalogRecord {
        require(bytes.size <= OpenAiSettingsStore.MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        return when (root.getValue("version").jsonPrimitive.int) {
            1 -> {
                val settings = decodeSettings(root, systemPrompt = null)
                val key = root.getValue("key").jsonPrimitive.content
                require(validApiKey(key))
                val defaultName = fallbackProfileDisplayName(settings.model)
                CatalogRecord(
                    activeProfileId = DEFAULT_PROFILE_ID,
                    profiles = listOf(ProfileRecord(DEFAULT_PROFILE_ID, defaultName, settings, key, noAuth = false)),
                )
            }
            2 -> {
                val activeId = root.getValue("active").jsonPrimitive.content
                val entries = root.getValue("profiles").jsonArray
                require(entries.size in 1..MAX_PROFILES)
                val profiles = entries.map { element ->
                    val obj = element.jsonObject
                    val id = obj.getValue("id").jsonPrimitive.content
                    val name = obj.getValue("name").jsonPrimitive.content.trim()
                    val system = obj["system"]?.jsonPrimitive?.content
                    val noAuth = obj["noAuth"]?.jsonPrimitive?.boolean ?: false
                    val key = if (noAuth) {
                        require("key" !in obj || obj.getValue("key").jsonPrimitive.content.isEmpty())
                        ""
                    } else {
                        obj.getValue("key").jsonPrimitive.content
                    }
                    val settings = decodeSettings(obj, system)
                    ProfileRecord(id, name, settings, key, noAuth)
                }
                CatalogRecord(activeId, profiles)
            }
            else -> throw IllegalArgumentException("Unsupported provider settings version")
        }
    }

    private fun decodeSettings(obj: JsonObject, systemPrompt: String?): OpenAiProviderSettings {
        val p = obj.getValue("parameters").jsonObject
        val c = obj.getValue("compat").jsonObject
        val parameters = OpenAiParameters(
            maxOutputTokens = p["maxOutput"]?.jsonPrimitive?.int,
            temperature = p["temperature"]?.jsonPrimitive?.double,
            topP = p["topP"]?.jsonPrimitive?.double,
            reasoningLevel = p["reasoning"]?.jsonPrimitive?.content,
            parallelToolCalls = p["parallel"]?.jsonPrimitive?.boolean,
            stopSequences = p.getValue("stop").jsonArray.map { it.jsonPrimitive.content },
        )
        val compat = OpenAiChatConfig.Compat(
            maxTokensField = c.getValue("maxTokensField").jsonPrimitive.content,
            reasoningField = c["reasoningField"]?.jsonPrimitive?.content,
            replayReasoning = c.getValue("replayReasoning").jsonPrimitive.boolean,
            streamUsage = c.getValue("streamUsage").jsonPrimitive.boolean,
            explicitCacheControl = c["explicitCacheControl"]?.jsonPrimitive?.boolean ?: false,
            schemaProfile = SchemaProfile.valueOf(c.getValue("schema").jsonPrimitive.content),
        )
        return OpenAiProviderSettings(
            providerId = obj.getValue("provider").jsonPrimitive.content,
            baseUrl = obj.getValue("base").jsonPrimitive.content,
            model = obj.getValue("model").jsonPrimitive.content,
            parameters = parameters,
            compat = compat,
            systemPrompt = systemPrompt,
        )
    }
}
