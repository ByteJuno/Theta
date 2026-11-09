package io.github.bytejuno.thetacore.agent.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

class ProtocolException(message: String) : IllegalArgumentException(message)
internal fun protocol(condition: Boolean, message: String) {
    if (!condition) throw ProtocolException(message)
}

/** Upper bound on the UTF-8 JSON envelope, including escaping and field names. */
fun eventSizeBytes(event: ModelEvent): Int {
    val values = when (event) {
        is ModelEvent.Started -> listOf(event.responseId, event.model)
        is ModelEvent.TextDelta -> listOf(event.text)
        is ModelEvent.ReasoningDelta -> listOf(event.text)
        is ModelEvent.ToolCallStart -> listOf(event.wireId, event.name)
        is ModelEvent.ToolCallDelta -> listOf(event.argsFragment)
        is ModelEvent.PartEnd -> listOf(event.replay?.protocol, event.replay?.model, event.replay?.payload)
        is ModelEvent.Finished -> listOf(event.rawReason)
        is ModelEvent.Failed -> listOf(event.error.message, event.error.param)
        else -> emptyList()
    }
    return 512 + values.sumOf { JsonPrimitive(it).toString().toByteArray(Charsets.UTF_8).size }
}

/** Split by code point, conservatively budgeting JSON escapes as six bytes per UTF-16 unit. */
internal fun eventFragments(text: String, maxUnits: Int = 8_000): List<String> {
    require(maxUnits in 2..8_000)
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxUnits, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

interface CollectedCall {
    val binding: CallBinding
    val name: String
    val argsJson: String
    /** Syntactic completeness, not JSON Schema validation or permission to execute. */
    val complete: Boolean
    /** The call ended with no arguments. The host may read this as `{}` only if the Schema allows an empty object. */
    val emptyArguments: Boolean
}
private data class CollectedCallValue(override val binding: CallBinding, override val name: String,
    override val argsJson: String, override val complete: Boolean, override val emptyArguments: Boolean) : CollectedCall

enum class HistoryDisposition { DISCARD, KEEP, NEEDS_CANCELLED_NONE_RESULTS }

/** A per-request state machine. The host must persist bindings and perform Schema/approval gates. */
class ModelEventReducer(private val provider: String, private val requestId: String) {
    private enum class Kind { TEXT, REASONING, TOOL }
    private class Part(val kind: Kind, val binding: CallBinding? = null, val name: String = "") {
        val content = StringBuilder()
        var ended = false
        var replay: ReplayData? = null
    }
    private val parts = linkedMapOf<Int, Part>()
    private var started = false
    private var responseId: String? = null
    var terminal: ModelEvent? = null
        private set
    var usage: TokenUsage? = null
        private set

    fun accept(event: ModelEvent) {
        protocol(terminal == null, "Event after terminal")
        protocol(eventSizeBytes(event) <= 64 * 1024, "Event exceeds 64 KiB")
        when (event) {
            is ModelEvent.KeepAlive -> Unit
            is ModelEvent.Failed -> terminal = event
            is ModelEvent.Started -> {
                protocol(!started && parts.isEmpty(), "Duplicate Started")
                started = true
                responseId = event.responseId
            }
            is ModelEvent.Usage -> {
                protocol(started, "Usage before Started")
                val u = event.usage
                protocol(listOf(u.inputTokens, u.outputTokens, u.cacheReadTokens, u.cacheWriteTokens,
                    u.reasoningTokens).all { it == null || it >= 0 }, "Negative usage")
                usage = u
            }
            is ModelEvent.TextDelta -> append(event.index, Kind.TEXT, event.text)
            is ModelEvent.ReasoningDelta -> append(event.index, Kind.REASONING, event.text)
            is ModelEvent.ToolCallStart -> {
                protocol(event.name.isNotBlank(), "Empty tool name")
                add(event.index, Part(Kind.TOOL, callBinding(provider, requestId, responseId,
                    event.index, event.wireId), event.name))
            }
            is ModelEvent.ToolCallDelta -> {
                val part = parts[event.index]
                protocol(part != null && part.kind == Kind.TOOL && !part.ended, "Delta without open tool")
                part!!.content.append(event.argsFragment)
            }
            is ModelEvent.PartEnd -> {
                val part = parts[event.index]
                protocol(part != null && !part.ended, "PartEnd without open part")
                part!!.ended = true
                part.replay = event.replay?.let { replayData(it.protocol, it.model, it.payload) }
            }
            is ModelEvent.Finished -> {
                protocol(started && parts.values.all { it.ended }, "Finished with unclosed parts")
                terminal = if (event.reason == FinishReason.STOP && calls().isNotEmpty() && calls().all { it.complete || it.emptyArguments })
                    Events.finished(FinishReason.TOOL_CALLS, event.rawReason) else event
            }
        }
    }

    private fun add(index: Int, part: Part) {
        protocol(started && index == parts.size && !parts.containsKey(index), "Part indices must start at zero in order")
        parts[index] = part
    }
    private fun append(index: Int, kind: Kind, value: String) {
        val part = parts[index] ?: Part(kind).also { add(index, it) }
        protocol(part.kind == kind && !part.ended, "Part type changed or delta after PartEnd")
        part.content.append(value)
    }
    fun endOfInput() { protocol(terminal != null, "EOF without terminal") }
    fun calls(): List<CollectedCall> = parts.values.filter { it.kind == Kind.TOOL }.map {
        val args = it.content.toString()
        val complete = it.ended && runCatching { Json.parseToJsonElement(args) is JsonObject }.getOrDefault(false)
        CollectedCallValue(it.binding!!, it.name, args, complete, it.ended && args.isEmpty())
    }
    /** Only eligibility for downstream validation, never an execution approval. */
    fun toolCandidates(): List<CollectedCall> {
        val finish = terminal as? ModelEvent.Finished ?: return emptyList()
        return if (finish.reason in setOf(FinishReason.STOP, FinishReason.TOOL_CALLS, FinishReason.OTHER)) calls()
        else emptyList()
    }
    /** Snapshot in part-index order, including opaque adapter replay on every part. */
    fun assistantMessage(): Message = message(Role.ASSISTANT, *parts.values.map { part ->
        when (part.kind) {
            Kind.TEXT -> textPart(part.content.toString(), part.replay)
            Kind.REASONING -> reasoningPart(part.content.toString(), part.replay)
            Kind.TOOL -> toolCallPart(part.binding!!, part.name, part.content.toString(), part.replay)
        }
    }.toTypedArray())
    fun historyDisposition(): HistoryDisposition {
        val finish = terminal as? ModelEvent.Finished ?: return HistoryDisposition.DISCARD
        if (finish.reason in setOf(FinishReason.REFUSAL, FinishReason.CONTENT_FILTER) || parts.isEmpty())
            return HistoryDisposition.DISCARD
        if (finish.reason in setOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)) {
            if (calls().any { !it.complete }) return HistoryDisposition.DISCARD
            if (calls().isNotEmpty()) return HistoryDisposition.NEEDS_CANCELLED_NONE_RESULTS
        }
        return HistoryDisposition.KEEP
    }
}

