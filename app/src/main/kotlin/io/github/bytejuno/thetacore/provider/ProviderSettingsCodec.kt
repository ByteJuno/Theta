package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.serialization.json.*

/** Private encrypted payload only. Never pass this encoding to a journal or diagnostic sink. */
internal object ProviderSettingsCodec {
    fun encode(settings: OpenAiProviderSettings, token: String): ByteArray = buildJsonObject {
        put("version", 1)
        put("provider", settings.providerId)
        put("base", settings.baseUrl)
        put("model", settings.model)
        put("key", token)
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
            put("schema", c.schemaProfile.name)
        }
    }.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size <= OpenAiSettingsStore.MAX_BYTES - 32) { "Provider settings exceed size limit" }
    }

    fun decode(bytes: ByteArray): OpenAiRunSnapshot {
        require(bytes.size <= OpenAiSettingsStore.MAX_BYTES)
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        val p = root.getValue("parameters").jsonObject
        val c = root.getValue("compat").jsonObject
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
            schemaProfile = SchemaProfile.valueOf(c.getValue("schema").jsonPrimitive.content),
        )
        return OpenAiRunSnapshot(OpenAiProviderSettings(
            root.getValue("provider").jsonPrimitive.content,
            root.getValue("base").jsonPrimitive.content,
            root.getValue("model").jsonPrimitive.content, parameters, compat,
        ), ApiKeyCredentials(root.getValue("key").jsonPrimitive.content))
    }
}
