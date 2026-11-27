package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.ProtocolException
import io.github.bytejuno.thetacore.agent.protocol.protocol
import kotlinx.serialization.json.*

/** Raw content and summaries have separate indices and must never validate each other's deltas. */
internal class ResponsesReasoningText {
    private class Part {
        val text = StringBuilder()
        var ended = false
        var emitted = 0
    }
    private class Channel {
        val parts = mutableMapOf<Int, Part>()
        var next = 0
    }
    private val content = Channel()
    private val summary = Channel()
    private var visible: Channel? = null

    suspend fun event(type: String, root: JsonObject, emit: suspend (String) -> Unit) {
        val isSummary = type.startsWith("response.reasoning_summary")
        val channel = if (isSummary) summary else content
        val key = if (isSummary) "summary_index" else "content_index"
        val index = (root[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it >= 0 } ?: throw ProtocolException("Invalid reasoning part index")
        val part = channel.parts.getOrPut(index) { Part() }
        if (type.endsWith(".delta")) {
            protocol(!part.ended, "Reasoning delta after part end")
            part.text.append(root.string("delta") ?: throw ProtocolException("Missing reasoning delta"))
        } else {
            val value = if (type.contains("_part.") || type.startsWith("response.content_part.")) {
                val obj = root["part"] as? JsonObject ?: throw ProtocolException("Missing reasoning part")
                text(obj, if (isSummary) "summary_text" else "reasoning_text")
            } else root.string("text") ?: throw ProtocolException("Missing completed reasoning text")
            complete(part, value, end = type.endsWith(".done"))
        }
        drain(channel, emit)
    }

    suspend fun finish(item: JsonObject, emit: suspend (String) -> Unit) {
        val raw = item["content"]?.takeUnless { it == JsonNull }?.let {
            it as? JsonArray ?: throw ProtocolException("Invalid reasoning content")
        } ?: JsonArray(emptyList())
        val summaries = item["summary"] as? JsonArray ?: throw ProtocolException("Missing reasoning summary")
        finish(content, raw, "reasoning_text", emit)
        finish(summary, summaries, "summary_text", emit)
    }

    private suspend fun finish(channel: Channel, values: JsonArray, kind: String, emit: suspend (String) -> Unit) {
        protocol(channel.parts.keys.all { it in values.indices }, "Final reasoning omitted a streamed part")
        values.forEachIndexed { index, value ->
            val obj = value as? JsonObject ?: throw ProtocolException("Invalid reasoning part")
            complete(channel.parts.getOrPut(index) { Part() }, text(obj, kind), end = true)
        }
        drain(channel, emit)
    }

    private fun text(obj: JsonObject, kind: String): String {
        protocol(obj.string("type") == kind, "Unexpected reasoning part type")
        return obj.string("text") ?: throw ProtocolException("Missing reasoning text")
    }

    private fun complete(part: Part, value: String, end: Boolean) {
        protocol(if (part.ended) value == part.text.toString() else value.startsWith(part.text.toString()),
            "Final reasoning contradicts deltas or completed text")
        part.text.append(value.substring(part.text.length))
        part.ended = part.ended || end
    }

    private suspend fun drain(channel: Channel, emit: suspend (String) -> Unit) {
        // Keep the first displayed channel stable. The other is still checked and retained in replay.
        // This avoids duplicating raw reasoning and its summary in one host reasoning part.
        while (true) {
            val part = channel.parts[channel.next] ?: return
            if (part.text.isNotEmpty() && visible == null) visible = channel
            if (visible === channel && part.emitted < part.text.length) {
                emit(part.text.substring(part.emitted))
                part.emitted = part.text.length
            }
            if (!part.ended) return
            channel.next++
        }
    }
}
