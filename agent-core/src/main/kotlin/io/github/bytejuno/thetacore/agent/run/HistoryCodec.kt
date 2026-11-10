package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Bounded transport frames, not message boundaries. JSON strings may span any number of frames.
 * The end digest authenticates order and completeness; EOF without the end frame is corruption.
 * There is no transaction-size ceiling or allocation based on an untrusted file/frame length.
 */
internal const val HISTORY_FRAME_BYTES = 64 * 1024
internal val HISTORY_STREAM_MAGIC = byteArrayOf(0x54, 0x48, 0x53, 0x32)

internal class HistoryFrameOutput(output: OutputStream) : OutputStream() {
    private val out = DataOutputStream(output)
    private val buffer = ByteArray(HISTORY_FRAME_BYTES)
    private var used = 0
    private val digest = MessageDigest.getInstance("SHA-256")
    init { out.write(HISTORY_STREAM_MAGIC) }
    override fun write(b: Int) { buffer[used++] = b.toByte(); if (used == buffer.size) frame() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        var start = off; var remaining = len
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size - used)
            b.copyInto(buffer, used, start, start + count)
            used += count; start += count; remaining -= count
            if (used == buffer.size) frame()
        }
    }
    private fun frame() {
        if (used == 0) return
        out.writeInt(used); out.write(buffer, 0, used); digest.update(buffer, 0, used); used = 0
    }
    fun finish() { frame(); out.writeInt(0); out.write(digest.digest()); out.flush() }
}

internal class HistoryFrameInput(input: InputStream) : InputStream() {
    private val source = DataInputStream(input)
    private val buffer = ByteArray(HISTORY_FRAME_BYTES)
    private var size = 0
    private var position = 0
    private var ended = false
    private val digest = MessageDigest.getInstance("SHA-256")
    init {
        val magic = ByteArray(HISTORY_STREAM_MAGIC.size); source.readFully(magic)
        require(magic.contentEquals(HISTORY_STREAM_MAGIC)) { "Invalid history stream version" }
    }
    private fun availableFrame(): Boolean {
        if (position < size) return true
        if (ended) return false
        size = source.readInt(); position = 0
        require(size in 0..buffer.size) { "Invalid history frame size" }
        if (size == 0) {
            val expected = ByteArray(32); source.readFully(expected)
            require(MessageDigest.isEqual(expected, digest.digest())) { "History stream checksum mismatch" }
            require(source.read() == -1) { "Trailing history stream data" }
            ended = true; return false
        }
        source.readFully(buffer, 0, size); digest.update(buffer, 0, size)
        return true
    }
    override fun read(): Int = if (availableFrame()) buffer[position++].toInt() and 255 else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!availableFrame()) return -1
        val count = minOf(len, size - position)
        buffer.copyInto(b, off, position, position + count); position += count
        return count
    }
}

/** Explicit versioned wire values, never Java object serialization or provider-specific parsing. */
@Serializable
internal data class StoredReplay(val protocol: String, val model: String, val payload: String)
@Serializable
internal data class StoredBinding(val provider: String, val requestId: String, val responseId: String?,
    val index: Int, val wireId: String?, val callId: String)
@Serializable
internal data class StoredPart(val kind: String, val text: String? = null, val name: String? = null,
    val args: String? = null, val binding: StoredBinding? = null, val callId: String? = null,
    val error: Boolean? = null, val replay: StoredReplay? = null)
@Serializable
internal data class StoredMessage(val role: Role, val parts: List<StoredPart>)
@Serializable
internal data class HistoryHead(val version: Int = 1, val sessionId: String, val revision: Long,
    val messageCount: Long, val hash: String)
@Serializable
internal data class HistoryBatch(val version: Int = 1, val sessionId: String, val revision: Long,
    val previousHash: String, val previousCount: Long, val messages: List<StoredMessage>)
@Serializable
internal data class StoredPending(val version: Int = 1, val revision: Long, val taskChainId: String,
    val sessionId: String, val runId: String, val unattended: Boolean, val stepId: String,
    val assistant: StoredMessage)
@Serializable
internal data class CallLocation(val callId: String, val revision: Long, val batchHash: String)
@Serializable
internal data class CheckedHistoryValue(val payload: String, val sha256: String)

