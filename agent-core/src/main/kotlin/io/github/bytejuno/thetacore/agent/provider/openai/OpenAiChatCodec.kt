package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*

/** OpenAI Chat only (not Responses). Unknown extraBody keys pass unchanged, reserved keys fail closed. */
class OpenAiChatCodec(private val config: OpenAiChatConfig) {
    private val reserved = setOf("model", "messages", "tools", "tool_choice", "parallel_tool_calls", "stream",
        "stream_options", "max_tokens", "max_completion_tokens", "reasoning_effort", "temperature", "top_p",
        "stop", "response_format", "n", "functions", "function_call")

    fun canReplay(data: ReplayData, targetModel: String): Boolean = runCatching {
        if (!config.compat.replayReasoning || data.protocol != "openai-chat" || data.model != targetModel) return false
        val payload = Json.parseToJsonElement(data.payload).jsonObject
        payload.keys == setOf("v", "provider", "endpoint", "field", "text") &&
            payload["v"] == JsonPrimitive(1) && payload.string("provider") == config.providerId &&
            payload.string("endpoint") == config.endpoint.toString() &&
            payload.string("field") == config.compat.reasoningField && payload["text"] is JsonPrimitive &&
            payload["text"]!!.jsonPrimitive.isString
    }.getOrDefault(false)

    internal fun replay(model: String, text: String): ReplayData = replayData("openai-chat", model, buildJsonObject {
        put("v", 1); put("provider", config.providerId); put("endpoint", config.endpoint.toString())
        put("field", config.compat.reasoningField); put("text", text)
    }.toString())

    internal fun reasoningFragmentUnits(model: String): Int {
        if (!config.compat.replayReasoning) return 8_000
        // Measure all metadata after BOTH JSON encodings. A control character in
        // text takes at most seven bytes (\\u0001 plus its escaped backslash).
        val overhead = eventSizeBytes(Events.end(0, replay(model, "")))
        val units = minOf(8_000, (64 * 1024 - overhead) / 7)
        require(units >= 2) { "Reasoning replay metadata exceeds event budget" }
        return units
    }

