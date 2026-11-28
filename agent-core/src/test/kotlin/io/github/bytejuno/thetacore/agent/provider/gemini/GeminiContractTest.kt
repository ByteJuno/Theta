package io.github.bytejuno.thetacore.agent.provider.gemini

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

class GeminiContractTest {
    private val config = GeminiConfig("provider", "https://example.invalid/v1beta")
    private val auth = Credentials { "synthetic-key" }
    private fun request() = chatRequest("gemini-model") { messages = listOf(message(Role.USER, textPart("hi"))) }
    private fun transport(wire: String, chunk: Int = 7) = ModelTransport { _, _, _ -> flow {
        emit(TransportEvent.Headers(200, "text/event-stream", null))
        wire.toByteArray().asList().chunked(chunk).forEach { emit(TransportEvent.Bytes(it.toByteArray())) }
    } }
    private fun wire(vararg roots: String) = roots.joinToString("") { "data: $it\n\n" }
    private fun root(parts: String, finish: String? = null) =
        """{"responseId":"r","modelVersion":"gemini-model","candidates":[{"index":0,"content":{"role":"model","parts":$parts}${finish?.let { ",\"finishReason\":\"$it\"" }.orEmpty()}}]}"""
    private suspend fun collect(wire: String): Pair<List<ModelEvent>, ModelEventReducer> {
        val events = GeminiProvider(config, transport(wire)).chat(request(), auth).validated("provider", "request").toList()
        return events to ModelEventReducer("provider", "request").also { r -> events.forEach(r::accept) }
    }

