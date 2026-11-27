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
    @Test fun `native tool loop executes once and returns call id with original reasoning`() = runTest {
        val f = RunFixture(listOf(testDefinition("read")))
        val reasoning = Json.parseToJsonElement("""{"id":"rs","type":"reasoning","summary":[],"encrypted_content":"opaque"}""").jsonObject
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
