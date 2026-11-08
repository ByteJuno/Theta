package io.github.bytejuno.thetacore.agent.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.util.Collections
import java.util.UUID

/** Experimental host protocol, NOT the plugin SDK ABI. No tool execution lives here. */
interface ModelProvider {
    fun executionMode(request: ChatRequest): ModelExecutionMode = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
    fun canReplay(data: ReplayData, targetModel: String): Boolean = false
    fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent>
}

fun interface Credentials { suspend fun accessToken(): String }
enum class ModelExecutionMode { GENERATION_ONLY, MAY_EXECUTE_SERVER_TOOLS }
enum class Role { USER, ASSISTANT, TOOL }
enum class FinishReason { STOP, TOOL_CALLS, LENGTH, CONTEXT_EXCEEDED, PAUSED, REFUSAL, CONTENT_FILTER, OTHER }
enum class ModelErrorCode {
    AUTH, RATE_LIMIT, QUOTA, OVERLOADED, SERVER, NETWORK, TIMEOUT, CONTEXT_TOO_LONG,
    UNSUPPORTED_PARAMETER, INVALID_REQUEST, MODEL_NOT_FOUND, CONTENT_BLOCKED, PROTOCOL, UNKNOWN
}

interface ModelError {
    val code: ModelErrorCode
    val message: String
    val httpStatus: Int?
    val retryAfterMs: Long?
    val param: String?
}
fun modelError(code: ModelErrorCode, message: String, httpStatus: Int? = null,
               retryAfterMs: Long? = null, param: String? = null): ModelError =
    ErrorValue(code, message, httpStatus, retryAfterMs, param)
private data class ErrorValue(override val code: ModelErrorCode, override val message: String,
    override val httpStatus: Int?, override val retryAfterMs: Long?, override val param: String?) : ModelError

interface TokenUsage {
    val inputTokens: Long?
    val cacheReadTokens: Long?
    val cacheWriteTokens: Long?
    val outputTokens: Long?
    val reasoningTokens: Long?
}
fun tokenUsage(inputTokens: Long? = null, cacheReadTokens: Long? = null, cacheWriteTokens: Long? = null,
               outputTokens: Long? = null, reasoningTokens: Long? = null): TokenUsage =
    UsageValue(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens, reasoningTokens)
private data class UsageValue(override val inputTokens: Long?, override val cacheReadTokens: Long?,
    override val cacheWriteTokens: Long?, override val outputTokens: Long?, override val reasoningTokens: Long?) : TokenUsage

interface ReplayData { val protocol: String; val model: String; val payload: String }
fun replayData(protocol: String, model: String, payload: String): ReplayData = ReplayValue(protocol, model, payload)
private data class ReplayValue(override val protocol: String, override val model: String,
    override val payload: String) : ReplayData

/** Persist this entire tuple with the call, never use wireId as a global identity. */
interface CallBinding {
    val provider: String; val requestId: String; val responseId: String?
    val index: Int; val wireId: String?; val callId: String
}
fun callBinding(provider: String, requestId: String, responseId: String?, index: Int,
                wireId: String?, callId: String = UUID.randomUUID().toString()): CallBinding {
    require(callId != wireId) { "Internal callId must not reuse wireId" }
    UUID.fromString(callId)
    return BindingValue(provider, requestId, responseId, index, wireId, callId)
}
private data class BindingValue(override val provider: String, override val requestId: String,
    override val responseId: String?, override val index: Int, override val wireId: String?,
    override val callId: String) : CallBinding

sealed interface ContentPart {
    val replay: ReplayData?
    interface Text : ContentPart { val text: String }
    interface Reasoning : ContentPart { val summary: String? }
    interface ToolCall : ContentPart { val binding: CallBinding; val name: String; val argsJson: String }
    interface ToolResult : ContentPart { val callId: String; val text: String; val isError: Boolean }
}
fun textPart(text: String, replay: ReplayData? = null): ContentPart.Text = TextValue(text, replay)
fun reasoningPart(summary: String?, replay: ReplayData?): ContentPart.Reasoning = ReasoningValue(summary, replay)
fun toolCallPart(binding: CallBinding, name: String, argsJson: String): ContentPart.ToolCall = CallValue(binding, name, argsJson)
fun toolResultPart(callId: String, text: String, isError: Boolean = false): ContentPart.ToolResult = ResultValue(callId, text, isError)
private data class TextValue(override val text: String, override val replay: ReplayData?) : ContentPart.Text
private data class ReasoningValue(override val summary: String?, override val replay: ReplayData?) : ContentPart.Reasoning
private data class CallValue(override val binding: CallBinding, override val name: String,
    override val argsJson: String, override val replay: ReplayData? = null) : ContentPart.ToolCall
private data class ResultValue(override val callId: String, override val text: String,
    override val isError: Boolean, override val replay: ReplayData? = null) : ContentPart.ToolResult

interface Message { val role: Role; val parts: List<ContentPart> }
fun message(role: Role, vararg parts: ContentPart): Message = MessageValue(role, frozen(parts.toList()))
private data class MessageValue(override val role: Role, override val parts: List<ContentPart>) : Message
interface ModelTool { val name: String; val description: String; val inputSchema: JsonObject }
fun modelTool(name: String, description: String, inputSchema: JsonObject): ModelTool = ToolValue(name, description, inputSchema)
private data class ToolValue(override val name: String, override val description: String,
    override val inputSchema: JsonObject) : ModelTool
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    interface Named : ToolChoice { val name: String }
}
fun namedTool(name: String): ToolChoice.Named = NamedValue(name)
private data class NamedValue(override val name: String) : ToolChoice.Named

