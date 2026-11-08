package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*

internal class OpenAiChatStream(private val config: OpenAiChatConfig, private val request: ChatRequest,
                                private val codec: OpenAiChatCodec) {
    private class Tool(val part: Int, var wireId: String?, var name: String) { var started = false }
    private val tools = linkedMapOf<Int, Tool>()
    private var nextPart = 0
    private var textIndex: Int? = null
    // Reasoning is coalesced per part, but a part never outgrows the replay budget:
    // its opaque payload is carried by a single PartEnd event.
    private val reasoningParts = linkedMapOf<Int, StringBuilder>()
    private var reasoningIndex: Int? = null
    private val reasoningFragmentUnits = codec.reasoningFragmentUnits(request.model)
    private var started = false
    private var responseId: String? = null
    private var responseModel: String? = null
    private var reason: String? = null
    private var done = false

    suspend fun frame(event: String?, data: String?, heartbeat: Boolean, emit: suspend (ModelEvent) -> Unit) {
        val keepAlive = heartbeat || event == "ping" || event == "heartbeat"
        // A heartbeat after the terminal is connection noise, anything else may be a second answer.
        if (done) { protocol(keepAlive, "SSE frame after terminal"); return }
        if (keepAlive) { emit(Events.keepAlive()); return }
        if (event == "error" && data == null) throw ProtocolException("Empty stream error")
        if (data == null) return
        if (data == "[DONE]") {
            protocol(reason != null, "DONE without finish_reason")
            protocol(tools.values.all { it.started }, "Incomplete tool name")
            for (index in 0 until nextPart) {
                val replay = if (index in reasoningParts && config.compat.replayReasoning)
                    codec.replay(request.model, reasoningParts.getValue(index).toString()) else null
                emit(Events.end(index, replay))
            }
            emit(Events.finished(mapReason(reason!!), reason))
            done = true
            return
        }
        val root = Json.parseToJsonElement(data) as? JsonObject ?: throw ProtocolException("Chunk must be an object")
        val streamError = root["error"]?.takeUnless { it == JsonNull }
        if (event == "error" || streamError != null) {
            val error = streamError as? JsonObject ?: root
            emit(Events.failed(decodeError(error, null, null))); done = true; return
        }
        val id = root.string("id")
        val model = root.string("model")
        if (!started) {
            started = true; responseId = id; responseModel = model
            emit(Events.started(id, model))
        } else {
            protocol(id == null || id == responseId, "Response ID changed")
            protocol(model == null || model == responseModel, "Response model changed")
        }
        val choices = root["choices"] as? JsonArray ?: throw ProtocolException("Missing choices")
        protocol(choices.size <= 1, "Multiple choices unsupported")
        if (choices.isNotEmpty()) {
            protocol(reason == null, "Choice after finish_reason")
            val choice = choices.single().jsonObject
            protocol(choice["index"]?.jsonPrimitive?.intOrNull == 0, "Choice index must be zero")
            val delta = choice["delta"] as? JsonObject ?: throw ProtocolException("Missing delta")
            protocol(delta["function_call"] == null, "Legacy function_call unsupported")
            delta.string("role")?.let { protocol(it == "assistant", "Unexpected response role") }
            delta.string("content")?.takeIf { it.isNotEmpty() }?.let { text ->
                val index = textIndex ?: nextPart++.also { textIndex = it }
                eventFragments(text).forEach { emit(Events.text(index, it)) }
            }
            config.compat.reasoningField?.let { field -> delta.string(field)?.takeIf { it.isNotEmpty() }?.let { text ->
                eventFragments(text, reasoningFragmentUnits).forEach { fragment ->
                    val index = reasoningPartFor(fragment)
                    reasoningParts.getValue(index).append(fragment)
                    emit(Events.reasoning(index, fragment))
                }
            } }
            // Refusal text is visible but not eligible for history replay.
            delta.string("refusal")?.takeIf { it.isNotEmpty() }?.let { text ->
                refused = true
                val index = textIndex ?: nextPart++.also { textIndex = it }
                eventFragments(text).forEach { emit(Events.text(index, it)) }
            }
            delta["tool_calls"]?.takeUnless { it == JsonNull }?.jsonArray?.forEach { element ->
                val call = element.jsonObject
                val wireIndex = call["index"]?.jsonPrimitive?.intOrNull ?: throw ProtocolException("Missing tool index")
                protocol(wireIndex >= 0, "Negative tool index")
                call.string("type")?.let { protocol(it == "function", "Non-function tool unsupported") }
                val function = call["function"] as? JsonObject ?: throw ProtocolException("Missing function")
                val name = function.string("name")
                val wireId = call.string("id")
                val tool = tools.getOrPut(wireIndex) {
                    protocol(wireIndex == tools.size, "Tool indices must be introduced in order")
                    Tool(nextPart++, wireId, name ?: "")
                }
                if (tool.started) {
                    protocol(name == null || name == tool.name, "Tool name changed")
                    protocol(wireId == null || wireId == tool.wireId, "Tool ID changed")
                } else {
                    if (name != null) tool.name = name
                    if (wireId != null) tool.wireId = wireId
                    // Chat function name and id are metadata, not argument deltas. Require a complete name.
                    protocol(tool.name.matches(Regex("[A-Za-z0-9_-]{1,64}")), "Missing or invalid tool name")
                    emit(Events.toolStart(tool.part, tool.wireId, tool.name)); tool.started = true
                }
                function.string("arguments")?.let { args -> eventFragments(args).forEach { emit(Events.toolDelta(tool.part, it)) } }
            }
            choice.string("finish_reason")?.let { reason = it }
        }
        root["usage"]?.takeUnless { it == JsonNull }?.let { element ->
            val usage = element.jsonObject
            fun count(obj: JsonObject?, key: String): Long? {
                val raw = obj?.get(key)?.takeUnless { it == JsonNull } ?: return null
                val n = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                protocol(n != null && n >= 0, "Invalid usage count")
                return n
            }
            emit(Events.usage(tokenUsage(
                inputTokens = count(usage, "prompt_tokens"),
                outputTokens = count(usage, "completion_tokens"),
                cacheReadTokens = count(usage["prompt_tokens_details"] as? JsonObject, "cached_tokens"),
                reasoningTokens = count(usage["completion_tokens_details"] as? JsonObject, "reasoning_tokens"),
            )))
        }
    }
    private fun reasoningPartFor(fragment: String): Int {
        // Only the newest part can continue, so reasoning split by text or tools keeps its order.
        val open = reasoningIndex?.takeIf { it == nextPart - 1 }
        if (open != null && reasoningParts.getValue(open).length + fragment.length <= reasoningFragmentUnits) return open
        return nextPart++.also { reasoningIndex = it; reasoningParts[it] = StringBuilder() }
    }
    private var refused = false
    private fun mapReason(value: String): FinishReason = if (refused) FinishReason.REFUSAL else when (value) {
        "stop" -> FinishReason.STOP
        "tool_calls" -> FinishReason.TOOL_CALLS
        "length" -> FinishReason.LENGTH
        "context_exceeded" -> FinishReason.CONTEXT_EXCEEDED
        "content_filter" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.OTHER
    }
    fun endOfInput() { protocol(done, "EOF without DONE or stream error") }
}

