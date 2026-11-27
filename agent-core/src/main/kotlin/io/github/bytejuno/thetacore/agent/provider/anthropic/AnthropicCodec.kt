package io.github.bytejuno.thetacore.agent.provider.anthropic

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.serialization.json.*
import java.util.Base64

class AnthropicCodec(private val config: AnthropicConfig, private val mediaReader: MediaContentReader? = null) {
    private val reserved = setOf("model", "messages", "system", "tools", "tool_choice", "stream", "max_tokens",
        "thinking", "output_config", "temperature", "top_p", "stop_sequences")

    fun canReplay(data: ReplayData, targetModel: String): Boolean = runCatching {
        if (data.protocol != "anthropic" || data.model != targetModel ||
            config.models.isNotEmpty() && targetModel !in config.models || data.payload.length > 64 * 1024) return false
        val p = Json.parseToJsonElement(data.payload).jsonObject
        p.keys == setOf("v", "provider", "endpoint", "block") && p["v"] == JsonPrimitive(1) &&
            p.string("provider") == config.providerId && p.string("endpoint") == config.endpoint.toString() &&
            validThinking(p.getValue("block").jsonObject) && eventSizeBytes(Events.end(0, data)) <= 64 * 1024
    }.getOrDefault(false)

    private fun validThinking(block: JsonObject) = when (block.string("type")) {
        "thinking" -> block.string("thinking") != null && !block.string("signature").isNullOrBlank()
        "redacted_thinking" -> !block.string("data").isNullOrBlank()
        else -> false
    }

    internal fun replay(model: String, block: JsonObject): ReplayData {
        require(validThinking(block)) { "Incomplete thinking replay" }
        return replayData("anthropic", model, buildJsonObject {
            put("v", 1); put("provider", config.providerId); put("endpoint", config.endpoint.toString()); put("block", block)
        }.toString())
    }

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
        require(request.extraBody.keys.none { it in reserved }) { "Reserved Anthropic field" }
        require(request.tools.map { it.name }.distinct().size == request.tools.size)
        require(request.tools.all { it.name.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        require(request.temperature == null || request.temperature!! <= 1.0)
        val prepared = ToolSchemaProfiles().prepare(request.tools, config.schemaProfile); diagnostics(prepared)
        val tools = prepared.tools; val choice = request.toolChoice
        require(choice !is ToolChoice.Named || tools.any { it.name == choice.name })
        require(choice != ToolChoice.Required || tools.isNotEmpty())
        return buildJsonObject {
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { t -> addJsonObject {
                put("name", t.name); put("description", t.description); put("input_schema", t.inputSchema)
            } } }
            request.system?.let { putJsonArray("system") { addJsonObject { put("type", "text"); put("text", it) } } }
            put("messages", history(request, readMedia)); put("model", request.model); put("stream", true)
            put("max_tokens", request.maxOutputTokens ?: 4096)
            if (tools.isNotEmpty()) putJsonObject("tool_choice") {
                put("type", when (choice) { ToolChoice.Auto -> "auto"; ToolChoice.None -> "none"; ToolChoice.Required -> "any"; is ToolChoice.Named -> "tool" })
                if (choice is ToolChoice.Named) put("name", choice.name)
                if (choice != ToolChoice.None) request.parallelToolCalls?.let { put("disable_parallel_tool_use", !it) }
            }
            request.reasoningLevel?.let { putJsonObject("thinking") { put("type", "adaptive") } }
            if (request.reasoningLevel != null || request.responseSchema != null) putJsonObject("output_config") {
                request.reasoningLevel?.let { put("effort", it) }
                request.responseSchema?.let { schema -> putJsonObject("format") { put("type", "json_schema"); put("schema", schema) } }
            }
            request.temperature?.let { put("temperature", it) }; request.topP?.let { put("top_p", it) }
            if (request.stopSequences.isNotEmpty()) put("stop_sequences", JsonArray(request.stopSequences.map(::JsonPrimitive)))
            request.extraBody.forEach { (k, v) -> put(k, v) }
        }.toString()
    }

