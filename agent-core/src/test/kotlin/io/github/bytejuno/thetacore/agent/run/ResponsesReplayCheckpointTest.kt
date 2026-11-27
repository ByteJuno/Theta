package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ResponsesReplayCheckpointTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `contradictory argument done never reaches tool execution or successful history`() = runTest {
        val f = RunFixture(listOf(testDefinition("read")))
        val transport = ModelTransport { _, _, _ -> flow {
            val call = Json.parseToJsonElement("""{"id":"fc","type":"function_call","call_id":"wire","name":"read","arguments":"{\"q\":\"B\"}"}""").jsonObject
            val frames = listOf(
                """{"type":"response.created","response":{"id":"r","model":"m"}}""",
                """{"type":"response.output_item.added","output_index":0,"item":${JsonObject(call + ("arguments" to JsonPrimitive("")))}}""",
                """{"type":"response.function_call_arguments.done","output_index":0,"item_id":"fc","arguments":"{\"q\":\"A\"}"}""",
                """{"type":"response.output_item.done","output_index":0,"item":$call}""",
                """{"type":"response.completed","response":{"id":"r","model":"m","status":"completed","output":[$call]}}""",
            )
            emit(TransportEvent.Headers(200, "text/event-stream", null))
            emit(TransportEvent.Bytes(frames.joinToString("") { "data: $it\n\n" }.toByteArray()))
        } }
        val result = f.loop(OpenAiResponsesProvider(OpenAiResponsesConfig("provider", "https://example.invalid/v1"), transport),
            limits = RunLimits(maxRetries = 0)).run()
        assertEquals(RunState.FAILED, result.state)
        assertTrue(f.submitted.isEmpty())
        assertTrue(f.store.records().isEmpty())
        assertEquals(1, f.history.snapshot().messages.size)
        assertEquals(ModelErrorCode.PROTOCOL, assertIs<ModelEvent.Failed>(f.journal.attempts().single().terminal).error.code)
    }

    @Test fun `Responses schema errors recover through the existing responseSchema policy for HTTP and SSE`() = runTest {
        for (param in listOf("response_format", "text", "text.format", "text.format.type", "text.format.schema")) {
            for (channel in listOf("http", "error", "failed")) {
                val f = RunFixture(); var requests = 0; var recoveries = 0
                val transport = ModelTransport { _, body, _ -> flow {
                    val request = Json.parseToJsonElement(body).jsonObject
                    if (requests++ == 0) {
                        assertNotNull(request["text"])
                        val error = buildJsonObject { put("code", "unsupported_parameter"); put("param", param); put("message", "fake") }
                        emit(TransportEvent.Headers(if (channel == "http") 400 else 200,
                            if (channel == "http") "application/json" else "text/event-stream", null))
                        val payload = when (channel) {
                            "http" -> "{\"error\":$error}"
                            "error" -> "data: ${JsonObject(error + ("type" to JsonPrimitive("error")))}\n\n"
                            else -> "data: {\"type\":\"response.failed\",\"response\":{\"id\":\"r\",\"model\":\"m\",\"status\":\"failed\",\"error\":$error}}\n\n"
                        }
                        emit(TransportEvent.Bytes(payload.toByteArray()))
                    } else {
                        assertEquals(2, requests); assertNull(request["text"])
                        val answer = """{"id":"msg","type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}"""
                        val frames = listOf("""{"type":"response.created","response":{"id":"r","model":"m"}}""",
                            """{"type":"response.output_item.added","output_index":0,"item":$answer}""",
                            """{"type":"response.completed","response":{"id":"r","model":"m","status":"completed","output":[$answer]}}""")
                        emit(TransportEvent.Headers(200, "text/event-stream", null))
                        emit(TransportEvent.Bytes(frames.joinToString("") { "data: $it\n\n" }.toByteArray()))
                    }
                } }
                val base = f.request()
                val request = object : ChatRequest by base { override val responseSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject }
                val recovery = RecoveryStrategy { action, error, target, original ->
                    assertEquals(RecoveryAction.REMOVE_PARAMETER, action); assertEquals("response_format", error!!.param); recoveries++
                    RecoveryProposal(target, object : ChatRequest by original { override val responseSchema: JsonObject? = null })
                }
                val result = f.loop(OpenAiResponsesProvider(OpenAiResponsesConfig("provider", "https://example.invalid/v1"), transport),
                    recovery, request = request).run()
                assertEquals(RunState.COMPLETED, result.state, "$param/$channel")
                assertEquals(2, requests); assertEquals(1, recoveries)
            }
        }
    }
    @Test fun `native tool loop executes once and returns call id with original reasoning`() = runTest {
        val f = RunFixture(listOf(testDefinition("read")))
        val reasoning = Json.parseToJsonElement("""{"id":"rs","type":"reasoning","content":[{"type":"reasoning_text","text":"raw plan"}],"summary":[{"type":"summary_text","text":"summary plan"}],"encrypted_content":"opaque","metadata":{"retain":true}}""").jsonObject
        val call = Json.parseToJsonElement("""{"id":"fc","type":"function_call","call_id":"wire","name":"read","arguments":"{}"}""").jsonObject
        val answer = Json.parseToJsonElement("""{"id":"msg","type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}""").jsonObject
        var submissions = 0
        val transport = ModelTransport { _, body, _ -> flow {
            val input = Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray
            if (submissions > 0) {
                assertEquals(reasoning, input[1])
                assertEquals("wire", input[2].jsonObject.getValue("call_id").jsonPrimitive.content)
                assertEquals("wire", input[3].jsonObject.getValue("call_id").jsonPrimitive.content)
                assertEquals("function_call_output", input[3].jsonObject.getValue("type").jsonPrimitive.content)
                val result = Json.parseToJsonElement(input[3].jsonObject.getValue("output").jsonPrimitive.content).jsonObject
                assertEquals("read", result.getValue("message").jsonPrimitive.content)
                assertEquals("succeeded", result.getValue("status").jsonPrimitive.content)
                assertEquals("none", result.getValue("effect").jsonPrimitive.content)
            }
            val output = if (submissions++ == 0) listOf(reasoning, call) else listOf(answer)
            fun event(type: String, fields: JsonObjectBuilder.() -> Unit) = "data: " + buildJsonObject { put("type", type); fields() } + "\n\n"
            val stream = buildString {
                append(event("response.created") { putJsonObject("response") { put("id", "r$submissions"); put("model", "m") } })
                output.forEachIndexed { i, item ->
                    append(event("response.output_item.added") { put("output_index", i); put("item", item) })
                    if (item == reasoning) {
                        append(event("response.reasoning_text.delta") {
                            put("output_index", i); put("item_id", "rs"); put("content_index", 0); put("delta", "raw ")
                        })
                        append(event("response.reasoning_summary_text.delta") {
                            put("output_index", i); put("item_id", "rs"); put("summary_index", 0); put("delta", "summary ")
                        })
                    }
                    append(event("response.output_item.done") { put("output_index", i); put("item", item) })
                }
                append(event("response.completed") { putJsonObject("response") {
                    put("id", "r$submissions"); put("model", "m"); put("status", "completed"); put("output", JsonArray(output))
                } })
            }
            emit(TransportEvent.Headers(200, "text/event-stream", null)); emit(TransportEvent.Bytes(stream.toByteArray()))
        } }
        val result = f.loop(OpenAiResponsesProvider(OpenAiResponsesConfig("provider", "https://example.invalid/v1"), transport)).run()
        assertEquals(RunState.COMPLETED, result.state)
        assertEquals(2, submissions); assertEquals(1, f.submitted.size)
        assertNotEquals("wire", f.submitted.single().callId)
        assertEquals("done", assertIs<ContentPart.Text>(f.history.snapshot().messages.last().parts.single()).text)
        val savedReason = f.history.snapshot().messages.flatMap { it.parts }.filterIsInstance<ContentPart.Reasoning>().single()
        assertEquals("raw plan", savedReason.summary)
        assertEquals(reasoning, Json.parseToJsonElement(assertNotNull(savedReason.replay).payload).jsonObject.getValue("item"))
    }

    @Test fun `switching either protocol retains original replay and offers explicit summary continuation`() = runTest {
        val endpoint = "https://example.invalid/v1"
        val native = OpenAiResponsesConfig("provider", endpoint)
        val chat = OpenAiChatConfig("provider", endpoint, compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content", replayReasoning = true))
        val nativeReplay = OpenAiResponsesCodec(native).replay("m", buildJsonObject {
            put("id", "reasoning_1"); put("type", "reasoning"); put("encrypted_content", "retain-exactly")
            putJsonArray("summary") { addJsonObject { put("type", "summary_text"); put("text", "previous reasoning") } }
        })
        val chatReplay = OpenAiChatCodec(chat).replay("m", "retain chat reasoning")
        val noSubmit = ModelTransport { _, _, _ -> error("Incompatible history must never reach transport") }
        for ((name, replay, provider) in listOf(
            Triple("native-to-chat", nativeReplay, OpenAiChatProvider(chat, noSubmit)),
            Triple("chat-to-native", chatReplay, OpenAiResponsesProvider(native, noSubmit)),
        )) {
            val f = RunFixture()
            TestRunHistory.create(temp.newFolder(name).toPath(), "session", Durability.PROCESS_CRASH_ONLY).use { history ->
                history.append(0, listOf(message(Role.USER, textPart("old goal")),
                    message(Role.ASSISTANT, reasoningPart("previous reasoning", replay), textPart("old answer")),
                    message(Role.USER, textPart("continue"))))
                val summarizer = ScriptProvider(listOf(answer("Previous answer retained; continue the goal")))
                val budget = ContextBudget({ 100L }, { 10_000L })
                val summary = SummaryModel(ModelTarget("summary", summarizer, NoAuthCredentials),
                    chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100 }, budget)
                val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                    ModelTarget("provider", provider, NoAuthCredentials), f.request(), history,
                    LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                    f.journal, budget = budget, contextPolicy = ContextPolicy("continue", 2, summary)).run()
                assertEquals(RunState.PAUSED, result.state)
                val checkpoint = assertNotNull(history.loadCheckpoint())
                assertEquals("CONTINUATION", checkpoint.kind)
                assertNotNull(checkpoint.summary); assertNotNull(checkpoint.nextRunId)
                assertTrue(checkpoint.reason.contains("Replay incompatible"))
                assertEquals(replay.payload, history.snapshot().messages[1].parts[0].replay!!.payload)
                assertTrue(summarizer.requests.isNotEmpty())
                assertTrue(summarizer.requests.all { r -> r.messages.flatMap { it.parts }.none { it.replay != null } })
            }
        }
    }
}
