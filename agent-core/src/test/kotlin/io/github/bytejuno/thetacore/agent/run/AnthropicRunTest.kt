package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.anthropic.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class AnthropicRunTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = AnthropicConfig("provider", "https://example.invalid/v1")
    private val thought = json("""{"type":"thinking","thinking":"plan","signature":"signed"}""")
    private val hidden = json("""{"type":"redacted_thinking","data":"opaque"}""")
    private val answer = json("""{"type":"text","text":"done"}""")

    @Test fun `native tool loop replays thinking and sends only paired tool results`() = runTest {
        val f = RunFixture(listOf(testDefinition("read"))); var requests = 0
        val call = json("""{"type":"tool_use","id":"toolu_1","name":"read","input":{}}""")
        val transport = ModelTransport { _, body, _ -> flow {
            if (requests > 0) {
                val messages = json(body).getValue("messages").jsonArray
                assertEquals(JsonArray(listOf(thought, hidden, call)), messages[1].jsonObject["content"])
                val results = messages[2].jsonObject.getValue("content").jsonArray
                assertEquals(1, results.size)
                assertEquals("tool_result", results.single().jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals("toolu_1", results.single().jsonObject["tool_use_id"]!!.jsonPrimitive.content)
            }
            val wire = if (requests++ == 0) nativeWire(listOf(thought, hidden, call), "tool_use") else nativeWire(listOf(answer))
            emit(TransportEvent.Headers(200, "text/event-stream", null)); emit(TransportEvent.Bytes(wire.toByteArray()))
        } }
        assertEquals(RunState.COMPLETED, f.loop(AnthropicProvider(config, transport)).run().state)
        assertEquals(2, requests); assertEquals(1, f.submitted.size); assertNotEquals("toolu_1", f.submitted.single().callId)
        validateHistory(f.history.snapshot().messages)
    }

    @Test fun `pause turn preserves complete assistant history and stops at the native continuation budget`() = runTest {
        for (end in listOf(true, false)) {
            var requests = 0; val f = RunFixture()
            val transport = ModelTransport { _, body, _ -> flow {
                if (requests > 0) {
                    val blocks = json(body)["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
                    assertEquals(listOf(thought, hidden), blocks.take(2))
                    assertEquals(JsonObject(answer + ("cache_control" to json("""{"type":"ephemeral"}"""))), blocks[2])
                }
                val reason = if (end && requests > 0) "end_turn" else "pause_turn"; requests++
                emit(TransportEvent.Headers(200, "text/event-stream", null))
                emit(TransportEvent.Bytes(nativeWire(listOf(thought, hidden, answer), reason).toByteArray()))
            } }
            val result = f.loop(AnthropicProvider(config, transport), limits = RunLimits(maxPaused = 1)).run()
            assertEquals(if (end) RunState.COMPLETED else RunState.PAUSED, result.state)
            assertEquals(2, requests)
            if (!end) assertEquals("Server continuation limit", result.reason)
        }
    }

    @Test fun `refusal remains journaled and never replays its thinking to a fallback attempt`() = runTest {
        val f = RunFixture(); var attempts = 0
        val transport = ModelTransport { _, body, _ -> flow {
            assertEquals(1, json(body)["messages"]!!.jsonArray.size)
            val wire = if (attempts++ == 0) nativeWire(listOf(thought, hidden, answer), "refusal") else nativeWire(listOf(answer))
            emit(TransportEvent.Headers(200, "text/event-stream", null)); emit(TransportEvent.Bytes(wire.toByteArray()))
        } }
        val provider = AnthropicProvider(config, transport)
        val result = f.loop(provider, recovery = RecoveryStrategy { _, _, _, request -> RecoveryProposal(ModelTarget("fallback", provider, Credentials { "key" }), request) }).run()
        assertEquals(RunState.COMPLETED, result.state); assertEquals(2, attempts)
        assertEquals(HistoryDisposition.DISCARD, f.journal.attempts().first().disposition)
        assertTrue(f.history.snapshot().messages.flatMap { it.parts }.none { it is ContentPart.Reasoning })
        assertTrue(f.journal.attempts().first().partial.parts.any { it.replay != null })
    }

    @Test fun `both OpenAI protocols require checkpoint summaries in both switching directions`() = runTest {
        val native = AnthropicCodec(config).replay("m", thought)
        val chatConfig = OpenAiChatConfig("provider", "https://example.invalid/v1", compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content", replayReasoning = true))
        val chat = OpenAiChatCodec(chatConfig).replay("m", "old chat thinking")
        val responseConfig = OpenAiResponsesConfig("provider", "https://example.invalid/v1")
        val responses = OpenAiResponsesCodec(responseConfig).replay("m", json("""{"id":"r","type":"reasoning","summary":[],"encrypted_content":"old"}"""))
        val noSend = ModelTransport { _, _, _ -> error("Incompatible history submitted") }
        val cases = listOf(native to OpenAiChatProvider(chatConfig, noSend), native to OpenAiResponsesProvider(responseConfig, noSend),
            chat to AnthropicProvider(config, noSend), responses to AnthropicProvider(config, noSend))
        cases.forEachIndexed { i, (replay, provider) ->
            val f = RunFixture()
            TestRunHistory.create(temp.newFolder("case$i").toPath(), "session", Durability.PROCESS_CRASH_ONLY).use { history ->
                history.append(0, listOf(message(Role.USER, textPart("old")),
                    message(Role.ASSISTANT, reasoningPart("thought", replay), textPart("answer")), message(Role.USER, textPart("continue"))))
                val summarizer = ScriptProvider(listOf(answer("Summary of previous work")))
                val budget = ContextBudget({ 100L }, { 10_000L })
                val summary = SummaryModel(ModelTarget("summary", summarizer, NoAuthCredentials),
                    chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100 }, budget)
                val result = AgentRunLoop(RunIdentity("chain", "session", "run"), ModelTarget("provider", provider, Credentials { "key" }),
                    f.request(), history, LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                    f.journal, budget = budget, contextPolicy = ContextPolicy("continue", 2, summary)).run()
                assertEquals(RunState.PAUSED, result.state)
                val checkpoint = assertNotNull(history.loadCheckpoint()); assertEquals("CONTINUATION", checkpoint.kind)
                assertNotNull(checkpoint.summary); assertNotNull(checkpoint.nextRunId)
                assertEquals(replay.payload, history.snapshot().messages[1].parts[0].replay!!.payload)
                assertTrue(summarizer.requests.isNotEmpty())
            }
        }
    }
}
