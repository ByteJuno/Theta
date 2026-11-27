package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiChatConfig
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.provider.openai.SchemaProfile
import io.github.bytejuno.thetacore.provider.validProfileDisplayName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ProviderTemplateProtocol(val wire: String, val supported: Boolean) {
    OPENAI_CHAT("openai-chat", true),
    OPENAI_RESPONSES("openai-responses", true),
    ANTHROPIC("anthropic", true),
    GEMINI("gemini", false);

    companion object {
        fun fromWire(value: String): ProviderTemplateProtocol? = entries.firstOrNull { it.wire == value }
    }
}

internal data class ProviderTemplateModel(
    val id: String,
    val displayName: String? = null,
    val contextWindow: Long? = null,
    val maxOutputTokens: Int? = null,
    val reasoningLevels: List<String> = emptyList(),
    val defaultReasoningLevel: String? = null,
)

internal data class ProviderTemplate(
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val entryPath: String,
    val authorUnverified: Boolean,
    val id: String,
    val name: String,
    val protocol: ProviderTemplateProtocol,
    val baseUrl: String,
    val host: String,
    val models: List<ProviderTemplateModel>,
    val compat: OpenAiChatConfig.Compat?,
) {
    val supported: Boolean get() = protocol.supported
}

internal data class ProviderTemplateLoadResult(
    val templates: List<ProviderTemplate>,
    val errors: List<String>,
)

private val TEMPLATE_TOP_KEYS = setOf("id", "name", "protocol", "baseUrl", "models", "compat")
private val TEMPLATE_MODEL_KEYS = setOf(
    "id",
    "displayName",
    "contextWindow",
    "maxOutputTokens",
    "reasoningLevels",
    "defaultReasoningLevel",
    "inputKinds",
    "outputKinds",
    "tools",
    "promptCache",
    "structuredOutput",
)
private val TEMPLATE_MODEL_NULLABLE_KEYS = TEMPLATE_MODEL_KEYS - setOf("id", "displayName")
private val TEMPLATE_TOOL_KEYS = setOf(
    "supported", "parallel", "forcedChoice", "schemaProfile", "maxTools", "maxNameLength", "mediaInResults",
)
private val TEMPLATE_COMPAT_KEYS = setOf(
    "maxTokensField",
    "reasoningField",
    "replayReasoning",
    "streamUsage",
    "schemaProfile",
    "explicitCacheControl",
)
private val TEMPLATE_ID_REGEX = Regex("[A-Za-z0-9._-]{1,128}")
private val TEMPLATE_MEDIA_KINDS = setOf("text", "image", "audio", "video", "document")
private const val MAX_TEMPLATE_MODELS = 256
private const val MAX_TEMPLATE_BASE_URL_CHARS = 4096

internal fun extractTargetHost(endpoint: String): String? =
    runCatching {
        // Use the request's URL parser, including scheme and internationalized-host normalization.
        OpenAiChatConfig("preview", endpoint.trim()).targetHost
    }.getOrNull()

internal fun parseProviderTemplate(
    pluginId: String,
    pluginName: String,
    pluginVersion: String,
    entryPath: String,
    bytes: ByteArray,
    authorUnverified: Boolean = true,
): ProviderTemplate {
    val json = dataJson(bytes)
    dataRequire(json.keys.all { it in TEMPLATE_TOP_KEYS }, "Unknown field in provider template")
    val id = dataString(json, "id")
    dataRequire(TEMPLATE_ID_REGEX.matches(id), "Invalid provider template id")
    val name = dataString(json, "name").trim()
    dataRequire(validProfileDisplayName(name), "Invalid provider template name")
    val protocolWire = dataString(json, "protocol")
    val protocol = ProviderTemplateProtocol.fromWire(protocolWire)
        ?: throw PluginPackageException("Unsupported provider protocol")
    val baseUrl = dataString(json, "baseUrl").trim()
    dataRequire(baseUrl.length <= MAX_TEMPLATE_BASE_URL_CHARS, "Provider baseUrl is too long")

    // Reuse the request's HTTPS / private-cleartext / no-userinfo / no-query URL validation.
    val host = extractTargetHost(baseUrl) ?: throw PluginPackageException("Invalid provider baseUrl")

    val models = parseTemplateModels(json)
    val compat = parseTemplateCompat(json, protocol)

    if (protocol == ProviderTemplateProtocol.OPENAI_CHAT) {
        val effectiveCompat = compat ?: OpenAiChatConfig.Compat()
        val sampleModel = models.firstOrNull()?.id ?: "model"
        try {
            OpenAiProviderSettings(
                providerId = "chat",
                baseUrl = baseUrl,
                model = sampleModel,
                compat = effectiveCompat,
            )
        } catch (_: IllegalArgumentException) {
            throw PluginPackageException("Provider template failed OpenAI settings validation")
        }
    }

    return ProviderTemplate(
        pluginId = pluginId,
        pluginName = pluginName,
        pluginVersion = pluginVersion,
        entryPath = entryPath,
        authorUnverified = authorUnverified,
        id = id,
        name = name,
        protocol = protocol,
        baseUrl = baseUrl,
        host = host,
        models = models,
        compat = if (protocol == ProviderTemplateProtocol.OPENAI_CHAT) (compat ?: OpenAiChatConfig.Compat()) else null,
    )
}

