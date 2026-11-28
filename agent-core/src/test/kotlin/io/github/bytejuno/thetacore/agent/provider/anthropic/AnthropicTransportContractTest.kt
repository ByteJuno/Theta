package io.github.bytejuno.thetacore.agent.provider.anthropic

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Provider boundaries that an SDK replacement must preserve, not just typed message contents. */
class AnthropicTransportContractTest {
    private val auth = Credentials { "synthetic-key" }
    private fun request() = chatRequest("m") {
        messages = listOf(message(Role.USER, textPart("hi")))
    }

    @Test fun `native generation sends one attempt even when 503 explicitly requests immediate retry`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0")
                .body("""{"type":"error","error":{"type":"overloaded_error","message":"synthetic-key"}}""").build())
            // A second response makes an accidental follow-up observable without a timeout.
            server.enqueue(MockResponse.Builder().code(400)
                .body("""{"type":"error","error":{"type":"invalid_request_error","message":"unexpected retry"}}""").build())
            val provider = AnthropicProvider(AnthropicConfig("provider", server.url("/v1").toString()))
            val events = withTimeout(5_000) { provider.chat(request(), auth).toList() }
            val error = assertIs<ModelEvent.Failed>(events.single()).error
            assertEquals(ModelErrorCode.OVERLOADED, error.code)
            assertEquals(503, error.httpStatus)
            assertEquals("Model request failed", error.message)
            assertEquals(1, server.requestCount)
            val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/messages", sent.url.encodedPath)
            assertEquals("synthetic-key", sent.headers["x-api-key"])
            assertNull(sent.headers["Authorization"])
        }
    }

    @Test fun `named ping and comment heartbeat survive alongside unnamed native event frames`() = runTest {
        val wire = ": heartbeat\n\n" + "event: ping\ndata: {\"type\":\"ping\"}\n\n" +
            nativeWire(listOf(json("""{"type":"text","text":"answer"}""")))
                .lineSequence().filterNot { it.startsWith("event:") }.joinToString("\n")
        val provider = AnthropicProvider(AnthropicConfig("provider", "https://example.invalid/v1"), nativeTransport(wire, 1))
        val events = provider.chat(request(), auth).validated("provider", "req").toList()
        assertEquals(2, events.count { it is ModelEvent.KeepAlive })
        assertEquals(1, events.count { it is ModelEvent.Started })
        assertEquals(FinishReason.STOP, assertIs<ModelEvent.Finished>(events.last()).reason)
        assertEquals("answer", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test fun `delta replay retains unknown signed fields on thinking and redacted blocks`() = runTest {
        val config = AnthropicConfig("provider", "https://example.invalid/v1")
        val initial = json("""{"type":"thinking","thinking":"","signature":"","future_field":{"keep":[1,true]}}""")
        val redacted = json("""{"type":"redacted_thinking","data":"opaque","future_field":{"binding":"unchanged"}}""")
        val wire = nativeWire(listOf(initial, redacted)).replace(
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"thought\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"signed\"}}\n\n" +
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
        )
        val events = AnthropicProvider(config, nativeTransport(wire, 1)).chat(request(), auth).toList()
        assertIs<ModelEvent.Finished>(events.last())
        val reducer = ModelEventReducer("provider", "req").apply { events.forEach(::accept); endOfInput() }
        val encoded = json(AnthropicCodec(config).encode(chatRequest("m") {
            messages = listOf(reducer.assistantMessage())
        }))
        assertEquals(JsonArray(listOf(
            JsonObject(initial + mapOf("thinking" to JsonPrimitive("thought"), "signature" to JsonPrimitive("signed"))),
            redacted,
        )), encoded["messages"]!!.jsonArray.single().jsonObject["content"])
    }
}
