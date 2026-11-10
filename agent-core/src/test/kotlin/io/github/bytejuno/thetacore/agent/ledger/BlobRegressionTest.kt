package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.*

class BlobRegressionTest {
    private fun normalize(parts: List<OutputPart>, structured: JsonElement? = null) = ToolOutputNormalizer.normalize(
        parts, structured, false, Effect.UNKNOWN, null, ArgumentsValidator { _, _ -> true }, "host_blob_read")
    private fun publish(f: Fixture, out: NormalizedOutput, run: String = "run", order: Int = 0): InvocationRecord {
        val c = f.claim(f.ready(f.input(run = run, order = order)))
        return assertIs<Mutation.Applied>(f.ledger.recordToolOutput(c.record.callId, c.ticket!!.attemptId,
            Status.SUCCEEDED, out, Effect.UNKNOWN)).record
    }
    private fun model(f: Fixture, r: InvocationRecord): String = (f.ledger.resultsForStep(r.input.sessionId,
        r.input.runId, r.input.stepId, listOf(r.callId))!!.parts.single() as ContentPart.ToolResult).text

    @Test fun hugeTextAccountingIsBoundedAndMatchesActualEncoder() {
        val units = (0..31).map { it.toChar().toString() } + listOf("😀", "界", "é", "\"", "\\", "\uD800", "\uDC00")
        for (unit in units) {
            val text = unit.repeat(2_000_000)
            val notice = "\n[notice: \u0000 😀 \" \\]"
            val work = ModelResultText.Work()
            val bounded = ModelResultText.boundMeasured(text, notice, work = work)
            assertTrue(work.codeUnits <= ModelResultText.LIMIT + notice.length + 2)
            assertTrue(work.prefixCodeUnits <= ModelResultText.LIMIT)
            assertTrue(bounded.endsWith(notice))
            assertTrue(ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, bounded).toByteArray().size <= 32768)
            // Independent reference uses actual JSON serialization, only on a bounded candidate.
            val canonicalUnit = String(unit.toByteArray(), Charsets.UTF_8)
            val prefix = bounded.removeSuffix(notice)
            assertTrue(ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, prefix + canonicalUnit + notice).toByteArray().size > 32768)
            assertEquals(unit, ModelResultText.bound(unit, notice)) // no truncation: retain lone surrogate
        }
        println("MODEL_BOUND_WORK cases=${units.size} inputUnits=2000000 maxScanned=LIMIT+notice+2 maxPrefix=LIMIT")
    }

    @Test fun damagedContentDoesNotBlockExitOrColdRecovery() {
        for (damage in listOf("missing", "corrupt", "size")) Fixture().use { f ->
            val out = normalize(listOf(OutputPart.Text("large".repeat(10000))))
            val r = publish(f, out)
            damageContent(f.directory, out.stored.raw.contentHash, damage)
            assertFailsWith<StoreException> { f.store.readBlob(out.stored.raw, 0, 8192) }
            assertIs<Mutation.Applied>(f.ledger.recordActualExit(r.callId, r.instance!!.attemptId, "exit"))
            f.reopen()
            assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(r.callId)!!.instance!!.state)
            assertFailsWith<StoreException> { f.store.readBlob(out.stored.raw, 0, 8192) }
        }
    }

    @Test fun indexReplacementCountsSharedAndLateRootsWithoutHistoryWalk() = Fixture().use { f ->
        val out = normalize(listOf(OutputPart.Text("value".repeat(10000))))
        val first = publish(f, out)
        // Shared roots need a second invocation, not a bypass of the live UNKNOWN chain barrier.
        assertIs<Mutation.Applied>(f.ledger.recordActualExit(first.callId, first.instance!!.attemptId, "worker exited"))
        val second = publish(f, out, order = 1)
        val index = BlobReferenceIndex(listOf(first, second))
        val ref = out.stored.raw
        val cleared = first.copy(result = first.result!!.copy(output = null), evidence = emptyList())
        val candidates = index.replace(first, cleared)
        assertTrue(candidates.isNotEmpty())
        assertTrue(index.contains(ref))
        assertEquals(setOf(ref), index.matches("run", ref.id))
        val lateOnly = second.copy(result = second.result!!.copy(output = null))
        assertTrue(index.replace(second, lateOnly).isEmpty())
        assertTrue(index.contains(ref))
        val moved = lateOnly.copy(input = lateOnly.input.copy(runId = "other"))
        index.replace(lateOnly, moved)
        assertTrue(index.matches("run", ref.id).isEmpty())
        assertEquals(setOf(ref), index.matches("other", ref.id))
        index.replace(moved, moved.copy(evidence = emptyList()))
        assertFalse(index.contains(ref))
        assertTrue(index.liveFiles().isEmpty())
        f.reopen()
        assertEquals(ref, f.ledger.readBlob("run", ref.id, 0).first)
    }

    @Test fun uncertainRootReplacementDoesNotCollectFromStaleIndex(): Unit = Fixture().use { f ->
        val out = normalize(listOf(OutputPart.Text("x".repeat(40000))))
        val r = publish(f, out)
        val ref = out.stored.raw
        f.stage = SqliteWriteStage.AFTER_COMMIT
        val error = assertFailsWith<StoreException> {
            f.store.save(r.copy(revision = r.revision + 1, result = r.result!!.copy(output = null),
                evidence = emptyList()), r.revision)
        }
        assertTrue(error.uncertain)
        assertFalse(hasContent(f.directory, ref.contentHash))
        assertFails { f.store.readBlob(ref, 0, 8192) }
        f.reopen()
        assertFalse(hasContent(f.directory, ref.contentHash))
        assertFails { f.ledger.readBlob("run", ref.id, 0) }
    }

    @Test fun escapedEnvelopeBoundariesAreMeasuredAfterEncoding() {
        val overhead = ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, "").toByteArray().size
        for ((unit, bytes) in listOf("a" to 1, "\n" to 2, "\u0000" to 6, "😀" to 4, "\"" to 2, "\\" to 2)) {
            val budget = 32768 - overhead
            val text = unit.repeat(budget / bytes) + "a".repeat(budget % bytes)
            assertTrue(ModelResultText.fits(text))
            assertEquals(32768, ModelResultText.encode(Status.TIMED_OUT, Effect.UNKNOWN, text).toByteArray().size)
            assertFalse(ModelResultText.fits(text + "a"))
            assertEquals(text, ModelResultText.bound(text, "[notice]"))
            val bounded = ModelResultText.bound(text + "a", "[notice]")
            assertTrue(bounded.endsWith("[notice]"))
            assertTrue(ModelResultText.fits(bounded))
            assertFalse(bounded.contains('�'))
        }
    }

    @Test fun finalModelEnvelopeIncludesEscapingAndPreservesFullRawOutput() {
        for (text in listOf("a".repeat(32768), "\n".repeat(32768), "\u0000".repeat(32768), "😀".repeat(8192))) Fixture().use { f ->
            val out = normalize(listOf(OutputPart.Text(text)))
            val r = publish(f, out)
            val encoded = model(f, r)
            assertTrue(encoded.toByteArray().size <= 32768)
            val value = Json.parseToJsonElement(encoded).jsonObject
            assertEquals("succeeded", value.getValue("status").jsonPrimitive.content)
            assertEquals("unknown", value.getValue("effect").jsonPrimitive.content)
            val message = value.getValue("message").jsonPrimitive.content
            assertTrue(message.endsWith("not a complete structured value.]"))
            assertTrue(message.contains(out.stored.raw.id)); assertFalse(message.contains('�'))
            val bytes = java.io.ByteArrayOutputStream()
            var offset = 0L
            while (offset < out.stored.raw.sizeBytes) {
                val chunk = f.ledger.readBlob("run", out.stored.raw.id, offset).second
                bytes.write(chunk); offset += chunk.size
            }
            val raw = Json.decodeFromString(RawToolOutput.serializer(), bytes.toString("UTF-8"))
            assertEquals(text, raw.content.single().text)
            f.reopen(); assertEquals(encoded, model(f, r))
        }
    }

    @Test fun structuredMediaAndEveryTerminalEnvelopeStayBoundedWithoutChangingSemantics() {
        val out = normalize(listOf(OutputPart.Text("\u0000".repeat(32768)),
            OutputPart.Media(MediaKind.DOCUMENT, "text/csv", "a,b".toByteArray())), JsonPrimitive("😀".repeat(12000)))
        for (status in Status.entries.filter { it.terminal }) for (effect in Effect.entries) {
            val r = LogicalResult(status, effect, out.text, false, out.stored)
            val encoded = ModelResultText.project(r)
            assertTrue(encoded.toByteArray().size <= 32768)
            val obj = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(status.name.lowercase(), obj.getValue("status").jsonPrimitive.content)
            assertEquals(effect.name.lowercase(), obj.getValue("effect").jsonPrimitive.content)
            assertTrue(obj.getValue("message").jsonPrimitive.content.endsWith("not a complete structured value.]"))
        }
        for (action in listOf("timeout", "cancel", "lost")) Fixture().use { f ->
            val c = f.claim(f.ready())
            when (action) { "timeout" -> f.ledger.timeout(c.record.callId); "cancel" -> f.ledger.cancel(c.record.callId); else -> f.reopen() }
            val before = model(f, c.record)
            assertTrue(before.toByteArray().size <= 32768)
            if (action != "lost") { f.reopen(); assertEquals(before, model(f, c.record)) }
        }
        // Non-output error/legacy paths do not fabricate blob access; retain their original ledger text.
        val original = "\u0000".repeat(32768)
        val legacy = LogicalResult(Status.REJECTED, Effect.NONE, original, false)
        assertTrue(ModelResultText.project(legacy).toByteArray().size <= 32768)
        assertEquals(original, legacy.text)
        assertTrue(ModelResultText.project(legacy).contains("invocation ledger"))
    }

    @Test fun mimeSpecificReferencesShareContentAndSurviveReopenAndRootCleanup() = Fixture().use { f ->
        val bytes = "a,b\n1,2".toByteArray()
        val refs = listOf("text/plain", "text/csv").mapIndexed { index, mime ->
            val out = normalize(listOf(OutputPart.Media(MediaKind.DOCUMENT, mime, bytes)))
            val published = publish(f, out, order = index)
            assertIs<Mutation.Applied>(f.ledger.recordActualExit(published.callId, published.instance!!.attemptId, "worker exited"))
            out.stored.media.single()
        }
        assertNotEquals(refs[0].id, refs[1].id)
        assertEquals(refs[0].contentHash, refs[1].contentHash)
        repeat(2) { pass ->
            if (pass == 1) f.reopen()
            for (ref in refs) {
                val (actual, data) = f.ledger.readBlob("run", ref.id, 0)
                assertEquals(ref, actual); assertContentEquals(bytes, data)
            }
        }
        f.store.records().forEachIndexed { index, r ->
            f.store.save(r.copy(revision = r.revision + 1, result = r.result!!.copy(output = null),
                evidence = r.evidence.map { it.copy(outcome = it.outcome?.copy(output = null)) }), r.revision)
            assertEquals(index == 0, hasContent(f.directory, refs[0].contentHash))
        }
    }

    @Test fun fullDownloadReadsLinearBytesAndSameLengthMutationFailsBeforeReturning(): Unit = Fixture().use { f ->
        val bytes = ByteArray(1024 * 1024 + 37) { (it % 251).toByte() }
        val out = normalize(listOf(OutputPart.Media(MediaKind.DOCUMENT, "application/octet-stream", bytes)))
        publish(f, out)
        val ref = out.stored.media.single()
        f.reopen()
        val before = f.store.blobBytesRead
        val downloaded = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (offset < bytes.size) {
            val chunk = f.ledger.readBlob("run", ref.id, offset).second
            downloaded.write(chunk); offset += chunk.size
        }
        assertContentEquals(bytes, downloaded.toByteArray())
        assertEquals(bytes.size.toLong(), f.store.blobBytesRead - before)
        assertContentEquals(bytes.copyOfRange(8190, 8200), f.store.readBlob(ref, 8190, 10))
        damageContent(f.directory, ref.contentHash, "corrupt", 1)
        assertFailsWith<StoreException> { f.store.readBlob(ref, 8192, 8192) }
        // Once damage is observed, the whole content is unavailable, not a partial normal result.
        assertFailsWith<StoreException> { f.store.readBlob(ref, 0, 8192) }
        f.reopen()
        assertFailsWith<StoreException> { f.store.readBlob(ref, 0, 8192) }
    }

    @Test fun legacyHashOnlyReferencesReadButConflictingMimeFailsExplicitly(): Unit = Fixture().use { f ->
        val bytes = "legacy-content".toByteArray()
        val hash = blobDigest(bytes)
        val first = f.claim(f.ready(f.input(run = "legacy")))
        val second = f.claim(f.ready(f.input(run = "legacy", order = 1)))
        f.ledger.complete(first.record.callId, first.ticket!!.attemptId, Status.SUCCEEDED, "old", Effect.NONE)
        f.ledger.complete(second.record.callId, second.ticket!!.attemptId, Status.SUCCEEDED, "old", Effect.NONE)
        sql(f.directory) { db ->
            db.prepare("INSERT INTO contents(hash,size) VALUES(?,?)").use {
                it.bindText(1, hash); it.bindLong(2, bytes.size.toLong()); it.step()
            }
            db.writeManifest(hash, chunkManifest(bytes))
            db.prepare("INSERT INTO content_chunks(hash,ordinal,bytes) VALUES(?,0,?)").use {
                it.bindText(1, hash); it.bindBlob(2, bytes); it.step()
            }
        }
        val refs = listOf("text/plain", "text/csv").map { mime ->
            // Decode the actual old three-field schema, not a newly constructed v2 reference.
            Json.decodeFromString(BlobRef.serializer(), "{\"id\":\"$hash\",\"mimeType\":\"$mime\",\"sizeBytes\":${bytes.size}}")
        }
        for ((index, r) in f.store.records().withIndex()) {
            f.store.save(r.copy(revision = r.revision + 1,
                result = r.result!!.copy(output = StoredToolOutput(refs[index], emptyList()))), r.revision)
        }
        f.reopen()
        refs.forEach { assertContentEquals(bytes, f.store.readBlob(it, 0, 8192)) }
        val error = assertFailsWith<IllegalArgumentException> { f.ledger.readBlob("legacy", hash, 0) }
        assertTrue(error.message!!.contains("Ambiguous legacy"))
        assertFalse(Files.exists(f.directory.resolve("$hash.chunks")))
        assertFailsWith<IllegalArgumentException> { BlobRef(hash, "text/plain", bytes.size.toLong(), hash, "a".repeat(64)) }
    }

    @Test fun corruptedManifestIsUnavailableAfterReopen(): Unit = Fixture().use { f ->
        val out = normalize(listOf(OutputPart.Text("x".repeat(40000))))
        val record = publish(f, out)
        damageContent(f.directory, out.stored.raw.contentHash, "manifest")
        f.reopen()
        assertNotNull(f.ledger.get(record.callId))
        assertFailsWith<StoreException> { f.store.readBlob(out.stored.raw, 0, 8192) }
    }
}
