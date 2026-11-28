package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.Credentials
import io.github.bytejuno.thetacore.agent.transport.ModelAuthentication
import io.github.bytejuno.thetacore.agent.transport.modelAuthentication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only fixed categories escape this boundary; no response, URL, key or cause is retained. */
class ModelsRequestException(val failure: Failure) : Exception(failure.name) {
    enum class Failure { INVALID_SETTINGS, HTTP, TIMEOUT, NETWORK, INVALID_RESPONSE, TOO_LARGE }
}

/** Explicit read-only discovery. Never changes settings or invokes a model/tool. */
class OpenAiModelsClient internal constructor(client: OkHttpClient, private val authentication: ModelAuthentication = ModelAuthentication.BEARER) {
    constructor() : this(OkHttpClient())
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun fetch(baseUrl: String, credentials: Credentials): List<String> {
        try {
            if (baseUrl.length > 4096) fail(ModelsRequestException.Failure.INVALID_SETTINGS)
            // Reuse the exact provider URL validation and path normalization, without requiring a model.
            val chat = try { OpenAiChatConfig("models", baseUrl).endpoint }
                catch (_: IllegalArgumentException) { fail(ModelsRequestException.Failure.INVALID_SETTINGS) }
            val endpoint = chat.newBuilder().removePathSegment(chat.pathSize - 1)
                .removePathSegment(chat.pathSize - 2).addPathSegment("models").build()
            val token = credentials.accessToken()
            if (authentication != ModelAuthentication.BEARER && token.isEmpty()) fail(ModelsRequestException.Failure.INVALID_SETTINGS)
            if (token.isNotEmpty() && !validApiKey(token)) fail(ModelsRequestException.Failure.INVALID_SETTINGS)
            val builder = Request.Builder().url(endpoint).get().header("Accept", "application/json")
            builder.modelAuthentication(token, authentication)
            val request = builder.build()
            // OkHttp cannot deliver its timeout callback until a blocking DNS lookup returns.
            // Bound the caller's wait independently; cancellation also cancels the underlying call.
            return withTimeoutOrNull(30_000) {
                val models = sortedSetOf<String>(); val cursors = mutableSetOf<String>()
                var next = request
                while (true) {
                    val page = read(next); models.addAll(page.models)
                    if (models.size > MAX_MODELS) fail(ModelsRequestException.Failure.TOO_LARGE)
                    val cursor = page.cursor ?: break
                    if (cursors.size >= 32 || !cursors.add(cursor)) fail(ModelsRequestException.Failure.INVALID_RESPONSE)
                    next = request.newBuilder().url(endpoint.newBuilder().addQueryParameter(if (authentication == ModelAuthentication.GEMINI) "pageToken" else "after_id", cursor).build()).build()
                }
                models.toList()
            }
                ?: fail(ModelsRequestException.Failure.TIMEOUT)
        } catch (error: CancellationException) { throw error }
        catch (error: ModelsRequestException) { throw error }
        catch (_: Exception) { fail(ModelsRequestException.Failure.INVALID_SETTINGS) }
    }

    private data class Page(val models: List<String>, val cursor: String?)
    private suspend fun read(request: Request): Page = suspendCancellableCoroutine { continuation ->
        // OkHttp can follow up 503 Retry-After:0 even when connection retries are disabled.
        val exchanged = AtomicBoolean()
        val call = client.newBuilder().addNetworkInterceptor { chain ->
            if (!exchanged.compareAndSet(false, true)) throw IOException("Model list follow-up blocked")
            chain.proceed(chain.request())
        }.build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(ModelsRequestException(if (e is InterruptedIOException)
                    ModelsRequestException.Failure.TIMEOUT else ModelsRequestException.Failure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val models = response.use {
                        if (!it.isSuccessful) fail(ModelsRequestException.Failure.HTTP)
                        if (it.body.contentLength() > MAX_RESPONSE_BYTES) fail(ModelsRequestException.Failure.TOO_LARGE)
                        // request() reads at most the cap plus one byte, including chunked responses.
                        val source = it.body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1L)) fail(ModelsRequestException.Failure.TOO_LARGE)
                        parse(source.readUtf8())
                    }
                    continuation.resume(models)
                } catch (error: Exception) {
                    val failure = when (error) {
                        is ModelsRequestException -> error
                        is InterruptedIOException -> ModelsRequestException(ModelsRequestException.Failure.TIMEOUT)
                        is IOException -> ModelsRequestException(ModelsRequestException.Failure.NETWORK)
                        else -> ModelsRequestException(ModelsRequestException.Failure.INVALID_RESPONSE)
                    }
                    continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun parse(text: String): Page {
        // Bound parser nesting as well as bytes before constructing the JSON tree.
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) fail(ModelsRequestException.Failure.INVALID_RESPONSE) }
                '}', ']' -> depth--
            }
        }
        val root = Json.parseToJsonElement(text) as? JsonObject ?: fail(ModelsRequestException.Failure.INVALID_RESPONSE)
        val data = root[if (authentication == ModelAuthentication.GEMINI) "models" else "data"] as? JsonArray
            ?: fail(ModelsRequestException.Failure.INVALID_RESPONSE)
        val result = sortedSetOf<String>()
        for (entry in data) {
            val obj = entry as? JsonObject ?: continue
            if (authentication == ModelAuthentication.GEMINI &&
                (obj["supportedGenerationMethods"] as? JsonArray)?.contains(JsonPrimitive("generateContent")) != true) continue
            val id = obj[if (authentication == ModelAuthentication.GEMINI) "name" else "id"] as? JsonPrimitive ?: continue
            if (!id.isString) continue
            val value = id.content.trim().let { if (authentication == ModelAuthentication.GEMINI) it.removePrefix("models/") else it }
            if (value.isEmpty()) continue
            if (value.length > 256 || value.any { it.isISOControl() }) fail(ModelsRequestException.Failure.INVALID_RESPONSE)
            result.add(value)
            if (result.size > MAX_MODELS) fail(ModelsRequestException.Failure.TOO_LARGE)
        }
        var cursor: String? = null
        if (authentication == ModelAuthentication.GEMINI && root["nextPageToken"] != null) {
            val next = root["nextPageToken"] as? JsonPrimitive ?: fail(ModelsRequestException.Failure.INVALID_RESPONSE)
            if (!next.isString || next.content.length > 2048 || next.content.any { it.isISOControl() }) fail(ModelsRequestException.Failure.INVALID_RESPONSE)
            cursor = next.content.takeIf { it.isNotBlank() }
        }
        if (authentication == ModelAuthentication.ANTHROPIC && root["has_more"] != null) {
            val more = root["has_more"] as? JsonPrimitive ?: fail(ModelsRequestException.Failure.INVALID_RESPONSE)
            if (more.isString || more.booleanOrNull == null) fail(ModelsRequestException.Failure.INVALID_RESPONSE)
            if (more.boolean) {
                val last = root["last_id"] as? JsonPrimitive ?: fail(ModelsRequestException.Failure.INVALID_RESPONSE)
                if (!last.isString || last.content.isBlank() || last.content.length > 256 || last.content.any { it.isISOControl() })
                    fail(ModelsRequestException.Failure.INVALID_RESPONSE)
                cursor = last.content
            }
        }
        return Page(result.toList(), cursor)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_MODELS = 2048
        private fun fail(failure: ModelsRequestException.Failure): Nothing = throw ModelsRequestException(failure)
    }
}
