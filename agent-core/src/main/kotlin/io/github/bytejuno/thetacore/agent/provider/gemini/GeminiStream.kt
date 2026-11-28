package io.github.bytejuno.thetacore.agent.provider.gemini

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.serialization.json.*

/** Preserve native part boundaries, including empty signed text parts in streamed responses. */
internal class GeminiStream(private val request: ChatRequest, private val codec: GeminiCodec) : OpenAiEventStream {
    private var started = false
    private var terminal = false
    private var index = 0
    private var calls = 0
    private val textParts = mutableListOf<JsonObject>()
    private var thinking = false
    private var textLength = 0

    override suspend fun frame(event: String?, data: String?, heartbeat: Boolean, emit: suspend (ModelEvent) -> Unit) {
        if (heartbeat) { if (!terminal) emit(Events.keepAlive()); return }
        protocol(!terminal && data != null, "Invalid Gemini event")
        val root = Json.parseToJsonElement(data!!).jsonObject
        (root["error"] as? JsonObject)?.let { terminal = true; emit(Events.failed(geminiError(it, null, null))); return }
        if (!started) { started = true; emit(Events.started(root.string("responseId"), root.string("modelVersion"))) }
        val candidates = root["candidates"] as? JsonArray ?: JsonArray(emptyList())
        protocol(candidates.size <= 1, "Multiple Gemini candidates")
        val candidate = candidates.firstOrNull()?.jsonObject
        candidate?.let { c ->
            protocol(c["index"] == null || c["index"] == JsonPrimitive(0), "Unexpected Gemini candidate")
            (c["content"] as? JsonObject)?.let { content ->
                protocol(content.string("role") == null || content.string("role") == "model", "Unexpected Gemini role")
                content.getValue("parts").jsonArray.forEach { part -> consume(part.jsonObject, emit) }
            }
        }
        (root["usageMetadata"] as? JsonObject)?.let { usage ->
            fun count(key: String): Long? {
                val v = usage[key] ?: return null
                protocol(v is JsonPrimitive && !v.isString && v.longOrNull != null && v.long >= 0, "Invalid Gemini usage")
                return v.jsonPrimitive.long
            }
            emit(Events.usage(tokenUsage(count("promptTokenCount"), count("cachedContentTokenCount"), null,
                count("candidatesTokenCount"), count("thoughtsTokenCount"))))
        }
        val blocked = (root["promptFeedback"] as? JsonObject)?.string("blockReason")
        val reason = candidate?.string("finishReason") ?: blocked
        if (reason != null && reason != "FINISH_REASON_UNSPECIFIED" && reason != "BLOCK_REASON_UNSPECIFIED") {
            closeText(emit)
            terminal = true
            val finish = when {
                blocked != null -> FinishReason.CONTENT_FILTER
                reason == "STOP" -> if (calls > 0) FinishReason.TOOL_CALLS else FinishReason.STOP
                reason == "MAX_TOKENS" -> FinishReason.LENGTH
                reason in setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION", "ESCALATION", "PUP_LIMITED_DISABLED") -> FinishReason.CONTENT_FILTER
                reason in setOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE") -> {
                    emit(Events.failed(modelError(ModelErrorCode.PROTOCOL, "Invalid Gemini response"))); return
                }
                else -> {
                    emit(Events.failed(modelError(ModelErrorCode.PROTOCOL, "Unsupported Gemini stop reason"))); return
                }
            }
            emit(Events.finished(finish, if (blocked != null) "PROMPT_BLOCKED" else reason))
        }
    }

    private suspend fun consume(p: JsonObject, emit: suspend (ModelEvent) -> Unit) {
        val signature = p.string("thoughtSignature")
        protocol(p["thoughtSignature"] == null || !signature.isNullOrBlank(), "Invalid thought signature")
        when {
            "functionCall" in p -> {
                closeText(emit)
                protocol(p.keys.all { it in setOf("functionCall", "thoughtSignature") }, "Unsupported Gemini call part")
                val call = p.getValue("functionCall").jsonObject
                protocol(codec.validCall(call), "Invalid Gemini function call")
                emit(Events.toolStart(index, call.string("id"), call.string("name")!!))
                eventFragments((call["args"] ?: JsonObject(emptyMap())).toString()).forEach { emit(Events.toolDelta(index, it)) }
                emit(Events.end(index++, signature?.let { codec.replay(request.model, p) })); calls++
            }
            "text" in p -> {
                protocol(p.keys.all { it in setOf("text", "thought", "thoughtSignature") } && p.string("text") != null,
                    "Unsupported Gemini text part")
                protocol(p["thought"] == null || p["thought"] == JsonPrimitive(true) || p["thought"] == JsonPrimitive(false), "Invalid thought flag")
                val nextThinking = p["thought"] == JsonPrimitive(true)
                if (textParts.isNotEmpty() && thinking != nextThinking) closeText(emit)
                thinking = nextThinking
                textParts += p
                val delta = p.string("text")!!
                textLength += delta.length
                protocol(textLength <= 8 * 1024 * 1024, "Gemini text exceeds limit")
                (if (delta.isEmpty()) listOf("") else eventFragments(delta)).forEach {
                    emit(if (thinking) Events.reasoning(index, it) else Events.text(index, it))
                }
                if (signature != null) closeText(emit)
            }
            p.keys == setOf("thoughtSignature") -> {
                closeText(emit)
                emit(Events.reasoning(index, "")); emit(Events.end(index++, codec.replay(request.model, p)))
            }
            else -> throw ProtocolException("Unsupported Gemini response part")
        }
    }
    private suspend fun closeText(emit: suspend (ModelEvent) -> Unit) {
        if (textParts.isEmpty()) return
        // One display block, but exact native boundaries survive in the opaque replay envelope.
        val replay = if (thinking || textParts.any { it["thoughtSignature"] != null }) codec.replayText(request.model, textParts.toList()) else null
        emit(Events.end(index++, replay)); textParts.clear(); textLength = 0
    }
    override fun endOfInput() { protocol(terminal, "Missing Gemini terminal") }
}
