package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import java.nio.file.Files
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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

    @Test fun deletingLastOwnerCollectsChunksEvenWhenContentRowIsMissing(): Unit = Fixture().use { f ->
        val bytes = ByteArray(9000) { (it % 251).toByte() }
        val (_, ref) = publish(f, bytes = bytes)
        publish(f, "second", "second-run", bytes)
        sql(f.directory) { db ->
            db.execute("PRAGMA foreign_keys=OFF")
            db.prepare("DELETE FROM contents WHERE hash=?").use { it.bindText(1, ref.contentHash); it.step() }
        }
        f.reopen()
        f.ledger.deleteSession("session")
        assertFailsWith<BlobReadException> { f.ledger.readSessionBlob("second", ref.id, 0) }
        f.ledger.deleteSession("second")
        val (_, fresh) = publish(f, "third", "third-run", bytes)
        assertEquals(ref, fresh)
        assertContentEquals(bytes.copyOfRange(8192, bytes.size), f.ledger.readSessionBlob("third", ref.id, 8192).second)
    }

    @Test fun deletionRefusesUnconfirmedWorkerAndPreservesUnknownEffectIsolationAndApprovals(): Unit = Fixture().use { f ->
        val input = f.input(definition(Risk.WRITE, resource = "screen"), run = "write")
        val ready = f.approve(f.prepare(input), Decision.ALLOW_ALWAYS)
        val claim = f.claim(ready)
        f.ledger.timeout(ready.callId)
        assertFailsWith<IllegalArgumentException> { f.ledger.deleteSession("session") }
        f.ledger.recordActualExit(ready.callId, claim.ticket!!.attemptId, "exited")
        val before = f.ledger.get(ready.callId)!!
        assertFailsWith<IllegalArgumentException> { f.ledger.deleteSession("session") }
        f.reopen()
        val after = f.ledger.get(ready.callId)!!
        assertEquals(before, after)
        assertEquals(Effect.UNKNOWN, after.result!!.effect)
        assertEquals("screen", after.isolation!!.resource)
        assertEquals(Decision.ALLOW_ALWAYS, after.receipts.single().decision)
        assertIs<Mutation.Applied>(f.ledger.resolveRecovery(after.callId, RecoveryResolution.VERIFIED_CLEAR))
        f.ledger.deleteSession("session")
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

    @Test fun deletingLongUtf8IdLeavesOtherSessionsUsableAfterRestart() {
        val root = Files.createTempDirectory("long-session")
        val file = root.resolve(AgentDatabase.FILE_NAME)
        val longId = "界".repeat(21846) // 65,538 UTF-8 bytes
        try {
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, longId).close()
                SqliteRunHistory.create(db, "other").close()
                SqliteInvocationStore.open(db).use { store ->
                    InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true }).deleteSession(longId)
                }
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                    assertTrue(store.isSessionDeleted(longId))
                    ledger.deleteSession(longId)
                    assertFailsWith<IllegalStateException> { SqliteRunHistory.create(db, longId) }
                    SqliteRunHistory.open(db, "other").use { assertEquals(0L, it.revision()) }
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun onlyBoundLedgerCanDeleteAndReplaysStayBlocked() = Fixture().use { fixture ->
        val input = fixture.input(cancelled = true)
        fixture.record(input)
        assertFailsWith<IllegalStateException> { fixture.store.bindCoordinator() }
        fixture.ledger.deleteSession(input.sessionId)
        assertIs<Mutation.Blocked>(fixture.ledger.recordInvocation(input))
        fixture.reopen()
        assertIs<Mutation.Blocked>(fixture.ledger.recordInvocation(input))
        Unit
    }

    @Test fun ledgerOnlySessionDeletionPreservesDistinctSessionKeys() = Fixture().use { fixture ->
        val target = "session-\uD800"
        val (_, unique) = publish(fixture, target, "source", byteArrayOf(1))
        val others = listOf("session-?", "session-\uFFFD", "session-\uD801")
        val retained = others.mapIndexed { index, id -> publish(fixture, id, "other-$index").second }
        fixture.reopen()
        fixture.ledger.deleteSession(target)
        fixture.reopen()
        assertTrue(fixture.store.isSessionDeleted(target))
        assertFalse(hasContent(fixture.directory, unique.contentHash))
        for ((id, ref) in others.zip(retained)) {
            assertFalse(fixture.store.isSessionDeleted(id))
            assertTrue(fixture.ledger.readSessionBlob(id, ref.id, 0).second.isNotEmpty())
        }
    }

    @Test fun deletionRetriesAbandonedScratchCleanupAfterCommittedInterruption() {
        val root = Files.createTempDirectory("session-scratch-retry")
        val file = root.resolve(AgentDatabase.FILE_NAME)
        try {
            fun abandoned(db: AgentDatabase, id: String) = SqliteRunHistory.create(db, id).use {
                val path = db.summaryDirectory(id).resolve("summary-tmp/theta-summary-123.data")
                Files.write(path, id.toByteArray())
            }
            val (target, other) = AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                val target = abandoned(db, "target")
                val other = abandoned(db, "other")
                SqliteInvocationStore.open(db) { if (it == SqliteWriteStage.AFTER_COMMIT) error("interrupted") }.use { store ->
                    val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                    assertFailsWith<StoreException> { ledger.deleteSession("target") }
                }
                target to other
            }
            assertTrue(Files.exists(target))
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                    assertFailsWith<IllegalStateException> { SqliteRunHistory.open(db, "target") }
                    ledger.deleteSession("target")
                    assertFalse(Files.exists(target))
                    assertContentEquals("other".toByteArray(), Files.readAllBytes(other))
                    ledger.deleteSession("other")
                    assertFalse(Files.exists(other))
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun damagedV3ResultIsIsolatedAcrossRestartWithSafetyEvidenceIntact() {
        for (damage in listOf("missing", "size", "corrupt")) Fixture().use { f ->
            val (_, healthy) = publish(f, session = "other", run = "other-run", bytes = byteArrayOf(1, 2, 3))
            val input = f.input(definition(Risk.WRITE, resource = "screen"), run = "writer")
            val claim = f.claim(f.approve(f.prepare(input), Decision.ALLOW_ALWAYS))
            val output = ToolOutputNormalizer.normalize(listOf(OutputPart.Text("result")), null, false,
                Effect.UNKNOWN, null, ArgumentsValidator { _, _ -> true }, "host_blob_read")
            val id = claim.record.callId
            val attempt = claim.ticket!!.attemptId
            assertIs<Mutation.Applied>(f.ledger.recordToolOutput(id, attempt, Status.SUCCEEDED, output, Effect.UNKNOWN))
            assertIs<Mutation.Applied>(f.ledger.recordActualExit(id, attempt, "exit"))
            val before = f.ledger.get(id)!!
            assertEquals(Effect.UNKNOWN, before.result!!.effect)
            assertEquals("screen", before.isolation!!.resource)
            assertEquals(Decision.ALLOW_ALWAYS, before.receipts.single().decision)
            f.store.close()
            sql(f.directory) {
                it.execute("DROP TABLE document_blobs")
                it.execute("DROP TABLE composer_chunks")
                it.execute("DROP TABLE composer_parts")
                it.execute("DROP TABLE run_catalog_chunks"); it.execute("DROP TABLE run_catalogs")
                it.execute("DROP TABLE private_image_chunks"); it.execute("DROP TABLE private_image_attempts")
                it.execute("DROP TABLE quarantined_contents"); it.execute("PRAGMA user_version=3")
            }
            val ref = output.stored.raw
            damageContent(f.directory, ref.contentHash, damage)
            repeat(2) { pass ->
                f.reopen("after-damage-$pass")
                assertEquals(before, f.ledger.get(id))
                assertEquals("Result content is unavailable: missing or corrupt.", assertFailsWith<BlobReadException> {
                    f.ledger.readSessionBlob(input.sessionId, ref.id, 0)
                }.message)
                assertContentEquals(byteArrayOf(1, 2, 3), f.ledger.readSessionBlob("other", healthy.id, 0).second)
            }
        }
    }

    @Test fun versionTwoUpgradePreservesContentAndEnablesDeletion(): Unit = Fixture().use { f ->
        val (record, ref) = publish(f)
        f.store.close()
        sql(f.directory) {
            it.execute("DROP TABLE document_blobs")
            it.execute("DROP TABLE composer_chunks")
            it.execute("DROP TABLE composer_parts")
            it.execute("DROP TABLE run_catalog_chunks"); it.execute("DROP TABLE run_catalogs")
            it.execute("DROP TABLE private_image_chunks"); it.execute("DROP TABLE private_image_attempts")
            it.execute("DROP TABLE quarantined_contents"); it.execute("DROP TABLE deleted_sessions"); it.execute("PRAGMA user_version=2")
        }
        f.reopen()
        assertEquals(record, f.ledger.get(record.callId))
        assertTrue(f.ledger.readSessionBlob("session", ref.id, 0).second.isNotEmpty())
        f.ledger.deleteSession("session")
        f.reopen()
        assertEquals(record, f.ledger.get(record.callId))
        assertFalse(hasContent(f.directory, ref.contentHash))
    }
}
