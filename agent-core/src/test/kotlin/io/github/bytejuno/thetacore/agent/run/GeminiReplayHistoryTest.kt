package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.gemini.*
import io.github.bytejuno.thetacore.agent.provider.openai.NoAuthCredentials
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class GeminiReplayHistoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val config = GeminiConfig("provider", "https://example.invalid/v1beta")
    private val codec = GeminiCodec(config)
    private val provider = GeminiProvider(config)
    private fun call(index: Int, wire: String, signed: Boolean = false): ContentPart.ToolCall {
        val raw = buildJsonObject {
            putJsonObject("functionCall") { put("id", wire); put("name", "tool"); put("args", JsonObject(emptyMap())) }
            if (signed) put("thoughtSignature", "signature-$index")
        }
        return toolCallPart(callBinding("provider", "request-$index", "response-$index", 0, wire,
            "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"), "tool", "{}",
            if (signed) codec.replay("m", raw) else null)
    }
    private fun history(vararg calls: ContentPart.ToolCall, parallel: Boolean = false): List<Message> =
        listOf(message(Role.USER, textPart("goal"))) + (if (parallel) listOf(
            message(Role.ASSISTANT, *calls), message(Role.TOOL, *calls.map { toolResultPart(it.binding.callId, "done") }.toTypedArray())
        ) else calls.flatMap { listOf(message(Role.ASSISTANT, it), message(Role.TOOL, toolResultPart(it.binding.callId, "done"))) }) +
            message(Role.USER, textPart("continue"))

    private fun encode(messages: List<Message>) = codec.encode(chatRequest("m") { this.messages = messages })
    private fun contents(encoded: String) = Json.parseToJsonElement(encoded).jsonObject.getValue("contents").jsonArray
    private fun ids(encoded: String, type: String) = contents(encoded).flatMap {
        it.jsonObject.getValue("parts").jsonArray.mapNotNull { part ->
            (part.jsonObject[type] as? JsonObject)?.get("id")?.jsonPrimitive?.content
        }
    }

    @Test fun `duplicate IDs pair deterministically without taking later original IDs after cold read`() {
        for (signedFirst in listOf(false, true)) for (parallel in listOf(false, true)) {
            val first = call(1, "reused", signedFirst)
            val second = call(2, "reused")
            val reserved = "call_${second.binding.callId.replace("-", "")}"
            val messages = history(first, second, call(3, reserved), parallel = parallel)
            assertTrue(provider.canReplayMessages(messages, "m"))
            val before = encode(messages)
            val calls = ids(before, "functionCall")
            assertEquals(listOf("reused", "${reserved}_1", reserved), calls)
            assertEquals(calls, ids(before, "functionResponse"))
            assertEquals(before, encode(messages))
            if (signedFirst) {
                val original = Json.parseToJsonElement(first.replay!!.payload).jsonObject.getValue("part")
                assertEquals(original, contents(before)[1].jsonObject.getValue("parts").jsonArray[0])
            }
            val dir = temp.newFolder().toPath()
            TestRunHistory.create(dir, "session", Durability.PROCESS_CRASH_ONLY).use { it.append(0, messages) }
            TestRunHistory.open(dir, Durability.PROCESS_CRASH_ONLY).use {
                assertTrue(provider.canReplayMessages(it.snapshot().messages, "m"))
                assertEquals(before, encode(it.snapshot().messages))
                assertEquals(first.replay?.payload, it.snapshot().messages[1].parts[0].replay?.payload)
            }
            assertEquals("reused", second.binding.wireId)
        }
    }

    @Test fun `signed duplicate enters durable continuation before estimation or transport after cold read`() = runTest {
        for (signedFirst in listOf(false, true)) for (parallel in listOf(false, true)) {
            val first = call(1, "reused", signedFirst)
            val second = call(2, "reused", true)
            val f = RunFixture(listOf(testDefinition()))
            val executor = LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE })
            val prior = RunIdentity("chain", "session", "prior")
            val messages = history(first, second, parallel = parallel).toMutableList()
            messages.indices.filter { messages[it].role == Role.ASSISTANT }.forEach { index ->
                val calls = messages[index].parts.filterIsInstance<ContentPart.ToolCall>().map { call ->
                    object : CollectedCall {
                        override val binding = call.binding
                        override val name = call.name
                        override val argsJson = call.argsJson
                        override val complete = true
                        override val emptyArguments = false
                    }
                }
                executor.register(prior, "step-$index", calls, cancelled = true)
                executor.execute(calls)
                messages[index + 1] = executor.results(prior, "step-$index", calls)
            }
            // Each envelope is individually valid; only the full history reveals the conflict.
            assertTrue(messages.all { provider.canReplayMessage(it, "m") })
            assertFalse(provider.canReplayMessages(messages, "m"))
            assertFailsWith<IllegalArgumentException> { encode(messages) }
            val dir = temp.newFolder().toPath()
            TestRunHistory.create(dir, "session", Durability.PROCESS_CRASH_ONLY).use { it.append(0, messages) }
            TestRunHistory.open(dir, Durability.PROCESS_CRASH_ONLY).use { history ->
                val original = history.snapshot().messages.map(HistoryCodec::encodeMessage)
                val noSend = GeminiProvider(config, ModelTransport { _, _, _ -> error("Incompatible history sent") })
                val summarizer = ScriptProvider(listOf(answer("Prior tools finished")))
                val summary = SummaryModel(ModelTarget("summary", summarizer, NoAuthCredentials), chatRequest("m") {
                    toolChoice = ToolChoice.None; maxOutputTokens = 100
                }, ContextBudget({ 100L }, { 100_000L }))
                val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                    ModelTarget("provider", noSend, Credentials { "key" }), f.request(), history,
                    executor,
                    budget = object : RequestBudget {
                        override fun tokens(request: ChatRequest): Long {
                            assertTrue(noSend.canReplayMessages(request.messages, request.model), "Incompatible history estimated")
                            return codec.estimateRequestBytes(request)
                        }
                        override fun limit(model: String): Long = 100_000
                    }, contextPolicy = ContextPolicy("continue", messages.lastIndex, summary)).run()
                assertEquals(RunState.PAUSED, result.state)
                assertEquals("CONTINUATION", history.loadCheckpoint()!!.kind)
                assertEquals(original, history.snapshot().messages.map(HistoryCodec::encodeMessage))
                assertTrue(f.submitted.isEmpty())
            }
        }
    }

    @Test fun `terminal without parts neither retries nor loses truncated visible history`() = runTest {
        for (reason in listOf("SAFETY", "MAX_TOKENS")) for (textFirst in listOf(false, true)) {
            val f = RunFixture()
            var sends = 0
            val target = GeminiProvider(config, ModelTransport { _, _, _ -> flow {
                sends++
                emit(TransportEvent.Headers(200, "text/event-stream", null))
                if (textFirst) emit(TransportEvent.Bytes("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}\n\n".toByteArray()))
                emit(TransportEvent.Bytes("data: {\"candidates\":[{\"content\":{\"role\":\"model\"},\"finishReason\":\"$reason\"}],\"usageMetadata\":{\"candidatesTokenCount\":3}}\n\n".toByteArray()))
            } })
            val dir = temp.newFolder().toPath()
            TestRunHistory.create(dir, "session", Durability.PROCESS_CRASH_ONLY).use { history ->
                history.append(0, listOf(message(Role.USER, textPart("goal"))))
                val recovery = mutableListOf<RecoveryAction>()
                val result = f.loop(target, history = history, recovery = RecoveryStrategy { action, _, model, request ->
                    recovery += action
                    if (action == RecoveryAction.RETRY) RecoveryProposal(model, request) else null
                }).run()
                assertEquals(if (reason == "SAFETY") RunState.FAILED else RunState.PAUSED, result.state)
                assertEquals(0, result.retries)
                assertEquals(1, sends)
                assertFalse(RecoveryAction.RETRY in recovery)
                assertEquals(3L, f.journal.attempts().single().usage!!.outputTokens)
                assertTrue(f.submitted.isEmpty())
            }
            TestRunHistory.open(dir, Durability.PROCESS_CRASH_ONLY).use { history ->
                val visible = history.snapshot().messages.filter { it.role == Role.ASSISTANT }
                    .flatMap { it.parts }.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
                assertEquals(if (reason == "MAX_TOKENS" && textFirst) "partial" else "", visible)
            }
        }
    }
}
