package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class SqliteReviewRegressionTest {
    private fun completed(f: Fixture): InvocationRecord {
        val call = f.claim(f.ready())
        return assertIs<Mutation.Applied>(f.ledger.complete(call.record.callId, call.ticket!!.attemptId, Status.SUCCEEDED, "done")).record
    }
    @Test fun establishedEmptyOrDeletedDatabaseIsRejectedBeforeDriverOpen() {
        for (deleted in listOf(false, true)) Fixture().use { f ->
            f.record(); f.store.close()
            sql(f.directory) { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            val file = f.directory.resolve(AgentDatabase.FILE_NAME)
            if (deleted) Files.delete(file) else Files.write(file, byteArrayOf())
            var opened = false
            val driver = object : SQLiteDriver by BundledSQLiteDriver() {
                override fun open(fileName: String): SQLiteConnection {
                    opened = true
                    return BundledSQLiteDriver().open(fileName)
                }
            }
            assertFailsWith<StoreException> { SqliteInvocationStore.open(file, driver).close() }
            assertFalse(opened, "Helper onCreate must not run before lost-evidence check")
            assertEquals(!deleted, Files.exists(file))
            if (!deleted) assertEquals(0L, Files.size(file))
        }
    }
    @Test fun confirmedRepublicationUnquarantinesBothOldAndNewReferences() = Fixture().use { f ->
        val blob = BlobWrite(ByteArray(30_000) { (it % 251).toByte() }, "application/octet-stream")
        val old = completed(f)
        val first = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
        f.store.saveWithBlobs(first, old.revision, listOf(blob))
        sql(f.directory) { db ->
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("DELETE FROM contents")
        }
        assertFailsWith<StoreException> { f.store.readBlob(blob.ref, 0, 8192) }
        val second = completed(f)
        val next = second.copy(revision = second.revision + 1, result = second.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
        f.store.saveWithBlobs(next, second.revision, listOf(blob))
        for (r in listOf(first, next)) assertContentEquals(blob.bytes().copyOfRange(0, 8192), f.store.readBlob(r.result!!.output!!.raw, 0, 8192))
    }
    @Test fun repairRollbackAndUncertainCommitRemainQuarantined() {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) Fixture().use { f ->
            val blob = BlobWrite(ByteArray(30_000) { (it % 251).toByte() }, "application/octet-stream")
            val old = completed(f)
            val first = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
            f.store.saveWithBlobs(first, old.revision, listOf(blob))
            damageContent(f.directory, blob.ref.contentHash, "missing")
            assertFailsWith<StoreException> { f.store.readBlob(blob.ref, 0, 8192) }
            f.stage = stage
            val next = first.copy(revision = first.revision + 1)
            val error = assertFailsWith<StoreException> { f.store.saveWithBlobs(next, first.revision, listOf(blob)) }
            assertEquals(stage == SqliteWriteStage.AFTER_COMMIT, error.uncertain)
            assertFails { f.store.readBlob(blob.ref, 0, 8192) }
            f.stage = null
            if (stage == SqliteWriteStage.BEFORE_COMMIT) {
                // No publication means no repair, even when bookkeeping commits.
                f.store.save(next, first.revision)
                assertFailsWith<StoreException> { f.store.readBlob(blob.ref, 0, 8192) }
                f.store.saveWithBlobs(next.copy(revision = next.revision + 1), next.revision, listOf(blob))
                assertContentEquals(blob.bytes().copyOfRange(0, 8192), f.store.readBlob(blob.ref, 0, 8192))
            } else {
                f.store.close()
                TestInvocationStore.open(f.directory).use { assertContentEquals(blob.bytes().copyOfRange(0, 8192), it.readBlob(blob.ref, 0, 8192)) }
            }
        }
    }
    @Test fun damagedManifestAndContentRowsNeverOverflowWindowOrReturnData() {
        val damages = listOf(
            "DELETE FROM content_manifests WHERE ordinal=1",
            "UPDATE content_manifests SET ordinal=ordinal+1000",
            "UPDATE content_manifests SET bytes=zeroblob(length(bytes)) WHERE ordinal=0",
            "UPDATE content_manifests SET bytes=zeroblob(3000000) WHERE ordinal=0",
            "INSERT INTO content_manifests SELECT hash,99999,x'01' FROM contents",
            "UPDATE content_chunks SET bytes=zeroblob(3000000) WHERE ordinal=0",
            "INSERT INTO content_chunks SELECT hash,99999,x'01' FROM contents",
        )
        for (damage in damages) Fixture().use { f ->
            val blob = BlobWrite(ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }, "application/octet-stream")
            val old = completed(f)
            val next = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
            f.store.saveWithBlobs(next, old.revision, listOf(blob)); f.store.close()
            sql(f.directory) { it.execute(damage) }
            val driver = WindowBoundDriver(windowBytes = 9000)
            SqliteInvocationStore.open(f.directory.resolve(AgentDatabase.FILE_NAME), driver).use {
                assertEquals(next, it.records().single())
                assertFailsWith<StoreException>(damage) { it.readBlob(blob.ref, 0, 8192) }
            }
            assertTrue(driver.largestRow <= 9000, damage)
        }
    }
    @Test fun manifestPublicationReopenAndDownloadUseBoundedRows() = Fixture().use { f ->
        val old = completed(f)
        f.store.close()
        val driver = WindowBoundDriver(windowBytes = 9000)
        val blob = BlobWrite(ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }, "application/octet-stream")
        val next = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
        val file = f.directory.resolve(AgentDatabase.FILE_NAME)
        SqliteInvocationStore.open(file, driver).use { it.saveWithBlobs(next, old.revision, listOf(blob)) }
        SqliteInvocationStore.open(file, driver).use { store ->
            val before = store.blobBytesRead
            val bytes = blob.bytes()
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + 8192, bytes.size)
                assertContentEquals(bytes.copyOfRange(offset, end), store.readBlob(blob.ref, offset.toLong(), 8192))
                offset = end
            }
            assertEquals(bytes.size.toLong(), store.blobBytesRead - before)
        }
        assertTrue(driver.largestRow <= 9000)
        println("SQLITE_MANIFEST_WINDOW_PROOF bytes=${blob.ref.sizeBytes} maxRow=${driver.largestRow} rows=${driver.rows}")
    }
}
