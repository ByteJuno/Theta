package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpenAiCredentialsTest {
    private val secret = "synthetic-secret-issue18"
    private val settings = OpenAiProviderSettings("test", "https://example.invalid/v1", "model")

    @Test fun `failed or blank credentials fail AUTH before transport without leaking causes`() = runTest {
        var dispatches = 0
        val transport = ModelTransport { _, _, _ -> flow { dispatches++ } }
        val provider = OpenAiChatProvider(settings.adapterConfig(), transport)
        for (auth in listOf(Credentials { throw IllegalStateException(secret) }, Credentials { "" },
            Credentials { "  " }, Credentials { "$secret\r\nheader" })) {
            val event = provider.chat(chatRequest("model"), auth).toList().single() as ModelEvent.Failed
            assertEquals(ModelErrorCode.AUTH, event.error.code)
            assertTrue(event.error.message.contains("credentials"))
            assertFalse(event.toString().contains(secret))
        }
        assertEquals(0, dispatches)
    }

    @Test fun `key is only passed to transport never request or diagnostic values`() = runTest {
        val snapshot = OpenAiRunSnapshot(settings, ApiKeyCredentials(secret))
        val request = snapshot.request()
        val transport = ModelTransport { _, body, token -> flow {
            assertEquals(secret, token)
            assertFalse(body.contains(secret))
            emit(TransportEvent.Headers(401, "application/json", null))
            emit(TransportEvent.Bytes("""{"error":{"message":"$secret","param":"Bearer $secret"}}""".toByteArray()))
        } }
        val target = snapshot.target(transport)
        val events = target.provider.chat(request, target.credentials).toList()
        assertNull((events.single() as ModelEvent.Failed).error.param)
        for (value in listOf(snapshot, target, target.credentials, settings, settings.parameters, request, events)) {
            assertFalse(value.toString().contains(secret))
        }
    }

    @Test fun `error metadata rejects partial or encoded credential echoes but retains known parameters`() = runTest {
        val longKey = "synthetic-" + "x".repeat(512)
        for ((param, expected) in listOf(longKey to null, "synthetic%2Dsecret" to null, "temperature" to "temperature")) {
            val transport = ModelTransport { _, _, _ -> flow {
                emit(TransportEvent.Headers(400, "application/json", null))
                emit(TransportEvent.Bytes("""{"error":{"code":"unsupported_parameter","param":"$param"}}""".toByteArray()))
            } }
            val event = OpenAiChatProvider(settings.adapterConfig(), transport)
                .chat(chatRequest("model"), ApiKeyCredentials(longKey)).toList().single() as ModelEvent.Failed
            assertEquals(expected, event.error.param)
            assertFalse(event.toString().contains("synthetic"))
        }
    }

    @Test fun `parameters and request snapshot detach mutable inputs`() {
        val stops = mutableListOf("original")
        val parameters = OpenAiParameters(123, 0.5, 0.8, "high", false, stops)
        val snapshot = OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v2", "fixed", parameters), ApiKeyCredentials(secret))
        stops[0] = "changed"
        val request = snapshot.request { temperature = 1.9; messages = listOf(message(Role.USER, textPart("hi"))) }
        assertEquals("fixed", request.model)
        assertEquals(0.5, request.temperature)
        assertEquals(123, request.maxOutputTokens)
        assertEquals(listOf("original"), request.stopSequences)
        assertFailsWith<UnsupportedOperationException> { (parameters.stopSequences as MutableList<String>).clear() }
    }

    @Test fun `invalid inputs never print credentials or parser causes`() {
        for (url in listOf("$secret://broken", "https://user:$secret@example.invalid/?key=$secret")) {
            val failure = assertFailsWith<IllegalArgumentException> { OpenAiProviderSettings("test", url, "model") }
            assertFalse(failure.stackTraceToString().contains(secret))
            assertNull(failure.cause)
        }
        for (token in listOf("", " ", "$secret\n")) {
            val failure = assertFailsWith<IllegalArgumentException> { ApiKeyCredentials(token) }
            assertFalse(failure.stackTraceToString().contains(secret))
        }
    }
}
