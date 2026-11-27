package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.serialization.json.*

/** Public editing projection. Saved credentials never leave the encrypted store. */
data class ProviderConnection(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val protocol: String = "openai-chat",
    val noAuth: Boolean = false,
    val compat: OpenAiChatConfig.Compat = OpenAiChatConfig.Compat(),
) {
    init {
        require(validProfileId(providerId) && validProfileDisplayName(displayName))
        require(protocol in setOf("openai-chat", "openai-responses", "anthropic")) { "Protocol is not enabled" }
        require(protocol != "anthropic" || !noAuth) { "Anthropic requires an API key" }
        OpenAiChatConfig(providerId, baseUrl, compat = compat)
    }
}

data class EnabledModel(
    val modelId: String,
    val alias: String? = null,
    val chat: Boolean = true,
    val image: Boolean = false,
    val compat: OpenAiChatConfig.Compat? = null,
    val systemPrompt: String? = null,
    val parameters: OpenAiParameters = OpenAiParameters(),
    // Preserve the adapter identity of migrated profiles for replay compatibility.
    val runtimeProviderId: String? = null,
    val legacyImageSettings: OpenAiProviderSettings? = null,
) {
    init {
        require(modelId.isNotBlank() && modelId.length <= 256)
        require(alias == null || validProfileDisplayName(alias))
        require(chat || image)
        require(systemPrompt == null || systemPrompt.length <= MAX_SYSTEM_PROMPT_CHARS)
    }
    val label: String get() = alias ?: modelId
}

data class ModelRef(val providerId: String, val modelId: String)
data class ModelBindings(val activeChatModelRef: ModelRef? = null, val activeImageModelRef: ModelRef? = null)
data class ConfiguredProvider(val connection: ProviderConnection, val models: List<EnabledModel>)
data class ProviderCatalog(val providers: List<ConfiguredProvider> = emptyList(), val bindings: ModelBindings = ModelBindings()) {
    fun model(ref: ModelRef?): EnabledModel? = providers.firstOrNull { it.connection.providerId == ref?.providerId }
        ?.models?.firstOrNull { it.modelId == ref?.modelId }
}

internal class ConnectionRecord(val connection: ProviderConnection, val models: List<EnabledModel>, val key: String) {
    init {
        require(models.size in 1..256 && models.map { it.modelId }.distinct().size == models.size)
        require(if (connection.noAuth) key.isEmpty() else validApiKey(key))
        require(connection.protocol != "anthropic" || models.none { it.image }) { "Anthropic does not generate images" }
        models.forEach { settings(it) }
    }
    fun settings(model: EnabledModel) = OpenAiProviderSettings(
        model.runtimeProviderId ?: connection.providerId, connection.baseUrl, model.modelId,
        model.parameters, model.compat ?: connection.compat, model.systemPrompt,
    )
    fun snapshot(model: EnabledModel, image: Boolean = false): OpenAiRunSnapshot {
        val imageSettings = model.legacyImageSettings?.takeIf { image }
        val resolved = if (imageSettings == null) settings(model) else OpenAiProviderSettings(
            imageSettings.providerId, connection.baseUrl, model.modelId, imageSettings.parameters,
            imageSettings.compat, imageSettings.systemPrompt)
        val effective = if (connection.protocol == "openai-chat") resolved else OpenAiProviderSettings(
            resolved.providerId, resolved.baseUrl, resolved.model, resolved.parameters, systemPrompt = resolved.systemPrompt)
        return OpenAiRunSnapshot(effective,
        if (connection.noAuth) NoAuthCredentials else ApiKeyCredentials(key), protocol = connection.protocol)
    }
    override fun toString() = "ConnectionRecord([REDACTED])"
}

internal class ConnectionsRecord(val providers: List<ConnectionRecord>, val bindings: ModelBindings) {
    init {
        require(providers.size in 0..128)
        require(providers.map { it.connection.providerId }.distinct().size == providers.size)
        bindings.activeChatModelRef?.let { require(resolve(it).second.chat) }
        bindings.activeImageModelRef?.let { require(resolve(it).second.image) }
    }
    fun resolve(ref: ModelRef): Pair<ConnectionRecord, EnabledModel> {
        val p = providers.first { it.connection.providerId == ref.providerId }
        return p to p.models.first { it.modelId == ref.modelId }
    }
    fun snapshot(ref: ModelRef?, image: Boolean = false): OpenAiRunSnapshot {
        if (ref == null) throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
        val (p, m) = resolve(ref)
        return p.snapshot(m, image)
    }
    fun summary() = ProviderCatalog(providers.map { ConfiguredProvider(it.connection, it.models.toList()) }, bindings)
    override fun toString() = "ConnectionsRecord([REDACTED])"
}

