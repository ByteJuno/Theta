package io.github.bytejuno.thetacore.web

import io.github.bytejuno.thetacore.agent.transport.SharedHttpClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class WebError { ADDRESS, DOWNLOAD, RESPONSE, NETWORK, SETTINGS, INPUT }
internal class WebFailure(val reason: WebError) : IOException(reason.name)
internal const val WEB_DOWNLOAD_LIMIT = 2 * 1024 * 1024

/** Fail closed for non-global, mapped, transition and special-purpose ranges. No DNS in this predicate. */
internal fun publicWebAddress(address: InetAddress): Boolean {
    val b = address.address.map { it.toInt() and 255 }
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    if (b.size == 4) return when {
        b[0] in listOf(0, 10, 127) || b[0] >= 224 -> false
        b[0] == 100 && b[1] in 64..127 -> false
        b[0] == 169 && b[1] == 254 -> false
        b[0] == 172 && b[1] in 16..31 -> false
        b[0] == 192 && (b[1] == 168 || (b[1] == 0 && b[2] in listOf(0, 2)) ||
            (b[1] == 88 && b[2] == 99)) -> false
        b[0] == 198 && (b[1] in 18..19 || (b[1] == 51 && b[2] == 100)) -> false
        b[0] == 203 && b[1] == 0 && b[2] == 113 -> false
        else -> true
    }
    // Global unicast only; exclude protocol assignments, documentation and 6to4 tunnelling.
    return b.size == 16 && b[0] in 0x20..0x3f &&
        !(b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || (b[2] == 0x0d && b[3] == 0xb8))) &&
        !(b[0] == 0x20 && b[1] == 0x02) && !(b[0] == 0x3f && b[1] == 0xff)
}

internal fun webUrl(raw: String): HttpUrl {
    val url = raw.takeIf { it.length <= 8192 }?.toHttpUrlOrNull() ?: throw WebFailure(WebError.ADDRESS)
    if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) throw WebFailure(WebError.ADDRESS)
    return url.newBuilder().fragment(null).build()
}

internal class WebResponse(val url: HttpUrl, val type: String, val bytes: ByteArray)

/** Shares the host pool, but no host headers, proxy, cookies, auth callbacks or automatic redirects.
 * Every hop resolves and validates ALL answers, then pins those exact addresses to the socket DNS.
 * HTTP/1.1 prevents HTTP/2 cross-origin connection coalescing across validated host boundaries. */
internal class WebHttp(
    private val client: OkHttpClient = SharedHttpClient.instance,
    private val dns: WebDns = AndroidWebDns,
    private val requestTimeoutMillis: Long = 20_000,
) {
    suspend fun get(url: String): WebResponse = request(Request.Builder().url(webUrl(url)).get().build(), redirects = true)

    suspend fun request(initial: Request, redirects: Boolean = false): WebResponse = withTimeoutOrNull(requestTimeoutMillis) {
        var request = initial
        repeat(6) { hop ->
            val url = webUrl(request.url.toString())
            val addresses = dns.resolve(url.host)
            if (addresses.isEmpty() || addresses.any { !publicWebAddress(it) }) throw WebFailure(WebError.ADDRESS)
            val attempted = AtomicBoolean()
            val safe = client.newBuilder().proxy(Proxy.NO_PROXY).cache(null)
                .dns { host -> if (host == url.host) addresses else throw WebFailure(WebError.ADDRESS) }
                .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
                .apply { interceptors().clear(); networkInterceptors().clear() }
                .addNetworkInterceptor { chain ->
                    if (!attempted.compareAndSet(false, true)) throw WebFailure(WebError.NETWORK)
                    val peer = chain.connection()?.route()?.socketAddress?.address
                    // The shared pool can contain connections created by other host features.
                    // Never send a request on a cached route outside this hop's validated answers.
                    if (peer == null || !publicWebAddress(peer) || peer !in addresses) throw WebFailure(WebError.ADDRESS)
                    chain.proceed(chain.request())
                }.build()
            val reply = exchange(safe.newCall(request))
            if (reply.status in 300..399) {
                if (!redirects || hop == 5) throw WebFailure(WebError.RESPONSE)
                val next = reply.location?.let(url::resolve) ?: throw WebFailure(WebError.RESPONSE)
                // A new GET has no headers from the previous origin. Search credentials never redirect.
                request = Request.Builder().url(webUrl(next.toString())).get().build()
            } else {
                if (reply.status !in 200..299) throw WebFailure(WebError.RESPONSE)
                return@withTimeoutOrNull WebResponse(url, reply.type, reply.bytes)
            }
        }
        throw WebFailure(WebError.RESPONSE)
    } ?: throw WebFailure(WebError.NETWORK)

    private class Reply(val status: Int, val location: String?, val type: String, val bytes: ByteArray)
    private suspend fun exchange(call: Call): Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(
                    if (e is WebFailure) e else WebFailure(WebError.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val body = it.body
                        val bytes = if (it.code in 200..299) {
                            if (body.contentLength() > WEB_DOWNLOAD_LIMIT) throw WebFailure(WebError.DOWNLOAD)
                            val source = body.source()
                            val buffer = okio.Buffer()
                            while (true) {
                                val n = source.read(buffer, minOf(8192L, WEB_DOWNLOAD_LIMIT + 1L - buffer.size))
                                if (buffer.size > WEB_DOWNLOAD_LIMIT) throw WebFailure(WebError.DOWNLOAD)
                                if (n == -1L) break
                            }
                            buffer.readByteArray()
                        } else byteArrayOf()
                        Reply(it.code, it.header("Location"), body.contentType()?.toString().orEmpty(), bytes)
                    }
                    if (!continuation.isCancelled) continuation.resume(result)
                } catch (e: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(
                        if (e is WebFailure) e else WebFailure(WebError.NETWORK))
                }
            }
        })
    }
}
