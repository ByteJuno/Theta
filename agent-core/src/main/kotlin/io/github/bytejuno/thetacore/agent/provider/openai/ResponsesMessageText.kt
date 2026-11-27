package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.ProtocolException
import io.github.bytejuno.thetacore.agent.protocol.protocol
import kotlinx.serialization.json.*

/** One host text part, with independently checked wire parts drained in content_index order. */
internal class ResponsesMessageText {
    private class Part(val kind: String) {
        val text = StringBuilder()
        var ended = false
        var emitted = 0
    }
    private val parts = mutableMapOf<Int, Part>()
    private var next = 0
    var refusal = false
        private set

    suspend fun event(type: String, root: JsonObject, emit: suspend (String) -> Unit) {
        val index = (root["content_index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it >= 0 } ?: throw ProtocolException("Invalid message content index")
        if (type.startsWith("response.content_part.")) {
            val obj = root["part"] as? JsonObject ?: throw ProtocolException("Missing message content part")
            complete(part(index, kind(obj)), text(obj), end = type.endsWith(".done"))
        } else {
            val kind = if (type.startsWith("response.refusal.")) "refusal" else "output_text"
            val part = part(index, kind)
            if (type.endsWith(".delta")) {
                protocol(!part.ended, "Message delta after part end")
                part.text.append(root.string("delta") ?: throw ProtocolException("Missing message delta"))
            } else {
                val value = root.string(if (kind == "refusal") "refusal" else "text")
                    ?: throw ProtocolException("Missing completed message text")
                complete(part, value, end = true)
            }
        }
        drain(emit)
    }

    suspend fun finish(item: JsonObject, emit: suspend (String) -> Unit) {
        val content = item["content"] as? JsonArray ?: throw ProtocolException("Missing message content")
        protocol(parts.keys.all { it in content.indices }, "Final message omitted a streamed part")
        content.forEachIndexed { index, value ->
            val obj = value as? JsonObject ?: throw ProtocolException("Invalid message content part")
            complete(part(index, kind(obj)), text(obj), end = true)
        }
        drain(emit)
    }

    private fun kind(obj: JsonObject): String = obj.string("type")?.also {
        protocol(it == "output_text" || it == "refusal", "Unsupported message content")
    } ?: throw ProtocolException("Missing message content type")

    private fun text(obj: JsonObject): String = obj.string(if (kind(obj) == "refusal") "refusal" else "text")
        ?: throw ProtocolException("Missing message content text")

    private fun part(index: Int, kind: String): Part {
        val part = parts.getOrPut(index) { Part(kind) }
        protocol(part.kind == kind, "Message content type changed")
        if (kind == "refusal") refusal = true
        return part
    }

    private fun complete(part: Part, value: String, end: Boolean) {
        protocol(if (part.ended) value == part.text.toString() else value.startsWith(part.text.toString()),
            "Final message contradicts deltas or completed text")
        part.text.append(value.substring(part.text.length))
        part.ended = part.ended || end
    }

    private suspend fun drain(emit: suspend (String) -> Unit) {
        while (true) {
            val part = parts[next] ?: return
            if (part.emitted < part.text.length) {
                emit(part.text.substring(part.emitted))
                part.emitted = part.text.length
            }
            if (!part.ended) return
            next++
        }
    }
}