    @Test fun `recorded SSE text chunks and final usage match native response`() = runTest {
        val recorded = requireNotNull(javaClass.getResource("/gemini/text.sse")).readText()
        val (events, reducer) = collect(recorded)
        assertEquals("Hello 世界", reducer.assistantMessage().parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text })
        assertEquals(1, reducer.assistantMessage().parts.size)
        assertEquals(FinishReason.STOP, assertIs<ModelEvent.Finished>(events.last()).reason)
        assertEquals(listOf(20L, 5L, 16L, 9L), reducer.usage!!.let { listOf(it.inputTokens, it.cacheReadTokens, it.outputTokens, it.reasoningTokens) })
        assertEquals(1, events.count { it is ModelEvent.Started })
    }

    @Test fun `parallel calls preserve signature only on original first part and group results`() = runTest {
        val parts = """[{"functionCall":{"name":"first","args":{"x":1}},"thoughtSignature":"c2lnbmVk"},{"functionCall":{"name":"second","args":{},"id":"wire-2"}}]"""
        val (_, reducer) = collect(wire(root(parts, "STOP")))
        val calls = reducer.assistantMessage().parts.filterIsInstance<ContentPart.ToolCall>()
        assertEquals(2, calls.size); assertNotEquals(calls[0].binding.callId, calls[1].binding.callId)
        assertNull(calls[0].binding.wireId); assertEquals("wire-2", calls[1].binding.wireId)
        assertNotNull(calls[0].replay); assertNull(calls[1].replay)
        val request = chatRequest("gemini-model") {
            messages = listOf(message(Role.USER, textPart("hi")), reducer.assistantMessage(), message(Role.TOOL,
                *calls.map { toolResultPart(it.binding.callId, "result-${it.name}") }.toTypedArray()))
        }
        val encoded = Json.parseToJsonElement(GeminiCodec(config).encode(request)).jsonObject
        val contents = encoded.getValue("contents").jsonArray
        assertEquals(Json.parseToJsonElement(parts), contents[1].jsonObject.getValue("parts"))
        assertEquals("user", contents[2].jsonObject["role"]!!.jsonPrimitive.content)
        val results = contents[2].jsonObject.getValue("parts").jsonArray.map { it.jsonObject.getValue("functionResponse").jsonObject }
        assertEquals(listOf("first", "second"), results.map { it["name"]!!.jsonPrimitive.content })
        assertNull(results[0]["id"]); assertEquals(JsonPrimitive("wire-2"), results[1]["id"])
        assertTrue(GeminiProvider(config).canReplayMessage(reducer.assistantMessage(), "gemini-model"))
        assertFalse(GeminiProvider(config).canReplayMessage(reducer.assistantMessage(), "other"))
        assertFalse(GeminiProvider(GeminiConfig("other", "https://example.invalid/v1beta")).canReplayMessage(reducer.assistantMessage(), "gemini-model"))
        assertFalse(GeminiProvider(GeminiConfig("provider", "https://other.invalid/v1beta")).canReplayMessage(reducer.assistantMessage(), "gemini-model"))
        val tampered = message(Role.ASSISTANT, toolCallPart(calls[0].binding, "changed", calls[0].argsJson, calls[0].replay))
        assertFalse(GeminiProvider(config).canReplayMessage(tampered, "gemini-model"))
        assertFailsWith<IllegalArgumentException> { GeminiCodec(config).encode(object : ChatRequest by request {
            override val messages = request.messages.take(2) + message(Role.TOOL,
                *calls.reversed().map { toolResultPart(it.binding.callId, "wrong order") }.toTypedArray())
        }) }
        assertFailsWith<IllegalArgumentException> { GeminiCodec(config).encode(object : ChatRequest by request {
            override val messages = request.messages.take(2) + message(Role.USER, textPart("interleaved")) + request.messages.last()
        }) }
        val restored = toolCallPart(calls[0].binding, calls[0].name, calls[0].argsJson, calls[0].replay)
        assertEquals(calls[0].binding.callId, restored.binding.callId)
    }

    @Test fun `usage without thinking keeps visible output and missing output remains unknown`() = runTest {
        for ((metadata, expected) in listOf(
            """{"candidatesTokenCount":7}""" to 7L,
            """{"candidatesTokenCount":0,"thoughtsTokenCount":9}""" to 9L,
            """{"thoughtsTokenCount":9}""" to null,
            "{}" to null,
        )) {
            val response = """{"candidates":[{"content":{"parts":[{"text":"done"}]},"finishReason":"STOP"}],"usageMetadata":$metadata}"""
            val (events, reducer) = collect(wire(response))
            assertIs<ModelEvent.Finished>(events.last())
            assertEquals(expected, reducer.usage!!.outputTokens)
        }
    }

    @Test fun `role only terminal frames retain finish usage and preceding text`() = runTest {
        for (reason in listOf("SAFETY", "MAX_TOKENS")) for (textFirst in listOf(false, true)) {
            val terminal = """{"candidates":[{"content":{"role":"model"},"finishReason":"$reason"}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":3,"thoughtsTokenCount":2}}"""
            val (events, reducer) = collect((if (textFirst) wire(root("""[{"text":"partial"}]""")) else "") + wire(terminal))
            assertEquals(if (reason == "SAFETY") FinishReason.CONTENT_FILTER else FinishReason.LENGTH,
                assertIs<ModelEvent.Finished>(events.last()).reason)
            assertEquals(reason, (events.last() as ModelEvent.Finished).rawReason)
            assertEquals(8L, reducer.usage!!.inputTokens)
            assertEquals(5L, reducer.usage!!.outputTokens)
            assertEquals(if (textFirst) "partial" else "", reducer.assistantMessage().parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text })
            assertFalse(events.any { it is ModelEvent.Failed })
        }
        // Only omission is accepted. A malformed present parts field must still fail.
        val (bad, _) = collect(wire("""{"candidates":[{"content":{"parts":{}},"finishReason":"MAX_TOKENS"}]}"""))
        assertIs<ModelEvent.Failed>(bad.last())
    }

    @Test fun `signed text and thought replay remain bound to their exact content`() = runTest {
        for (thinking in listOf(false, true)) {
            val part = """{"text":"thought or answer","thought":$thinking,"thoughtSignature":"c2ln"}"""
            val (_, r) = collect(wire(root("[$part]", "STOP")))
            val content = r.assistantMessage()
            assertTrue(GeminiProvider(config).canReplayMessage(content, "gemini-model"))
            val encoded = Json.parseToJsonElement(GeminiCodec(config).encode(chatRequest("gemini-model") {
                messages = listOf(message(Role.USER, textPart("hi")), content)
            })).jsonObject
            val replay = encoded.getValue("contents").jsonArray[1].jsonObject.getValue("parts").jsonArray[0].jsonObject
            assertEquals("c2ln", replay["thoughtSignature"]!!.jsonPrimitive.content)
            assertEquals("thought or answer", replay["text"]!!.jsonPrimitive.content)
            assertFalse(GeminiProvider(config).canReplayMessage(message(Role.ASSISTANT,
                textPart("mutated", content.parts.single().replay)), "gemini-model"))
        }
    }

    @Test fun `empty signed streaming part stays separate from earlier text`() = runTest {
        val signed = """{"text":"","thoughtSignature":"c2ln"}"""
        val (_, r) = collect(wire(root("""[{"text":"answer"}]"""), root("[$signed]", "STOP")))
        assertEquals(1, r.assistantMessage().parts.size)
        assertEquals("answer", assertIs<ContentPart.Text>(r.assistantMessage().parts.single()).text)
        val encoded = Json.parseToJsonElement(GeminiCodec(config).encode(chatRequest("gemini-model") {
            messages = listOf(message(Role.USER, textPart("hi")), r.assistantMessage())
        })).jsonObject
        val parts = encoded["contents"]!!.jsonArray[1].jsonObject["parts"]!!.jsonArray
        assertEquals(Json.parseToJsonElement(signed), parts[1])
        assertNull(parts[0].jsonObject["thoughtSignature"])
    }

    @Test fun `schema projection choice image and thinking configuration are native`() {
        val bytes = byteArrayOf(1, 2, 3)
        val codec = GeminiCodec(config, MediaContentReader { bytes })
        for (choice in listOf(ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, namedTool("tool"))) {
            val request = chatRequest("gemini-model") {
                messages = listOf(message(Role.USER, textPart("see"), mediaPart(MediaKind.IMAGE, blobRef("img", "image/png", 3))))
                system = "system"; reasoningLevel = "high"; maxOutputTokens = 123; toolChoice = choice
                tools = listOf(modelTool("tool", "help", Json.parseToJsonElement("""{"type":"object","properties":{"x":{"type":"string"}},"oneOf":[{"required":["x"]}]}""").jsonObject))
            }
            val json = Json.parseToJsonElement(codec.encode(request)).jsonObject
            assertEquals("HIGH", json["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
            val image = json["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inlineData"]!!.jsonObject
            assertEquals("AQID", image["data"]!!.jsonPrimitive.content)
            val schema = json["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["parametersJsonSchema"]!!.jsonObject
            assertNull(schema["oneOf"]); assertTrue(schema["description"]!!.jsonPrimitive.content.contains("oneOf"))
            val fc = json["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject
            assertEquals(when (choice) { ToolChoice.Auto -> "AUTO"; ToolChoice.None -> "NONE"; else -> "ANY" }, fc["mode"]!!.jsonPrimitive.content)
            if (choice is ToolChoice.Named) assertEquals(JsonArray(listOf(JsonPrimitive("tool"))), fc["allowedFunctionNames"])
        }
    }

    @Test fun `truncation filtering and malformed calls cannot execute tools`() = runTest {
        for (reason in listOf("MAX_TOKENS", "SAFETY", "RECITATION", "MALFORMED_FUNCTION_CALL", "CONTINUATION", "UNKNOWN_NEW_REASON")) {
            val (events, r) = collect(wire(root("""[{"functionCall":{"name":"tool","args":{}}}]""", reason)))
            assertTrue(r.toolCandidates().isEmpty())
            if (reason in setOf("MALFORMED_FUNCTION_CALL", "CONTINUATION", "UNKNOWN_NEW_REASON")) assertIs<ModelEvent.Failed>(events.last())
            else assertEquals(if (reason == "MAX_TOKENS") FinishReason.LENGTH else FinishReason.CONTENT_FILTER,
                assertIs<ModelEvent.Finished>(events.last()).reason)
        }
        val (blocked, _) = collect(wire("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals(FinishReason.CONTENT_FILTER, assertIs<ModelEvent.Finished>(blocked.last()).reason)
    }

    @Test fun `missing terminal and oversized signature fail explicitly without stripping replay`() = runTest {
        val (missing, _) = collect(wire(root("""[{"text":"partial"}]""")))
        assertIs<ModelEvent.Failed>(missing.last())
        val (oversized, _) = collect(wire(root("""[{"functionCall":{"name":"tool","args":{}},"thoughtSignature":"${"s".repeat(65536)}"}]""", "STOP")))
        assertIs<ModelEvent.Failed>(oversized.last())
    }

    @Test fun `large whole function arguments fragment without alteration`() = runTest {
        val args = buildJsonObject { put("payload", "中".repeat(24000)) }
        val (events, r) = collect(wire(root("""[{"functionCall":{"name":"tool","args":$args}}]""", "STOP")))
        assertTrue(events.all { eventSizeBytes(it) <= 65536 })
        assertEquals(args.toString(), r.calls().single().argsJson)
        assertTrue(events.count { it is ModelEvent.ToolCallDelta } > 1)
    }

    @Test fun `native authentication error sanitization and one attempt use real HTTP`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"thinking_level is not supported synthetic-key"}}""").build())
            val provider = GeminiProvider(GeminiConfig("provider", server.url("/v1beta").toString()))
            val events = withTimeout(5000) { provider.chat(request(), auth).toList() }
            val error = assertIs<ModelEvent.Failed>(events.single()).error
            assertEquals(ModelErrorCode.UNSUPPORTED_PARAMETER, error.code); assertEquals("thinkingConfig", error.param)
            assertFalse(error.message.contains("synthetic-key")); assertEquals(1, server.requestCount)
            val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1beta/models/gemini-model:streamGenerateContent", sent.url.encodedPath)
            assertEquals("alt=sse", sent.url.encodedQuery); assertFalse(sent.url.toString().contains("synthetic-key"))
            assertEquals("synthetic-key", sent.headers["x-goog-api-key"]); assertNull(sent.headers["Authorization"])
        }
        for (url in listOf("https://example.invalid/v1beta?key=secret", "https://user:secret@example.invalid/v1beta", "http://public.invalid/v1beta"))
            assertFailsWith<IllegalArgumentException> { GeminiConfig("provider", url) }
    }

    @Test fun `models discovery uses native pagination auth and filters non generative models`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("""{"models":[{"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"models":[{"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}]}""").build())
            assertEquals(listOf("gemini-a", "gemini-b"), GeminiModelsClient().fetch(server.url("/v1beta").toString(), auth))
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!; val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("synthetic-key", first.headers["x-goog-api-key"])
            assertNull(first.url.encodedQuery); assertEquals("pageToken=next", second.url.encodedQuery)
        }
    }
}