    private fun history(request: ChatRequest, readMedia: Boolean): JsonArray {
        data class Turn(val role: String, val toolResults: Boolean, val blocks: MutableList<JsonObject>)
        val turns = mutableListOf<Turn>()
        var pending = emptyList<ContentPart.ToolCall>()
        val ids = mutableMapOf<String, String>(); val used = mutableSetOf<String>()
        request.messages.forEach { m ->
            require(m.parts.isNotEmpty())
            m.parts.forEach { p -> p.replay?.let {
                require(m.role == Role.ASSISTANT && p is ContentPart.Reasoning && canReplay(it, request.model)) { "Incompatible replay; checkpoint required" }
            } }
            val blocks = if (m.role == Role.TOOL) {
                require(pending.isNotEmpty() && pending.size == m.parts.size)
                m.parts.zip(pending).map { (p, call) ->
                    require(p is ContentPart.ToolResult && p.callId == call.binding.callId)
                    buildJsonObject { put("type", "tool_result"); put("tool_use_id", ids.getValue(p.callId)); put("content", p.text); put("is_error", p.isError) }
                }.also { pending = emptyList() }
            } else {
                require(pending.isEmpty()) { "Missing tool results" }
                m.parts.map { p -> when (p) {
                    is ContentPart.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                    is ContentPart.Media -> { require(m.role == Role.USER); image(p, readMedia) }
                    is ContentPart.Reasoning -> {
                        require(m.role == Role.ASSISTANT && p.replay != null)
                        Json.parseToJsonElement(p.replay!!.payload).jsonObject.getValue("block").jsonObject
                    }
                    is ContentPart.ToolCall -> {
                        require(m.role == Role.ASSISTANT && p.binding.callId !in ids && p.name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                        val original = p.binding.wireId?.takeIf { p.binding.provider == config.providerId && it.matches(Regex("[A-Za-z0-9_-]{1,256}")) }
                        val wire = original?.takeIf { used.add(it) } ?: "toolu_${p.binding.callId.replace("-", "")}".also { require(used.add(it)) }
                        ids[p.binding.callId] = wire
                        val args = Json.parseToJsonElement(p.argsJson); require(args is JsonObject)
                        buildJsonObject { put("type", "tool_use"); put("id", wire); put("name", p.name); put("input", args) }
                    }
                    else -> throw IllegalArgumentException("Unsupported content")
                } }.also { pending = m.parts.filterIsInstance<ContentPart.ToolCall>() }
            }.toMutableList()
            // Thinking-only PAUSED messages remain replayable. Anthropic cannot cache a thinking
            // block explicitly, so leave that optional optimization absent rather than mutate it.
            if (m.cacheBreakpoint && blocks.last().string("type") !in setOf("thinking", "redacted_thinking")) {
                blocks[blocks.lastIndex] = JsonObject(blocks.last() + ("cache_control" to buildJsonObject { put("type", "ephemeral") }))
            }
            val role = if (m.role == Role.ASSISTANT) "assistant" else "user"
            val last = turns.lastOrNull()
            if (last?.role == role) {
                require(last.toolResults == (m.role == Role.TOOL)) { "Tool results must not merge with user text" }
                last.blocks.addAll(blocks)
            } else turns += Turn(role, m.role == Role.TOOL, blocks)
        }
        require(pending.isEmpty()) { "Unpaired calls" }
        return JsonArray(turns.map { buildJsonObject { put("role", it.role); put("content", JsonArray(it.blocks)) } })
    }

    private fun image(p: ContentPart.Media, readMedia: Boolean): JsonObject {
        require(p.kind == MediaKind.IMAGE && p.blob.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif"))
        require(p.blob.sizeBytes in 1L..ImageLimits.BYTES.toLong())
        val data = if (readMedia) {
            val bytes = requireNotNull(mediaReader?.read(p.blob)); require(bytes.size.toLong() == p.blob.sizeBytes)
            Base64.getEncoder().encodeToString(bytes)
        } else ""
        return buildJsonObject { put("type", "image"); putJsonObject("source") {
            put("type", "base64"); put("media_type", p.blob.mimeType); put("data", data)
        } }
    }
}
