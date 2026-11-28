package io.github.bytejuno.thetacore.agent.transport

import io.github.bytejuno.thetacore.agent.provider.openai.isPrivateCleartextHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One collection is exactly one HTTP attempt. No redirects, authentication callbacks or retries. */
fun interface ModelTransport {
    fun exchange(url: String, body: String, token: String): Flow<TransportEvent>
}
sealed interface TransportEvent {
    class Headers(val status: Int, val contentType: String?, val retryAfter: String?) : TransportEvent
    class Bytes(val bytes: ByteArray) : TransportEvent
}

object SharedHttpClient {
    val instance: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}

/** Fixed by the adapter, never inferred from a URL or a credential's contents. */
enum class ModelAuthentication { BEARER, ANTHROPIC }

internal fun Request.Builder.modelAuthentication(token: String, authentication: ModelAuthentication): Request.Builder = apply {
    when (authentication) {
        ModelAuthentication.BEARER -> if (token.isNotEmpty()) header("Authorization", "Bearer $token")
        ModelAuthentication.ANTHROPIC -> {
            require(token.isNotEmpty())
            header("x-api-key", token)
            header("anthropic-version", "2023-06-01")
        }
    }
}

class OkHttpModelTransport(private val authentication: ModelAuthentication = ModelAuthentication.BEARER) : ModelTransport {
    private val client = SharedHttpClient.instance
    fun withAuthentication(value: ModelAuthentication): OkHttpModelTransport =
        if (value == authentication) this else OkHttpModelTransport(value)

    override fun exchange(url: String, body: String, token: String): Flow<TransportEvent> = callbackFlow {
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" || isPrivateCleartextHost(parsed.host)) {
            "Cleartext HTTP requires localhost, private LAN, or Tailscale address"
        }
        val delegate = body.toRequestBody("application/json; charset=utf-8".toMediaType())
        // Disallow follow-up replay too (503 Retry-After: 0 and HTTP/2 recovery), not just connection retries.
        val oneShotBody = object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = delegate.writeTo(sink)
        }
        val request = Request.Builder().url(url)
            .header("Accept", "text/event-stream")
            .modelAuthentication(token, authentication)
            .post(oneShotBody).build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) close(e)
            }
            override fun onResponse(call: Call, response: Response) {
                // Cancellation can race response delivery: never leave the delivered body unowned.
                val job = launch(Dispatchers.IO) {
                    try {
                        response.use {
                            send(TransportEvent.Headers(it.code, it.header("Content-Type"),
                                it.header("Retry-After")))
                            val source = it.body.source()
                            val buffer = ByteArray(8_192)
                            while (true) {
                                val size = source.read(buffer)
                                if (size == -1) break
                                send(TransportEvent.Bytes(buffer.copyOf(size)))
                            }
                        }
                        close()
                    } catch (error: Exception) {
                        // awaitClose can cancel the call before cancellation reaches this child.
                        // That socket error belongs to cleanup, even while the child is still active.
                        if (call.isCanceled()) return@launch
                        coroutineContext.ensureActive()
                        close(error)
                    }
                }
                job.invokeOnCompletion { response.close() }
            }
        })
        awaitClose { call.cancel() }
    }
}
