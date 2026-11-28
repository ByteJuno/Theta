package io.github.bytejuno.thetacore.agent.provider.anthropic

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiEventStream
import io.github.bytejuno.thetacore.agent.provider.openai.string
import kotlinx.serialization.json.*

/** One decoder per HTTP attempt. Native content indices are the protocol part indices. */
internal class AnthropicStream(private val request: ChatRequest, private val codec: AnthropicCodec) : OpenAiEventStream {
    private class Block(val initial: JsonObject, val type: String) {
        val text = StringBuilder()
        val signature = StringBuilder()
        var deltas = false
        var ended = false
    }
    private val blocks = mutableListOf<Block>()
    private var started = false
    private var terminal = false
    private var reason: String? = null
    private var usage = JsonObject(emptyMap())

    override suspend fun frame(event: String?, data: String?, heartbeat: Boolean, emit: suspend (ModelEvent) -> Unit) {
        if (heartbeat) { if (!terminal) emit(Events.keepAlive()); return }
        protocol(data != null, "Missing Anthropic event data")
        val root = Json.parseToJsonElement(data!!).jsonObject
        val type = root.string("type") ?: throw ProtocolException("Missing Anthropic event type")
        protocol(event == null || event == type, "Anthropic event type mismatch")
        if (type == "ping") { if (!terminal) emit(Events.keepAlive()); return }
        protocol(!terminal, "Event after terminal")
        if (type == "error") {
            terminal = true; emit(Events.failed(anthropicError(root["error"] as? JsonObject, null, null))); return
        }
        when (type) {
            "message_start" -> {
                protocol(!started, "Duplicate message start")
                val m = root.getValue("message").jsonObject
                protocol(m.string("role") == "assistant" && m.getValue("content").jsonArray.isEmpty(), "Invalid message start")
                protocol(!m.string("id").isNullOrBlank() && !m.string("model").isNullOrBlank(), "Missing message identity")
                started = true; emit(Events.started(m.string("id"), m.string("model")))
                (m["usage"] as? JsonObject)?.let { updateUsage(it, emit) }
            }
            "content_block_start" -> {
                protocol(started && reason == null && index(root) == blocks.size, "Invalid content start")
                val raw = root.getValue("content_block").jsonObject
                val kind = raw.string("type") ?: throw ProtocolException("Missing content type")
                val b = Block(raw, kind); blocks += b; val i = blocks.lastIndex
                when (kind) {
                    "text" -> { b.text.append(required(raw, "text")); emitText(i, b.text.toString(), false, emit) }
                    "thinking" -> {
                        b.text.append(required(raw, "thinking")); b.signature.append(raw.string("signature").orEmpty())
                        emitText(i, b.text.toString(), true, emit)
                    }
                    "redacted_thinking" -> { protocol(!raw.string("data").isNullOrBlank(), "Missing redacted thinking"); emit(Events.reasoning(i, "")) }
                    "tool_use" -> {
                        val name = required(raw, "name"); val id = required(raw, "id")
                        protocol(name.matches(Regex("[A-Za-z0-9_-]{1,64}")) && id.matches(Regex("[A-Za-z0-9_-]{1,256}")), "Invalid tool identity")
                        protocol(raw["input"] is JsonObject, "Invalid initial tool input")
                        emit(Events.toolStart(i, id, name))
                        if (raw.getValue("input").jsonObject.isNotEmpty()) {
                            b.text.append(raw.getValue("input").toString())
                            eventFragments(b.text.toString()).forEach { emit(Events.toolDelta(i, it)) }
                        }
                    }
                    else -> throw ProtocolException("Unsupported Anthropic content block")
                }
            }
            "content_block_delta" -> {
                protocol(started && reason == null, "Delta outside message")
                val i = index(root); val b = open(i); val d = root.getValue("delta").jsonObject
                when (d.string("type")) {
                    "text_delta", "thinking_delta" -> {
                        val thinking = d.string("type") == "thinking_delta"
                        protocol(b.type == if (thinking) "thinking" else "text", "Delta changes content type")
                        val text = required(d, if (thinking) "thinking" else "text")
                        b.text.append(text); emitText(i, text, thinking, emit)
                    }
                    "signature_delta" -> { protocol(b.type == "thinking", "Signature outside thinking"); b.signature.append(required(d, "signature")) }
                    "input_json_delta" -> {
                        protocol(b.type == "tool_use" && b.initial.getValue("input").jsonObject.isEmpty(), "Unexpected tool input delta")
                        val text = required(d, "partial_json"); b.deltas = true; b.text.append(text)
                        eventFragments(text).forEach { emit(Events.toolDelta(i, it)) }
                    }
                    else -> throw ProtocolException("Unsupported Anthropic content delta")
                }
                // Fail explicitly before retaining an unbounded response. Never truncate a signature.
                protocol(b.text.length + b.signature.length <= 8 * 1024 * 1024, "Content block exceeds limit")
            }
            "content_block_stop" -> {
                protocol(started && reason == null, "Content stop outside message")
                val i = index(root); val b = open(i); b.ended = true
                val replay = when (b.type) {
                    "thinking" -> codec.replay(request.model, JsonObject(b.initial + mapOf(
                        "thinking" to JsonPrimitive(b.text.toString()), "signature" to JsonPrimitive(b.signature.toString()))))
                    "redacted_thinking" -> codec.replay(request.model, b.initial)
                    "tool_use" -> {
                        if (!b.deltas && b.text.isEmpty()) emit(Events.toolDelta(i, "{}"))
                        null
                    }
                    else -> null
                }
                emit(Events.end(i, replay))
            }
            "message_delta" -> {
                protocol(started && blocks.all { it.ended }, "Message delta before content completion")
                val delta = root.getValue("delta").jsonObject
                delta.string("stop_reason")?.let { protocol(reason == null, "Duplicate stop reason"); reason = it }
                (root["usage"] as? JsonObject)?.let { updateUsage(it, emit) }
            }
            "message_stop" -> {
                protocol(started && reason != null && blocks.all { it.ended }, "Incomplete Anthropic message")
                terminal = true
                emit(Events.finished(when (reason) {
                    "end_turn", "stop_sequence" -> FinishReason.STOP
                    "tool_use" -> FinishReason.TOOL_CALLS
                    "max_tokens" -> FinishReason.LENGTH
                    "pause_turn" -> FinishReason.PAUSED
                    "refusal" -> FinishReason.REFUSAL
                    "model_context_window_exceeded" -> FinishReason.CONTEXT_EXCEEDED
                    else -> throw ProtocolException("Unknown Anthropic stop reason")
                }, reason))
            }
            else -> throw ProtocolException("Unsupported Anthropic event")
        }
    }

