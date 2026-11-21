package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.Credentials
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
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
}
