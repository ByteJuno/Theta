package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