private fun parseTemplateModels(json: JsonObject): List<ProviderTemplateModel> {
    if ("models" !in json) return emptyList()
    val raw = json["models"] as? JsonArray ?: throw PluginPackageException("models must be an array")
    dataRequire(raw.size <= MAX_TEMPLATE_MODELS, "Too many models in provider template")
    val seenIds = linkedSetOf<String>()
    return raw.map { element ->
        val rawModel = element as? JsonObject ?: throw PluginPackageException("Model entry must be an object")
        dataRequire(rawModel.keys.all { it in TEMPLATE_MODEL_KEYS }, "Unknown field in provider template model")
        // PROTOCOL 2.2: nullable model metadata is unknown, just like an omitted field.
        val obj = JsonObject(rawModel.filterNot { (key, value) ->
            key in TEMPLATE_MODEL_NULLABLE_KEYS && value == JsonNull
        })
        val id = templateRequiredString(obj, "id", 256)
        dataRequire(seenIds.add(id), "Duplicate model id in provider template")
        val displayName = templateOptionalString(obj, "displayName", 128)
        val contextWindow = templateOptionalLong(obj, "contextWindow", 1L..100_000_000L)
        val maxOutputTokens = templateOptionalLong(obj, "maxOutputTokens", 1L..10_000_000L)?.toInt()
        val reasoningLevels = templateOptionalStringList(obj, "reasoningLevels", maxItems = 16, maxLen = 64)
        val defaultReasoningLevel = templateOptionalString(obj, "defaultReasoningLevel", 64)
        if (defaultReasoningLevel != null && reasoningLevels.isNotEmpty()) {
            dataRequire(defaultReasoningLevel in reasoningLevels, "defaultReasoningLevel must be in reasoningLevels")
        }
        val inputKinds = templateOptionalStringList(obj, "inputKinds", maxItems = 16, maxLen = 32)
        dataRequire(inputKinds.all { it in TEMPLATE_MEDIA_KINDS }, "Unknown inputKinds value")
        val outputKinds = templateOptionalStringList(obj, "outputKinds", maxItems = 16, maxLen = 32)
        dataRequire(outputKinds.all { it in TEMPLATE_MEDIA_KINDS }, "Unknown outputKinds value")
        validateTemplateToolSupport(obj)
        templateOptionalBoolean(obj, "promptCache")
        templateOptionalBoolean(obj, "structuredOutput")
        ProviderTemplateModel(
            id = id,
            displayName = displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            reasoningLevels = reasoningLevels,
            defaultReasoningLevel = defaultReasoningLevel,
        )
    }
}

private fun validateTemplateToolSupport(model: JsonObject) {
    if ("tools" !in model) return
    val raw = model["tools"] as? JsonObject ?: throw PluginPackageException("tools must be an object")
    dataRequire(raw.keys.all { it in TEMPLATE_TOOL_KEYS }, "Unknown field in provider template tools")
    val obj = JsonObject(raw.filterNot { (key, value) -> key != "supported" && value == JsonNull })
    dataRequire(templateOptionalBoolean(obj, "supported") != null, "tools.supported must be a boolean")
    templateOptionalBoolean(obj, "parallel")
    templateOptionalBoolean(obj, "forcedChoice")
    templateOptionalBoolean(obj, "mediaInResults")
    templateOptionalLong(obj, "maxTools", 0L..Int.MAX_VALUE.toLong())
    templateOptionalLong(obj, "maxNameLength", 0L..Int.MAX_VALUE.toLong())
    templateOptionalString(obj, "schemaProfile", 64)?.let { wire ->
        dataRequire(SchemaProfile.entries.any { it.name == wire }, "Unknown tools.schemaProfile")
    }
}