    fun encode(request: ChatRequest): String {
        require(config.models.isEmpty() || request.model in config.models) { "Model not configured" }
        // Fail before sending/billing, not after receiving an otherwise valid response.
        reasoningFragmentUnits(request.model)
        require(request.extraBody.keys.none { it in reserved || it == config.compat.reasoningField }) {
            "extraBody conflicts with reserved protocol fields"
        }
        require(request.tools.map { it.name }.distinct().size == request.tools.size) { "Duplicate tool names" }
        request.tools.forEach {
            require(it.name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid mapped tool name" }
            checkSchema(it.inputSchema)
        }
        if (request.toolChoice is ToolChoice.Named) require(request.tools.any { it.name == (request.toolChoice as ToolChoice.Named).name })
        if (request.toolChoice == ToolChoice.Required) require(request.tools.isNotEmpty())
        val history = encodeHistory(request)
        return buildJsonObject {
            // Tool catalog first for a stable serialized prefix.
            if (request.tools.isNotEmpty()) putJsonArray("tools") {
                request.tools.sortedBy { it.name }.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name); put("description", tool.description); put("parameters", tool.inputSchema)
                    }
                }) }
            }
            put("model", request.model); put("messages", history); put("stream", true); put("n", 1)
            // Without tools these fields carry no meaning and strict servers reject them.
            if (request.tools.isNotEmpty()) put("tool_choice", when (val choice = request.toolChoice) {
                ToolChoice.Auto -> JsonPrimitive("auto")
                ToolChoice.None -> JsonPrimitive("none")
                ToolChoice.Required -> JsonPrimitive("required")
                is ToolChoice.Named -> buildJsonObject {
                    put("type", "function"); putJsonObject("function") { put("name", choice.name) }
                }
            })
            if (request.tools.isNotEmpty()) request.parallelToolCalls?.let { put("parallel_tool_calls", it) }
            request.reasoningLevel?.let { put("reasoning_effort", it) }
            request.maxOutputTokens?.let { put(config.compat.maxTokensField, it) }
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            if (request.stopSequences.isNotEmpty()) put("stop", JsonArray(request.stopSequences.map(::JsonPrimitive)))
            if (config.compat.streamUsage) putJsonObject("stream_options") { put("include_usage", true) }
            request.responseSchema?.let { schema -> putJsonObject("response_format") {
                put("type", "json_schema"); putJsonObject("json_schema") { put("name", "response"); put("schema", schema) }
            } }
            request.extraBody.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private fun encodeHistory(request: ChatRequest): JsonArray {
        val output = mutableListOf<JsonElement>()
        request.system?.let { output += buildJsonObject { put("role", "system"); put("content", it) } }
        var pending = emptyList<ContentPart.ToolCall>()
        val seen = mutableSetOf<String>()
        val wireIds = mutableMapOf<String, String>()
        val usedWireIds = mutableSetOf<String>()
        request.messages.forEach { message ->
            message.parts.forEach { part -> part.replay?.let {
                require(canReplay(it, request.model)) { "Incompatible replay; checkpoint required" }
                require(part is ContentPart.Reasoning && message.role == Role.ASSISTANT) { "Unsupported replay placement" }
            } }
            if (message.role == Role.TOOL) {
                require(pending.isNotEmpty() && message.parts.size == pending.size) { "Tool results must exactly follow calls" }
                message.parts.zip(pending).forEach { (part, call) ->
                    require(part is ContentPart.ToolResult && part.callId == call.binding.callId) { "Tool result order or identity mismatch" }
                    output += buildJsonObject { put("role", "tool"); put("tool_call_id", wireIds.getValue(part.callId)); put("content", part.text) }
                }
                pending = emptyList()
            } else {
                require(pending.isEmpty()) { "Missing tool results before next message" }
                require(message.parts.none { it is ContentPart.ToolResult })
                if (message.role == Role.USER) require(message.parts.all { it is ContentPart.Text })
                val calls = message.parts.filterIsInstance<ContentPart.ToolCall>()
                val reasonings = message.parts.filterIsInstance<ContentPart.Reasoning>()
                require(reasonings.all { !config.compat.replayReasoning || it.replay != null }) { "Required reasoning replay missing" }
                calls.forEach { call ->
                    require(message.role == Role.ASSISTANT && seen.add(call.binding.callId)) { "Duplicate internal call ID" }
                    require(call.name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                    // History includes rejected calls. Preserve even malformed arguments so
                    // the paired rejection reaches the model; execution validation is host-owned.
                    val original = call.binding.wireId?.takeIf { call.binding.provider == config.providerId && it.isNotBlank() }
                    // Providers may reuse IDs across turns: the first use keeps it, later ones get the stable mapping.
                    val wire = original?.takeIf { usedWireIds.add(it) } ?: "call_${call.binding.callId.replace("-", "")}".also {
                        require(usedWireIds.add(it)) { "Wire ID collision; host must supply a stable collision-free mapping" }
                    }
                    wireIds[call.binding.callId] = wire
                }
                output += buildJsonObject {
                    put("role", message.role.name.lowercase())
                    val text = message.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                    put("content", if (text.isEmpty() && calls.isNotEmpty()) JsonNull else JsonPrimitive(text))
                    if (reasonings.isNotEmpty() && config.compat.replayReasoning) {
                        put(config.compat.reasoningField!!, reasonings.joinToString("") {
                            Json.parseToJsonElement(it.replay!!.payload).jsonObject.string("text")!!
                        })
                    }
                    if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                        calls.forEach { call -> add(buildJsonObject {
                            put("id", wireIds.getValue(call.binding.callId)); put("type", "function")
                            putJsonObject("function") { put("name", call.name); put("arguments", call.argsJson) }
                        }) }
                    }
                }
                pending = calls
            }
        }
        require(pending.isEmpty()) { "Unpaired calls at end of history" }
        return JsonArray(output)
    }

    private fun checkSchema(schema: JsonObject) {
        if (config.compat.schemaProfile == SchemaProfile.NO_TOP_LEVEL_COMBINATORS)
            require(schema.keys.none { it in setOf("oneOf", "anyOf", "allOf") }) { "Schema requires catalog downgrade" }
        if (config.compat.schemaProfile == SchemaProfile.BASIC) {
            val allowed = setOf("type", "properties", "items", "enum", "required", "description")
            fun visit(node: JsonObject) {
                require(node.keys.all { it in allowed }) { "Schema requires catalog downgrade" }
                (node["properties"] as? JsonObject)?.values?.forEach { require(it is JsonObject); visit(it) }
                node["items"]?.let { require(it is JsonObject); visit(it) }
            }
            visit(schema)
        }
    }
}

internal fun JsonObject.string(key: String): String? {
    val value = get(key) ?: return null
    if (value == JsonNull) return null
    protocol(value is JsonPrimitive && value.isString, "$key must be a string")
    return value.jsonPrimitive.content
}
