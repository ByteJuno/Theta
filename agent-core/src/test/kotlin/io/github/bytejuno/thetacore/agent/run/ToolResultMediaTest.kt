package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.AgentDatabase
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.anthropic.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class ToolResultMediaTest {
    private fun messages(userBytes: Long = 3, screenBytes: Long = 3): List<Message> {
        val binding = callBinding("test", "request", "response", 0, "wire")
        return listOf(
            message(Role.USER, textPart("Look"), mediaPart(MediaKind.IMAGE, blobRef("user", "image/png", userBytes))),
            message(Role.ASSISTANT, toolCallPart(binding, "screen_capture", "{}")),
            message(Role.TOOL, toolResultPart(binding.callId, listOf(textPart("[screen capture]"),
                mediaPart(MediaKind.IMAGE, blobRef("screen", "image/jpeg", screenBytes))))),
        )
    }

    @Test fun nativeAdaptersKeepImagesInsideOriginalToolResultAndUnsupportedUsesPlaceholder() {
        val request = chatRequest("vision") { messages = messages() }
        val reader = MediaContentReader { byteArrayOf(1, 2, 3) }
        val anthropic = AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"), reader)
        val wire = Json.parseToJsonElement(anthropic.encode(request)).jsonObject["messages"]!!.jsonArray
        assertEquals(3, wire.size)
        val result = wire.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("wire", result["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("AQID", result["content"]!!.jsonArray.last().jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        val responses = OpenAiResponsesCodec(OpenAiResponsesConfig("test", "https://example.invalid/v1"), reader)
        val input = Json.parseToJsonElement(responses.encode(request)).jsonObject["input"]!!.jsonArray
        assertEquals(3, input.size)
        assertEquals("function_call_output", input.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", input.last().jsonObject["output"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
        val text = toolMediaRequest(request) { false }
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), text.messages.map { it.role })
        assertSame(request.messages.first(), text.messages.first())
        assertTrue((text.messages.last().parts.single() as ContentPart.ToolResult).text.contains("Tool media unavailable"))
        val chat = OpenAiChatCodec(OpenAiChatConfig("test", "https://example.invalid/v1"), reader)
        val chatWire = Json.parseToJsonElement(chat.encode(request)).jsonObject["messages"]!!.jsonArray
        assertEquals(3, chatWire.size)
        assertEquals("tool", chatWire.last().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(chatWire.last().jsonObject["content"]!!.jsonPrimitive.content.contains("Tool media unavailable"))
        assertTrue(anthropic.estimateRequestBytes(request) > anthropic.estimateRequestBytes(text))
        assertTrue(responses.estimateRequestBytes(request) > responses.estimateRequestBytes(text))
    }

    @Test fun combinedUserAndToolImagesPauseBeforeEncodingAndNormalRequestsComplete() = runBlocking {
        for ((userBytes, screenBytes, supported) in listOf(Triple(3_500_000L, 700_000L, true),
            Triple(3L, 700_000L, true), Triple(3_500_000L, 700_000L, false))) {
            io.github.bytejuno.thetacore.agent.ledger.Fixture().use { f ->
            val input = f.input()
            val ticket = f.claim(f.ready(input)).ticket!!
            val output = io.github.bytejuno.thetacore.agent.ledger.ToolOutputNormalizer.normalize(
                listOf(io.github.bytejuno.thetacore.agent.ledger.OutputPart.Media(
                    io.github.bytejuno.thetacore.agent.ledger.MediaKind.IMAGE, "image/jpeg", ByteArray(screenBytes.toInt()))),
                null, false, io.github.bytejuno.thetacore.agent.ledger.Effect.NONE, null,
                io.github.bytejuno.thetacore.agent.ledger.ArgumentsValidator { _, _ -> true }, null)
            assertIs<io.github.bytejuno.thetacore.agent.ledger.Mutation.Applied>(f.ledger.recordToolOutput(
                ticket.callId, ticket.attemptId, io.github.bytejuno.thetacore.agent.ledger.Status.SUCCEEDED,
                output, io.github.bytejuno.thetacore.agent.ledger.Effect.NONE))
            f.ledger.recordActualExit(ticket.callId, ticket.attemptId, "complete")
            val source = listOf(messages(userBytes, screenBytes).first(),
                message(Role.ASSISTANT, toolCallPart(input.binding, input.modelName, "{}")),
                requireNotNull(f.ledger.resultsForStep("session", input.runId, "step", listOf(ticket.callId))))
            val history = MemoryRunHistory(source)
            var encoded = 0
            val provider = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun supportsToolResultMedia(kind: MediaKind, model: String) = supported
                override fun chat(request: ChatRequest, auth: Credentials) = flow {
                    encoded++
                    assertEquals(3, request.messages.size)
                    assertEquals(userBytes, request.messages.first().mediaParts().single().blob.sizeBytes)
                    assertEquals(if (supported) 1 else 0, request.messages.last().mediaParts().size)
                    AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"),
                        MediaContentReader { ByteArray(it.sizeBytes.toInt()) }).encode(request)
                    emit(Events.started("r", "vision")); emit(Events.text(0, "done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
            val fixture = RunFixture()
            val executor = LedgerToolExecutor(f.ledger, f.gate, emptyList(), fixture.bridge)
            val result = AgentRunLoop(RunIdentity("next-chain", "session", "next-run"),
                ModelTarget("provider", provider, Credentials { "test" }), chatRequest("vision"), history, executor).run()
            val over = supported && userBytes == 3_500_000L
            assertEquals(if (over) RunState.PAUSED else RunState.COMPLETED, result.state)
            assertEquals(if (over) 0 else 1, encoded)
            if (over) assertEquals("Request memory budget exceeded; checkpoint required", result.reason)
            assertEquals(source.map(HistoryCodec::encodeMessage), history.snapshot().messages.take(3).map(HistoryCodec::encodeMessage))
            }
        }
    }

    @Test fun toolMediaSurvivesFreezeSqliteReopenAndLegacyTextEncoding() {
        val source = messages()
        validateHistory(source)
        val encoded = source.map(HistoryCodec::encodeMessage)
        assertEquals(encoded, freezeMessages(source).map(HistoryCodec::encodeMessage))
        assertEquals(encoded, encoded.map(HistoryCodec::decodeMessage).map(HistoryCodec::encodeMessage))
        val storedTool = encoded.last()
        val legacyTool = storedTool.copy(parts = storedTool.parts.map { it.copy(content = null) })
        assertTrue(HistoryCodec.agreesWithLedger(legacyTool, storedTool))
        assertFalse(HistoryCodec.agreesWithLedger(legacyTool.copy(parts = legacyTool.parts.map { it.copy(text = "changed") }), storedTool))
        assertFalse(HistoryCodec.agreesWithLedger(storedTool.copy(parts = storedTool.parts.map { it.copy(content = emptyList()) }), storedTool))
        val legacy = message(Role.TOOL, toolResultPart("legacy", "old result"))
        assertNull(HistoryCodec.encodeMessage(legacy).parts.single().content)
        assertEquals(HistoryCodec.encodeMessage(legacy), HistoryCodec.encodeMessage(HistoryCodec.decodeMessage(HistoryCodec.encodeMessage(legacy))))
        val dir = Files.createTempDirectory("tool-media-history")
        try {
            AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").use { it.append(0, source) }
            }
            AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.open(db, "session").use { assertEquals(encoded, it.snapshot().messages.map(HistoryCodec::encodeMessage)) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
