package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.*

class UnknownTerminalWarningTest {
    private val warning = "Side effects are unknown. The operation may have taken effect; verify the outcome before retrying."
    private fun projected(f: Fixture, input: InvocationInput): String =
        (f.ledger.resultsForStep(input.sessionId, input.runId, input.stepId, listOf(input.binding.callId))!!
            .parts.single() as ContentPart.ToolResult).text

    @Test fun newUnknownWarningIsStoredOnceAndRawEvidenceSurvivesSQLiteReopen() {
        for (text in listOf("failed", "x".repeat(32700), "\"\\\u0000\n中文😀".repeat(10000))) Fixture().use { f ->
            val input = f.input(definition(Risk.WRITE))
            val claim = f.claim(f.ready(input))
            val id = claim.record.callId
            val attempt = claim.ticket!!.attemptId
            f.ledger.complete(id, attempt, Status.FAILED, text)
            val original = f.ledger.get(id)!!
            assertEquals("$warning\n$text", original.result!!.text)
            assertEquals(text, original.evidence.single().text)
            assertEquals(text, original.evidence.single().outcome!!.text)
            val sent = projected(f, input)
            assertTrue(sent.contains(warning))
            assertTrue(sent.toByteArray().size <= ModelResultText.LIMIT)
            assertEquals(LegacyModelResultTextFixture.project(original.result), sent)
            assertIs<Mutation.AlreadyApplied>(f.ledger.complete(id, attempt, Status.FAILED, text))
            f.ledger.complete(id, attempt, Status.FAILED, "late unknown", Effect.UNKNOWN)
            assertEquals(original.result, f.ledger.get(id)!!.result)
            f.reopen()
            assertEquals(original.result, f.ledger.get(id)!!.result)
            assertEquals(sent, projected(f, input))
            assertEquals(1, f.ledger.get(id)!!.result!!.text.split(warning).size - 1)
        }
    }

    @Test fun normalizedUnknownKeepsWarningRetrievalSuffixAndFullRawBlob() {
        for (text in listOf("failed", "x".repeat(32700), "\"\\\u0000\n中文😀".repeat(10000))) Fixture().use { f ->
            val input = f.input(definition(Risk.WRITE))
            val claim = f.claim(f.ready(input))
            val output = ToolOutputNormalizer.normalize(listOf(OutputPart.Text(text)), null, true, null,
                null, ArgumentsValidator { _, _ -> true }, "retrieve")
            val id = claim.record.callId
            val attempt = claim.ticket!!.attemptId
            f.ledger.recordToolOutput(id, attempt, Status.FAILED, output, null)
            f.ledger.recordActualExit(id, attempt, "exit")
            val original = f.ledger.get(id)!!.result!!
            val sent = projected(f, input)
            assertTrue(original.text.startsWith(warning))
            assertTrue(sent.contains(warning))
            assertTrue(sent.toByteArray().size <= ModelResultText.LIMIT)
            if (text.length > 32000) {
                assertTrue(sent.contains(output.stored.raw.id))
                assertTrue(sent.contains("Retrieve with retrieve"))
            }
            assertIs<Mutation.AlreadyApplied>(f.ledger.recordToolOutput(id, attempt, Status.FAILED, output, null))
            f.reopen()
            assertEquals(original, f.ledger.get(id)!!.result)
            assertEquals(sent, projected(f, input))
            val bytes = ByteArrayOutputStream()
            while (bytes.size().toLong() < output.stored.raw.sizeBytes) {
                bytes.write(f.ledger.readBlob(input.runId, output.stored.raw.id, bytes.size().toLong()).second)
            }
            val raw = Json.decodeFromString(RawToolOutput.serializer(), bytes.toString(Charsets.UTF_8.name()))
            assertEquals(text, raw.content.single().text)
        }
    }

    @Test fun localTimeoutCancellationAndColdLossWarnOnlyAtCreation() {
        for (kind in listOf("timeout", "cancel", "lost")) Fixture().use { f ->
            val input = f.input(definition(Risk.WRITE))
            val claim = f.claim(f.ready(input))
            when (kind) {
                "timeout" -> f.ledger.timeout(claim.record.callId)
                "cancel" -> f.ledger.cancel(claim.record.callId)
                else -> f.reopen()
            }
            val result = f.ledger.get(claim.record.callId)!!.result!!
            assertEquals(Effect.UNKNOWN, result.effect)
            assertTrue(result.text.startsWith(warning))
            assertEquals(1, result.text.split(warning).size - 1)
            f.reopen("final-$kind")
            assertEquals(result, f.ledger.get(claim.record.callId)!!.result)
        }
    }
}