internal fun decodeError(error: JsonObject?, status: Int?, retryAfterMs: Long?): ModelError {
    val rawCode = error?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }
    val code = when (rawCode) {
        "insufficient_quota" -> ModelErrorCode.QUOTA
        "context_length_exceeded" -> ModelErrorCode.CONTEXT_TOO_LONG
        "unsupported_parameter" -> ModelErrorCode.UNSUPPORTED_PARAMETER
        "model_not_found" -> ModelErrorCode.MODEL_NOT_FOUND
        "content_filter" -> ModelErrorCode.CONTENT_BLOCKED
        else -> when (status) {
            401, 403 -> ModelErrorCode.AUTH
            429 -> ModelErrorCode.RATE_LIMIT
            503 -> ModelErrorCode.OVERLOADED
            in 500..599 -> ModelErrorCode.SERVER
            in 400..499 -> ModelErrorCode.INVALID_REQUEST
            // In-stream errors have no HTTP status to classify by.
            null -> when (rawCode ?: error?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                "rate_limit_exceeded" -> ModelErrorCode.RATE_LIMIT
                "server_error" -> ModelErrorCode.SERVER
                else -> ModelErrorCode.UNKNOWN
            }
            else -> ModelErrorCode.UNKNOWN
        }
    }
    // Do not forward arbitrary error bodies (which may echo credentials or exceed event limits).
    val param = error?.get("param")?.let { (it as? JsonPrimitive)?.contentOrNull }?.take(256)
    return modelError(code, "OpenAI Chat request failed", status, retryAfterMs, param)
}
