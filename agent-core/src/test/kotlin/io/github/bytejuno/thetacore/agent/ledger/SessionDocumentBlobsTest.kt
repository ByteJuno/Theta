package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class SessionDocumentBlobsTest {
    @Test fun atomicallyStoresFullTextAndChecksOwnershipRangesRestartAndDeletion() {
        val root = Files.createTempDirectory("document-store")
        val file = root.resolve("agent.db")
        val text = "文档abcdef\n".repeat(18000)
        var id = ""
        try {
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                SqliteRunHistory.create(db, "other").close()
                val blobs = SessionDocumentBlobs(db)
                id = blobs.put("session", text).id
                assertEquals(id, blobs.put("session", text).id)
                assertNull(blobs.read("other", id, -1))
                assertNull(blobs.read("session", "f".repeat(64), -1))
                assertFailsWith<BlobReadException> { blobs.read("session", id, -1) }
                val bytes = text.toByteArray()
                assertContentEquals(bytes.copyOfRange(8190, 8205), blobs.read("session", id, 8190, 15)!!.second)
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                val store = SqliteInvocationStore.open(db)
                val blobs = SessionDocumentBlobs(db)
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() < text.toByteArray().size) bytes.write(store.readDocumentBlob("session", id, bytes.size().toLong(), 8192)!!.second)
                assertEquals(text, bytes.toString(Charsets.UTF_8.name()))
                assertEquals(0, blobs.read("session", id, bytes.size().toLong())!!.second.size)
                val shared = blobs.put("other", text)
                val delete = requireNotNull(store.bindCoordinator())
                delete("session")
                assertNull(blobs.read("session", id, 0))
                assertTrue(blobs.read("other", shared.id, 0)!!.second.isNotEmpty())
                delete("other")
                assertEquals(0L, db.connection.longValue("SELECT count(*) FROM contents"))
                assertEquals(0L, db.connection.longValue("SELECT count(*) FROM content_chunks"))
                store.close()
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun rejectsCorruptContentWithoutReturningBytes() {
        val root = Files.createTempDirectory("document-corrupt")
        try {
            AgentDatabase.open(root.resolve("agent.db"), BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                val blobs = SessionDocumentBlobs(db)
                val ref = blobs.put("session", "valid text")
                db.connection.prepare("UPDATE content_chunks SET bytes=? WHERE hash=?").use {
                    it.bindBlob(1, "bad".toByteArray()); it.bindText(2, ref.contentHash); it.step()
                }
                assertFailsWith<StoreException> { blobs.read("session", ref.id, 0) }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun observedDamageQuarantinesEveryOffsetAndSurvivesRestart() {
        val root = Files.createTempDirectory("document-quarantine")
        val file = root.resolve("agent.db")
        val text = "a".repeat(BLOB_CHUNK_SIZE * 2 + 1)
        var id = ""
        try {
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                val blobs = SessionDocumentBlobs(db)
                val ref = blobs.put("session", text); id = ref.id
                db.connection.prepare("UPDATE content_chunks SET bytes=? WHERE hash=? AND ordinal=1").use {
                    it.bindBlob(1, ByteArray(BLOB_CHUNK_SIZE)); it.bindText(2, ref.contentHash); it.step()
                }
                assertFailsWith<StoreException> { blobs.read("session", id, BLOB_CHUNK_SIZE.toLong()) }
                assertFailsWith<StoreException> { blobs.read("session", id, 0) }
                assertEquals(1L, db.connection.longValue("SELECT count(*) FROM quarantined_contents"))
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                val blobs = SessionDocumentBlobs(db)
                assertFailsWith<StoreException> { blobs.read("session", id, 0) }
                assertFailsWith<StoreException> { blobs.read("session", id, text.length.toLong()) }
                assertFailsWith<StoreException> { blobs.put("session", text) }
                assertNotNull(blobs.put("session", "a different healthy document"))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun republicationRejectsDamageWithoutDisablingUnrelatedSessions() {
        val root = Files.createTempDirectory("document-republish")
        try {
            AgentDatabase.open(root.resolve("agent.db"), BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                SqliteRunHistory.create(db, "other").close()
                val blobs = SessionDocumentBlobs(db)
                val ref = blobs.put("session", "shared document")
                db.connection.execute("UPDATE content_chunks SET bytes=x'00'")
                repeat(2) {
                    val error = assertFailsWith<StoreException> { blobs.put("other", "shared document") }
                    assertFalse(error.uncertain)
                    SessionComposerStore(db).save("other", ComposerSnapshot("v$it", "still usable", emptyList()), emptyList())
                    assertEquals("still usable", SessionComposerStore(db).read("other").text)
                    assertNull(blobs.read("other", ref.id, 0))
                }
                assertEquals(1L, db.connection.longValue("SELECT count(*) FROM quarantined_contents"))
                assertNotNull(blobs.put("other", "healthy"))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun uncertainPublicationAndQuarantineStillCloseAdmission() {
        for (quarantine in listOf(false, true)) {
            val root = Files.createTempDirectory("document-commit")
            var failCommit = false
            val driver = object : androidx.sqlite.SQLiteDriver by BundledSQLiteDriver() {
                override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
                    val connection = BundledSQLiteDriver().open(fileName)
                    return object : androidx.sqlite.SQLiteConnection by connection {
                        override fun prepare(sql: String): androidx.sqlite.SQLiteStatement {
                            val statement = connection.prepare(sql)
                            return object : androidx.sqlite.SQLiteStatement by statement {
                                override fun step(): Boolean {
                                    val result = statement.step()
                                    if (failCommit && sql == "COMMIT") throw IllegalStateException("lost commit acknowledgement")
                                    return result
                                }
                            }
                        }
                    }
                }
            }
            try {
                AgentDatabase.open(root.resolve("agent.db"), driver).use { db ->
                    SqliteRunHistory.create(db, "session").close()
                    val blobs = SessionDocumentBlobs(db)
                    val ref = if (quarantine) blobs.put("session", "corrupt me") else null
                    if (quarantine) db.connection.execute("UPDATE content_chunks SET bytes=x'00'")
                    failCommit = true
                    assertFails { if (ref != null) blobs.read("session", ref.id, 0) else blobs.put("session", "new") }
                    assertTrue(assertFailsWith<StoreException> { db.sessionIds() }.uncertain)
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun toolPublicationQuarantinesDocumentOwnedDamageBeforeItsTransaction() {
        val root = Files.createTempDirectory("document-tool-quarantine")
        val file = root.resolve("agent.db")
        var id = ""
        try {
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "document-session").close()
                SqliteRunHistory.create(db, "other").close()
                val text = "a".repeat(BLOB_CHUNK_SIZE * 2 + 1)
                val documents = SessionDocumentBlobs(db)
                val ref = documents.put("document-session", text)
                id = ref.id
                SqliteInvocationStore.open(db).use { store ->
                    val gate = DefinitionGate()
                    val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                    ledger.recoverColdStart("epoch")
                    val def = definition()
                    gate.install(def); gate.freezeRun("run", listOf(def))
                    val input = InvocationInput(BindingValue.copyOf(callBinding("provider", "request", "response", 0, "wire")),
                        "chain", "other", "run", "step", 0, "{}", def, false, Risk.READ, false)
                    val created = assertIs<Mutation.Applied>(ledger.recordInvocation(input)).record
                    var ready = assertIs<Mutation.Applied>(ledger.validateAndPrepare(created.callId)).record
                    if (ready.pending != null) ready = assertIs<Mutation.Applied>(
                        ledger.resolveApproval(ready.callId, ready.pending!!, Decision.ALLOW_ONCE)).record
                    val claim = assertIs<Mutation.Applied>(ledger.claimExecution(ready.callId, ready.revision))
                    db.connection.prepare("UPDATE content_chunks SET bytes=? WHERE hash=? AND ordinal=1").use {
                        it.bindBlob(1, ByteArray(BLOB_CHUNK_SIZE)); it.bindText(2, ref.contentHash); it.step()
                    }
                    val output = ToolOutputNormalizer.normalize(listOf(OutputPart.Media(MediaKind.DOCUMENT,
                        ref.mimeType, text.toByteArray())), null, false, Effect.NONE, null,
                        ArgumentsValidator { _, _ -> true }, "host_blob_read")
                    assertIs<Mutation.Applied>(ledger.recordToolOutput(ready.callId, claim.ticket!!.attemptId,
                        Status.SUCCEEDED, output, Effect.NONE))
                    assertEquals(1L, db.connection.longValue("SELECT count(*) FROM quarantined_contents"))
                    assertFailsWith<StoreException> { documents.read("document-session", id, 0) }
                    assertFailsWith<BlobReadException> { ledger.readSessionBlob("other", output.stored.media.single().id, 0) }
                    assertNull(ledger.executionAdmissionFailure())
                    SessionComposerStore(db).save("other", ComposerSnapshot("v", "still usable", emptyList()), emptyList())
                    assertEquals("still usable", SessionComposerStore(db).read("other").text)
                }
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                assertFailsWith<StoreException> { SessionDocumentBlobs(db).read("document-session", id, 0) }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun upgradesVersionSevenWithoutChangingComposerDrafts() {
        val root = Files.createTempDirectory("document-upgrade")
        val file = root.resolve("agent.db")
        try {
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                SessionComposerStore(db).save("session", ComposerSnapshot("v", "draft", emptyList()), emptyList())
                db.connection.execute("DROP TABLE document_blobs")
                db.connection.execute("PRAGMA user_version=7")
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                assertEquals("draft", SessionComposerStore(db).read("session").text)
                assertNotNull(SessionDocumentBlobs(db).put("session", "new document"))
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
