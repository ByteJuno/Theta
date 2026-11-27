package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*

/** Output item order is also host part order; delta arrival order never allocates part identities. */
internal class OpenAiResponsesStream(private val request: ChatRequest, private val codec: OpenAiResponsesCodec) : OpenAiEventStream {
    private class Item(val index: Int, val id: String, val type: String, val initial: JsonObject) {
        val text = StringBuilder()
        var ended = false
        var final: JsonObject? = null
        var refusal = false
    }
    private val items = mutableListOf<Item>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var started = false
    private var done = false

    override suspend fun frame(event: String?, data: String?, heartbeat: Boolean, emit: suspend (ModelEvent) -> Unit) {
        val ping = heartbeat || event in setOf("ping", "heartbeat")
        if (done) { protocol(ping, "SSE frame after terminal"); return }
        if (ping) { emit(Events.keepAlive()); return }
        if (data == null) { protocol(event == null, "Empty Responses event"); return }
        val root = Json.parseToJsonElement(data) as? JsonObject ?: throw ProtocolException("Expected Responses object")
        val type = root.string("type") ?: throw ProtocolException("Missing Responses event type")
        protocol(event == null || event == type, "SSE event type mismatch")
        when (type) {
            "error" -> { emit(Events.failed(decodeError(root, null, null))); done = true }
            "response.created" -> start(root.getValue("response").jsonObject, emit)
            "response.queued", "response.in_progress" -> {
                val response = root.getValue("response").jsonObject
                if (!started) start(response, emit) else identity(response)
                emit(Events.keepAlive())
            }
            "response.output_item.added" -> {
                protocol(started, "Item before response")
                val index = index(root)
                protocol(index == items.size, "Output items must be introduced in order")
                val obj = root.getValue("item").jsonObject
                val id = obj.string("id") ?: throw ProtocolException("Missing item ID")
                protocol(id.isNotBlank() && items.none { it.id == id }, "Invalid item ID")
                val kind = obj.string("type") ?: throw ProtocolException("Missing item type")
                val item = Item(index, id, kind, obj)
                items += item
                when (kind) {
                    "message" -> { protocol(obj.string("role") == "assistant", "Unexpected message role"); emit(Events.text(index, "")) }
                    "reasoning" -> emit(Events.reasoning(index, ""))
                    "function_call" -> {
                        val name = obj.string("name") ?: throw ProtocolException("Missing function name")
                        protocol(name.matches(Regex("[A-Za-z0-9_-]{1,64}")), "Invalid function name")
                        val call = obj.string("call_id") ?: throw ProtocolException("Missing call_id")
                        protocol(call.isNotBlank(), "Empty call_id")
                        emit(Events.toolStart(index, call, name))
                        append(item, obj.string("arguments").orEmpty(), emit)
                    }
                    else -> throw ProtocolException("Unsupported Responses output item")
                }
            }
            "response.output_text.delta", "response.refusal.delta", "response.reasoning_summary_text.delta",
            "response.function_call_arguments.delta" -> {
                val item = item(root)
                val expected = when (type) {
                    "response.function_call_arguments.delta" -> "function_call"
                    "response.reasoning_summary_text.delta" -> "reasoning"
                    else -> "message"
                }
                protocol(item.type == expected, "Delta changed item type")
                if (type == "response.refusal.delta") item.refusal = true
                append(item, root.string("delta") ?: throw ProtocolException("Missing delta"), emit)
            }
            "response.output_item.done" -> finish(item(root, requireItemId = false), root.getValue("item").jsonObject, emit)
            "response.completed", "response.incomplete", "response.failed" -> {
                val response = root.getValue("response").jsonObject
                if (!started) start(response, emit) else identity(response)
                val status = type.removePrefix("response.")
                protocol(response.string("status") == status, "Terminal status mismatch")
                if (type == "response.failed") {
                    emit(Events.failed(decodeError(response["error"] as? JsonObject, null, null)))
                } else {
                    val output = response["output"] as? JsonArray ?: throw ProtocolException("Missing terminal output")
                    protocol(output.size == items.size, "Terminal output changed")
                    output.forEachIndexed { n, raw ->
                        val obj = raw.jsonObject
                        val item = items[n]
                        if (!item.ended) finish(item, obj, emit)
                        else protocol(item.final == obj, "Terminal item differs from completed item")
                    }
                    response["usage"]?.takeUnless { it == JsonNull }?.jsonObject?.let { usage ->
                        fun count(obj: JsonObject?, key: String): Long? {
                            val v = obj?.get(key)?.takeUnless { it == JsonNull } ?: return null
                            val n = (v as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                            protocol(n != null && n >= 0, "Invalid usage count")
                            return n
                        }
                        emit(Events.usage(tokenUsage(inputTokens = count(usage, "input_tokens"),
                            cacheReadTokens = count(usage["input_tokens_details"] as? JsonObject, "cached_tokens"),
                            outputTokens = count(usage, "output_tokens"),
                            reasoningTokens = count(usage["output_tokens_details"] as? JsonObject, "reasoning_tokens"))))
                    }
                    val raw = (response["incomplete_details"] as? JsonObject)?.string("reason")
                    val reason = when {
                        raw == "content_filter" -> FinishReason.CONTENT_FILTER
                        items.any { it.refusal } || raw == "refusal" -> FinishReason.REFUSAL
                        type == "response.incomplete" -> FinishReason.LENGTH
                        items.any { it.type == "function_call" } -> FinishReason.TOOL_CALLS
                        else -> FinishReason.STOP
                    }
                    emit(Events.finished(reason, raw ?: status))
                }
                done = true
            }
            // The authoritative final item is checked against every streamed delta at output_item.done.
            "response.content_part.added", "response.content_part.done", "response.output_text.done",
            "response.refusal.done", "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.done", "response.function_call_arguments.done",
            "response.output_text.annotation.added" -> { item(root); emit(Events.keepAlive()) }
            else -> throw ProtocolException("Unsupported Responses stream event")
        }
    }

    private suspend fun start(response: JsonObject, emit: suspend (ModelEvent) -> Unit) {
        protocol(!started, "Duplicate response start")
        responseId = response.string("id") ?: throw ProtocolException("Missing response ID")
        responseModel = response.string("model") ?: throw ProtocolException("Missing response model")
        started = true
        emit(Events.started(responseId, responseModel))
    }
    private fun identity(response: JsonObject) {
        protocol(response.string("id") == responseId && response.string("model") == responseModel, "Response identity changed")
    }
    private fun index(root: JsonObject): Int = root["output_index"]?.jsonPrimitive?.intOrNull
        ?.takeIf { it >= 0 } ?: throw ProtocolException("Invalid output index")
    private fun item(root: JsonObject, requireItemId: Boolean = true): Item {
        val item = items.getOrNull(index(root)) ?: throw ProtocolException("Unknown output item")
        protocol(!item.ended, "Event after item end")
        if (requireItemId) protocol(root.string("item_id") == item.id, "Item ID changed")
        return item
    }
    private suspend fun append(item: Item, text: String, emit: suspend (ModelEvent) -> Unit) {
        item.text.append(text)
        eventFragments(text).forEach { fragment -> emit(when (item.type) {
            "message" -> Events.text(item.index, fragment)
            "reasoning" -> Events.reasoning(item.index, fragment)
            else -> Events.toolDelta(item.index, fragment)
        }) }
    }
    private suspend fun finish(item: Item, obj: JsonObject, emit: suspend (ModelEvent) -> Unit) {
        protocol(obj.string("id") == item.id && obj.string("type") == item.type, "Completed item identity changed")
        val full = when (item.type) {
            "function_call" -> {
                protocol(obj.string("call_id") == item.initial.string("call_id") && obj.string("name") == item.initial.string("name"), "Function identity changed")
                obj.string("arguments") ?: throw ProtocolException("Missing final arguments")
            }
            "reasoning" -> (obj["summary"] as? JsonArray ?: throw ProtocolException("Missing reasoning summary")).joinToString("") {
                val p = it.jsonObject
                protocol(p.string("type") == "summary_text", "Unsupported reasoning summary")
                p.string("text") ?: throw ProtocolException("Missing summary text")
            }
            else -> {
                protocol(obj.string("role") == "assistant", "Message role changed")
                (obj["content"] as? JsonArray ?: throw ProtocolException("Missing message content")).joinToString("") {
                    val p = it.jsonObject
                    when (p.string("type")) {
                        "output_text" -> p.string("text") ?: throw ProtocolException("Missing output text")
                        "refusal" -> { item.refusal = true; p.string("refusal") ?: throw ProtocolException("Missing refusal") }
                        else -> throw ProtocolException("Unsupported message content")
                    }
                }
            }
        }
        protocol(full.startsWith(item.text.toString()), "Final item contradicts deltas")
        append(item, full.substring(item.text.length), emit)
        val replay = if (item.type == "reasoning") codec.replay(request.model, obj) else null
        emit(Events.end(item.index, replay))
        item.final = obj; item.ended = true
    }
    override fun endOfInput() { protocol(done, "EOF without Responses terminal") }
}
