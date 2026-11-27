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
        put("output_index", index); put("item_id", id); put("delta", value)
        put(if (type == "reasoning_summary_text") "summary_index" else "content_index", 0)
    }
    private fun text(value: String, refusal: Boolean = false) = buildJsonObject {
        put("id", "msg_1"); put("type", "message"); put("role", "assistant"); put("status", "completed")
        putJsonArray("content") { addJsonObject {
            put("type", if (refusal) "refusal" else "output_text"); put(if (refusal) "refusal" else "text", value)
        } }
    }
    private fun messageParts(vararg values: String) = JsonObject(text("") + ("content" to JsonArray(values.map {
        buildJsonObject { put("type", "output_text"); put("text", it) }
    })))
    private fun messageEvent(type: String, index: Int, value: String) = event("response.$type") {
        put("output_index", 0); put("item_id", "msg_1"); put("content_index", index)
        if (type.startsWith("content_part")) putJsonObject("part") { put("type", "output_text"); put("text", value) }
        else put(if (type.endsWith(".delta")) "delta" else "text", value)
    }
    private fun call(id: String, args: String) = buildJsonObject {
        put("id", "item_$id"); put("type", "function_call"); put("call_id", "call_$id"); put("name", "read"); put("arguments", args)
    }
    private fun reasoning(summary: String, encrypted: String = "opaque-final") = buildJsonObject {
        put("id", "rs_1"); put("type", "reasoning"); put("encrypted_content", encrypted)
        putJsonArray("summary") { addJsonObject { put("type", "summary_text"); put("text", summary) } }
    }
    private fun rawReasoning(content: List<String>, summaries: List<String> = emptyList()) = buildJsonObject {
        put("id", "rs_1"); put("type", "reasoning"); put("status", "completed")
        put("encrypted_content", "opaque-complete")
        putJsonArray("content") { content.forEach { addJsonObject { put("type", "reasoning_text"); put("text", it) } } }
        putJsonArray("summary") { summaries.forEach { addJsonObject { put("type", "summary_text"); put("text", it) } } }
        putJsonObject("provider_metadata") { put("retain", "unknown-field") }
    }
    private fun reasoningEvent(type: String, value: String, part: Int = 0) = event("response.$type") {
        put("output_index", 0); put("item_id", "rs_1")
        put(if (type.startsWith("reasoning_summary")) "summary_index" else "content_index", part)
        put(if (type.endsWith(".delta")) "delta" else "text", value)
    }
    private fun assertReasoningAnswer(result: List<ModelEvent>, reason: JsonObject, visible: String) {
        assertEquals(FinishReason.STOP, assertIs<ModelEvent.Finished>(result.last()).reason)
        assertEquals(1, result.count { it is ModelEvent.Finished })
        val message = reduced(result).assistantMessage()
        assertEquals("answer", assertIs<ContentPart.Text>(message.parts.last()).text)
        val part = assertIs<ContentPart.Reasoning>(message.parts.first())
        assertEquals(visible, part.summary)
        val replay = assertNotNull(part.replay)
        assertTrue(codec.canReplay(replay, "m"))
        assertEquals(reason, obj(replay.payload).getValue("item"))
        assertEquals(reason, obj(codec.encode(chatRequest("m") { messages = listOf(message) })).getValue("input").jsonArray.first())
        assertTrue(result.all { eventSizeBytes(it) <= 64 * 1024 })
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

    @Test fun `raw reasoning deltas and done text deliver answer and preserve entire replay`() = runTest {
        val reason = rawReasoning(listOf("思考 🌍 complete")); val answer = text("answer")
        val result = collect(listOf(created(), added(0, rawReasoning(emptyList())),
            reasoningEvent("reasoning_text.delta", "思考 🌍"),
            reasoningEvent("reasoning_text.done", "思考 🌍 complete"), ended(0, reason),
            added(1, text("")), delta(1, "msg_1", "output_text", "answer"), ended(1, answer), terminal(listOf(reason, answer))), 1)
        assertReasoningAnswer(result, reason, "思考 🌍 complete")
        assertEquals(listOf("", "思考 🌍", " complete"), result.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.text })
    }

    @Test fun `message content indices preserve ordering across interleaved deltas and done text`() = runTest {
        val final = messageParts("left", "right 🌍")
        for (prefix in listOf(
            listOf(messageEvent("output_text.delta", 0, "le"), messageEvent("output_text.delta", 1, "right 🌍"),
                messageEvent("output_text.delta", 0, "ft")),
            listOf(messageEvent("content_part.added", 0, ""), messageEvent("output_text.delta", 0, "le"),
                messageEvent("output_text.done", 0, "left"), messageEvent("content_part.done", 0, "left"),
                messageEvent("output_text.delta", 1, "right 🌍")),
            listOf(messageEvent("content_part.done", 1, "right 🌍"), messageEvent("content_part.done", 0, "left")),
        )) {
            val result = collect(listOf(created(), added(0, messageParts())) + prefix + listOf(ended(0, final), terminal(listOf(final))), 1)
            assertEquals(FinishReason.STOP, assertIs<ModelEvent.Finished>(result.last()).reason)
            assertEquals("leftright 🌍", assertIs<ContentPart.Text>(reduced(result).assistantMessage().parts.single()).text)
            assertEquals(1, result.count { it is ModelEvent.PartEnd })
        }
    }

    @Test fun `message completion checks each part type index and completed value`() = runTest {
        val final = messageParts("left", "right")
        for (prefix in listOf(
            listOf(messageEvent("output_text.done", 0, "wrong")),
            listOf(messageEvent("content_part.done", 0, "le")),
            listOf(messageEvent("output_text.delta", 2, "")),
            listOf(messageEvent("output_text.done", 0, "left"), messageEvent("output_text.delta", 0, "")),
            listOf(messageEvent("output_text.delta", 0, "le"), messageEvent("content_part.added", 0, "")),
            listOf(event("response.refusal.done") {
                put("output_index", 0); put("item_id", "msg_1"); put("content_index", 0); put("refusal", "left")
            }),
            listOf(JsonObject(messageEvent("output_text.delta", 0, "left") + ("content_index" to JsonPrimitive("0")))),
        )) {
            val result = collect(listOf(created(), added(0, messageParts())) + prefix + listOf(ended(0, final), terminal(listOf(final))))
            assertEquals(ModelErrorCode.PROTOCOL, assertIs<ModelEvent.Failed>(result.last()).error.code)
            assertTrue(result.none { it is ModelEvent.Finished })
        }
        val refusal = text("Cannot answer", true)
        val result = collect(listOf(created(), added(0, messageParts()), event("response.refusal.done") {
            put("output_index", 0); put("item_id", "msg_1"); put("content_index", 0); put("refusal", "Cannot answer")
        }, ended(0, refusal), terminal(listOf(refusal))))
        assertEquals(FinishReason.REFUSAL, assertIs<ModelEvent.Finished>(result.last()).reason)
    }

    @Test fun `function argument done reconciles deltas and forbids later mutations`() = runTest {
        val args = "{\"q\":\"ok\"}"
        fun done(value: String) = event("response.function_call_arguments.done") {
            put("output_index", 0); put("item_id", "item_a"); put("arguments", value)
        }
        val final = call("a", args)
        for (prefix in listOf(listOf(done(args)), listOf(delta(0, "item_a", "function_call_arguments", "{\"q\":"), done(args)))) {
            val result = collect(listOf(created(), added(0, call("a", ""))) + prefix + listOf(ended(0, final), terminal(listOf(final))))
            assertEquals(args, reduced(result).calls().single().argsJson)
        }
        for (prefix in listOf(listOf(done("{}")),
            listOf(delta(0, "item_a", "function_call_arguments", "{\"other\":"), done(args)),
            listOf(done(args), delta(0, "item_a", "function_call_arguments", "")))) {
            val result = collect(listOf(created(), added(0, call("a", ""))) + prefix + listOf(ended(0, final), terminal(listOf(final))))
            assertEquals(ModelErrorCode.PROTOCOL, assertIs<ModelEvent.Failed>(result.last()).error.code)
            assertTrue(reduced(result).toolCandidates().isEmpty())
        }
    }

    @Test fun `Responses schema parameter normalization preserves credential sanitization and Chat isolation`() = runTest {
        for (param in listOf("text", "text.format", "text.format.type", "text.format.schema", "text.format.unknown", "text.format.synthetic-key")) {
            val t = ModelTransport { _, _, _ -> flow {
                emit(TransportEvent.Headers(400, "application/json", null))
                emit(TransportEvent.Bytes(buildJsonObject { putJsonObject("error") {
                    put("code", "unsupported_parameter"); put("param", param); put("message", "synthetic-key")
                } }.toString().toByteArray()))
            } }
            val request = chatRequest("m") { responseSchema = schema }
            val error = assertIs<ModelEvent.Failed>(OpenAiResponsesProvider(config, t).chat(request, auth).toList().single()).error
            assertEquals(if (param in setOf("text", "text.format", "text.format.type", "text.format.schema")) "response_format" else null, error.param)
            assertFalse(error.toString().contains("synthetic-key"))
            if (param == "text.format") {
                val chat = OpenAiChatProvider(OpenAiChatConfig("test", "https://example.invalid/v1"), t)
                assertNull(assertIs<ModelEvent.Failed>(chat.chat(request, auth).toList().single()).error.param)
                assertNull(assertIs<ModelEvent.Failed>(OpenAiResponsesProvider(config, t).chat(request, Credentials { "text.format" }).toList().single()).error.param)
            }
        }
    }

    @Test fun `terminal only raw reasoning is delivered with complete replay`() = runTest {
        val reason = rawReasoning(listOf("terminal reasoning")); val answer = text("answer")
        assertReasoningAnswer(collect(listOf(created(), added(0, rawReasoning(emptyList())),
            added(1, text("")), terminal(listOf(reason, answer)))), reason, "terminal reasoning")
    }

    @Test fun `raw reasoning and summary use independent indexed terminal checks`() = runTest {
        val reason = rawReasoning(listOf("raw zero", "raw one"), listOf("summary zero", "summary one"))
        val answer = text("answer")
        val result = collect(listOf(created(), added(0, rawReasoning(emptyList())),
            reasoningEvent("reasoning_text.delta", "raw ", 0),
            reasoningEvent("reasoning_summary_text.delta", "summary ", 0),
            reasoningEvent("reasoning_text.delta", "raw one", 1),
            reasoningEvent("reasoning_summary_text.delta", "summary one", 1),
            reasoningEvent("reasoning_text.done", "raw zero", 0),
            reasoningEvent("reasoning_summary_text.done", "summary zero", 0),
            ended(0, reason), added(1, text("")), ended(1, answer), terminal(listOf(reason, answer))))
        assertReasoningAnswer(result, reason, "raw zeroraw one")
    }

    @Test fun `summary first remains visible while raw text and part completions are preserved`() = runTest {
        val reason = rawReasoning(listOf("private reasoning"), listOf("visible summary")); val answer = text("answer")
        fun part(type: String, summary: Boolean, value: String) = event("response.$type") {
            put("output_index", 0); put("item_id", "rs_1")
            put(if (summary) "summary_index" else "content_index", 0)
            putJsonObject("part") { put("type", if (summary) "summary_text" else "reasoning_text"); put("text", value) }
        }
        val result = collect(listOf(created(), added(0, rawReasoning(emptyList())),
            part("reasoning_summary_part.added", true, ""),
            reasoningEvent("reasoning_summary_text.delta", "visible "),
            part("content_part.added", false, ""),
            reasoningEvent("reasoning_text.delta", "private "),
            reasoningEvent("reasoning_text.done", "private reasoning"),
            part("content_part.done", false, "private reasoning"),
            reasoningEvent("reasoning_summary_text.done", "visible summary"),
            part("reasoning_summary_part.done", true, "visible summary"),
            ended(0, reason), added(1, text("")), ended(1, answer), terminal(listOf(reason, answer))))
        assertReasoningAnswer(result, reason, "visible summary")
    }

    @Test fun `nonvisible reasoning channel still proves liveness to the kernel`() = runTest {
        val reason = rawReasoning(listOf("rawrawrawraw"), listOf("summary")); val answer = text("answer")
        val frames = listOf(created(), added(0, rawReasoning(emptyList())),
            reasoningEvent("reasoning_summary_text.delta", "summary")) +
            List(4) { reasoningEvent("reasoning_text.delta", "raw") } +
            listOf(ended(0, reason), added(1, text("")), ended(1, answer), terminal(listOf(reason, answer)))
        val transport = ModelTransport { _, _, _ -> flow {
            emit(TransportEvent.Headers(200, "text/event-stream", null))
            frames.forEach { frame ->
                delay(60)
                emit(TransportEvent.Bytes(wire(listOf(frame)).toByteArray()))
            }
        } }
        val result = OpenAiResponsesProvider(config, transport).chat(req(), auth)
            .validated("test", "req", EventTimeouts(firstMs = 100, idleMs = 100)).toList()
        assertReasoningAnswer(result, reason, "summary")
    }

    @Test fun `terminal checks reject malformed raw content and changes after item completion`() = runTest {
        val valid = rawReasoning(listOf("raw"), listOf("summary"))
        for (bad in listOf(JsonPrimitive("not an array"),
            JsonArray(listOf(obj("""{"type":"summary_text","text":"raw"}"""))),
            JsonArray(listOf(obj("""{"type":"reasoning_text"}"""))))) {
            val invalid = JsonObject(valid + ("content" to bad))
            val result = collect(listOf(created(), added(0, rawReasoning(emptyList())), ended(0, invalid), terminal(listOf(invalid))))
            assertEquals(ModelErrorCode.PROTOCOL, assertIs<ModelEvent.Failed>(result.last()).error.code)
            val replay = replayData("openai-responses", "m", buildJsonObject {
                put("v", 1); put("provider", "test"); put("endpoint", config.endpoint.toString()); put("item", invalid)
            }.toString())
            assertFalse(codec.canReplay(replay, "m"))
        }
        val result = collect(listOf(created(), added(0, rawReasoning(emptyList())), ended(0, valid),
            terminal(listOf(rawReasoning(listOf("changed"), listOf("summary"))))))
        assertEquals(ModelErrorCode.PROTOCOL, assertIs<ModelEvent.Failed>(result.last()).error.code)
    }

    @Test fun `reasoning contradictions cannot be hidden by the other channel or part`() = runTest {
        val reason = rawReasoning(listOf("raw"), listOf("summary"))
        val prefixes = listOf(
            listOf(reasoningEvent("reasoning_text.delta", "summary")),
            listOf(reasoningEvent("reasoning_summary_text.delta", "raw")),
            listOf(reasoningEvent("reasoning_text.delta", "raw", 1)),
            listOf(reasoningEvent("reasoning_summary_text.delta", "summary", 1)),
            listOf(reasoningEvent("reasoning_text.delta", "raw"), reasoningEvent("reasoning_text.done", "other")),
            listOf(reasoningEvent("reasoning_text.done", "ra")),
            listOf(reasoningEvent("reasoning_summary_text.done", "summ")),
            listOf(reasoningEvent("reasoning_text.done", "raw"), reasoningEvent("reasoning_text.delta", "extra")),
        )
        prefixes.forEach { prefix ->
            val result = collect(listOf(created(), added(0, rawReasoning(emptyList()))) + prefix +
                listOf(ended(0, reason), terminal(listOf(reason))))
            assertEquals(ModelErrorCode.PROTOCOL, assertIs<ModelEvent.Failed>(result.last()).error.code)
            assertTrue(result.none { it is ModelEvent.Finished })
        }
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
