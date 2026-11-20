package io.github.bytejuno.thetacore.agent.provider.openai

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit

class ImageRequestException(val failure: Failure) : Exception(failure.name) {
    enum class Failure { HTTP, NETWORK, INVALID_RESPONSE, TOO_LARGE, UNSAFE_URL }
}

object ImageLimits {
    const val BYTES = 8 * 1024 * 1024
    const val RESPONSE_BYTES = 12 * 1024 * 1024
    const val DIMENSION = 4096
    const val PIXELS = 4_194_304L
    fun validDimensions(width: Int, height: Int) = width in 1..DIMENSION && height in 1..DIMENSION &&
        width.toLong() * height <= PIXELS
}

internal fun publicImageAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val first = bytes[0]; val second = bytes[1]
        return first !in setOf(0, 10, 127) && first < 224 &&
            !(first == 100 && second in 64..127) && !(first == 169 && second == 254) &&
            !(first == 172 && second in 16..31) && !(first == 192 && second in setOf(0, 168)) &&
            !(first == 198 && second in setOf(18, 19, 51)) && !(first == 203 && second == 0)
    }
    return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
        !(bytes[0] == 0x20 && (bytes[1] == 0x02 || (bytes[1] == 0x01 &&
            (bytes[2] <= 1 || (bytes[2] == 0x0d && bytes[3] == 0xb8)))))
}

internal class PublicImageDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = delegate.lookup(hostname).also { addresses ->
        if (addresses.isEmpty() || addresses.any { !publicImageAddress(it) })
            throw java.net.UnknownHostException("Image download requires a public address")
    }
}

class OpenAiImageClient internal constructor(private val generation: OkHttpClient, private val download: OkHttpClient) {
    constructor() : this(builder().build(), builder().proxy(Proxy.NO_PROXY).dns(PublicImageDns()).build())
    companion object {
    private fun builder() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
    }

    suspend fun generate(snapshot: OpenAiRunSnapshot, prompt: String): ByteArray {
        require(prompt.isNotBlank() && prompt.length <= 8000)
        try {
            val base = snapshot.settings.baseUrl.toHttpUrl()
            val endpoint = base.newBuilder().apply {
                if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1)
                addPathSegments("images/generations")
            }.build()
            val payload = buildJsonObject {
                put("model", snapshot.settings.model); put("prompt", prompt); put("n", 1)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val oneShot = object : RequestBody() {
                override fun contentType() = payload.contentType()
                override fun contentLength() = payload.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: okio.BufferedSink) = payload.writeTo(sink)
            }
            val request = Request.Builder().url(endpoint).header("Accept", "application/json")
                .header("Authorization", "Bearer ${snapshot.target().credentials.accessToken()}")
                .post(oneShot).build()
            val response = fetch(generation, request, ImageLimits.RESPONSE_BYTES)
            val items = Json.parseToJsonElement(response.toString(Charsets.UTF_8)).jsonObject.getValue("data").jsonArray
            require(items.size == 1)
            val item = items.single().jsonObject
            val encoded = item["b64_json"]?.jsonPrimitive?.contentOrNull
            if (encoded != null) {
                if (encoded.length > (ImageLimits.BYTES + 2L) / 3 * 4) tooLarge()
                return Base64.getDecoder().decode(encoded).also {
                    require(it.isNotEmpty()); if (it.size > ImageLimits.BYTES) tooLarge()
                }
            }
            val url = imageDownloadUrl(item.getValue("url").jsonPrimitive.content)
            return fetch(download, Request.Builder().url(url).get().build(), ImageLimits.BYTES)
        } catch (error: CancellationException) { throw error }
        catch (error: ImageRequestException) { throw error }
        catch (_: IOException) { throw ImageRequestException(ImageRequestException.Failure.NETWORK) }
        catch (_: Exception) { throw ImageRequestException(ImageRequestException.Failure.INVALID_RESPONSE) }
    }

    private suspend fun fetch(client: OkHttpClient, request: Request, limit: Int): ByteArray = callbackFlow {
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { close(error) }
            override fun onResponse(call: Call, response: Response) {
                val reader = launch(Dispatchers.IO) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw ImageRequestException(ImageRequestException.Failure.HTTP)
                            if (it.body.contentLength() > limit) tooLarge()
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            it.body.byteStream().use { input ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() > limit - count) tooLarge()
                                    output.write(buffer, 0, count)
                                }
                            }
                            send(output.toByteArray())
                        }
                        close()
                    } catch (error: Exception) { close(error) }
                }
                reader.invokeOnCompletion { response.close() }
            }
        })
        awaitClose { call.cancel() }
    }.single()

    private fun tooLarge(): Nothing = throw ImageRequestException(ImageRequestException.Failure.TOO_LARGE)
}

internal fun imageDownloadUrl(value: String): HttpUrl {
    val url = try { value.toHttpUrl() } catch (_: Exception) {
        throw ImageRequestException(ImageRequestException.Failure.UNSAFE_URL)
    }
    if (value.length > 8192 || url.scheme != "https" ||
        url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
        throw ImageRequestException(ImageRequestException.Failure.UNSAFE_URL)
    if ((url.host.contains(':') || url.host.all { it.isDigit() || it == '.' }) &&
        !publicImageAddress(InetAddress.getByName(url.host)))
        throw ImageRequestException(ImageRequestException.Failure.UNSAFE_URL)
    return url
}

