package io.github.bytejuno.thetacore.agent.provider.anthropic

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
internal fun nativeWire(blocks: List<JsonObject>, reason: String = "end_turn") = buildString {
    fun event(value: JsonObject) { append("event: ${value.getValue("type").jsonPrimitive.content}\ndata: $value\n\n") }
    event(json("""{"type":"message_start","message":{"id":"msg_1","model":"m","role":"assistant","content":[],"usage":{"input_tokens":5,"cache_read_input_tokens":7,"cache_creation_input_tokens":11,"output_tokens":1}}}"""))
    blocks.forEachIndexed { i, block ->
        event(buildJsonObject { put("type", "content_block_start"); put("index", i); put("content_block", block) })
        event(buildJsonObject { put("type", "content_block_stop"); put("index", i) })
    }
    event(json("""{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":17}}"""))
    event(json("""{"type":"message_stop"}"""))
}
internal fun nativeTransport(wire: String, chunk: Int = 8192) = ModelTransport { _, _, _ -> flow {
    emit(TransportEvent.Headers(200, "text/event-stream", null))
    wire.toByteArray().toList().chunked(chunk).forEach { emit(TransportEvent.Bytes(it.toByteArray())) }
} }

class AnthropicTest {
    private val config = AnthropicConfig("provider", "https://example.invalid/v1/")
    private val codec = AnthropicCodec(config)
    private val auth = Credentials { "synthetic-key" }
    private val thinking = json("""{"type":"thinking","thinking":"思考 🌍","signature":"unchanged-signature","extra":{"keep":true}}""")
    private val redacted = json("""{"type":"redacted_thinking","data":"opaque-encrypted"}""")
    private val text = json("""{"type":"text","text":"answer"}""")
    private fun request() = chatRequest("m") { messages = listOf(message(Role.USER, textPart("hi"))) }
    private suspend fun collect(wire: String, chunk: Int = 8192) =
        AnthropicProvider(config, nativeTransport(wire, chunk)).chat(request(), auth).validated("provider", "req").toList()
    private fun reduce(events: List<ModelEvent>) = ModelEventReducer("provider", "req").apply { events.forEach(::accept); endOfInput() }

    @Test fun `thinking signature and redacted data replay in exact part order with cumulative usage`() = runTest {
        val result = collect(": ping\n\n" + nativeWire(listOf(thinking, redacted, text)), 1)
        assertIs<ModelEvent.Finished>(result.last()); assertTrue(result.any { it is ModelEvent.KeepAlive })
        val r = reduce(result); val message = r.assistantMessage()
        assertEquals("思考 🌍", assertIs<ContentPart.Reasoning>(message.parts[0]).summary)
        assertEquals("", assertIs<ContentPart.Reasoning>(message.parts[1]).summary)
        val output = json(codec.encode(chatRequest("m") { messages = listOf(message) }))["messages"]!!.jsonArray.single().jsonObject["content"]
        assertEquals(JsonArray(listOf(thinking, redacted, text)), output)
        assertEquals(23L, r.usage!!.inputTokens); assertEquals(7L, r.usage!!.cacheReadTokens)
        assertEquals(11L, r.usage!!.cacheWriteTokens); assertEquals(17L, r.usage!!.outputTokens)
        assertNull(r.usage!!.reasoningTokens)
        assertTrue(message.parts.take(2).all { codec.canReplay(it.replay!!, "m") })
    }

