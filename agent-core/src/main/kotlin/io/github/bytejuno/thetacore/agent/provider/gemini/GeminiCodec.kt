package io.github.bytejuno.thetacore.agent.provider.gemini

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.serialization.json.*
import java.util.Base64

class GeminiCodec(private val config: GeminiConfig, private val mediaReader: MediaContentReader? = null) {
    private val reserved = setOf("model", "contents", "systemInstruction", "tools", "toolConfig", "generationConfig",
        "system_instruction", "tool_config", "generation_config")

    fun canReplay(data: ReplayData, model: String): Boolean = runCatching {
        if (data.protocol != "gemini" || data.model != model || data.payload.length > 64 * 1024 ||
            config.models.isNotEmpty() && model !in config.models) return false
        val envelope = Json.parseToJsonElement(data.payload).jsonObject
        val single = envelope["part"] as? JsonObject
        val parts = single?.let { listOf(it) } ?: envelope.getValue("parts").jsonArray.map { it.jsonObject }
        envelope.keys == setOf("v", "provider", "endpoint", if (single == null) "parts" else "part") && envelope["v"] == JsonPrimitive(1) &&
            envelope.string("provider") == config.providerId && envelope.string("endpoint") == config.base.toString() &&
            parts.isNotEmpty() && parts.all(::validPart) &&
            (single != null || parts.all { "text" in it && (it["thought"] == JsonPrimitive(true)) == (parts[0]["thought"] == JsonPrimitive(true)) }) &&
            parts.any { !it.string("thoughtSignature").isNullOrBlank() || it["thought"] == JsonPrimitive(true) } &&
            eventSizeBytes(Events.end(0, data)) <= 64 * 1024
    }.getOrDefault(false)

    private fun validPart(p: JsonObject): Boolean = when {
        "functionCall" in p -> p.keys.all { it in setOf("functionCall", "thoughtSignature") } &&
            (p["functionCall"] as? JsonObject)?.let { call -> validCall(call) } == true
        "text" in p -> p.keys.all { it in setOf("text", "thought", "thoughtSignature") } && p.string("text") != null &&
            (p["thought"] == null || p["thought"] == JsonPrimitive(true) || p["thought"] == JsonPrimitive(false))
        else -> p.keys == setOf("thoughtSignature")
    }