private fun parseTemplateCompat(
    json: JsonObject,
    protocol: ProviderTemplateProtocol,
): OpenAiChatConfig.Compat? {
    if ("compat" !in json) return null
    val obj = json["compat"] as? JsonObject ?: throw PluginPackageException("compat must be an object")
    dataRequire(obj.keys.all { it in TEMPLATE_COMPAT_KEYS }, "Unknown field in provider template compat")
    if (protocol != ProviderTemplateProtocol.OPENAI_CHAT) {
        dataRequire(obj.isEmpty(), "compat is only supported for openai-chat protocol")
        return null
    }
    val maxTokensField = templateOptionalString(obj, "maxTokensField", 64) ?: "max_completion_tokens"
    val reasoningField = templateOptionalString(obj, "reasoningField", 64)
    val replayReasoning = templateOptionalBoolean(obj, "replayReasoning") ?: false
    val streamUsage = templateOptionalBoolean(obj, "streamUsage") ?: true
    val explicitCacheControl = templateOptionalBoolean(obj, "explicitCacheControl") ?: false
    val schemaWire = templateOptionalString(obj, "schemaProfile", 64)
    val schemaProfile = if (schemaWire == null) {
        SchemaProfile.FULL
    } else {
        SchemaProfile.entries.firstOrNull { it.name == schemaWire }
            ?: throw PluginPackageException("Unknown schemaProfile")
    }
    return try {
        OpenAiChatConfig.Compat(
            maxTokensField = maxTokensField,
            reasoningField = reasoningField,
            replayReasoning = replayReasoning,
            streamUsage = streamUsage,
            schemaProfile = schemaProfile,
            explicitCacheControl = explicitCacheControl,
        )
    } catch (_: IllegalArgumentException) {
        throw PluginPackageException("Invalid compat configuration")
    }
}

private fun templateRequiredString(obj: JsonObject, key: String, maxLen: Int): String {
    val primitive = obj[key] as? JsonPrimitive ?: throw PluginPackageException("$key must be a string")
    dataRequire(primitive.isString, "$key must be a string")
    val value = primitive.content
    dataRequire(
        value.isNotBlank() && value.length <= maxLen && value.none { it < ' ' || it.isISOControl() },
        "Invalid $key",
    )
    return value
}

private fun templateOptionalString(obj: JsonObject, key: String, maxLen: Int): String? {
    if (key !in obj) return null
    return templateRequiredString(obj, key, maxLen)
}

private fun templateOptionalBoolean(obj: JsonObject, key: String): Boolean? {
    if (key !in obj) return null
    val primitive = obj[key] as? JsonPrimitive ?: throw PluginPackageException("$key must be a boolean")
    dataRequire(
        !primitive.isString && (primitive.content == "true" || primitive.content == "false"),
        "$key must be a boolean",
    )
    return primitive.content == "true"
}

private fun templateOptionalLong(obj: JsonObject, key: String, range: LongRange): Long? {
    if (key !in obj) return null
    val value = dataInteger(obj, key)
    dataRequire(value in range, "$key is out of range")
    return value
}

private fun templateOptionalStringList(
    obj: JsonObject,
    key: String,
    maxItems: Int,
    maxLen: Int,
): List<String> {
    if (key !in obj) return emptyList()
    val arr = obj[key] as? JsonArray ?: throw PluginPackageException("$key must be an array")
    dataRequire(arr.size <= maxItems, "$key has too many entries")
    val seen = linkedSetOf<String>()
    return arr.map { item ->
        val primitive = item as? JsonPrimitive ?: throw PluginPackageException("$key items must be strings")
        dataRequire(primitive.isString, "$key items must be strings")
        val value = primitive.content
        dataRequire(
            value.isNotBlank() && value.length <= maxLen && value.none { it < ' ' || it.isISOControl() },
            "Invalid $key item",
        )
        dataRequire(seen.add(value), "Duplicate $key item")
        value
    }
}

/**
 * Reads and strictly validates provider preset JSON templates from an active data selection.
 * Kept outside [PluginDataCatalog] so `:samples:storage-fixture` compiles standalone without `:agent-core`.
 */
internal fun PluginDataCatalog.readProviderTemplates(selection: SelectedPluginData): ProviderTemplateLoadResult {
    val rawEntries = readEntries(selection) { name ->
        name.startsWith("providers/") && name.endsWith(".json")
    }
    val manifest = selection.manifest
    val templates = mutableListOf<ProviderTemplate>()
    val errors = mutableListOf<String>()
    rawEntries.forEach { (entryPath, bytes) ->
        try {
            templates += parseProviderTemplate(
                pluginId = manifest.id,
                pluginName = manifest.name,
                pluginVersion = manifest.version,
                entryPath = entryPath,
                bytes = bytes,
                authorUnverified = true,
            )
        } catch (error: Exception) {
            errors += "${manifest.id}/$entryPath: ${error.message ?: "invalid provider template"}"
        }
    }
    return ProviderTemplateLoadResult(templates.toList(), errors.toList())
}
