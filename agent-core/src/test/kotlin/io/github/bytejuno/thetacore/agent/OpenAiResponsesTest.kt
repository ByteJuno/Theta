package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class OpenAiResponsesTest {
    private val config = OpenAiResponsesConfig("test", "https://example.invalid/v1/")
    private val codec = OpenAiResponsesCodec(config)
    private val auth = Credentials { "synthetic-key" }
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"q":{"type":"string"}}}""").jsonObject
    private fun req() = chatRequest("m") { messages = listOf(message(Role.USER, textPart("hi"))) }
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun event(type: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("type", type); fields() }
    private fun response(status: String, output: List<JsonObject> = emptyList(), reason: String? = null) = buildJsonObject {
        put("id", "resp_1"); put("model", "m"); put("status", status); put("output", JsonArray(output))
        if (status == "failed") put("error", obj("""{"code":"server_error","message":"synthetic-key","param":"synthetic-key"}"""))
        reason?.let { putJsonObject("incomplete_details") { put("reason", it) } }
        put("usage", obj("""{"input_tokens":100,"input_tokens_details":{"cached_tokens":40},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":20}}"""))
    }
    private fun created() = event("response.created") { put("response", response("in_progress")) }
    private fun terminal(items: List<JsonObject>, status: String = "completed", reason: String? = null) =
        event("response.$status") { put("response", response(status, items, reason)) }
    private fun added(index: Int, item: JsonObject) = event("response.output_item.added") { put("output_index", index); put("item", item) }
    private fun ended(index: Int, item: JsonObject) = event("response.output_item.done") { put("output_index", index); put("item", item) }
    private fun delta(index: Int, id: String, type: String, value: String) = event("response.$type.delta") {
        put("output_index", index); put("item_id", id); put("delta", value); put("content_index", 0)
    }
    private fun text(value: String, refusal: Boolean = false) = buildJsonObject {
        put("id", "msg_1"); put("type", "message"); put("role", "assistant"); put("status", "completed")
        putJsonArray("content") { addJsonObject {
            put("type", if (refusal) "refusal" else "output_text"); put(if (refusal) "refusal" else "text", value)
        } }
    }
    private fun call(id: String, args: String) = buildJsonObject {
        put("id", "item_$id"); put("type", "function_call"); put("call_id", "call_$id"); put("name", "read"); put("arguments", args)
    }
    private fun reasoning(summary: String, encrypted: String = "opaque-final") = buildJsonObject {
        put("id", "rs_1"); put("type", "reasoning"); put("encrypted_content", encrypted)
        putJsonArray("summary") { addJsonObject { put("type", "summary_text"); put("text", summary) } }
    }
    private fun wire(events: List<JsonObject>) = events.joinToString("") { "event: ${it.getValue("type").jsonPrimitive.content}\ndata: $it\n\n" }
    private fun transport(wire: String, segment: Int = 8192) = ModelTransport { _, _, _ -> flow {
        emit(TransportEvent.Headers(200, "text/event-stream", null))
        wire.toByteArray().toList().chunked(segment).forEach { emit(TransportEvent.Bytes(it.toByteArray())) }
    } }
    private suspend fun collect(events: List<JsonObject>, segment: Int = 8192) =
        OpenAiResponsesProvider(config, transport(wire(events), segment)).chat(req(), auth).validated("test", "req").toList()
    private fun reduced(events: List<ModelEvent>) = ModelEventReducer("test", "req").apply { events.forEach(::accept); endOfInput() }

    @Test fun `request maps system tools choices parameters and image without leaking chat compat`() {
        for (choice in listOf(ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, namedTool("read"))) {
            val c = OpenAiResponsesCodec(config, MediaContentReader { byteArrayOf(1, 2, 3) })
            val request = chatRequest("m") {
                system = "system"; tools = listOf(modelTool("read", "Read", schema)); toolChoice = choice
                reasoningLevel = "high"; maxOutputTokens = 123; parallelToolCalls = false
                temperature = 0.4; topP = 0.8; responseSchema = schema
                messages = listOf(message(Role.USER, textPart("see"), mediaPart(MediaKind.IMAGE, blobRef("image", "image/png", 3))))
            }
            val body = obj(c.encode(request))
            assertEquals("https://example.invalid/v1/responses", config.endpoint.toString())
            assertEquals("system", body["instructions"]!!.jsonPrimitive.content)
            assertEquals(123, body["max_output_tokens"]!!.jsonPrimitive.int)
            assertEquals("high", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals(schema, body["tools"]!!.jsonArray[0].jsonObject["parameters"])
            assertFalse(body["tools"]!!.jsonArray[0].jsonObject["strict"]!!.jsonPrimitive.boolean)
            assertFalse(body["store"]!!.jsonPrimitive.boolean)
            assertEquals("data:image/png;base64,AQID", body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonPrimitive.content)
            assertNull(body["messages"]); assertNull(body["stream_options"]); assertNull(body["max_completion_tokens"])
            if (choice is ToolChoice.Named) assertEquals("read", body["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            else assertEquals(if (choice == ToolChoice.Auto) "auto" else if (choice == ToolChoice.None) "none" else "required", body["tool_choice"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `text heartbeat usage and fragmented utf8 preserve a single terminal`() = runTest {
        val final = text("你好 🌍")
        val events = listOf(created(), added(0, text("")), delta(0, "msg_1", "output_text", "你好 🌍"), ended(0, final), terminal(listOf(final)))
        val result = OpenAiResponsesProvider(config, transport(": ping\n\n" + wire(events), 1)).chat(req(), auth).validated("test", "req").toList()
        assertTrue(result.any { it is ModelEvent.KeepAlive })
        val r = reduced(result)
        assertEquals("你好 🌍", (r.assistantMessage().parts.single() as ContentPart.Text).text)
        assertEquals(100L, r.usage!!.inputTokens); assertEquals(40L, r.usage!!.cacheReadTokens)
        assertEquals(30L, r.usage!!.outputTokens); assertEquals(20L, r.usage!!.reasoningTokens)
        assertEquals(FinishReason.STOP, (result.last() as ModelEvent.Finished).reason)
    }

    @Test fun `parallel arguments and final reasoning item round trip through internal call bindings`() = runTest {
        val reason = reasoning("Plan", "encrypted-complete")
        val a = call("a", "{\"q\":\"one\"}"); val b = call("b", "{\"q\":\"two\"}")
        val result = collect(listOf(created(), added(0, reasoning("", "partial")),
            delta(0, "rs_1", "reasoning_summary_text", "Plan"), ended(0, reason),
            added(1, call("a", "")), added(2, call("b", "")),
            delta(1, "item_a", "function_call_arguments", "{\"q\":"),
            delta(2, "item_b", "function_call_arguments", "{\"q\":\"two\"}"),
            delta(1, "item_a", "function_call_arguments", "\"one\"}"), ended(1, a), ended(2, b), terminal(listOf(reason, a, b))))
        val r = reduced(result); val message = r.assistantMessage()
        assertEquals(FinishReason.TOOL_CALLS, (result.last() as ModelEvent.Finished).reason)
        assertEquals(listOf("{\"q\":\"one\"}", "{\"q\":\"two\"}"), r.calls().map { it.argsJson })
        val replay = message.parts.first().replay!!
        assertTrue(codec.canReplay(replay, "m")); assertFalse(codec.canReplay(replay, "other"))
        val next = chatRequest("m") { messages = listOf(message, io.github.bytejuno.thetacore.agent.protocol.message(Role.TOOL,
            *r.calls().map { toolResultPart(it.binding.callId, "result") }.toTypedArray())) }
        val input = obj(codec.encode(next))["input"]!!.jsonArray
        assertEquals(reason, input[0]); assertEquals("call_a", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("result", input[4].jsonObject["output"]!!.jsonPrimitive.content)
        assertTrue(codec.estimateRequestBytes(next) >= codec.encode(next).toByteArray().size)
        assertFalse(OpenAiChatProvider(OpenAiChatConfig("test", "https://example.invalid/v1")).canReplay(replay, "m"))
        assertFalse(OpenAiResponsesCodec(OpenAiResponsesConfig("test", "https://else.invalid/v1")).canReplay(replay, "m"))
        assertFalse(codec.canReplay(replayData("openai-responses", "m", "{}"), "m"))
    }

    @Test fun `incomplete refusal filtering and failed events never execute tools`() = runTest {
        for ((status, reason, expected) in listOf(Triple("incomplete", "max_output_tokens", FinishReason.LENGTH),
            Triple("incomplete", "content_filter", FinishReason.CONTENT_FILTER), Triple("completed", null, FinishReason.REFUSAL))) {
            val final = if (expected == FinishReason.REFUSAL) text("No", true) else call("a", "{}")
            val result = collect(listOf(created(), added(0, final), ended(0, final), terminal(listOf(final), status, reason)))
            assertEquals(expected, (result.last() as ModelEvent.Finished).reason)
            assertTrue(reduced(result).toolCandidates().isEmpty())
            if (expected != FinishReason.LENGTH) assertEquals(HistoryDisposition.DISCARD, reduced(result).historyDisposition())
        }
        val failed = collect(listOf(created(), terminal(emptyList(), "failed")))
        assertEquals(ModelErrorCode.SERVER, (failed.last() as ModelEvent.Failed).error.code)
        assertFalse(failed.toString().contains("synthetic-key"))
        assertEquals(1, failed.count { it is ModelEvent.Failed || it is ModelEvent.Finished })
    }

    @Test fun `large argument deltas split without alteration and oversized opaque replay fails closed`() = runTest {
        val args = "{\"q\":\"" + "😀".repeat(35_000) + "\"}"
        val c = call("a", args)
        val result = collect(listOf(created(), added(0, call("a", "")), delta(0, "item_a", "function_call_arguments", args), ended(0, c), terminal(listOf(c))))
        assertEquals(args, reduced(result).calls().single().argsJson)
        assertTrue(result.all { eventSizeBytes(it) <= 64 * 1024 })
        val reason = reasoning("", "x".repeat(70_000))
        assertIs<ModelEvent.Failed>(collect(listOf(created(), added(0, reasoning("")), ended(0, reason), terminal(listOf(reason)))).last())
    }

    @Test fun `reserved fields stop sequences and wrong media fail before transport`() = runTest {
        val p = OpenAiResponsesProvider(config, ModelTransport { _, _, _ -> error("Must not send") })
        for (key in listOf("input", "tools", "previous_response_id", "conversation", "background", "model", "store")) {
            val request = chatRequest("m") { extraBody = buildJsonObject { put(key, "override") } }
            assertEquals(ModelErrorCode.INVALID_REQUEST, (p.chat(request, auth).toList().single() as ModelEvent.Failed).error.code)
        }
        assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") { stopSequences = listOf("stop") }) }
        assertEquals(ModelExecutionMode.GENERATION_ONLY, p.executionMode(req()))
        assertEquals(ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS, p.executionMode(chatRequest("m") { extraBody = obj("""{"vendor":{"server_tools":true}}""") }))
        assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") {
            messages = listOf(message(Role.USER, mediaPart(MediaKind.AUDIO, blobRef("audio", "audio/wav", 4))))
        }) }
    }

    @Test fun `orphan reordered duplicate history and foreign replay are rejected`() {
        val a = callBinding("test", "r", null, 0, "wire"); val b = callBinding("test", "r", null, 1, "wire")
        val histories = listOf(
            listOf(message(Role.TOOL, toolResultPart(a.callId, "orphan"))),
            listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}"))),
            listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}"), toolCallPart(b, "read", "{}")),
                message(Role.TOOL, toolResultPart(b.callId, "b"), toolResultPart(a.callId, "a"))),
            listOf(message(Role.ASSISTANT, reasoningPart("old", replayData("openai-chat", "m", "{}")))),
        )
        histories.forEach { h -> assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") { messages = h }) } }
        val good = chatRequest("m") { messages = listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}"), toolCallPart(b, "read", "{}")),
            message(Role.TOOL, toolResultPart(a.callId, "a"), toolResultPart(b.callId, "b"))) }
        val input = obj(codec.encode(good))["input"]!!.jsonArray
        assertNotEquals(input[0].jsonObject["call_id"], input[1].jsonObject["call_id"])
        assertEquals(input[1].jsonObject["call_id"], input[3].jsonObject["call_id"])
    }

    @Test fun `malformed stream truncation duplicate terminal and inconsistent final are rejected`() = runTest {
        val final = text("yes")
        for (events in listOf(listOf(created(), added(0, final)),
            listOf(created(), added(0, final), delta(0, "msg_1", "output_text", "wrong"), ended(0, final)),
            listOf(created(), added(0, final), ended(0, final), terminal(listOf(final)), terminal(listOf(final))),
            listOf(created(), added(1, final)))) {
            assertEquals(ModelErrorCode.PROTOCOL, (collect(events).last() as ModelEvent.Failed).error.code)
        }
    }

    @Test fun `cold recollection cancellation and terminal grace close transport`() = runTest {
        var requests = 0; var closed = 0
        val final = text("ok")
        val t = ModelTransport { url, _, token -> flow {
            assertEquals(config.endpoint.toString(), url); assertEquals("", token); requests++
            try { emit(TransportEvent.Headers(200, "text/event-stream", null))
                emit(TransportEvent.Bytes(wire(listOf(created(), added(0, final), ended(0, final), terminal(listOf(final)))).toByteArray()))
                awaitCancellation()
            } finally { closed++ }
        } }
        val cold = OpenAiResponsesProvider(config, t).chat(req(), NoAuthCredentials)
        repeat(2) { assertIs<ModelEvent.Finished>(cold.toList().last()) }
        assertEquals(2, requests); assertEquals(2, closed)
        var cancelled = false
        val waiting = OpenAiResponsesProvider(config, ModelTransport { _, _, _ -> flow {
            try { emit(TransportEvent.Headers(200, "text/event-stream", null)); awaitCancellation() } finally { cancelled = true }
        } })
        assertNull(withTimeoutOrNull(50) { waiting.chat(req(), auth).toList() })
        assertTrue(cancelled)
    }
}
