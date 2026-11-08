package io.github.bytejuno.thetacore.agent.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.*
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

class OkHttpModelTransport : ModelTransport {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun exchange(url: String, body: String, token: String): Flow<TransportEvent> = callbackFlow {
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
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .post(oneShotBody).build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
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
                    } catch (error: Exception) { close(error) }
                }
                job.invokeOnCompletion { response.close() }
            }
        })
        awaitClose { call.cancel() }
    }
}