interface ChatRequest {
    val requestId: String; val model: String; val system: String?
    val messages: List<Message>; val tools: List<ModelTool>; val toolChoice: ToolChoice
    val parallelToolCalls: Boolean?; val reasoningLevel: String?; val maxOutputTokens: Int?
    val temperature: Double?; val topP: Double?; val stopSequences: List<String>
    val responseSchema: JsonObject?; val extraBody: JsonObject
}
class ChatRequestBuilder(val model: String) {
    var requestId: String = UUID.randomUUID().toString()
    var system: String? = null
    var messages: List<Message> = emptyList()
    var tools: List<ModelTool> = emptyList()
    var toolChoice: ToolChoice = ToolChoice.Auto
    var parallelToolCalls: Boolean? = null
    var reasoningLevel: String? = null
    var maxOutputTokens: Int? = null
    var temperature: Double? = null
    var topP: Double? = null
    var stopSequences: List<String> = emptyList()
    var responseSchema: JsonObject? = null
    var extraBody: JsonObject = JsonObject(emptyMap())
    internal fun build(): ChatRequest {
        require(model.isNotBlank() && requestId.isNotBlank())
        require(maxOutputTokens == null || maxOutputTokens!! > 0)
        require(temperature == null || (temperature!!.isFinite() && temperature!! in 0.0..2.0))
        require(topP == null || (topP!!.isFinite() && topP!! in 0.0..1.0))
        return RequestValue(requestId, model, system, frozen(messages.map { message(it.role, *it.parts.toTypedArray()) }),
            frozen(tools.sortedBy { it.name }), toolChoice, parallelToolCalls, reasoningLevel,
            maxOutputTokens, temperature, topP, frozen(stopSequences), responseSchema, extraBody)
    }
}
fun chatRequest(model: String, configure: ChatRequestBuilder.() -> Unit = {}): ChatRequest =
    ChatRequestBuilder(model).apply(configure).build()
private data class RequestValue(override val requestId: String, override val model: String,
    override val system: String?, override val messages: List<Message>, override val tools: List<ModelTool>,
    override val toolChoice: ToolChoice, override val parallelToolCalls: Boolean?, override val reasoningLevel: String?,
    override val maxOutputTokens: Int?, override val temperature: Double?, override val topP: Double?,
    override val stopSequences: List<String>, override val responseSchema: JsonObject?, override val extraBody: JsonObject) : ChatRequest
internal fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

/** Constructors are factory functions; concrete event representations stay private. */
sealed interface ModelEvent {
    interface Started : ModelEvent { val responseId: String?; val model: String? }
    interface TextDelta : ModelEvent { val index: Int; val text: String }
    interface ReasoningDelta : ModelEvent { val index: Int; val text: String }
    interface ToolCallStart : ModelEvent { val index: Int; val wireId: String?; val name: String }
    interface ToolCallDelta : ModelEvent { val index: Int; val argsFragment: String }
    interface PartEnd : ModelEvent { val index: Int; val replay: ReplayData? }
    interface Usage : ModelEvent { val usage: TokenUsage }
    interface KeepAlive : ModelEvent
    interface Finished : ModelEvent { val reason: FinishReason; val rawReason: String? }
    interface Failed : ModelEvent { val error: ModelError }
}
object Events {
    fun started(responseId: String?, model: String?): ModelEvent = Start(responseId, model)
    fun text(index: Int, text: String): ModelEvent = Text(index, text)
    fun reasoning(index: Int, text: String): ModelEvent = Reasoning(index, text)
    fun toolStart(index: Int, wireId: String?, name: String): ModelEvent = ToolStart(index, wireId, name)
    fun toolDelta(index: Int, fragment: String): ModelEvent = ToolDelta(index, fragment)
    fun end(index: Int, replay: ReplayData? = null): ModelEvent = End(index, replay)
    fun usage(usage: TokenUsage): ModelEvent = Usage(usage)
    fun keepAlive(): ModelEvent = Heartbeat
    fun finished(reason: FinishReason, rawReason: String? = null): ModelEvent = Finish(reason, rawReason)
    fun failed(error: ModelError): ModelEvent = Failure(error)
    private data class Start(override val responseId: String?, override val model: String?) : ModelEvent.Started
    private data class Text(override val index: Int, override val text: String) : ModelEvent.TextDelta
    private data class Reasoning(override val index: Int, override val text: String) : ModelEvent.ReasoningDelta
    private data class ToolStart(override val index: Int, override val wireId: String?, override val name: String) : ModelEvent.ToolCallStart
    private data class ToolDelta(override val index: Int, override val argsFragment: String) : ModelEvent.ToolCallDelta
    private data class End(override val index: Int, override val replay: ReplayData?) : ModelEvent.PartEnd
    private data class Usage(override val usage: TokenUsage) : ModelEvent.Usage
    private data object Heartbeat : ModelEvent.KeepAlive
    private data class Finish(override val reason: FinishReason, override val rawReason: String?) : ModelEvent.Finished
    private data class Failure(override val error: ModelError) : ModelEvent.Failed
}