    internal fun validCall(c: JsonObject) = c.keys.all { it in setOf("name", "args", "id") } &&
        c.string("name")?.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,63}")) == true &&
        (c["args"] == null || c["args"] is JsonObject) &&
        (c["id"] == null || c.string("id")?.let { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) } == true)

    internal fun replay(model: String, part: JsonObject): ReplayData = replayData("gemini", model, buildJsonObject {
        put("v", 1); put("provider", config.providerId); put("endpoint", config.base.toString()); put("part", part)
    }.toString()).also { require(canReplay(it, model)) { "Invalid Gemini replay" } }

    internal fun replayText(model: String, parts: List<JsonObject>): ReplayData = replayData("gemini", model, buildJsonObject {
        put("v", 1); put("provider", config.providerId); put("endpoint", config.base.toString()); put("parts", JsonArray(parts))
    }.toString()).also { require(canReplay(it, model)) { "Invalid Gemini replay" } }

    private fun replayParts(data: ReplayData): List<JsonObject> {
        val envelope = Json.parseToJsonElement(data.payload).jsonObject
        return (envelope["part"] as? JsonObject)?.let { listOf(it) } ?: envelope.getValue("parts").jsonArray.map { it.jsonObject }
    }

    internal fun canReplayMessage(message: Message, model: String): Boolean = message.parts.all { p ->
        val replay = p.replay
        if (replay == null) p !is ContentPart.Reasoning && (p !is ContentPart.ToolCall || runCatching {
            Json.parseToJsonElement(p.argsJson) is JsonObject
        }.getOrDefault(false)) else runCatching {
            if (message.role != Role.ASSISTANT || !canReplay(replay, model)) return@runCatching false
            val parts = replayParts(replay)
            val raw = parts.first()
            when (p) {
                is ContentPart.ToolCall -> parts.size == 1 && (raw["functionCall"] as? JsonObject)?.let {
                    it.string("name") == p.name && (it["args"] ?: JsonObject(emptyMap())) == Json.parseToJsonElement(p.argsJson) &&
                        it.string("id") == p.binding.wireId && p.binding.provider == config.providerId
                } == true
                is ContentPart.Text -> parts.all { "text" in it && it["thought"] != JsonPrimitive(true) } &&
                    parts.joinToString("") { it.string("text").orEmpty() } == p.text
                is ContentPart.Reasoning -> (parts.all { it["thought"] == JsonPrimitive(true) } &&
                    parts.joinToString("") { it.string("text").orEmpty() } == p.summary.orEmpty()) ||
                    (parts.size == 1 && raw.keys == setOf("thoughtSignature") && p.summary.isNullOrEmpty())
                else -> false
            }
        }.getOrDefault(false)
    }

    internal fun canReplayMessages(messages: List<Message>, model: String): Boolean =
        messages.all { canReplayMessage(it, model) } && runCatching {
            geminiCallIds(messages, config.providerId)
        }.isSuccess

    fun encode(request: ChatRequest, diagnostics: (ToolSchemaPreparation) -> Unit = {}): String {
        require(ImagePayloadBudget.admitsMessages(request.messages))
        return encodeInternal(request, true, diagnostics)
    }
    fun estimateRequestBytes(request: ChatRequest): Long = encodeInternal(request, false).toByteArray(Charsets.UTF_8).size.toLong() +
        request.messages.sumOf { m -> m.parts.filterIsInstance<ContentPart.Media>().sumOf {
            OpenAiChatCodec.IMAGE_BUDGET_BASE_BYTES + it.blob.sizeBytes / OpenAiChatCodec.IMAGE_BUDGET_SIZE_DIVISOR
        } }

    private fun encodeInternal(request: ChatRequest, readMedia: Boolean, diagnostics: (ToolSchemaPreparation) -> Unit = {}): String {
        require(config.models.isEmpty() || request.model in config.models)
        require(request.extraBody.keys.none { it in reserved }) { "Reserved Gemini field" }
        require(request.tools.map { it.name }.distinct().size == request.tools.size)
        require(request.tools.all { it.name.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,63}")) })
        val prepared = ToolSchemaProfiles().prepare(request.tools, config.schemaProfile); diagnostics(prepared)
        val choice = request.toolChoice
        require(choice !is ToolChoice.Named || prepared.tools.any { it.name == choice.name })
        require(choice != ToolChoice.Required || prepared.tools.isNotEmpty())
        return buildJsonObject {
            request.system?.let { putJsonObject("systemInstruction") { putJsonArray("parts") { add(text(it)) } } }
            put("contents", history(request, readMedia))
            if (prepared.tools.isNotEmpty()) putJsonArray("tools") { addJsonObject {
                putJsonArray("functionDeclarations") { prepared.tools.forEach { tool -> addJsonObject {
                    put("name", tool.name); put("description", tool.description)
                    // JSON-schema projection uses the documented JSON Schema alternative, not OpenAPI Schema.
                    put("parametersJsonSchema", tool.inputSchema)
                } } }
            } }
            putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") {
                put("mode", when (choice) { ToolChoice.Auto -> "AUTO"; ToolChoice.None -> "NONE"; else -> "ANY" })
                if (choice is ToolChoice.Named) putJsonArray("allowedFunctionNames") { add(choice.name) }
            } }
            putJsonObject("generationConfig") {
                put("candidateCount", 1)
                request.maxOutputTokens?.let { put("maxOutputTokens", it) }
                request.temperature?.let { put("temperature", it) }; request.topP?.let { put("topP", it) }
                if (request.stopSequences.isNotEmpty()) put("stopSequences", JsonArray(request.stopSequences.map(::JsonPrimitive)))
                request.reasoningLevel?.let { level -> putJsonObject("thinkingConfig") { put("thinkingLevel", level.uppercase(java.util.Locale.ROOT)) } }
                request.responseSchema?.let { put("responseMimeType", "application/json"); put("responseJsonSchema", it) }
            }
            request.extraBody.forEach { (k, v) -> put(k, v) }
        }.toString()
    }

    private fun history(request: ChatRequest, readMedia: Boolean): JsonArray {
        var pending = emptyList<ContentPart.ToolCall>()
        val used = mutableSetOf<String>()
        val ids = geminiCallIds(request.messages, config.providerId)
        return JsonArray(request.messages.map { m ->
            require(m.parts.isNotEmpty() && canReplayMessage(m, request.model)) { "Incompatible replay; checkpoint required" }
            val parts = if (m.role == Role.TOOL) {
                require(pending.isNotEmpty() && pending.size == m.parts.size) { "Incomplete tool result group" }
                m.parts.zip(pending).map { (p, call) ->
                    require(p is ContentPart.ToolResult && p.callId == call.binding.callId) { "Out of order tool result" }
                    buildJsonObject { putJsonObject("functionResponse") {
                        put("name", call.name)
                        ids[call.binding.callId]?.let { put("id", it) }
                        putJsonObject("response") { put(if (p.isError) "error" else "output", p.text) }
                    } }
                }.also { pending = emptyList() }
            } else {
                require(pending.isEmpty()) { "Missing tool results" }
                m.parts.flatMap { p ->
                    if (p is ContentPart.ToolCall) require(m.role == Role.ASSISTANT && used.add(p.binding.callId))
                    p.replay?.let(::replayParts) ?: listOf(when (p) {
                        is ContentPart.Text -> text(p.text)
                        is ContentPart.Media -> { require(m.role == Role.USER); image(p, readMedia) }
                        is ContentPart.ToolCall -> buildJsonObject { putJsonObject("functionCall") {
                            put("name", p.name); put("args", Json.parseToJsonElement(p.argsJson))
                            ids[p.binding.callId]?.let { put("id", it) }
                        } }
                        else -> throw IllegalArgumentException("Unsupported Gemini content")
                    })
                }.also { pending = m.parts.filterIsInstance<ContentPart.ToolCall>() }
            }
            buildJsonObject { put("role", if (m.role == Role.ASSISTANT) "model" else "user"); put("parts", JsonArray(parts)) }
        }.also { require(pending.isEmpty()) { "Unpaired calls" } })
    }

    private fun text(value: String) = buildJsonObject { put("text", value) }
    private fun image(p: ContentPart.Media, readMedia: Boolean): JsonObject {
        require(p.kind == MediaKind.IMAGE && p.blob.mimeType in setOf("image/jpeg", "image/png", "image/webp"))
        require(p.blob.sizeBytes in 1L..ImageLimits.BYTES.toLong())
        val data = if (readMedia) {
            val bytes = requireNotNull(mediaReader?.read(p.blob)); require(bytes.size.toLong() == p.blob.sizeBytes)
            Base64.getEncoder().encodeToString(bytes)
        } else ""
        return buildJsonObject { putJsonObject("inlineData") { put("mimeType", p.blob.mimeType); put("data", data) } }
    }
}
