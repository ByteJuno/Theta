package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.gemini.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class GeminiRunTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = GeminiConfig("provider", "https://example.invalid/v1beta")
    private val parallel = """[{"functionCall":{"name":"first","args":{}},"thoughtSignature":"c2ln"},{"functionCall":{"name":"second","args":{}}}]"""
    private fun response(parts: String, reason: String = "STOP") =
        "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":$parts},\"finishReason\":\"$reason\"}]}\n\n"

    @Test fun `native parallel calls persist stable bindings and return one ordered result group`() = runTest {
        val f = RunFixture(listOf(testDefinition("first", true), testDefinition("second", true)))
        var sends = 0
        val provider = GeminiProvider(config, ModelTransport { _, body, _ -> flow {
            if (sends > 0) {
                val contents = Json.parseToJsonElement(body).jsonObject["contents"]!!.jsonArray
                assertEquals(Json.parseToJsonElement(parallel), contents[1].jsonObject["parts"])
                val results = contents[2].jsonObject["parts"]!!.jsonArray
                assertEquals(listOf("first", "second"), results.map { it.jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content })
            }
            emit(TransportEvent.Headers(200, "text/event-stream", null))
            emit(TransportEvent.Bytes(response(if (sends++ == 0) parallel else """[{"text":"done"}]""").toByteArray()))
        } })
        val dir = temp.newFolder().toPath()
        var ids = emptyList<String>(); var signature: String? = null
        TestRunHistory.create(dir, "session", Durability.PROCESS_CRASH_ONLY).use { history ->
            history.append(0, listOf(message(Role.USER, textPart("goal"))))
            assertEquals(RunState.COMPLETED, f.loop(provider, history = history).run().state)
            val calls = history.snapshot().messages[1].parts.filterIsInstance<ContentPart.ToolCall>()
            ids = calls.map { it.binding.callId }; signature = calls[0].replay!!.payload
            assertEquals(2, sends); assertEquals(ids.toSet(), f.submitted.map { it.callId }.toSet())
        }
        TestRunHistory.open(dir, Durability.PROCESS_CRASH_ONLY).use { history ->
            val calls = history.snapshot().messages[1].parts.filterIsInstance<ContentPart.ToolCall>()
            assertEquals(ids, calls.map { it.binding.callId }); assertEquals(signature, calls[0].replay!!.payload)
            assertNull(calls[1].replay)
        }
    }

    @Test fun `truncated complete calls get cancelled results without execution`() = runTest {
        val f = RunFixture(listOf(testDefinition("first"), testDefinition("second")))
        val provider = GeminiProvider(config, ModelTransport { _, _, _ -> flow {
            emit(TransportEvent.Headers(200, "text/event-stream", null))
            emit(TransportEvent.Bytes(response(parallel, "MAX_TOKENS").toByteArray()))
        } })
        assertEquals(RunState.PAUSED, f.loop(provider).run().state)
        assertTrue(f.submitted.isEmpty()); assertEquals(2, f.store.records().size)
        assertTrue(f.store.records().all { it.result?.effect == Effect.NONE })
        validateHistory(f.history.snapshot().messages)
    }

    @Test fun `incompatible model or provider preserves signed history and creates continuation checkpoint`() = runTest {
        val replay = GeminiCodec(config).replay("m", Json.parseToJsonElement("""{"text":"prior","thoughtSignature":"c2ln"}""").jsonObject)
        val noSend = ModelTransport { _, _, _ -> error("Incompatible signed history submitted") }
        for ((i, target) in listOf(
            GeminiProvider(GeminiConfig("other", "https://example.invalid/v1beta"), noSend),
            GeminiProvider(config, noSend),
            OpenAiChatProvider(OpenAiChatConfig("provider", "https://example.invalid/v1"), noSend),
        ).withIndex()) {
            val f = RunFixture()
            TestRunHistory.create(temp.newFolder("switch$i").toPath(), "session", Durability.PROCESS_CRASH_ONLY).use { history ->
                history.append(0, listOf(message(Role.USER, textPart("old")), message(Role.ASSISTANT, textPart("prior", replay)),
                    message(Role.USER, textPart("continue"))))
                val summarizer = ScriptProvider(listOf(answer("Prior answer: prior")))
                val summary = SummaryModel(ModelTarget("summary", summarizer, NoAuthCredentials), chatRequest("m") {
                    toolChoice = ToolChoice.None; maxOutputTokens = 100
                }, ContextBudget({ 100L }, { 100_000L }))
                val result = AgentRunLoop(RunIdentity("chain", "session", "run"), ModelTarget("provider", target, Credentials { "key" }),
                    chatRequest(if (i == 1) "different-model" else "m"), history,
                    LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                    contextPolicy = ContextPolicy("continue", 2, summary)).run()
                assertEquals(RunState.PAUSED, result.state)
                assertEquals("CONTINUATION", history.loadCheckpoint()!!.kind)
                assertEquals(replay.payload, history.snapshot().messages[1].parts[0].replay!!.payload)
            }
        }
    }
}
