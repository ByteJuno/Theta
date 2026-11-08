package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class OpenAiChatProvider(
    private val config: OpenAiChatConfig,
    private val transport: ModelTransport = OkHttpModelTransport(),
) : ModelProvider {
    private val codec = OpenAiChatCodec(config)
    override fun canReplay(data: ReplayData, targetModel: String): Boolean = codec.canReplay(data, targetModel)
    override fun executionMode(request: ChatRequest): ModelExecutionMode =
        if (request.extraBody.isEmpty()) ModelExecutionMode.GENERATION_ONLY else ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS

    /** No timeout/retry policy here: the host wraps this cold flow with validated(). */
    override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
        val body = try { codec.encode(request) } catch (error: IllegalArgumentException) {
            emit(Events.failed(modelError(ModelErrorCode.INVALID_REQUEST, "Invalid OpenAI Chat request or incompatible history")))
            return@flow
        }
        val sse = SseDecoder()
        val stream = OpenAiChatStream(config, request, codec)
        var headers: TransportEvent.Headers? = null
        val errorBody = ByteArrayOutputStream()
        var pendingTerminal: ModelEvent? = null
        val forward: suspend (ModelEvent) -> Unit = { event ->
            protocol(pendingTerminal == null, "Event after terminal")
            protocol(eventSizeBytes(event) <= 64 * 1024, "Event exceeds 64 KiB (replay requires external storage)")
            if (event is ModelEvent.Finished || event is ModelEvent.Failed) pendingTerminal = event else emit(event)
        }
        val token = auth.accessToken()
        transport.exchange(config.endpoint.toString(), body, token).collect { event ->
            when (event) {
                is TransportEvent.Headers -> {
                    protocol(headers == null, "Duplicate HTTP headers")
                    headers = event
                    if (event.status in 200..299) protocol(event.contentType?.substringBefore(';')?.trim()
                        ?.equals("text/event-stream", ignoreCase = true) == true, "Expected text/event-stream")
                }
                is TransportEvent.Bytes -> {
                    val head = headers ?: throw ProtocolException("Body before HTTP headers")
                    if (head.status !in 200..299) {
                        val remaining = 64 * 1024 - errorBody.size()
                        if (remaining > 0) errorBody.write(event.bytes, 0, minOf(remaining, event.bytes.size))
                    } else sse.feed(event.bytes) { kind, data, heartbeat -> stream.frame(kind, data, heartbeat, forward) }
                }
            }
        }
        val head = headers ?: throw ProtocolException("Missing HTTP headers")
        if (head.status !in 200..299) {
            val root = runCatching { Json.parseToJsonElement(errorBody.toString("UTF-8")).jsonObject }.getOrNull()
            pendingTerminal = Events.failed(decodeError(root?.get("error") as? JsonObject, head.status, retryAfter(head.retryAfter)))
        } else {
            sse.endOfInput(); stream.endOfInput()
        }
        emit(pendingTerminal ?: Events.failed(modelError(ModelErrorCode.PROTOCOL, "Missing terminal")))
    }.catch { error ->
        if (error is CancellationException) throw error
        if (error !is Exception) throw error
        val code = when (error) {
            is IllegalArgumentException, is java.nio.charset.CharacterCodingException -> ModelErrorCode.PROTOCOL
            is java.io.IOException -> ModelErrorCode.NETWORK
            else -> ModelErrorCode.UNKNOWN
        }
        emit(Events.failed(modelError(code, if (error is ProtocolException) error.message ?: "Protocol error" else "Model request failed")))
    }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        value.toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1000 }?.let { return it * 1000 }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
        }.getOrNull()
    }
}
