package io.github.bytejuno.thetacore.agent.provider.openai

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit

class OpenAiImageClientTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private fun snapshot(base: String) = OpenAiRunSnapshot(OpenAiProviderSettings("image", base, "image-model"), ApiKeyCredentials("synthetic-image-key"))
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    @Test fun base64RequestUsesConfiguredPathModelPromptAndKey() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""))
            val result = OpenAiImageClient().generate(snapshot(server.url("/custom/v1/").toString()), "画一只猫")
            assertArrayEquals(bytes, result)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/custom/v1/images/generations", request.url.encodedPath)
            assertEquals("Bearer synthetic-image-key", request.headers["Authorization"])
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals("image-model", body.getValue("model").jsonPrimitive.content)
            assertEquals("画一只猫", body.getValue("prompt").jsonPrimitive.content)
            assertEquals(1, body.getValue("n").jsonPrimitive.int)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun httpsDownloadUsesSeparateUnauthenticatedClientAndRejectsRedirect() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { source -> MockWebServer().use { images ->
            images.useHttps(serverTls.sslSocketFactory())
            source.start(); images.start()
            val download = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            val client = OpenAiImageClient(OkHttpClient(), download)
            val url = images.url("/image?temporary=private-signature")
            source.enqueue(response(buildJsonObject { putJsonArray("data") { add(buildJsonObject { put("url", url.toString()) }) } }.toString()))
            images.enqueue(response("image-body"))
            assertEquals("image-body", client.generate(snapshot(source.url("/v1").toString()), "cat").toString(Charsets.UTF_8))
            val request = images.takeRequest(5, TimeUnit.SECONDS)!!
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
            source.enqueue(response("""{"data":[{"url":"$url"}]}"""))
            images.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1/private").build())
            try { client.generate(snapshot(source.url("/v1").toString()), "cat"); fail() }
            catch (error: ImageRequestException) { assertFalse(error.toString().contains("private-signature")) }
            assertEquals(2, images.requestCount)
        } }
    }
    @Test fun publicDnsRejectsEveryLocalPrivateAndMixedAnswer() {
        for (host in listOf("127.0.0.1", "10.1.1.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "100.64.0.1", "198.18.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1", "2001:db8::1", "::ffff:127.0.0.1")) {
            assertFalse(host, publicImageAddress(InetAddress.getByName(host)))
        }
        val public = InetAddress.getByName("8.8.8.8")
        assertTrue(publicImageAddress(public))
        assertTrue(publicImageAddress(InetAddress.getByName("2001:4860:4860::8888")))
        val dns = PublicImageDns(Dns { listOf(public, InetAddress.getByName("127.0.0.1")) })
        try { dns.lookup("mixed.invalid"); fail() } catch (_: java.net.UnknownHostException) { }
        for (url in listOf("http://example.com/image", "https://secret@example.com/image", "file:///image", "https://example.com/a#b")) {
            try { imageDownloadUrl(url); fail(url) } catch (_: ImageRequestException) { }
        }
    }
    @Test fun errorsMalformedAndOversizedBodiesAreSanitizedWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val responses = listOf(response("secret signed-url", 503), response("not json"),
                response("""{"data":[{"b64_json":"???"}]}"""),
                MockResponse.Builder().addHeader("Content-Length", ImageLimits.RESPONSE_BYTES + 1).build())
            for (reply in responses) {
                server.enqueue(reply)
                try { OpenAiImageClient().generate(snapshot(server.url("/v1").toString()), "cat"); fail() }
                catch (error: ImageRequestException) { assertNull(error.cause); assertFalse(error.toString().contains("secret")) }
            }
            assertEquals(responses.size, server.requestCount)
        }
    }
    @Test fun cancellingAnInFlightGenerationNeverRetries() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).body("{}").build())
            val task = async(Dispatchers.IO) { OpenAiImageClient().generate(snapshot(server.url("/v1").toString()), "cat") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(5000) { task.cancelAndJoin() }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun dimensionsAreBoundedBeforeDecode() {
        assertTrue(ImageLimits.validDimensions(2048, 2048))
        assertFalse(ImageLimits.validDimensions(4096, 4096))
        assertFalse(ImageLimits.validDimensions(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(ImageLimits.validDimensions(0, 1))
    }
}