internal object HistoryCodec {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true }

    /** kotlinx JSON's encodeToStream still buffers an entire escaped string token. Emit this
     * small, closed storage schema directly so even a huge result needs only writer/frame buffers.
     * Field order and escaping match the v1 serializer, preserving existing chain digests. */
    fun writeValue(value: Any, output: OutputStream) {
        val writer = java.io.OutputStreamWriter(output, Charsets.UTF_8).buffered(8192)
        fun quoted(s: String) {
            writer.write("\"")
            var start = 0
            for (i in s.indices) {
                val c = s[i]
                val escape = when (c) {
                    '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"
                    '\t' -> "\\t"; '\b' -> "\\b"; '\u000C' -> "\\f"
                    else -> if (c < ' ' ||
                        Character.isHighSurrogate(c) && (i + 1 == s.length || !Character.isLowSurrogate(s[i + 1])) ||
                        Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s[i - 1])))
                        "\\u" + c.code.toString(16).padStart(4, '0') else null
                }
                if (escape != null) {
                    if (i > start) writer.write(s, start, i - start)
                    writer.write(escape); start = i + 1
                }
            }
            if (start < s.length) writer.write(s, start, s.length - start)
            writer.write("\"")
        }
        lateinit var emit: (Any?) -> Unit
        fun fields(vararg values: Pair<String, Any?>) {
            writer.write("{")
            values.forEachIndexed { i, (key, v) ->
                if (i > 0) writer.write(",")
                quoted(key); writer.write(":"); emit(v)
            }
            writer.write("}")
        }
        emit = { v -> when (v) {
            null -> writer.write("null")
            is String -> quoted(v)
            is Number, is Boolean -> writer.write(v.toString())
            is Role -> quoted(v.name)
            is List<*> -> {
                writer.write("["); v.forEachIndexed { i, item -> if (i > 0) writer.write(","); emit(item) }; writer.write("]")
            }
            is HistoryHead -> fields("version" to v.version, "sessionId" to v.sessionId, "revision" to v.revision,
                "messageCount" to v.messageCount, "hash" to v.hash)
            is HistoryBatch -> fields("version" to v.version, "sessionId" to v.sessionId, "revision" to v.revision,
                "previousHash" to v.previousHash, "previousCount" to v.previousCount, "messages" to v.messages)
            is StoredPending -> fields("version" to v.version, "revision" to v.revision, "taskChainId" to v.taskChainId,
                "sessionId" to v.sessionId, "runId" to v.runId, "unattended" to v.unattended,
                "stepId" to v.stepId, "assistant" to v.assistant)
            is CallLocation -> fields("callId" to v.callId, "revision" to v.revision, "batchHash" to v.batchHash)
            is StoredMessage -> fields("role" to v.role, "parts" to v.parts)
            is StoredPart -> fields("kind" to v.kind, "text" to v.text, "name" to v.name, "args" to v.args,
                "binding" to v.binding, "callId" to v.callId, "error" to v.error, "replay" to v.replay)
            is StoredBinding -> fields("provider" to v.provider, "requestId" to v.requestId, "responseId" to v.responseId,
                "index" to v.index, "wireId" to v.wireId, "callId" to v.callId)
            is StoredReplay -> fields("protocol" to v.protocol, "model" to v.model, "payload" to v.payload)
            else -> error("Unsupported history value")
        } }
        emit(value); writer.flush() // caller owns channel, force, frame footer, and commit
    }
    fun encodeMessage(m: Message) = StoredMessage(m.role, m.parts.map { p ->
        val replay = p.replay?.let { StoredReplay(it.protocol, it.model, it.payload) }
        when (p) {
            is ContentPart.Text -> StoredPart("text", text = p.text, replay = replay)
            is ContentPart.Reasoning -> StoredPart("reasoning", text = p.summary, replay = replay)
            is ContentPart.ToolCall -> StoredPart("call", name = p.name, args = p.argsJson, replay = replay,
                binding = with(p.binding) { StoredBinding(provider, requestId, responseId, index, wireId, callId) })
            is ContentPart.ToolResult -> StoredPart("result", text = p.text, callId = p.callId, error = p.isError, replay = replay)
        }
    })
    /** UTF-8 cannot round-trip lone UTF-16 surrogates. Reject them before durable intent rather
     * than writing a different binding/argument/replay value which recovery cannot match. */
    fun requireUtf8(value: String) {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (Character.isHighSurrogate(c)) {
                require(i < value.length && Character.isLowSurrogate(value[i])) { "Unpaired surrogate in history input" }
                i++
            } else require(!Character.isLowSurrogate(c)) { "Unpaired surrogate in history input" }
        }
    }
    /** InvocationRecordCodec stores text as UTF-8, turning a lone surrogate into '?' after a restart.
     * History keeps the original, so comparing the two needs this same reduction on both sides. */
    fun atLedgerPrecision(m: StoredMessage) = m.copy(parts = m.parts.map { p ->
        p.copy(text = p.text?.let { String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) })
    })
    fun requireUtf8(m: StoredMessage) {
        for (p in m.parts) {
            listOfNotNull(p.text, p.name, p.args, p.callId).forEach(::requireUtf8)
            p.binding?.let { b -> listOfNotNull(b.provider, b.requestId, b.responseId, b.wireId, b.callId).forEach(::requireUtf8) }
            p.replay?.let { r -> listOf(r.protocol, r.model, r.payload).forEach(::requireUtf8) }
        }
    }
    fun decodeMessage(m: StoredMessage): Message = message(m.role, *m.parts.map { p ->
        val replay = p.replay?.let { replayData(it.protocol, it.model, it.payload) }
        val part = when (p.kind) {
            "text" -> textPart(requireNotNull(p.text), replay)
            "reasoning" -> reasoningPart(p.text, replay)
            "call" -> with(requireNotNull(p.binding)) {
                toolCallPart(callBinding(provider, requestId, responseId, index, wireId, callId),
                    requireNotNull(p.name), requireNotNull(p.args), replay)
            }
            "result" -> object : ContentPart.ToolResult {
                override val callId = requireNotNull(p.callId)
                override val text = requireNotNull(p.text)
                override val isError = requireNotNull(p.error)
                override val replay = replay
            }
            else -> error("Unknown history part: ${p.kind}")
        }
        // Reject contradictory/unused fields rather than silently dropping persisted information.
        require(encodeMessage(message(m.role, part)).parts.single() == p) { "Invalid history part fields" }
        part
    }.toTypedArray())
}