/** Kernel-owned timeouts; coroutine scheduler is injectable via the collecting context. */
class EventTimeouts(val firstMs: Long = 180_000, val idleMs: Long = 120_000) {
    init { require(firstMs > 0 && idleMs > 0) }
}

/** Hold terminal until EOF so duplicate terminals cannot leak a previously successful result. */
fun Flow<ModelEvent>.validated(provider: String, requestId: String,
                               timeouts: EventTimeouts = EventTimeouts()): Flow<ModelEvent> = flow {
    val reducer = ModelEventReducer(provider, requestId)
    var failure: ModelEvent? = null
    // Deliberately not a child Job: logical timeout/cancellation must not join a
    // non-cooperative adapter. Keep the caller's dispatcher (including test clocks).
    // The producer owns no downstream state and its channel is cancelled on every exit.
    val context = currentCoroutineContext()
    context.ensureActive()
    val producerJob = Job()
    try {
        // Catch upstream exceptions in the producer, without cancelling the consumer scope.
        val source = this@validated.catch { error ->
            // The producer is only cancelled by us; an adapter's internal cancellation must still end in Failed.
            if (error is CancellationException) currentCoroutineContext().ensureActive()
            if (error !is Exception) throw error
            emit(Events.failed(modelError(
                when (error) {
                    is ProtocolException -> ModelErrorCode.PROTOCOL
                    is CancellationException -> ModelErrorCode.UNKNOWN
                    else -> ModelErrorCode.NETWORK
                },
                if (error is ProtocolException) error.message ?: "Protocol error" else "Model transport failed")))
        }.produceIn(CoroutineScope(context + producerJob))
        var first = true
        try {
            while (true) {
                val result = withTimeoutOrNull(if (first) timeouts.firstMs else timeouts.idleMs) { source.receiveCatching() }
                if (result == null) {
                    failure = Events.failed(modelError(ModelErrorCode.TIMEOUT, "Model event timeout"))
                    break
                }
                result.exceptionOrNull()?.let { throw it }
                if (result.isClosed) break
                val event = result.getOrThrow()
                // A heartbeat proves liveness, not that the model has begun: the first-event window stays.
                if (event !is ModelEvent.KeepAlive) first = false
                try { reducer.accept(event) }
                catch (error: ProtocolException) {
                    failure = Events.failed(modelError(ModelErrorCode.PROTOCOL, error.message ?: "Protocol error"))
                    break
                }
                if (event !is ModelEvent.Finished && event !is ModelEvent.Failed) emit(event)
            }
            if (failure == null) {
                try { reducer.endOfInput() }
                catch (error: ProtocolException) { failure = Events.failed(modelError(ModelErrorCode.PROTOCOL, error.message!!)) }
            }
        } finally { source.cancel() }
    } finally { producerJob.cancel() }
    emit(failure ?: reducer.terminal!!)
}
