package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Atomic append seam. Implementations must CAS the revision and commit the ENTIRE batch or none.
 * An uncertain write must throw and halt the run, never trigger model/tool replay. Session disk
 * persistence and crash reconciliation are host work; this in-memory implementation is not durable.
 */
interface RunHistory {
    fun snapshot(): HistorySnapshot
    fun append(expectedRevision: Long, messages: List<Message>)
}
data class HistorySnapshot(val revision: Long, val messages: List<Message>)

class MemoryRunHistory(initial: List<Message> = emptyList()) : RunHistory {
    private var revision = 0L
    private var messages = freezeMessages(initial).also(::validateHistory)
    @Synchronized override fun snapshot() = HistorySnapshot(revision, freezeMessages(messages))
    @Synchronized override fun append(expectedRevision: Long, messages: List<Message>) {
        check(revision == expectedRevision) { "History changed during run" }
        val next = freezeMessages(this.messages + messages)
        validateHistory(next)
        this.messages = next
        revision++
    }
}

/** Reject orphan, duplicated, reordered results and user interleaving before any provider sees them. */
fun validateHistory(messages: List<Message>) {
    val seen = mutableSetOf<String>()
    var pending = emptyList<String>()
    for (m in messages) {
        if (pending.isNotEmpty()) {
            require(m.role == Role.TOOL && m.parts.all { it is ContentPart.ToolResult })
            require(m.parts.map { (it as ContentPart.ToolResult).callId } == pending)
            pending = emptyList()
        } else {
            require(m.role != Role.TOOL && m.parts.none { it is ContentPart.ToolResult })
            val calls = m.parts.filterIsInstance<ContentPart.ToolCall>()
            require(calls.isEmpty() || m.role == Role.ASSISTANT)
            pending = calls.map { it.binding.callId }
            require(pending.all { seen.add(it) }) { "Duplicate call identity" }
        }
    }
    require(pending.isEmpty()) { "Unpaired calls" }
}

internal fun freezeMessages(messages: List<Message>): List<Message> = frozen(messages.map { m ->
    message(m.role, *m.parts.map { p ->
        val replay = p.replay?.let { replayData(it.protocol, it.model, it.payload) }
        when (p) {
            is ContentPart.Text -> textPart(p.text, replay)
            is ContentPart.Reasoning -> reasoningPart(p.summary, replay)
            is ContentPart.ToolCall -> with(p.binding) {
                toolCallPart(callBinding(provider, requestId, responseId, index, wireId, callId), p.name, p.argsJson, replay)
            }
            is ContentPart.ToolResult -> FrozenResult(p.callId, p.text, p.isError, replay)
        }
    }.toTypedArray())
})

private data class FrozenResult(override val callId: String, override val text: String,
    override val isError: Boolean, override val replay: ReplayData?) : ContentPart.ToolResult

internal fun copyRequest(source: ChatRequest, messages: List<Message> = source.messages,
                         model: String = source.model, requestId: String = source.requestId): ChatRequest = chatRequest(model) {
    this.requestId = requestId
    system = source.system
    this.messages = freezeMessages(messages)
    tools = source.tools.map { modelTool(it.name, it.description, copyJson(it.inputSchema)) }
    toolChoice = (source.toolChoice as? ToolChoice.Named)?.let { namedTool(it.name) } ?: source.toolChoice
    parallelToolCalls = source.parallelToolCalls
    reasoningLevel = source.reasoningLevel
    maxOutputTokens = source.maxOutputTokens
    temperature = source.temperature
    topP = source.topP
    stopSequences = source.stopSequences.toList()
    responseSchema = source.responseSchema?.let(::copyJson)
    extraBody = copyJson(source.extraBody)
}
private fun copyJson(value: JsonObject) = Json.parseToJsonElement(value.toString()) as JsonObject
