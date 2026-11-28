package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.protocol.MediaKind
import io.github.bytejuno.thetacore.agent.protocol.BlobRef
import io.github.bytejuno.thetacore.agent.provider.anthropic.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class ToolMediaRecoveryTest {
    private val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

    private fun request(mime: String = "image/png", size: Long = 3): ChatRequest {
        val binding = callBinding("test", "request", "response", 0, "wire")
        return chatRequest("vision") { messages = listOf(
            message(Role.USER, textPart("Look"), mediaPart(MediaKind.IMAGE, blobRef("user", "image/png", 3))),
            message(Role.ASSISTANT, toolCallPart(binding, "screen_capture", "{}")),
            message(Role.TOOL, toolResultPart(binding.callId, listOf(textPart("original error reason"),
                mediaPart(MediaKind.IMAGE, blobRef("screen", mime, size)),
                mediaPart(MediaKind.IMAGE, blobRef("healthy", "image/png", 3))), isError = true)),
            message(Role.USER, textPart("Continue with text"))) }
    }

    @Test fun validGifUsesNativeAnthropicImageAndExplicitResponsesFormatPlaceholder() {
        val source = request("image/gif", gif.size.toLong())
        val reader = MediaContentReader { if (it.id == "screen") gif else byteArrayOf(1, 2, 3) }
        val anthropic = AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"), reader)
        val responses = OpenAiResponsesCodec(OpenAiResponsesConfig("test", "https://example.invalid/v1"), reader)
        assertTrue(anthropic.estimateRequestBytes(source) > 0)
        assertTrue(responses.estimateRequestBytes(source) > 0)
        assertTrue(anthropic.encode(source).contains("image/gif"))
        val encoded = responses.encode(source)
        assertTrue(encoded.contains("unavailable for this model or media format"))
        assertFalse(encoded.contains("data:image/gif"))
        assertTrue(encoded.contains("data:image/png"))
        assertEquals(2, source.messages[2].mediaParts().size)
        // Estimation without a reader must still respect the adapter's format restrictions.
        assertTrue(OpenAiResponsesCodec(OpenAiResponsesConfig("test", "https://example.invalid/v1"))
            .estimateRequestBytes(source) > 0)
    }

    @Test fun missingAttachmentKeepsUserImageSiblingCallIdentityAndOriginalError() {
        val source = request()
        val original = source.messages.map(HistoryCodec::encodeMessage)
        val reader = MediaContentReader { if (it.id == "screen") null else byteArrayOf(1, 2, 3) }
        val codec = AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"), reader)
        val prepared = codec.prepareToolMedia(source)
        val before = source.messages[2].parts.single() as ContentPart.ToolResult
        val after = prepared.messages[2].parts.single() as ContentPart.ToolResult
        assertSame(source.messages.first(), prepared.messages.first())
        assertEquals(before.callId, after.callId)
        assertTrue(after.isError)
        assertTrue(after.text.contains("original error reason"))
        assertTrue(after.text.contains("missing or quarantined"))
        assertTrue(after.text.contains("blob screen"))
        assertEquals("healthy", prepared.messages[2].mediaParts().single().blob.id)
        assertEquals(original, source.messages.map(HistoryCodec::encodeMessage))
        assertTrue(codec.encode(source).contains("missing or quarantined"))
        assertTrue(codec.estimateRequestBytes(source) > 0)
        // User media is never silently removed, even when the same reader cannot find it.
        assertFailsWith<IllegalArgumentException> {
            AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"), MediaContentReader { null }).encode(source)
        }
    }

    @Test fun sqliteReopenAndLaterTextRunDegradeOnlyQuarantinedToolAttachment() = runBlocking {
        for (damage in listOf("missing", "corrupt")) Fixture().use { f ->
            val input = f.input()
            val ticket = f.claim(f.ready(input)).ticket!!
            val output = ToolOutputNormalizer.normalize(listOf(OutputPart.Media(
                io.github.bytejuno.thetacore.agent.ledger.MediaKind.IMAGE, "image/gif", gif)),
                null, false, Effect.NONE, null, ArgumentsValidator { _, _ -> true }, null)
            assertIs<Mutation.Applied>(f.ledger.recordToolOutput(ticket.callId, ticket.attemptId,
                Status.SUCCEEDED, output, Effect.NONE))
            f.ledger.recordActualExit(ticket.callId, ticket.attemptId, "complete")
            val before = f.ledger.get(ticket.callId)!!
            val media = output.stored.media.single()
            val source = listOf(message(Role.USER, textPart("Look")),
                message(Role.ASSISTANT, toolCallPart(input.binding, input.modelName, "{}")),
                f.ledger.resultsForStep("session", input.runId, "step", listOf(ticket.callId))!!,
                message(Role.USER, textPart("Continue with text")))
            val encoded = source.map(HistoryCodec::encodeMessage)
            val dir = Files.createTempDirectory("tool-media-replay")
            try {
                AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                    SqliteRunHistory.create(db, "session").use { it.append(0, source) }
                }
                damageContent(f.directory, media.contentHash, damage)
                repeat(2) { pass ->
                    f.reopen("replay-$pass")
                    val reader = MediaContentReader { blob ->
                        try { f.ledger.readSessionBlob("session", blob.id, 0).second }
                        catch (_: BlobReadException) { null }
                    }
                    val codec = AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"), reader)
                    val native = AnthropicProvider(AnthropicConfig("test", "https://example.invalid/v1"), mediaReader = reader)
                    var sent = 0
                    val provider = object : ModelProvider by native {
                        override fun chat(request: ChatRequest, auth: Credentials) = flow {
                            assertTrue(request.messages.flatMap { it.mediaParts() }.isEmpty())
                            assertTrue(codec.encode(request).contains("missing or quarantined"))
                            sent++
                            answer().forEach { emit(it) }
                        }
                    }
                    AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                        SqliteRunHistory.open(db, "session").use { history ->
                            assertEquals(encoded, history.snapshot().messages.take(source.size).map(HistoryCodec::encodeMessage))
                            val result = AgentRunLoop(RunIdentity("later", "session", "later-$pass"),
                                ModelTarget("test", provider, Credentials { "test" }), chatRequest("vision"), history,
                                LedgerToolExecutor(f.ledger, f.gate, emptyList(), RunFixture().bridge)).run()
                            assertEquals(RunState.COMPLETED, result.state)
                            assertEquals(1, sent)
                            assertEquals(encoded, history.snapshot().messages.take(source.size).map(HistoryCodec::encodeMessage))
                        }
                    }
                    assertEquals(before, f.ledger.get(ticket.callId))
                }
            } finally { dir.toFile().deleteRecursively() }
        }
    }

    @Test fun summaryRetainsUnavailableReasonAndCallIdWithoutDroppingUserOrHealthyMedia() = runBlocking {
        for (responses in listOf(false, true)) {
            val source = request("image/gif", gif.size.toLong())
            val reader = MediaContentReader { if (it.id == "screen") null else byteArrayOf(1, 2, 3) }
            val native = if (responses) OpenAiResponsesProvider(OpenAiResponsesConfig("test", "https://example.invalid/v1"), mediaReader = reader)
                else AnthropicProvider(AnthropicConfig("test", "https://example.invalid/v1"), mediaReader = reader)
            val anthropic = AnthropicCodec(AnthropicConfig("test", "https://example.invalid/v1"), reader)
            val responseCodec = OpenAiResponsesCodec(OpenAiResponsesConfig("test", "https://example.invalid/v1"), reader)
            var sent = 0
            val provider = object : ModelProvider by native {
                override fun chat(request: ChatRequest, auth: Credentials) = flow {
                    val data = (request.messages.single().parts.first() as ContentPart.Text).text
                    assertTrue(data.contains("unavailable"))
                    assertTrue(data.contains("original error reason"))
                    assertTrue(data.contains((source.messages[2].parts.single() as ContentPart.ToolResult).callId))
                    assertEquals(listOf("user", "healthy"), request.messages.single().mediaParts().map { it.blob.id })
                    if (responses) responseCodec.encode(request) else anthropic.encode(request)
                    sent++
                    answer("summary").forEach { emit(it) }
                }
            }
            val dir = Files.createTempDirectory("tool-media-summary")
            try {
                AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                    SqliteRunHistory.create(db, "session").use { history ->
                        history.append(0, source.messages)
                        val original = history.snapshot().messages.map(HistoryCodec::encodeMessage)
                        val model = SummaryModel(ModelTarget("summary", provider, Credentials { "test" }),
                            chatRequest("vision") { toolChoice = ToolChoice.None; maxOutputTokens = 100 },
                            ContextBudget({ if (responses) responseCodec.estimateRequestBytes(it) else anthropic.estimateRequestBytes(it) }, { 100_000 }))
                        assertEquals("summary", ContextSummarizer(model, MemoryRunJournal(), EventTimeouts(1000, 1000),
                            history::createSummarySpool) { true }.compact(history.runSnapshot().messages, "session@1"))
                        assertEquals(1, sent)
                        assertEquals(original, history.snapshot().messages.map(HistoryCodec::encodeMessage))
                    }
                }
            } finally { dir.toFile().deleteRecursively() }
        }
    }
}
