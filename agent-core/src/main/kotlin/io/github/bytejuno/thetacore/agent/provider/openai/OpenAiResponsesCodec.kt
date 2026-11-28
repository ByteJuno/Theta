package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*
import java.util.Base64

class OpenAiResponsesCodec(
    private val config: OpenAiResponsesConfig,
    private val mediaReader: MediaContentReader? = null,
) {
    private val reserved = setOf("model", "input", "instructions", "tools", "tool_choice", "parallel_tool_calls",
        "stream", "store", "include", "reasoning", "max_output_tokens", "temperature", "top_p", "text",
        "previous_response_id", "conversation", "background", "truncation")

    fun canReplay(data: ReplayData, targetModel: String): Boolean = runCatching {
        if (data.protocol != "openai-responses" || data.model != targetModel ||
            config.models.isNotEmpty() && targetModel !in config.models) return false
        val p = Json.parseToJsonElement(data.payload).jsonObject
        p.keys == setOf("v", "provider", "endpoint", "item") && p["v"] == JsonPrimitive(1) &&
            p.string("provider") == config.providerId && p.string("endpoint") == config.endpoint.toString() &&
            validReasoning(p.getValue("item").jsonObject) && eventSizeBytes(Events.end(0, data)) <= 64 * 1024
    }.getOrDefault(false)

    internal fun replay(model: String, item: JsonObject): ReplayData {
        require(validReasoning(item)) { "Invalid reasoning item" }
        return replayData("openai-responses", model, buildJsonObject {
            put("v", 1); put("provider", config.providerId); put("endpoint", config.endpoint.toString()); put("item", item)
        }.toString())
    }

    private fun validReasoning(item: JsonObject): Boolean =
        item.string("type") == "reasoning" && !item.string("id").isNullOrBlank() &&
            item["summary"] is JsonArray && item.getValue("summary").jsonArray.all {
                it is JsonObject && it.string("type") == "summary_text" && it.string("text") != null
            } && (item["content"] == null || item["content"] == JsonNull ||
                item["content"] is JsonArray && item.getValue("content").jsonArray.all {
                    it is JsonObject && it.string("type") == "reasoning_text" && it.string("text") != null
                }) && (item["encrypted_content"] == null || item["encrypted_content"] == JsonNull ||
                item.string("encrypted_content") != null) &&
            (item["status"] == null || item.string("status") in setOf("completed", "incomplete"))

    fun encode(source: ChatRequest, onSchemaDiagnostics: (ToolSchemaPreparation) -> Unit = {}): String {
        val request = toolMediaRequest(source) { config.supportsImageInput && it == MediaKind.IMAGE }
        require(ImagePayloadBudget.admitsMessages(request.messages)) { "Image request exceeds memory budget" }
        return encodeInternal(request, true, onSchemaDiagnostics)
    }

    fun estimateRequestBytes(source: ChatRequest): Long {
        val request = toolMediaRequest(source) { config.supportsImageInput && it == MediaKind.IMAGE }
        return encodeInternal(request, false).toByteArray(Charsets.UTF_8).size.toLong() +
        request.messages.sumOf { m -> m.mediaParts().sumOf {
            OpenAiChatCodec.IMAGE_BUDGET_BASE_BYTES + it.blob.sizeBytes / OpenAiChatCodec.IMAGE_BUDGET_SIZE_DIVISOR
        } }
    }

    private fun encodeInternal(request: ChatRequest, readMedia: Boolean,
        diagnostics: (ToolSchemaPreparation) -> Unit = {}): String {
        require(config.models.isEmpty() || request.model in config.models)
        require(request.extraBody.keys.none { it in reserved }) { "Reserved Responses field" }
        // Responses has no stop-sequence parameter; never silently discard a configured stop.
        require(request.stopSequences.isEmpty()) { "Responses does not support stop sequences" }
        require(request.tools.map { it.name }.distinct().size == request.tools.size)
        require(request.tools.all { it.name.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        val prepared = ToolSchemaProfiles().prepare(request.tools, config.schemaProfile)
        diagnostics(prepared)
        val tools = prepared.tools
        val choice = request.toolChoice
        require(choice !is ToolChoice.Named || tools.any { it.name == choice.name })
        require(choice != ToolChoice.Required || tools.isNotEmpty())
        val history = history(request, readMedia)
        return buildJsonObject {
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { tool -> addJsonObject {
                put("type", "function"); put("name", tool.name); put("description", tool.description)
                put("parameters", tool.inputSchema)
                // SchemaProfile preserves optional properties; Responses otherwise defaults to strict normalization.
                put("strict", false)
            } } }
            put("model", request.model); put("input", history); put("stream", true); put("store", false)
            putJsonArray("include") { add("reasoning.encrypted_content") }
            request.system?.let { put("instructions", it) }
            if (tools.isNotEmpty()) {
                put("tool_choice", when (choice) {
                    ToolChoice.Auto -> JsonPrimitive("auto")
                    ToolChoice.None -> JsonPrimitive("none")
                    ToolChoice.Required -> JsonPrimitive("required")
                    is ToolChoice.Named -> buildJsonObject { put("type", "function"); put("name", choice.name) }
                })
                request.parallelToolCalls?.let { put("parallel_tool_calls", it) }
            }
            request.reasoningLevel?.let { putJsonObject("reasoning") { put("effort", it) } }
            request.maxOutputTokens?.let { put("max_output_tokens", it) }
            request.temperature?.let { put("temperature", it) }; request.topP?.let { put("top_p", it) }
            request.responseSchema?.let { schema -> putJsonObject("text") { putJsonObject("format") {
                put("type", "json_schema"); put("name", "response"); put("schema", schema); put("strict", false)
            } } }
            request.extraBody.forEach { (k, v) -> put(k, v) }
        }.toString()
    }

    private fun history(source: ChatRequest, readMedia: Boolean): JsonArray {
        val request = toolMediaRequest(source) { config.supportsImageInput && it == MediaKind.IMAGE }
        val output = mutableListOf<JsonElement>()
        var pending = emptyList<ContentPart.ToolCall>()
        val ids = mutableMapOf<String, String>(); val used = mutableSetOf<String>()
        request.messages.forEach { m ->
            m.parts.forEach { part -> part.replay?.let {
                require(m.role == Role.ASSISTANT && part is ContentPart.Reasoning && canReplay(it, request.model)) {
                    "Incompatible replay; checkpoint required"
                }
            } }
            if (m.role == Role.TOOL) {
                require(pending.isNotEmpty() && m.parts.size == pending.size)
                m.parts.zip(pending).forEach { (part, call) ->
                    require(part is ContentPart.ToolResult && part.callId == call.binding.callId)
                    output += buildJsonObject { put("type", "function_call_output"); put("call_id", ids.getValue(part.callId)); put("output", if (part.content.none { it is ContentPart.Media }) JsonPrimitive(part.text) else JsonArray(part.content.map { child -> when (child) {
                        is ContentPart.Text -> buildJsonObject { put("type", "input_text"); put("text", child.text) }
                        is ContentPart.Media -> image(child, readMedia)
                        else -> error("Invalid tool content")
                    } })) }
                }
                pending = emptyList()
            } else {
                require(pending.isEmpty()) { "Missing tool results" }
                if (m.role == Role.USER) {
                    output += buildJsonObject {
                        put("role", "user"); putJsonArray("content") { m.parts.forEach { part -> add(when (part) {
                            is ContentPart.Text -> buildJsonObject { put("type", "input_text"); put("text", part.text) }
                            is ContentPart.Media -> image(part, readMedia)
                            else -> throw IllegalArgumentException("Unsupported user content")
                        }) } }
                    }
                } else m.parts.forEach { part ->
                    output += when (part) {
                        is ContentPart.Text -> buildJsonObject {
                            put("role", "assistant"); put("content", part.text)
                        }
                        is ContentPart.Reasoning -> {
                            require(part.replay != null) { "Required reasoning replay missing" }
                            Json.parseToJsonElement(part.replay!!.payload).jsonObject.getValue("item")
                        }
                        is ContentPart.ToolCall -> {
                            require(part.binding.callId !in ids && part.name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                            val original = part.binding.wireId?.takeIf { part.binding.provider == config.providerId && it.isNotBlank() }
                            val wire = original?.takeIf { used.add(it) } ?: "call_${part.binding.callId.replace("-", "")}".also { require(used.add(it)) }
                            ids[part.binding.callId] = wire
                            buildJsonObject { put("type", "function_call"); put("call_id", wire); put("name", part.name); put("arguments", part.argsJson) }
                        }
                        else -> throw IllegalArgumentException("Unsupported assistant content")
                    }
                }
                pending = m.parts.filterIsInstance<ContentPart.ToolCall>()
            }
        }
        require(pending.isEmpty()) { "Unpaired calls" }
        return JsonArray(output)
    }

    private fun image(part: ContentPart.Media, readMedia: Boolean): JsonObject {
        require(config.supportsImageInput && part.kind == MediaKind.IMAGE)
        require(part.blob.mimeType in setOf("image/jpeg", "image/png", "image/webp") && part.blob.sizeBytes in 1L..ImageLimits.BYTES.toLong())
        val data = if (readMedia) {
            val bytes = requireNotNull(mediaReader?.read(part.blob))
            require(bytes.size.toLong() == part.blob.sizeBytes)
            Base64.getEncoder().encodeToString(bytes)
        } else ""
        return buildJsonObject { put("type", "input_image"); put("image_url", "data:${part.blob.mimeType};base64,$data") }
    }
}
