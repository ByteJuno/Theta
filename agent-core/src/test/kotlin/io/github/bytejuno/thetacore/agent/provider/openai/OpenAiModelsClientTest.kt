package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.Credentials
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenAiModelsClientTest {
    private val key = "synthetic-model-list-key"
    private val credentials = ApiKeyCredentials(key)
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private suspend fun failure(expected: ModelsRequestException.Failure, block: suspend () -> Unit) {
        try { block(); fail("Request must fail") }
        catch (error: ModelsRequestException) {
            assertEquals(expected, error.failure); assertNull(error.cause)
            assertEquals(0, error.suppressed.size); assertFalse(error.toString().contains(key))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun totalDeadlineCancelsCallEvenWhenDnsCannotReturn() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val transport = OkHttpClient.Builder().dns(Dns {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            listOf(InetAddress.getLoopbackAddress())
        }).eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        val request = async {
            failure(ModelsRequestException.Failure.TIMEOUT) {
                OpenAiModelsClient(transport).fetch("https://models.invalid/v1", credentials)
            }
        }
        try {
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Advance the caller's real 30-second deadline without waiting for OS DNS to return.
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue("The caller must finish while DNS is still blocked", request.isCompleted)
            assertEquals(0L, cancelled.count)
            request.await()
        } finally {
            release.countDown()
            request.cancel()
            runCurrent()
            request.join()
            transport.dispatcher.executorService.shutdown()
            transport.connectionPool.evictAll()
        }
    }

    @Test fun explicitGetUsesBearerAndNormalizedPathWithStableModelIds() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for ((base, path) in listOf("/" to "/models", "/v1" to "/v1/models", "/custom/v1/" to "/custom/v1/models")) {
                server.enqueue(response("""{"data":[{"id":"z"},{"id":" a "},{"id":"z"},{"id":null},{"id":1},{"id":true},{"other":"x"},null,7,{"id":""}]}"""))
                assertEquals(listOf("a", "z"), OpenAiModelsClient().fetch(server.url(base).toString(), credentials))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method); assertEquals(path, request.url.encodedPath)
                assertEquals("Bearer $key", request.headers["Authorization"])
                assertEquals(0L, request.bodySize)
            }
            server.enqueue(response("""{"data":[]}"""))
            assertEquals(emptyList<String>(), OpenAiModelsClient().fetch(server.url("/v1").toString(), credentials))
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun invalidSettingsNeverReachNetworkAndNeverKeepSensitiveCauses() = runBlocking {
        for (base in listOf("file:///secret", "https://$key@host/v1", "https://host/v1?key=$key", "https://host/v1#$key", "x".repeat(4097))) {
            failure(ModelsRequestException.Failure.INVALID_SETTINGS) { OpenAiModelsClient().fetch(base, credentials) }
        }
        failure(ModelsRequestException.Failure.INVALID_SETTINGS) {
            OpenAiModelsClient().fetch("https://example.test/v1", Credentials { "bad\r\n$key" })
        }
        failure(ModelsRequestException.Failure.INVALID_SETTINGS) {
            OpenAiModelsClient().fetch("https://example.test/v1", Credentials { throw IllegalStateException(key) })
        }
    }

    @Test fun httpFailuresRedirectsAndRetryAfterDoNotFollowUpOrExposeResponse() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            for (code in listOf(301, 302, 307, 308, 401, 404, 500)) {
                server.enqueue(MockResponse.Builder().code(code).body(key).addHeader("Location", other.url("/leak")).build())
                failure(ModelsRequestException.Failure.HTTP) { OpenAiModelsClient().fetch(server.url("/v1").toString(), credentials) }
            }
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body(key).build())
            failure(ModelsRequestException.Failure.NETWORK) { OpenAiModelsClient().fetch(server.url("/v1").toString(), credentials) }
            assertEquals(8, server.requestCount); assertEquals(0, other.requestCount)
        } }
    }

    @Test fun malformedAbnormalAndDeepResponsesFailClosed() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (body in listOf(key, "{}", "[]", """{"data":{}}""", """{"data":[{"id":"${"a".repeat(257)}"}]}""",
                """{"data":[{"id":"line\nbreak"}]}""", "[".repeat(40) + "]".repeat(40))) {
                server.enqueue(response(body))
                failure(ModelsRequestException.Failure.INVALID_RESPONSE) { OpenAiModelsClient().fetch(server.url("/").toString(), credentials) }
            }
        }
    }

    @Test fun fixedChunkedAndModelCountLimitsAreEnforced() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Length", OpenAiModelsClient.MAX_RESPONSE_BYTES + 1).build())
            failure(ModelsRequestException.Failure.TOO_LARGE) { OpenAiModelsClient().fetch(server.url("/").toString(), credentials) }
            server.enqueue(MockResponse.Builder().chunkedBody(" ".repeat(OpenAiModelsClient.MAX_RESPONSE_BYTES + 1), 8192).build())
            failure(ModelsRequestException.Failure.TOO_LARGE) { OpenAiModelsClient().fetch(server.url("/").toString(), credentials) }
            server.enqueue(response("{\"data\":[" + (0..OpenAiModelsClient.MAX_MODELS).joinToString(",") { "{\"id\":\"model-$it\"}" } + "]}"))
            failure(ModelsRequestException.Failure.TOO_LARGE) { OpenAiModelsClient().fetch(server.url("/").toString(), credentials) }
        }
    }

    @Test fun networkAndTimeoutErrorsDropUnderlyingSecrets() = runBlocking {
        for ((exception, expected) in listOf(IOException(key) to ModelsRequestException.Failure.NETWORK,
            SocketTimeoutException(key) to ModelsRequestException.Failure.TIMEOUT)) {
            val client = OpenAiModelsClient(OkHttpClient.Builder().addInterceptor { throw exception }.build())
            failure(expected) { client.fetch("https://example.test/v1", credentials) }
        }
    }

    @Test fun cancellingRequestCancelsTheNetworkCallWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("""{"data":[]}""").build())
            val job = async { OpenAiModelsClient().fetch(server.url("/").toString(), credentials) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
        }
    }

    @Test fun cleartextHttpAllowsLocalLanAndTailscaleButRejectsPublicEndpoints() = runBlocking {
        for (allowed in listOf(
            "http://localhost:8317/v1",
            "http://127.0.0.1:8317/v1",
            "http://10.0.0.1:8317/v1",
            "http://172.16.0.1:8317/v1",
            "http://192.168.1.10:11434/v1",
            "http://100.64.0.1:8317/v1",
            "http://100.120.32.105:8317/v1",
            "http://100.127.255.254:8317/v1",
            "http://169.254.1.1:8317/v1",
            "http://[::1]:8317/v1",
            "http://[fd7a:115c:a1e0::1]:8317/v1",
            "https://api.openai.com/v1",
            "https://8.8.8.8/v1",
        )) {
            assertEquals("completions", OpenAiChatConfig("p", allowed).endpoint.pathSegments.last())
        }
        for (rejected in listOf(
            "http://api.openai.com/v1",
            "http://models.invalid/v1",
            "http://8.8.8.8/v1",
            "http://100.63.255.255:8317/v1",
            "http://100.128.0.1:8317/v1",
            "http://172.32.0.1:8317/v1",
            "http://0.0.0.0:8317/v1",
            "http://[2001:4860:4860::8888]/v1",
        )) {
            failure(ModelsRequestException.Failure.INVALID_SETTINGS) {
                OpenAiModelsClient().fetch(rejected, credentials)
            }
        }
    }
}