/** v1/v2 decoding remains in the legacy codec; all new writes use one atomic v3 record. */
internal object ProviderConnectionsCodec {
    fun isV3(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["version"]?.jsonPrimitive?.int == 3

    fun migrate(old: ProviderSettingsCodec.CatalogRecord): ConnectionsRecord = ConnectionsRecord(
        old.profiles.map { p -> ConnectionRecord(
            ProviderConnection(p.id, p.displayName, p.settings.baseUrl, noAuth = p.noAuth, compat = p.settings.compat),
            listOf(EnabledModel(p.settings.model, systemPrompt = p.settings.systemPrompt, parameters = p.settings.parameters,
                runtimeProviderId = p.settings.providerId)), p.key,
        ) }, ModelBindings(ModelRef(old.active.id, old.active.settings.model)),
    )

    fun encode(record: ConnectionsRecord): ByteArray = buildJsonObject {
        put("version", 3)
        fun ref(name: String, value: ModelRef?) {
            value?.let { putJsonObject(name) { put("providerId", it.providerId); put("modelId", it.modelId) } }
        }
        ref("chat", record.bindings.activeChatModelRef); ref("image", record.bindings.activeImageModelRef)
        putJsonArray("providers") {
            record.providers.forEach { p -> addJsonObject {
                val c = p.connection
                put("id", c.providerId); put("name", c.displayName); put("protocol", c.protocol)
                put("base", c.baseUrl); put("noAuth", c.noAuth)
                if (!c.noAuth) put("key", p.key)
                put("defaults", settingsJson(OpenAiProviderSettings(c.providerId, c.baseUrl, p.models.first().modelId, compat = c.compat)))
                putJsonArray("models") { p.models.forEach { m -> addJsonObject {
                    put("id", m.modelId); m.alias?.let { put("alias", it) }
                    put("chat", m.chat); put("image", m.image); put("overrideCompat", m.compat != null)
                    m.runtimeProviderId?.let { put("runtimeProvider", it) }
                    put("settings", settingsJson(p.settings(m)))
                    m.systemPrompt?.let { put("system", it) }
                    m.legacyImageSettings?.let { image ->
                        put("legacyImage", settingsJson(image))
                        image.systemPrompt?.let { put("imageSystem", it) }
                    }
                } } }
            } }
        }
    }.toString().toByteArray(Charsets.UTF_8).also {
        if (it.size > OpenAiSettingsStore.MAX_BYTES - 32) { it.fill(0); throw IllegalArgumentException("Provider settings exceed size limit") }
    }

    private fun settingsJson(settings: OpenAiProviderSettings) = buildJsonObject {
        with(ProviderSettingsCodec) { encodeSettingsFields(settings) }
    }

    fun decode(bytes: ByteArray): ConnectionsRecord {
        require(bytes.size <= OpenAiSettingsStore.MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 3)
        fun ref(name: String) = root[name]?.jsonObject?.let {
            ModelRef(it.getValue("providerId").jsonPrimitive.content, it.getValue("modelId").jsonPrimitive.content)
        }
        val providers = root.getValue("providers").jsonArray.map { element ->
            val p = element.jsonObject
            val defaults = ProviderSettingsCodec.decodeSettings(p.getValue("defaults").jsonObject, null)
            val c = ProviderConnection(p.getValue("id").jsonPrimitive.content, p.getValue("name").jsonPrimitive.content,
                p.getValue("base").jsonPrimitive.content, p.getValue("protocol").jsonPrimitive.content,
                p.getValue("noAuth").jsonPrimitive.boolean, defaults.compat)
            val models = p.getValue("models").jsonArray.map { item ->
                val m = item.jsonObject
                val settings = ProviderSettingsCodec.decodeSettings(m.getValue("settings").jsonObject, m["system"]?.jsonPrimitive?.content)
                EnabledModel(m.getValue("id").jsonPrimitive.content, m["alias"]?.jsonPrimitive?.content,
                    m.getValue("chat").jsonPrimitive.boolean, m.getValue("image").jsonPrimitive.boolean,
                    if (m.getValue("overrideCompat").jsonPrimitive.boolean) settings.compat else null,
                    m["system"]?.jsonPrimitive?.content, settings.parameters, m["runtimeProvider"]?.jsonPrimitive?.content,
                    m["legacyImage"]?.jsonObject?.let { ProviderSettingsCodec.decodeSettings(it, m["imageSystem"]?.jsonPrimitive?.content) })
            }
            if (c.noAuth) require(p["key"] == null || p["key"]?.jsonPrimitive?.content == "")
            ConnectionRecord(c, models, if (c.noAuth) "" else p.getValue("key").jsonPrimitive.content)
        }
        return ConnectionsRecord(providers, ModelBindings(ref("chat"), ref("image")))
    }
}