    @Test fun `all native delta types preserve fragments without duplicating initial empty input`() = runTest {
        val prefix = nativeWire(emptyList()).substringBefore("event: message_delta")
        val frames = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thought"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"nature"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"read","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"你好\"}"}}""",
            """{"type":"content_block_stop","index":1}""",
        ).joinToString("") { "data: $it\n\n" }
        val suffix = "event: message_delta" + nativeWire(emptyList(), "tool_use").substringAfter("event: message_delta")
        val r = reduce(collect(prefix + frames + suffix, 1))
        assertEquals("{\"q\":\"你好\"}", r.calls().single().argsJson); assertTrue(r.calls().single().complete)
        val replay = r.assistantMessage().parts[0].replay!!
        assertEquals(json("""{"type":"thinking","thinking":"thought","signature":"signature"}"""), json(replay.payload)["block"])
    }

    @Test fun `request maps tools choices schema cache image reasoning and stable same-role messages`() {
        val schema = json("""{"type":"object","properties":{"q":{"type":"string"}}}""")
        for ((choice, type) in listOf(ToolChoice.Auto to "auto", ToolChoice.None to "none", ToolChoice.Required to "any", namedTool("read") to "tool")) {
            val request = chatRequest("m") {
                system = "system"; tools = listOf(modelTool("read", "Read", schema)); toolChoice = choice
                maxOutputTokens = 321; parallelToolCalls = false; stopSequences = listOf("stop")
                messages = listOf(message(Role.USER, textPart("first")), message(Role.USER, textPart("see"),
                    mediaPart(MediaKind.IMAGE, blobRef("image", "image/png", 3)), cacheBreakpoint = true))
            }
            val body = json(AnthropicCodec(config, MediaContentReader { byteArrayOf(1, 2, 3) }).encode(request))
            assertEquals("https://example.invalid/v1/messages", config.endpoint.toString())
            assertEquals("system", body["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(schema, body["tools"]!!.jsonArray[0].jsonObject["input_schema"])
            assertEquals(type, body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(321, body["max_tokens"]!!.jsonPrimitive.int)
            val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            assertEquals(3, content.size); assertEquals("AQID", content.last().jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            assertEquals(json("""{"type":"ephemeral"}"""), content.last().jsonObject["cache_control"])
        }
        val body = json(codec.encode(chatRequest("m") { reasoningLevel = "high"; responseSchema = schema }))
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") { extraBody = json("""{"messages":[]}""") }) }
    }

    @Test fun `tool results never absorb user text and wire ids remain paired`() {
        val binding = callBinding("provider", "req", "response", 0, "toolu_original")
        val call = message(Role.ASSISTANT, toolCallPart(binding, "read", "{}"))
        val result = message(Role.TOOL, toolResultPart(binding.callId, "result"))
        val req = chatRequest("m") { messages = listOf(call, result) }
        val encodedMessages = json(codec.encode(req))["messages"]!!.jsonArray
        assertEquals("toolu_original", encodedMessages[1].jsonObject["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        val nextRun = json(codec.encode(chatRequest("m") {
            messages = listOf(call, result, message(Role.USER, textPart("new goal")), message(Role.USER, textPart("details")))
        }))["messages"]!!.jsonArray
        assertEquals(3, nextRun.size)
        assertEquals(encodedMessages[1], nextRun[1])
        assertEquals(listOf("new goal", "details"), nextRun[2].jsonObject["content"]!!.jsonArray.map {
            it.jsonObject["text"]!!.jsonPrimitive.content
        })
        assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") {
            messages = listOf(call, message(Role.USER, textPart("unsettled insertion")), result)
        }) }
        assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") { messages = listOf(call) }) }
    }

    @Test fun `explicit input overflow enters context recovery without exposing HTTP or stream error content`() = runTest {
        for (status in listOf(400, 200)) for ((type, detail, expected) in listOf(
            Triple("invalid_request_error", "prompt is too long: 201000 tokens > 200000 maximum; synthetic-key", ModelErrorCode.CONTEXT_TOO_LONG),
            Triple("invalid_request_error", "invalid tool input synthetic-key", ModelErrorCode.INVALID_REQUEST),
            Triple("request_too_large", "request exceeds byte limit synthetic-key", ModelErrorCode.INVALID_REQUEST),
            Triple("authentication_error", "prompt is too long synthetic-key", ModelErrorCode.AUTH),
        )) {
            val body = buildJsonObject { put("type", "error"); putJsonObject("error") {
                put("type", type); put("message", detail)
            } }.toString()
            val provider = AnthropicProvider(config, ModelTransport { _, _, _ -> flow {
                emit(TransportEvent.Headers(status, if (status == 200) "text/event-stream" else "application/json", null))
                emit(TransportEvent.Bytes((if (status == 200) "event: error\ndata: $body\n\n" else body).toByteArray()))
            } })
            val error = assertIs<ModelEvent.Failed>(provider.chat(request(), auth).toList().last()).error
            assertEquals(expected, error.code)
            assertEquals(if (status == 400) 400 else null, error.httpStatus)
            assertEquals("Model request failed", error.message)
            assertFalse(error.message.contains("synthetic-key"))
        }
    }

    @Test fun `replay rejects foreign provider endpoint model protocol malformed or missing signature`() {
        val data = codec.replay("m", thinking)
        assertFalse(codec.canReplay(data, "other"))
        assertFalse(AnthropicCodec(AnthropicConfig("other", "https://example.invalid/v1")).canReplay(data, "m"))
        assertFalse(AnthropicCodec(AnthropicConfig("provider", "https://other.invalid/v1")).canReplay(data, "m"))
        assertFalse(codec.canReplay(replayData("openai-chat", "m", data.payload), "m"))
        assertFalse(codec.canReplay(replayData("anthropic", "m", "{}"), "m"))
        assertFalse(codec.canReplay(replayData("anthropic", "m", data.payload.replace("unchanged-signature", "")), "m"))
        assertFalse(AnthropicProvider(config).canReplayMessage(message(Role.ASSISTANT, reasoningPart("lost", null)), "m"))
        val cached = message(Role.ASSISTANT, reasoningPart("thought", data), cacheBreakpoint = true)
        assertEquals(thinking, json(codec.encode(chatRequest("m") { messages = listOf(cached) }))["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0])
    }

    @Test fun `finish reasons and refusal disposition map without replaying rejected thinking`() = runTest {
        for ((raw, expected) in mapOf("end_turn" to FinishReason.STOP, "max_tokens" to FinishReason.LENGTH,
            "pause_turn" to FinishReason.PAUSED, "refusal" to FinishReason.REFUSAL, "stop_sequence" to FinishReason.STOP,
            "model_context_window_exceeded" to FinishReason.CONTEXT_EXCEEDED)) {
            val r = reduce(collect(nativeWire(listOf(thinking, text), raw)))
            assertEquals(expected, assertIs<ModelEvent.Finished>(r.terminal).reason)
            assertEquals(if (raw == "refusal") HistoryDisposition.DISCARD else HistoryDisposition.KEEP, r.historyDisposition())
        }
    }

    @Test fun `malformed streams error events missing completion and oversize replay fail closed`() = runTest {
        for (wire in listOf(nativeWire(listOf(text)).substringBefore("event: message_stop"),
            nativeWire(listOf(thinking)).replace("unchanged-signature", ""),
            nativeWire(listOf(text)).replace("\"index\":0", "\"index\":1"),
            nativeWire(listOf(text)) + nativeWire(listOf(text)),
            nativeWire(listOf(JsonObject(thinking + ("thinking" to JsonPrimitive("x".repeat(70_000)))))),
            nativeWire(listOf(text), "unknown"))) {
            val result = collect(wire); assertIs<ModelEvent.Failed>(result.last()); assertEquals(0, result.count { it is ModelEvent.Finished })
        }
        val errors = mapOf("overloaded_error" to ModelErrorCode.OVERLOADED, "rate_limit_error" to ModelErrorCode.RATE_LIMIT,
            "authentication_error" to ModelErrorCode.AUTH)
        for ((type, code) in errors) {
            val event = "data: {\"type\":\"error\",\"error\":{\"type\":\"$type\",\"message\":\"synthetic-key\"}}\n\n"
            val error = assertIs<ModelEvent.Failed>(collect(event).last()).error
            assertEquals(code, error.code); assertFalse(error.message.contains("synthetic-key"))
        }
    }

    @Test fun `native HTTP headers remain isolated even with a shared bearer transport`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(nativeWire(listOf(text))).build())
            val provider = AnthropicProvider(AnthropicConfig("provider", server.url("/v1").toString()), OkHttpModelTransport())
            assertIs<ModelEvent.Finished>(provider.chat(request(), auth).toList().last())
            val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", sent.method); assertEquals("/v1/messages", sent.url.encodedPath)
            assertEquals("synthetic-key", sent.headers["x-api-key"]); assertEquals("2023-06-01", sent.headers["anthropic-version"])
            assertNull(sent.headers["Authorization"])
            assertEquals(ModelErrorCode.AUTH, assertIs<ModelEvent.Failed>(provider.chat(request(), NoAuthCredentials).toList().last()).error.code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `models use native authentication pagination and bounded redirect-free requests`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"z"}],"has_more":true,"last_id":"z"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"a"}],"has_more":false}""").build())
            assertEquals(listOf("a", "z"), AnthropicModelsClient().fetch(server.url("/v1/").toString(), auth))
            repeat(2) { i ->
                val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("GET", sent.method); assertEquals("/v1/models", sent.url.encodedPath)
                assertEquals(if (i == 0) null else "z", sent.url.queryParameter("after_id"))
                assertEquals("synthetic-key", sent.headers["x-api-key"]); assertNull(sent.headers["Authorization"])
            }
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/leak")).build())
            assertFailsWith<ModelsRequestException> { AnthropicModelsClient().fetch(server.url("/v1").toString(), auth) }
            assertEquals(3, server.requestCount)
        }
    }
}
