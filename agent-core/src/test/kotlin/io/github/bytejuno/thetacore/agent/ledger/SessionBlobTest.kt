package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import org.junit.Test
import kotlin.test.*

class SessionBlobTest {
    private fun publish(f: Fixture, session: String = "session", run: String = "source",
        bytes: ByteArray = ByteArray(20001) { (it % 251).toByte() }): Pair<InvocationRecord, BlobRef> {
        val claim = f.claim(f.ready(f.input(session = session, run = run, chain = session)))
        val output = ToolOutputNormalizer.normalize(listOf(OutputPart.Media(MediaKind.DOCUMENT,
            "application/octet-stream", bytes)), null, false, Effect.NONE, null,
            ArgumentsValidator { _, _ -> true }, "host_blob_read")
        val record = assertIs<Mutation.Applied>(f.ledger.recordToolOutput(claim.record.callId,
            claim.ticket!!.attemptId, Status.SUCCEEDED, output, Effect.NONE)).record
        assertIs<Mutation.Applied>(f.ledger.recordActualExit(record.callId, record.instance!!.attemptId, "exit"))
        return f.ledger.get(record.callId)!! to output.stored.media.single()
    }

    @Test fun laterRunReadsFullContentAndOffsetAcrossRestart() = Fixture().use { f ->
        val (_, ref) = publish(f)
        val next = f.input(run = "later")
        f.record(next)
        repeat(2) { pass ->
            if (pass == 1) f.reopen()
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < ref.sizeBytes) bytes.write(f.ledger.readSessionBlob(next.sessionId, ref.id, bytes.size().toLong()).second)
            assertContentEquals(ByteArray(20001) { (it % 251).toByte() }, bytes.toByteArray())
            assertContentEquals(byteArrayOf(), f.ledger.readSessionBlob(next.sessionId, ref.id, ref.sizeBytes).second)
            assertContentEquals(ByteArray(15) { ((8190 + it) % 251).toByte() },
                f.ledger.readSessionBlob(next.sessionId, ref.id, 8190, 15).second)
        }
    }

    @Test fun foreignAndUnknownIdsHaveIdenticalDenialEvenWithInvalidOffset() = Fixture().use { f ->
        val (_, ref) = publish(f)
        val expected = "Result content is unavailable in this session."
        for (offset in listOf(-1L, 0L, Long.MAX_VALUE)) for (id in listOf(ref.id, "f".repeat(64))) {
            assertEquals(expected, assertFailsWith<BlobReadException> {
                f.ledger.readSessionBlob("other-session", id, offset)
            }.message)
        }
        assertTrue(assertFailsWith<BlobReadException> {
            f.ledger.readSessionBlob("session", ref.id, ref.sizeBytes + 1)
        }.message!!.startsWith("Invalid result range"))
    }

    @Test fun deletingSessionCollectsUniqueContentRetainsSharedContentAndSafetyRecords(): Unit = Fixture().use { f ->
        val (first, shared) = publish(f)
        val (_, other) = publish(f, "other", "other-run")
        assertEquals(shared, other)
        val (uniqueRecord, unique) = publish(f, run = "unique", bytes = byteArrayOf(1, 2, 3))
        f.ledger.deleteSession("session")
        assertEquals(first, f.ledger.get(first.callId))
        assertEquals(uniqueRecord, f.ledger.get(uniqueRecord.callId))
        assertTrue(hasContent(f.directory, shared.contentHash))
        assertFalse(hasContent(f.directory, unique.contentHash))
        assertFailsWith<BlobReadException> { f.ledger.readSessionBlob("session", shared.id, 0) }
        assertTrue(f.ledger.readSessionBlob("other", shared.id, 0).second.isNotEmpty())
        f.reopen()
        assertEquals(first, f.ledger.get(first.callId))
        assertFailsWith<BlobReadException> { f.ledger.readSessionBlob("session", shared.id, 0) }
        assertIs<Mutation.Blocked>(f.ledger.recordInvocation(f.input(run = "resurrection")))
        val republish = BlobWrite(byteArrayOf(1, 2, 3), "application/octet-stream")
        assertFailsWith<IllegalArgumentException> { f.store.saveWithBlobs(uniqueRecord.copy(revision = uniqueRecord.revision + 1), uniqueRecord.revision, listOf(republish)) }
        f.ledger.deleteSession("other")
        assertFalse(hasContent(f.directory, shared.contentHash))
        f.ledger.deleteSession("session") // idempotent
    }

    @Test fun deletionRefusesUnconfirmedWorkerAndPreservesUnknownEffectIsolationAndApprovals(): Unit = Fixture().use { f ->
        val input = f.input(definition(Risk.WRITE, resource = "screen"), run = "write")
        val ready = f.approve(f.prepare(input), Decision.ALLOW_ALWAYS)
        val claim = f.claim(ready)
        f.ledger.timeout(ready.callId)
        assertFailsWith<IllegalArgumentException> { f.ledger.deleteSession("session") }
        f.ledger.recordActualExit(ready.callId, claim.ticket!!.attemptId, "exited")
        val before = f.ledger.get(ready.callId)!!
        f.ledger.deleteSession("session")
        f.reopen()
        val after = f.ledger.get(ready.callId)!!
        assertEquals(before, after)
        assertEquals(Effect.UNKNOWN, after.result!!.effect)
        assertEquals("screen", after.isolation!!.resource)
        assertEquals(Decision.ALLOW_ALWAYS, after.receipts.single().decision)
        assertIs<Mutation.Applied>(f.ledger.resolveRecovery(after.callId, RecoveryResolution.VERIFIED_CLEAR))
        f.reopen("epoch-3")
        assertEquals(Effect.UNKNOWN, f.ledger.get(after.callId)!!.result!!.effect)
        assertEquals(RecoveryResolution.VERIFIED_CLEAR, f.ledger.get(after.callId)!!.isolation!!.resolution)
    }

    @Test fun deletionAcknowledgementFailuresRequireReopenAndPreserveEvidence(): Unit {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) Fixture().use { f ->
            val (record, ref) = publish(f)
            f.stage = stage
            assertTrue(assertFailsWith<StoreException> { f.ledger.deleteSession("session") }.uncertain)
            assertIs<Mutation.Blocked>(f.ledger.executionAdmissionFailure())
            f.reopen()
            assertEquals(record, f.ledger.get(record.callId))
            if (stage == SqliteWriteStage.BEFORE_COMMIT) {
                assertTrue(f.ledger.readSessionBlob("session", ref.id, 0).second.isNotEmpty())
                f.ledger.deleteSession("session")
            } else assertFailsWith<BlobReadException> { f.ledger.readSessionBlob("session", ref.id, 0) }
            assertFalse(hasContent(f.directory, ref.contentHash))
        }
    }

    @Test fun historyDeletionRequiresClosedHandleAndCannotBeRecreated(): Unit {
        val root = java.nio.file.Files.createTempDirectory("session-delete-history")
        try {
            AgentDatabase.open(root.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                    val history = SqliteRunHistory.create(db, "session")
                    assertFailsWith<IllegalStateException> { ledger.deleteSession("session") }
                    history.close()
                    ledger.deleteSession("session")
                    assertEquals(0L, db.connection.longValue("SELECT count(*) FROM sessions"))
                    assertEquals(0L, db.connection.longValue("SELECT count(*) FROM history_chunks"))
                    assertFailsWith<IllegalStateException> { SqliteRunHistory.create(db, "session") }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun versionTwoUpgradePreservesContentAndEnablesDeletion(): Unit = Fixture().use { f ->
        val (record, ref) = publish(f)
        f.store.close()
        sql(f.directory) { it.execute("DROP TABLE deleted_sessions"); it.execute("PRAGMA user_version=2") }
        f.reopen()
        assertEquals(record, f.ledger.get(record.callId))
        assertTrue(f.ledger.readSessionBlob("session", ref.id, 0).second.isNotEmpty())
        f.ledger.deleteSession("session")
        f.reopen()
        assertEquals(record, f.ledger.get(record.callId))
        assertFalse(hasContent(f.directory, ref.contentHash))
    }
}