    private fun index(root: JsonObject): Int {
        val p = root["index"] as? JsonPrimitive
        protocol(p != null && !p.isString && p.intOrNull != null && p.int >= 0, "Invalid content index")
        return p!!.int
    }
    private fun open(index: Int): Block {
        protocol(index in blocks.indices && !blocks[index].ended, "Content block is not open")
        return blocks[index]
    }
    private fun required(o: JsonObject, key: String) = o.string(key) ?: throw ProtocolException("Missing content field")
    private suspend fun emitText(i: Int, text: String, thinking: Boolean, emit: suspend (ModelEvent) -> Unit) {
        (if (text.isEmpty()) listOf("") else eventFragments(text)).forEach {
            emit(if (thinking) Events.reasoning(i, it) else Events.text(i, it))
        }
    }
    private suspend fun updateUsage(value: JsonObject, emit: suspend (ModelEvent) -> Unit) {
        usage = JsonObject(usage + value)
        fun count(key: String): Long? {
            val p = usage[key] ?: return null
            if (p == JsonNull) return null
            protocol(p is JsonPrimitive && !p.isString && p.longOrNull != null && p.long >= 0, "Invalid token usage")
            return p.jsonPrimitive.long
        }
        val input = count("input_tokens"); val read = count("cache_read_input_tokens"); val write = count("cache_creation_input_tokens")
        val total = input?.let { Math.addExact(Math.addExact(it, read ?: 0), write ?: 0) }
        emit(Events.usage(tokenUsage(total, read, write, count("output_tokens"), count("thinking_tokens"))))
    }
    override fun endOfInput() { protocol(terminal, "Missing Anthropic terminal") }
}
