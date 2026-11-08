package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiChatTest {
    private val config = OpenAiChatConfig("test", "https://example.invalid/v1")
    private val auth = Credentials { "synthetic-test-token" }
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject
    private fun request() = chatRequest("model") { messages = listOf(message(Role.USER, textPart("hello"))) }
    private fun chunk(delta: JsonObject = buildJsonObject {}, finish: String? = null, usage: JsonObject? = null,
                      choices: Boolean = true): String = buildJsonObject {
        put("id", "response_1"); put("model", "model")
        putJsonArray("choices") { if (choices) add(buildJsonObject {
            put("index", 0); put("delta", delta); put("finish_reason", finish)
        }) }
        usage?.let { put("usage", it) }
    }.toString()
    private fun text(value: String) = buildJsonObject { put("content", value) }
    private fun tool(index: Int, args: String, name: String? = null, id: String? = null): JsonObject = buildJsonObject {
        putJsonArray("tool_calls") { add(buildJsonObject {
            put("index", index); name?.let { put("type", "function") }; id?.let { put("id", it) }
            putJsonObject("function") { name?.let { put("name", it) }; put("arguments", args) }
        }) }
    }
    private fun sse(vararg chunks: String, done: Boolean = true): String =
        chunks.joinToString("") { "data: $it\r\n\r\n" } + if (done) "data: [DONE]\r\n\r\n" else ""

    private class Fake(private val bytes: ByteArray, private val segment: Int = 8192,
                       private val status: Int = 200, private val fail: Boolean = false,
                       private val hang: Boolean = false) : ModelTransport {
        var requests = 0
        var closed = 0
        override fun exchange(url: String, body: String, token: String): Flow<TransportEvent> = flow {
            requests++
            try {
                emit(TransportEvent.Headers(status, "text/event-stream", "2"))
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + segment)
                    emit(TransportEvent.Bytes(bytes.copyOfRange(offset, end))); offset = end
                }
                if (fail) throw IOException("synthetic failure")
                if (hang) awaitCancellation()
            } finally { closed++ }
        }
    }
    private suspend fun collect(wire: String, segment: Int = 8192,
                                cfg: OpenAiChatConfig = config): List<ModelEvent> =
        OpenAiChatProvider(cfg, Fake(wire.toByteArray(), segment)).chat(request(), auth)
            .validated(cfg.providerId, "request").toList()
    private fun reducer(events: List<ModelEvent>): ModelEventReducer = ModelEventReducer("test", "request").apply {
        events.forEach(::accept); endOfInput()
    }
    private fun assertFailure(events: List<ModelEvent>, code: ModelErrorCode = ModelErrorCode.PROTOCOL) {
        assertEquals(1, events.count { it is ModelEvent.Finished || it is ModelEvent.Failed })
        assertEquals(code, assertIs<ModelEvent.Failed>(events.last()).error.code)
    }

    @Test fun `request encoding covers fields and four choices`() {
        for (choice in listOf(ToolChoice.Auto, ToolChoice.None, ToolChoice.Required, namedTool("read"))) {
            val req = chatRequest("model") {
                system = "independent system"
                messages = listOf(message(Role.USER, textPart("one")))
                tools = listOf(modelTool("read", "Read", schema)); toolChoice = choice
                parallelToolCalls = false; reasoningLevel = "high"; maxOutputTokens = 99
                temperature = 0.3; topP = 0.7; stopSequences = listOf("END"); responseSchema = schema
            }
            val body = Json.parseToJsonElement(OpenAiChatCodec(config).encode(req)).jsonObject
            assertEquals("independent system", body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(99, body["max_completion_tokens"]!!.jsonPrimitive.int)
            assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertFalse(body["parallel_tool_calls"]!!.jsonPrimitive.boolean)
            assertTrue(body["stream"]!!.jsonPrimitive.boolean)
            if (choice is ToolChoice.Named) assertEquals("read", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            else assertEquals(when (choice) { ToolChoice.Auto -> "auto"; ToolChoice.None -> "none"; else -> "required" }, body["tool_choice"]!!.jsonPrimitive.content)
        }
    }
    @Test fun `compat max tokens and stream usage`() {
        val cfg = OpenAiChatConfig("test", "https://example.invalid/v1/", compat = OpenAiChatConfig.Compat(maxTokensField = "max_tokens", streamUsage = false))
        assertEquals("https://example.invalid/v1/chat/completions", cfg.endpoint.toString())
        val body = Json.parseToJsonElement(OpenAiChatCodec(cfg).encode(chatRequest("m") { maxOutputTokens = 7 })).jsonObject
        assertEquals(7, body["max_tokens"]!!.jsonPrimitive.int); assertNull(body["stream_options"])
    }
    @Test fun `extra body cannot overwrite reserved fields even if omitted`() {
        for (key in listOf("model", "tools", "stream", "n", "max_tokens", "function_call", "messages")) {
            assertFailsWith<IllegalArgumentException> { OpenAiChatCodec(config).encode(chatRequest("m") { extraBody = buildJsonObject { put(key, "override") } }) }
        }
        val req = chatRequest("m") { extraBody = buildJsonObject { putJsonObject("vendor") { put("server_tools", true) } } }
        assertEquals(req.extraBody["vendor"], Json.parseToJsonElement(OpenAiChatCodec(config).encode(req)).jsonObject["vendor"])
        assertEquals(ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS, OpenAiChatProvider(config).executionMode(req))
        assertEquals(ModelExecutionMode.GENERATION_ONLY, OpenAiChatProvider(config).executionMode(request()))
    }
    @Test fun `tool results use original wire ID not UUID`() {
        val binding = callBinding("test", "old-request", "old-response", 0, "wire_original")
        val req = chatRequest("model") { messages = listOf(
            message(Role.ASSISTANT, toolCallPart(binding, "read", "{}")),
            message(Role.TOOL, toolResultPart(binding.callId, "result"))) }
        val messages = Json.parseToJsonElement(OpenAiChatCodec(config).encode(req)).jsonObject["messages"]!!.jsonArray
        assertEquals("wire_original", messages[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("wire_original", messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertNotEquals(binding.callId, "wire_original")
    }
    @Test fun `missing wire IDs get stable target mapping`() {
        val binding = callBinding("other", "r", null, 0, null)
        val req = chatRequest("model") { messages = listOf(message(Role.ASSISTANT, toolCallPart(binding, "read", "{}")), message(Role.TOOL, toolResultPart(binding.callId, "ok"))) }
        val codec = OpenAiChatCodec(config)
        assertEquals(codec.encode(req), codec.encode(req))
        assertTrue(codec.encode(req).contains("call_${binding.callId.replace("-", "")}"))
    }
    @Test fun `history rejects orphan duplicate and reordered calls`() {
        val a = callBinding("test", "r", null, 0, "a")
        val b = callBinding("test", "r", null, 1, "b")
        val histories = listOf(
            listOf(message(Role.TOOL, toolResultPart(a.callId, "orphan"))),
            listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}"))),
            listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}"), toolCallPart(a, "read", "{}")),
                message(Role.TOOL, toolResultPart(a.callId, "a"), toolResultPart(a.callId, "duplicate"))),
            listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}"), toolCallPart(b, "read", "{}")),
                message(Role.TOOL, toolResultPart(b.callId, "b"), toolResultPart(a.callId, "a"))),
            listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}")), message(Role.USER, textPart("interruption")))
        )
        histories.forEach { history -> assertFailsWith<IllegalArgumentException> { OpenAiChatCodec(config).encode(chatRequest("model") { messages = history }) } }
    }
    @Test fun `invalid arguments and paired rejection survive the next request unchanged`() = runTest {
        for (args in listOf("[]", "{", "null", "")) {
            val state = reducer(collect(sse(chunk(tool(0, args, "read", "wire")), chunk(finish = "tool_calls"))))
            val call = state.toolCandidates().single()
            assertFalse(call.complete)
            assertEquals(HistoryDisposition.KEEP, state.historyDisposition())
            val rejection = """{"status":"rejected","effect":"none","message":"Invalid arguments"}"""
            val req = chatRequest("model") { messages = listOf(
                message(Role.ASSISTANT, toolCallPart(call.binding, call.name, call.argsJson)),
                message(Role.TOOL, toolResultPart(call.binding.callId, rejection, isError = true))) }
            val messages = Json.parseToJsonElement(OpenAiChatCodec(config).encode(req)).jsonObject["messages"]!!.jsonArray
            assertEquals(args, messages[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
            assertEquals(rejection, messages[1].jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("wire", messages[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            val fake = Fake(sse(chunk(text("corrected")), chunk(finish = "stop")).toByteArray())
            assertIs<ModelEvent.Finished>(OpenAiChatProvider(config, fake).chat(req, auth).toList().last())
            assertEquals(1, fake.requests)
        }
    }
    @Test fun `SSE survives every small byte boundary CRLF BOM and heartbeat`() = runTest {
        val wire = "﻿: heartbeat\r\n\r\n" + sse(chunk(text("汉字🙂")), chunk(finish = "stop"))
        for (segment in 1..17) {
            val events = collect(wire, segment)
            assertEquals("汉字🙂", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
            assertTrue(events.any { it is ModelEvent.KeepAlive })
            assertIs<ModelEvent.Finished>(events.last())
        }
    }
    @Test fun `multiline SSE data and LF framing`() = runTest {
        val wire = "data: {\ndata: \"id\":\"response_1\",\"model\":\"model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        assertIs<ModelEvent.Finished>(collect(wire, 1).last())
    }
    @Test fun `interleaved tools preserve seventy KiB arguments exactly`() = runTest {
        val args = buildJsonObject { put("text", "汉🙂\\\n".repeat(14_000)) }.toString()
        val cut = args.length / 2
        val wire = sse(chunk(tool(0, args.substring(0, cut), "read", "same")), chunk(tool(1, "{", "other", "other")),
            chunk(tool(0, args.substring(cut))), chunk(tool(1, "}")), chunk(finish = "tool_calls"))
        val events = collect(wire, 137)
        assertTrue(args.toByteArray().size > 70 * 1024)
        assertTrue(events.all { eventSizeBytes(it) <= 64 * 1024 })
        val state = reducer(events)
        assertEquals(listOf(args, "{}"), state.calls().map { it.argsJson })
        assertEquals(2, state.toolCandidates().size)
        val other = reducer(events)
        assertNotEquals(state.calls()[0].binding.callId, other.calls()[0].binding.callId)
        assertEquals("same", state.calls()[0].binding.wireId)
    }
    @Test fun `STOP with complete tool becomes TOOL_CALLS`() = runTest {
        val events = collect(sse(chunk(tool(0, "{}", "read", "wire")), chunk(finish = "stop")))
        assertEquals(FinishReason.TOOL_CALLS, assertIs<ModelEvent.Finished>(events.last()).reason)
    }
    @Test fun `truncation never exposes tool candidates`() = runTest {
        for (reason in listOf("length", "context_exceeded")) for (args in listOf("{}", "{", "null", "")) {
            val state = reducer(collect(sse(chunk(tool(0, args, "read", "wire")), chunk(finish = reason))))
            assertTrue(state.toolCandidates().isEmpty())
            assertEquals(if (args == "{}") HistoryDisposition.NEEDS_CANCELLED_NONE_RESULTS else HistoryDisposition.DISCARD, state.historyDisposition())
        }
    }
    @Test fun `mixed complete incomplete truncation discards whole message`() = runTest {
        val state = reducer(collect(sse(chunk(tool(0, "{}", "one", "one")), chunk(tool(1, "{", "two", "two")), chunk(finish = "length"))))
        assertEquals(HistoryDisposition.DISCARD, state.historyDisposition()); assertTrue(state.toolCandidates().isEmpty())
    }
    @Test fun `tail usage snapshots overwrite and unknown stays null`() = runTest {
        val events = collect(sse(chunk(text("ok"), usage = buildJsonObject { put("prompt_tokens", 10) }), chunk(finish = "stop"),
            chunk(choices = false, usage = buildJsonObject { put("completion_tokens", 7); putJsonObject("completion_tokens_details") { put("reasoning_tokens", 3) } })))
        val usage = reducer(events).usage!!
        assertNull(usage.inputTokens); assertNull(usage.cacheWriteTokens)
        assertEquals(7L, usage.outputTokens); assertEquals(3L, usage.reasoningTokens)
    }
    @Test fun `reasoning replay is bound to provider endpoint model and config`() = runTest {
        val cfg = OpenAiChatConfig("test", "https://example.invalid/v1", compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content", replayReasoning = true))
        val events = collect(sse(chunk(buildJsonObject { put("reasoning_content", "raw\n思考") }), chunk(text("ok")), chunk(finish = "stop")), cfg = cfg)
        val replay = events.filterIsInstance<ModelEvent.PartEnd>().first().replay!!
        val provider = OpenAiChatProvider(cfg)
        assertTrue(provider.canReplay(replay, "model")); assertFalse(provider.canReplay(replay, "other"))
        assertFalse(OpenAiChatProvider(config).canReplay(replay, "model"))
        assertFalse(OpenAiChatProvider(OpenAiChatConfig("different", "https://example.invalid/v1", compat = cfg.compat)).canReplay(replay, "model"))
        assertFalse(provider.canReplay(replayData("openai-chat", "model", "{}"), "model"))
        val req = chatRequest("model") { messages = listOf(message(Role.ASSISTANT, reasoningPart("summary", replay), textPart("ok"))) }
        val encoded = Json.parseToJsonElement(OpenAiChatCodec(cfg).encode(req)).jsonObject
        assertEquals("raw\n思考", encoded["messages"]!!.jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { OpenAiChatCodec(config).encode(req) }
    }
    @Test fun `large reasoning replay stays within every event budget`() = runTest {
        val cfg = OpenAiChatConfig("test", "https://example.invalid/v1", compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content", replayReasoning = true))
        val original = "\u0001\\\"汉🙂".repeat(20_000)
        val events = collect(sse(chunk(buildJsonObject { put("reasoning_content", original) }), chunk(finish = "stop")), cfg = cfg)
        assertIs<ModelEvent.Finished>(events.last())
        assertTrue(events.all { eventSizeBytes(it) <= 65536 })
        val replays = events.filterIsInstance<ModelEvent.PartEnd>().mapNotNull { it.replay }
        assertTrue(replays.size > 1)
        val req = chatRequest("model") { messages = listOf(message(Role.ASSISTANT, *replays.map { reasoningPart(null, it) }.toTypedArray())) }
        val body = Json.parseToJsonElement(OpenAiChatCodec(cfg).encode(req)).jsonObject
        assertEquals(original, body["messages"]!!.jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }
    @Test fun `reasoning fragments budget escaped replay metadata and preserve unicode`() = runTest {
        val cfg = OpenAiChatConfig("provider\u0001".repeat(500), "https://example.invalid/" + "p".repeat(10_240),
            compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_" + "r".repeat(1024), replayReasoning = true))
        val model = "model".repeat(1000)
        val original = "\u0001".repeat(8_000) + "🙂汉\\\"".repeat(4000)
        val wire = sse(chunk(buildJsonObject { put(cfg.compat.reasoningField!!, original) }), chunk(finish = "stop"))
        val events = OpenAiChatProvider(cfg, Fake(wire.toByteArray())).chat(chatRequest(model), auth)
            .validated(cfg.providerId, "request").toList()
        assertIs<ModelEvent.Finished>(events.last())
        assertTrue(events.all { eventSizeBytes(it) <= 65536 })
        assertEquals(original, events.filterIsInstance<ModelEvent.ReasoningDelta>().joinToString("") { it.text })
        val replays = events.filterIsInstance<ModelEvent.PartEnd>().mapNotNull { it.replay }
        assertTrue(replays.size > 1)
        assertTrue(replays.all { OpenAiChatCodec(cfg).canReplay(it, model) })
        events.filterIsInstance<ModelEvent.ReasoningDelta>().forEach {
            assertFalse(it.text.first().isLowSurrogate()); assertFalse(it.text.last().isHighSurrogate())
        }
        val req = chatRequest(model) { messages = listOf(message(Role.ASSISTANT, *replays.map { reasoningPart(null, it) }.toTypedArray())) }
        val body = Json.parseToJsonElement(OpenAiChatCodec(cfg).encode(req)).jsonObject
        assertEquals(original, body["messages"]!!.jsonArray[0].jsonObject[cfg.compat.reasoningField]!!.jsonPrimitive.content)
    }
    @Test fun `unrepresentable replay metadata is rejected before transport`() = runTest {
        val cfg = OpenAiChatConfig("test", "https://example.invalid/" + "p".repeat(65_536),
            compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content", replayReasoning = true))
        val fake = Fake(sse(chunk(finish = "stop")).toByteArray())
        assertFailure(OpenAiChatProvider(cfg, fake).chat(request(), auth).toList(), ModelErrorCode.INVALID_REQUEST)
        assertEquals(0, fake.requests)
    }
    @Test fun `downstream failure is propagated without synthetic Failed`() = runTest {
        val fake = Fake(sse(chunk(text("ok")), chunk(finish = "stop")).toByteArray())
        val expected = IllegalStateException("consumer failure")
        val actual = assertFailsWith<IllegalStateException> {
            OpenAiChatProvider(config, fake).chat(request(), auth).collect { throw expected }
        }
        assertSame(expected, actual); assertEquals(1, fake.closed)
    }
    @Test fun `reasoning replay disabled keeps visible delta without replay`() = runTest {
        val cfg = OpenAiChatConfig("test", "https://example.invalid/v1", compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content"))
        val events = collect(sse(chunk(buildJsonObject { put("reasoning_content", "thinking") }), chunk(finish = "stop")), cfg = cfg)
        assertTrue(events.any { it is ModelEvent.ReasoningDelta }); assertTrue(events.filterIsInstance<ModelEvent.PartEnd>().all { it.replay == null })
    }
    @Test fun `stream errors and invalid JSON each have single failed terminal`() = runTest {
        assertFailure(collect(sse("{invalid", done = false)))
        assertFailure(collect(sse("""{"error":{"code":"insufficient_quota","message":"ignored"}}""", done = false)), ModelErrorCode.QUOTA)
    }
    @Test fun `EOF DONE without finish and duplicate terminals fail`() = runTest {
        assertFailure(collect(sse(chunk(text("partial")), done = false)))
        assertFailure(collect(sse(chunk(finish = "stop"), done = false)))
        assertFailure(collect("data: [DONE]\n\n"))
        assertFailure(collect(sse(chunk(finish = "stop")) + "data: [DONE]\n\n"))
        assertFailure(collect(sse(chunk(finish = "stop"), chunk(finish = "stop"))))
    }
    @Test fun `malformed UTF8 and incomplete SSE are protocol failures`() = runTest {
        val fake = Fake("data: ".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\n\n".toByteArray())
        assertFailure(OpenAiChatProvider(config, fake).chat(request(), auth).toList())
        assertFailure(collect("data: {}"))
    }
    @Test fun `cold collection makes one request each without retries`() = runTest {
        val fake = Fake(sse(chunk(text("ok")), chunk(finish = "stop")).toByteArray())
        var tokenReads = 0
        val stream = OpenAiChatProvider(config, fake).chat(request(), Credentials { tokenReads++; "fake" })
        assertEquals(0, fake.requests); assertEquals(0, tokenReads)
        stream.toList(); stream.toList()
        assertEquals(2, fake.requests); assertEquals(2, fake.closed); assertEquals(2, tokenReads)
        val broken = Fake(byteArrayOf(), fail = true)
        assertFailure(OpenAiChatProvider(config, broken).chat(request(), auth).toList(), ModelErrorCode.NETWORK)
        assertEquals(1, broken.requests); assertEquals(1, broken.closed)
    }
    @Test fun `cancellation closes transport and is not Failed`() = runTest {
        val fake = Fake(sse(chunk(text("partial")), done = false).toByteArray(), hang = true)
        val events = mutableListOf<ModelEvent>()
        val job = launch { OpenAiChatProvider(config, fake).chat(request(), auth).collect { events += it } }
        runCurrent(); job.cancelAndJoin()
        assertEquals(1, fake.closed); assertTrue(events.none { it is ModelEvent.Failed })
    }
    @Test fun `HTTP errors map and preserve retry after with no retry`() = runTest {
        for ((status, code) in listOf(401 to ModelErrorCode.AUTH, 429 to ModelErrorCode.RATE_LIMIT, 503 to ModelErrorCode.OVERLOADED, 500 to ModelErrorCode.SERVER)) {
            val fake = Fake("{}".toByteArray(), status = status)
            val events = OpenAiChatProvider(config, fake).chat(request(), auth).toList()
            assertFailure(events, code)
            assertEquals(2000L, assertIs<ModelEvent.Failed>(events.last()).error.retryAfterMs)
            assertEquals(1, fake.requests)
        }
    }
    @Test fun `first timeout uses virtual time and closes transport`() = runTest {
        val fake = Fake(byteArrayOf(), hang = true)
        val events = OpenAiChatProvider(config, fake).chat(request(), auth).validated("p", "r", EventTimeouts(100, 50)).toList()
        assertFailure(events, ModelErrorCode.TIMEOUT); assertEquals(100L, currentTime)
        runCurrent() // Cooperative cleanup is scheduled, not joined by logical timeout.
        assertEquals(1, fake.closed)
    }
    @Test fun `heartbeats reset idle timeout but do not disable it`() = runTest {
        var closed = false
        val source = flow {
            try {
                emit(Events.started(null, null)); delay(40); emit(Events.keepAlive()); delay(40)
                emit(Events.keepAlive()); awaitCancellation()
            } finally { closed = true }
        }
        val events = source.validated("p", "r", EventTimeouts(100, 50)).toList()
        assertFailure(events, ModelErrorCode.TIMEOUT); assertEquals(130L, currentTime)
        runCurrent(); assertTrue(closed)
    }
    @Test fun `timeouts finish before noncooperative source exits and discard late terminal`() = runTest {
        for (started in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            var exited = false
            val source = flow {
                try {
                    if (started) emit(Events.started(null, null))
                    withContext(NonCancellable) { release.await() }
                    emit(Events.finished(FinishReason.STOP))
                } finally { exited = true }
            }
            val events = mutableListOf<ModelEvent>()
            val collection = launch { source.validated("p", "r", EventTimeouts(100, 50)).toList(events) }
            try {
                runCurrent()
                advanceTimeBy(if (started) 50 else 100)
                runCurrent()
                assertTrue(collection.isCompleted, "Timeout must finish collection without joining upstream")
                assertFalse(exited)
                assertFailure(events, ModelErrorCode.TIMEOUT)
            } finally {
                release.complete(Unit)
                runCurrent()
                collection.cancelAndJoin()
            }
            assertTrue(exited)
            assertFailure(events, ModelErrorCode.TIMEOUT)
        }
    }
    @Test fun `collector cancellation does not join noncooperative source or emit Failed`() = runTest {
        val release = CompletableDeferred<Unit>()
        var exited = false
        val events = mutableListOf<ModelEvent>()
        val source = flow {
            try {
                emit(Events.started(null, null))
                withContext(NonCancellable) { release.await() }
            } finally { exited = true }
        }
        val collection = launch { source.validated("p", "r").toList(events) }
        try {
            runCurrent(); collection.cancel(); runCurrent()
            assertTrue(collection.isCompleted)
            assertFalse(exited)
            assertTrue(events.none { it is ModelEvent.Failed })
        } finally {
            release.complete(Unit); runCurrent(); collection.cancelAndJoin()
        }
        assertTrue(exited)
    }
    @Test fun `validator rejects index type terminal and size boundaries`() = runTest {
        val invalid = listOf(
            listOf(Events.started(null, null), Events.text(1, "bad")),
            listOf(Events.started(null, null), Events.text(0, "x"), Events.reasoning(0, "bad")),
            listOf(Events.started(null, null), Events.text(0, "x"), Events.finished(FinishReason.STOP)),
            listOf(Events.started(null, null), Events.finished(FinishReason.STOP), Events.finished(FinishReason.STOP)),
            listOf(Events.started(null, null), Events.text(0, "x".repeat(65536))),
            listOf(Events.started(null, null), Events.toolDelta(0, "{}")),
            listOf(Events.started(null, null))
        )
        invalid.forEach { assertFailure(it.asFlow().validated("p", "r").toList()) }
    }
    @Test fun `validator does not swallow cancellation`() = runTest {
        assertFailsWith<CancellationException> { flow<ModelEvent> { throw CancellationException("cancel") }.validated("p", "r").toList() }
    }
    @Test fun `response identity and tool metadata cannot change midstream`() = runTest {
        assertFailure(collect(sse(chunk(tool(0, "{", "read", "wire")), chunk(tool(0, "}", "other", "wire")), chunk(finish = "tool_calls"))))
        assertFailure(collect(sse(chunk(tool(0, "{", "read", "wire")), chunk(tool(0, "}", id = "new")), chunk(finish = "tool_calls"))))
        assertFailure(collect(sse(chunk(text("first")), chunk(text("second")).replace("response_1", "response_2"), chunk(finish = "stop"))))
    }
    @Test fun `HTTP framing errors do not yield successful terminal`() = runTest {
        for (transportEvents in listOf(
            listOf(TransportEvent.Bytes(byteArrayOf())),
            listOf(TransportEvent.Headers(200, "application/json", null)),
            listOf(TransportEvent.Headers(200, "text/event-stream", null), TransportEvent.Headers(200, "text/event-stream", null))
        )) {
            val transport = ModelTransport { _, _, _ -> transportEvents.asFlow() }
            assertFailure(OpenAiChatProvider(config, transport).chat(request(), auth).toList())
        }
    }
    @Test fun `wire ID collisions reject instead of silently changing replay`() {
        val a = callBinding("test", "r1", "response1", 0, "same")
        val b = callBinding("test", "r2", "response2", 0, "same")
        assertFailsWith<IllegalArgumentException> { OpenAiChatCodec(config).encode(chatRequest("model") {
            messages = listOf(message(Role.ASSISTANT, toolCallPart(a, "read", "{}")), message(Role.TOOL, toolResultPart(a.callId, "a")),
                message(Role.ASSISTANT, toolCallPart(b, "read", "{}")), message(Role.TOOL, toolResultPart(b.callId, "b")))
        }) }
    }
    @Test fun `schema downgrade is explicit and never silently drops constraints`() {
        val cfg = OpenAiChatConfig("test", "https://example.invalid", compat = OpenAiChatConfig.Compat(schemaProfile = SchemaProfile.BASIC))
        assertFailsWith<IllegalArgumentException> { OpenAiChatCodec(cfg).encode(chatRequest("model") {
            tools = listOf(modelTool("read", "Read", Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject))
        }) }
    }
    @Test fun `empty and filtered responses are not replayable`() = runTest {
        assertEquals(HistoryDisposition.DISCARD, reducer(collect(sse(chunk(finish = "stop")))).historyDisposition())
        assertEquals(HistoryDisposition.DISCARD, reducer(collect(sse(chunk(text("filtered")), chunk(finish = "content_filter")))).historyDisposition())
    }
}
