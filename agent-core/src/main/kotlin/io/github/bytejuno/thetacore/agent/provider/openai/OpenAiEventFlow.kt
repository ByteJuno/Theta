package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** How long a server may keep the connection open once the response is complete. */
private const val TERMINAL_GRACE_MS = 2_000L
private class TerminalGraceExpired : Exception()
// Do not retain arbitrary echoed strings or truncated credentials in error metadata.
private val SAFE_ERROR_PARAMETERS = setOf("temperature", "top_p", "parallel_tool_calls", "response_format",
    "max_output_tokens", "reasoning", "text", "stop", "reasoning_effort", "max_tokens", "max_completion_tokens", "stream_options", "cache_control")

/** Shared transport lifecycle for the two OpenAI wire protocols. */
internal fun openAiEventFlow(
    endpoint: String, transport: ModelTransport, auth: Credentials,
    encode: () -> String,
    stream: () -> OpenAiEventStream,
    normalizeErrorParameter: (String) -> String = { it },
    decodeHttpError: (JsonObject?, Int?, Long?) -> ModelError = ::decodeError,
    requireAuthentication: Boolean = false,
): Flow<ModelEvent> = flow {
    val body = try { encode() } catch (error: IllegalArgumentException) {
        emit(Events.failed(modelError(ModelErrorCode.INVALID_REQUEST, "Invalid model request or incompatible history")))
        return@flow
    }
    val sse = SseDecoder()
    val decoder = stream()
    var headers: TransportEvent.Headers? = null
    val errorBody = ByteArrayOutputStream()
    var pendingTerminal: ModelEvent? = null
    val forward: suspend (ModelEvent) -> Unit = { event ->
        protocol(pendingTerminal == null, "Event after terminal")
        protocol(eventSizeBytes(event) <= 64 * 1024, "Event exceeds 64 KiB (replay requires external storage)")
        if (event is ModelEvent.Finished || event is ModelEvent.Failed) pendingTerminal = event else emit(event)
    }
    val token = try { auth.accessToken() } catch (error: Exception) {
        if (error is CancellationException) currentCoroutineContext().ensureActive()
        emit(Events.failed(modelError(ModelErrorCode.AUTH, "Provider credentials unavailable; re-enter the API key")))
        return@flow
    }
    // Empty credentials select no authentication. ApiKeyCredentials rejects empty keys;
    // other nonempty credentials must still be safe to put in a Bearer header.
    if (requireAuthentication && token.isEmpty() || token.isNotEmpty() && !validApiKey(token)) {
        emit(Events.failed(modelError(ModelErrorCode.AUTH, "Provider credentials invalid; re-enter the API key")))
        return@flow
    }
    var abandoned = false
    try { coroutineScope {
        var grace: Job? = null
        transport.exchange(endpoint, body, token).collect { event ->
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
                } else sse.feed(event.bytes) { kind, data, heartbeat -> decoder.frame(kind, data, heartbeat, forward) }
            }
        }
        // One window from the terminal, so later heartbeats cannot extend it.
        if (pendingTerminal != null && grace == null) grace = launch { delay(TERMINAL_GRACE_MS); throw TerminalGraceExpired() }
        }
        grace?.cancel()
    } } catch (_: TerminalGraceExpired) {
        abandoned = true
    } catch (error: java.io.IOException) {
        // A reset after the terminal cannot change a response that is already complete.
        if (pendingTerminal == null || error is java.nio.charset.CharacterCodingException) throw error
        abandoned = true
    }
    val head = headers ?: throw ProtocolException("Missing HTTP headers")
    if (head.status !in 200..299) {
        val root = runCatching { Json.parseToJsonElement(errorBody.toString("UTF-8")).jsonObject }.getOrNull()
        pendingTerminal = Events.failed(decodeHttpError(root?.get("error") as? JsonObject, head.status, retryAfter(head.retryAfter)))
    } else if (!abandoned) {
        sse.endOfInput(); decoder.endOfInput()
    }
    val terminal = pendingTerminal ?: Events.failed(modelError(ModelErrorCode.PROTOCOL, "Missing terminal"))
    // Servers may echo Authorization in error.param as well as message/body. Never journal it.
    if (terminal is ModelEvent.Failed) {
        val error = terminal.error
        // Check the original wire value before normalization as well as the canonical value.
        // Otherwise a credential equal to an alias could bypass the existing redaction check.
        val param = error.param?.takeIf { token.isEmpty() || !it.contains(token) }
            ?.let(normalizeErrorParameter)?.takeIf {
                it in SAFE_ERROR_PARAMETERS && (token.isEmpty() || !it.contains(token))
            }
        emit(Events.failed(modelError(error.code, "Model request failed", error.httpStatus,
            error.retryAfterMs, param)))
    } else emit(terminal)
}.catch { error ->
    // Only the collector's own cancellation stays silent; an adapter's internal one is a failure.
    if (error is CancellationException) currentCoroutineContext().ensureActive()
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

internal interface OpenAiEventStream {
    suspend fun frame(event: String?, data: String?, heartbeat: Boolean, emit: suspend (ModelEvent) -> Unit)
    fun endOfInput()
}
