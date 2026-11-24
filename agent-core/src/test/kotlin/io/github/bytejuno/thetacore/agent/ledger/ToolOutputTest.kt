package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.*

class ToolOutputTest {
    private val validator = Schema202012Validator()
    private fun output(text: String = "界😀".repeat(12000), parts: List<OutputPart> = listOf(OutputPart.Text(text)),
        structured: JsonElement? = null, schema: String? = null) = ToolOutputNormalizer.normalize(
        parts, structured, false, Effect.APPLIED, schema, ArgumentsValidator(validator::validOutput), "host_blob_read")

    @Test fun utf8BudgetIncludesNoticeAndExactBoundaryDoesNotTruncate() {
        val budget = 32768 - ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, "").toByteArray().size
        for (text in listOf("a".repeat(budget), "界".repeat(budget / 3) + "a".repeat(budget % 3),
            "😀".repeat(budget / 4) + "a".repeat(budget % 4))) {
            assertEquals(text, output(text).text)
            val truncated = output(text + "界")
            assertTrue(truncated.text.toByteArray().size <= 32768)
            assertTrue(truncated.text.contains("Truncated tool result"))
            assertTrue(truncated.text.contains("host_blob_read"))
            assertFalse(truncated.text.contains('�'))
            assertEquals(truncated.stored.raw, output(text + "界").stored.raw)
        }
        assertEquals("界", utf8Prefix("界😀", 6))
        assertEquals("界😀", utf8Prefix("界😀", 7))
    }

    @Test fun structuredValidationAndContentOrderingIncludeLargeValidValues() {
        val parts = listOf(OutputPart.Text("first"), OutputPart.Media(MediaKind.IMAGE, "image/png", byteArrayOf(1, 2)), OutputPart.Text("last"))
        val valid = output(parts = parts, structured = JsonPrimitive(7), schema = "{\"type\":\"integer\"}")
        assertTrue(valid.text.startsWith("[structured JSON]\n7\nfirst\n[IMAGE"))
        assertTrue(valid.text.endsWith("\nlast"))
        assertEquals(1, valid.stored.media.size)
        assertEquals(2, valid.writes.size)
        val invalid = output(parts = parts, structured = JsonPrimitive("wrong"), schema = "{\"type\":\"integer\"}")
        assertFalse(invalid.text.contains("wrong")); assertFalse(invalid.isError)
        assertNotNull(invalid.stored.warning)
        val empty = output(parts = emptyList(), structured = JsonPrimitive("wrong"), schema = "false")
        assertTrue(empty.isError); assertEquals("Tool returned invalid structured output", empty.text)
        val jsonOnly = output(parts = emptyList(), structured = JsonNull)
        assertEquals("[structured JSON]\nnull", jsonOnly.text)
        val large = output(parts = emptyList(), structured = JsonPrimitive("x".repeat(70000)), schema = "{\"type\":\"string\"}")
        assertNull(large.stored.warning); assertTrue(large.text.contains("Truncated"))
        val raw = Json.decodeFromString(RawToolOutput.serializer(), String(large.writes.last().bytes()))
        assertEquals(70000, raw.structured!!.jsonPrimitive.content.length)
    }

    @Test fun durableRawAndMediaSurvivePendingUnrelatedUpdatesReopenAndLateEvidence() = Fixture().use { f ->
        val pending = f.prepare(f.input(definition(source = Source.THIRD_PARTY)))
        assertEquals(Status.WAITING_APPROVAL, pending.state)
        val claimed = f.claim(f.ready(f.input()))
        val ticket = claimed.ticket!!
        val out = output(parts = listOf(OutputPart.Text("x".repeat(100000)), OutputPart.Media(MediaKind.AUDIO, "audio/wav", byteArrayOf(0, -1, 4))))
        f.ledger.timeout(ticket.callId)
        assertIs<Mutation.Applied>(f.ledger.recordToolOutput(ticket.callId, ticket.attemptId, Status.SUCCEEDED, out, Effect.UNKNOWN))
        assertEquals(Status.TIMED_OUT, f.ledger.get(ticket.callId)!!.state)
        val run = claimed.record.input.runId
        assertContentEquals(byteArrayOf(0, -1, 4), f.ledger.readBlob(run, out.stored.media.single().id, 0).second)
        assertFails { f.ledger.readBlob("other", out.stored.raw.id, 0) }
        val recordBytes = payload(f.directory, ticket.callId)
        assertTrue(recordBytes.size < 50000)
        f.ledger.recordActualExit(ticket.callId, ticket.attemptId, "exited")
        assertIs<Mutation.AlreadyApplied>(f.ledger.recordToolOutput(ticket.callId, ticket.attemptId, Status.SUCCEEDED, out, Effect.UNKNOWN))
        f.reopen()
        assertEquals(Status.WAITING_APPROVAL, f.ledger.get(pending.callId)!!.state)
        assertEquals(Status.TIMED_OUT, f.ledger.get(ticket.callId)!!.state)
        assertNotNull(f.ledger.get(ticket.callId)!!.isolation)
        assertContentEquals(out.writes.last().bytes().take(8192).toByteArray(), f.ledger.readBlob(run, out.stored.raw.id, 0).second)
        assertContentEquals(byteArrayOf(0, -1, 4), f.ledger.readBlob(run, out.stored.media.single().id, 0).second)
    }

    @Test fun crashCutpointsNeverCollectCommittedReferencesAndRecoverOrphans() {
        for (stage in SqliteWriteStage.entries) Fixture().use { f ->
            val claim = f.claim(f.ready())
            val t = claim.ticket!!
            val out = output()
            f.stage = stage
            val failure = assertIs<Mutation.StorageFailure>(f.ledger.recordToolOutput(t.callId, t.attemptId, Status.SUCCEEDED, out, Effect.APPLIED))
            val committed = stage == SqliteWriteStage.AFTER_COMMIT
            assertEquals(committed, failure.uncertain)
            if (committed) assertTrue(hasContent(f.directory, out.stored.raw.contentHash))
            f.reopen()
            assertEquals(if (committed) Status.SUCCEEDED else Status.LOST, f.ledger.get(t.callId)!!.state)
            assertEquals(committed, hasContent(f.directory, out.stored.raw.contentHash))
            if (committed) assertTrue(f.ledger.readBlob(claim.record.input.runId, out.stored.raw.id, 0).second.isNotEmpty())
        }
    }

    @Test fun corruptContentIsQuarantinedWithoutRemovingLedgerEvidence() = Fixture().use { f ->
        val c = f.claim(f.ready()); val t = c.ticket!!; val out = output()
        assertIs<Mutation.Applied>(f.ledger.recordToolOutput(t.callId, t.attemptId, Status.SUCCEEDED, out, Effect.UNKNOWN))
        damageContent(f.directory, out.stored.raw.contentHash, "corrupt")
        f.reopen()
        assertEquals(Status.SUCCEEDED, f.ledger.get(t.callId)!!.state)
        assertFailsWith<StoreException> { f.store.readBlob(out.stored.raw, 0, 8192) }
        assertTrue(hasContent(f.directory, out.stored.raw.contentHash))
    }

    @Test fun replacementCollectsOnlyUnreferencedContentAndNeverSharedLiveRoots() = Fixture().use { f ->
        val first = f.claim(f.ready(f.input()))
        val second = f.claim(f.ready(f.input()))
        val out = output()
        for (c in listOf(first, second)) assertIs<Mutation.Applied>(f.ledger.recordToolOutput(
            c.record.callId, c.ticket!!.attemptId, Status.SUCCEEDED, out, Effect.NONE))
        // Exercise the existing revision-CAS replacement API, not an invented deletion API.
        // Production ledger transitions retain all evidence; this is a storage ownership test.
        for ((index, c) in listOf(first, second).withIndex()) {
            val r = f.store.records().single { it.callId == c.record.callId }
            f.store.save(r.copy(revision = r.revision + 1, result = r.result!!.copy(output = null),
                evidence = r.evidence.map { it.copy(outcome = it.outcome?.copy(output = null)) }), r.revision)
            assertEquals(index == 0, hasContent(f.directory, out.stored.raw.contentHash))
        }
    }

    @Test fun concurrentPublicationAndReadsShareWriterOwnership() = Fixture().use { f ->
        val claims = (0..3).map { f.claim(f.ready(f.input())) }
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = claims.map { claim -> thread {
            try {
                start.await()
                val out = output(); val t = claim.ticket!!
                assertIs<Mutation.Applied>(f.ledger.recordToolOutput(t.callId, t.attemptId, Status.SUCCEEDED, out, Effect.NONE))
                repeat(5) { assertTrue(f.ledger.readBlob(claim.record.input.runId, out.stored.raw.id, 0).second.isNotEmpty()) }
            } catch (e: Throwable) { errors += e }
        } }
        assertFails { TestInvocationStore.open(f.directory) }
        start.countDown(); threads.forEach { it.join(10000); assertFalse(it.isAlive) }
        assertTrue(errors.isEmpty(), errors.toString())
        f.reopen()
        sql(f.directory) { assertEquals(1L, it.longValue("SELECT count(*) FROM contents")) }
    }

    @Test fun structuredResultAndLongTextReserveBudgetForAllMediaPlaceholders() {
        val structured = buildJsonObject {
            put("payload", "s".repeat(60_000))
        }
        val media = listOf(
            OutputPart.Media(MediaKind.IMAGE, "image/png", byteArrayOf(1, 2, 3)),
            OutputPart.Media(MediaKind.AUDIO, "audio/wav", byteArrayOf(4, 5, 6)),
            OutputPart.Media(MediaKind.DOCUMENT, "application/pdf", byteArrayOf(7, 8, 9)),
            OutputPart.Media(MediaKind.VIDEO, "video/mp4", byteArrayOf(10, 11, 12)),
        )
        val normalized = output(parts = listOf(OutputPart.Text("summary text")) + media, structured = structured)
        assertEquals(4, normalized.stored.media.size)
        val envelope = ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, normalized.text)
        assertTrue(envelope.toByteArray(Charsets.UTF_8).size <= ModelResultText.LIMIT)
        assertTrue(normalized.text.startsWith("[structured JSON]\n"))
        assertTrue(normalized.text.endsWith(normalized.truncationNotice))
        assertTrue(normalized.text.contains(normalized.stored.raw.id))
        val expectedKinds = listOf("IMAGE", "AUDIO", "DOCUMENT", "VIDEO")
        val expectedMimes = listOf("image/png", "audio/wav", "application/pdf", "video/mp4")
        normalized.stored.media.forEachIndexed { i, ref ->
            val placeholder = "[${expectedKinds[i]}: ${expectedMimes[i]}; blob ${ref.id}; " +
                "Retrieve with host_blob_read(id=\"${ref.id}\", offset=0)]"
            assertTrue(normalized.text.contains(placeholder), "Missing media placeholder #$i")
        }
        val raw = Json.decodeFromString(RawToolOutput.serializer(), String(normalized.writes.last().bytes()))
        assertEquals(60_000, raw.structured!!.jsonObject.getValue("payload").jsonPrimitive.content.length)
        assertEquals(4, raw.content.count { it.blob != null })

        // Long multibyte/escaped text with media also preserves every media placeholder and stays within 32 KiB.
        val escapedLongText = ("界😀\"\\\u0001").repeat(12_000)
        val textWithMedia = output(parts = listOf(OutputPart.Text(escapedLongText)) + media, structured = null)
        val textEnvelope = ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, textWithMedia.text)
        assertTrue(textEnvelope.toByteArray(Charsets.UTF_8).size <= ModelResultText.LIMIT)
        assertFalse(textWithMedia.text.contains('\uFFFD'))
        assertTrue(textWithMedia.text.endsWith(textWithMedia.truncationNotice))
        textWithMedia.stored.media.forEach { ref ->
            assertTrue(textWithMedia.text.contains("blob ${ref.id}"))
        }
    }

    @Test fun unknownEffectWarningPrefixPreservesMediaPlaceholdersWithinEnvelopeLimit() = Fixture().use { f ->
        val media = listOf(
            OutputPart.Media(MediaKind.IMAGE, "image/png", byteArrayOf(11, 22)),
            OutputPart.Media(MediaKind.DOCUMENT, "text/csv", "a,b".toByteArray()),
        )
        val structured = buildJsonObject { put("big", "z".repeat(60_000)) }
        val normalized = ToolOutputNormalizer.normalize(
            media, structured, false, Effect.UNKNOWN, null, ArgumentsValidator { _, _ -> true }, "host_blob_read",
        )
        val claim = f.claim(f.ready(f.input()))
        val applied = assertIs<Mutation.Applied>(
            f.ledger.recordToolOutput(claim.record.callId, claim.ticket!!.attemptId, Status.SUCCEEDED, normalized, Effect.UNKNOWN),
        ).record
        val projected = (f.ledger.resultsForStep(
            applied.input.sessionId, applied.input.runId, applied.input.stepId, listOf(applied.callId),
        )!!.parts.single() as io.github.bytejuno.thetacore.agent.protocol.ContentPart.ToolResult).text
        assertTrue(projected.toByteArray(Charsets.UTF_8).size <= ModelResultText.LIMIT)
        val message = Json.parseToJsonElement(projected).jsonObject.getValue("message").jsonPrimitive.content
        assertTrue(message.startsWith("Side effects are unknown."))
        assertTrue(message.endsWith(normalized.truncationNotice))
        normalized.stored.media.forEach { ref ->
            assertTrue(message.contains("blob ${ref.id}"))
        }
    }

    @Test fun excessiveMediaAttachmentsEmitExplicitOmissionNoticeAndStayWithin32KiB() {
        val totalMedia = 400
        val manyMedia = (0 until totalMedia).map { index ->
            OutputPart.Media(MediaKind.IMAGE, "image/png", "img-$index".toByteArray())
        }
        val structured = buildJsonObject { put("data", "m".repeat(60_000)) }
        val normalized = output(parts = manyMedia, structured = structured)
        assertEquals(totalMedia, normalized.stored.media.size)
        val envelope = ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, normalized.text)
        assertTrue(envelope.toByteArray(Charsets.UTF_8).size <= ModelResultText.LIMIT)
        assertTrue(normalized.text.endsWith(normalized.truncationNotice))
        val shownCount = normalized.stored.media.count { ref -> normalized.text.contains("blob ${ref.id}") }
        assertTrue(shownCount in 1 until totalMedia, "Expected partial media list, got $shownCount of $totalMedia")
        val omittedCount = totalMedia - shownCount
        assertTrue(
            normalized.text.contains("[$omittedCount of $totalMedia media attachment(s) omitted due to 32 KiB limit;"),
            "Missing explicit media omission notice in: ${normalized.text.takeLast(600)}",
        )
        assertTrue(normalized.text.contains("blob ${normalized.stored.raw.id}"))
    }
}
