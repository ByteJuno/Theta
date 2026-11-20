package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class PrivateImageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun database(path: java.nio.file.Path) = AgentDatabase.open(path, BundledSQLiteDriver())
    @Test fun saveIsAtomicAcrossChunksAndCommitAcknowledgementFailure() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        for (stage in listOf(SqliteWriteStage.AFTER_CONTENT_CHUNK, SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) {
            val path = temporary.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
            database(path).use { owner ->
                var armed = false
                val images = PrivateImageStore(owner) { if (armed && it == stage) error("injected") }
                assertTrue(images.claim("session", "call"))
                armed = true
                assertFailsWith<IllegalStateException> { images.save("session", "call", bytes) }
            }
            database(path).use { owner ->
                val images = PrivateImageStore(owner)
                assertFalse(images.claim("session", "call"))
                if (stage == SqliteWriteStage.AFTER_COMMIT) assertContentEquals(bytes, images.read("session", "call"))
                else {
                    assertNull(images.read("session", "call"))
                    assertEquals(0L, owner.connection.longValue("SELECT count(*) FROM private_image_chunks"))
                }
            }
        }
    }
    @Test fun attemptCommitSurvivesLostAcknowledgementAndImageIsNotLedgerContent() {
        val path = temporary.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
        database(path).use { owner ->
            val images = PrivateImageStore(owner) { if (it == SqliteWriteStage.AFTER_COMMIT) error("lost acknowledgement") }
            assertFailsWith<IllegalStateException> { images.claim("session", "call") }
        }
        database(path).use { owner ->
            val images = PrivateImageStore(owner)
            assertFalse(images.claim("session", "call"))
            images.save("session", "call", byteArrayOf(1, 2, 3))
            SqliteInvocationStore.open(owner).use { store ->
                val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                val hash = owner.connection.textValue("SELECT hash FROM private_image_attempts")
                for (id in listOf("call", hash)) assertFailsWith<BlobReadException> { ledger.readSessionBlob("session", id, 0) }
                assertEquals(0L, owner.connection.longValue("SELECT count(*) FROM contents"))
                assertEquals(0L, owner.connection.longValue("SELECT count(*) FROM record_chunks"))
                SqliteRunHistory.create(owner, "session").use { assertTrue(it.page(null, 40).messages.isEmpty()) }
            }
        }
    }
    @Test fun schemaFourUpgradesWithoutChangingExistingHistory() {
        val path = temporary.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
        database(path).use { owner ->
            SqliteRunHistory.create(owner, "existing").close()
            owner.connection.execute("DROP TABLE private_image_chunks")
            owner.connection.execute("DROP TABLE private_image_attempts")
            owner.connection.execute("PRAGMA user_version=4")
        }
        database(path).use { owner ->
            assertEquals(listOf("existing"), owner.sessionIds())
            assertTrue(PrivateImageStore(owner).claim("existing", "call"))
            assertEquals(AgentDatabase.SCHEMA_VERSION.toLong(), owner.connection.longValue("PRAGMA user_version"))
        }
    }
    @Test fun corruptImageFailsClosedWithoutResettingAttempt() {
        val path = temporary.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
        database(path).use { owner ->
            val images = PrivateImageStore(owner)
            assertTrue(images.claim("session", "call")); images.save("session", "call", byteArrayOf(1, 2, 3))
            owner.connection.execute("UPDATE private_image_chunks SET bytes=x'040506'")
            assertFailsWith<IllegalStateException> { images.read("session", "call") }
        }
        database(path).use { owner ->
            val images = PrivateImageStore(owner)
            assertFalse(images.claim("session", "call"))
            assertFailsWith<IllegalStateException> { images.read("session", "call") }
        }
    }
}
