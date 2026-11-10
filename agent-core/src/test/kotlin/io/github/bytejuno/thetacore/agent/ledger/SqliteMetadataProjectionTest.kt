package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

/** Actual production schema and bundled SQLite; WindowBoundDriver inspects step(),
 * before a reader can coerce an oversized value with getLong/getText/getBlob. */
@RunWith(Parameterized::class)
class SqliteMetadataProjectionTest(private val table: String, private val column: String, private val value: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}.{1}={2}")
        fun cases(): List<Array<String>> = buildList {
            val numbers = listOf("zeroblob(3000000)", "replace(hex(zeroblob(1500000)),'0','x')", "x'00'", "'invalid'", "0.5", "-1", "9223372036854775807", "9223372036854775808")
            for ((table, column) in listOf("invocations" to "revision", "invocations" to "size", "record_chunks" to "ordinal", "contents" to "size", "content_chunks" to "ordinal", "content_manifests" to "ordinal")) {
                for (value in numbers) add(arrayOf(table, column, value))
            }
            for ((table, column) in listOf("invocations" to "call_id", "invocations" to "hash", "invocation_contents" to "call_id", "invocation_contents" to "hash")) {
                for (value in listOf("zeroblob(3000000)", "replace(hex(zeroblob(1500000)),'0','x')", "CAST($column AS BLOB)")) add(arrayOf(table, column, value))
            }
            for (table in listOf("record_chunks", "content_chunks", "content_manifests")) {
                // TEXT payload must not pass as a BLOB, even when its bytes match.
                add(arrayOf(table, "bytes", "CAST(bytes AS TEXT)"))
                add(arrayOf(table, "bytes", "replace(hex(zeroblob(1500000)),'0','x')"))
            }
        }
    }

    @Test fun corruptProjectionIsBoundedAndRejected() = Fixture().use { f ->
        val call = f.claim(f.ready())
        val old = assertIs<Mutation.Applied>(f.ledger.complete(call.record.callId, call.ticket!!.attemptId, Status.SUCCEEDED, "done")).record
        val blob = BlobWrite(ByteArray(128) { 65 }, "application/octet-stream")
        val record = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
        f.store.saveWithBlobs(record, old.revision, listOf(blob))
        f.store.close()
        sql(f.directory) { it.execute("UPDATE $table SET $column=$value") }
        val driver = WindowBoundDriver(windowBytes = 9000)
        val file = f.directory.resolve(AgentDatabase.FILE_NAME)
        val content = table in listOf("contents", "content_chunks", "content_manifests")
        if (content) {
            SqliteInvocationStore.open(file, driver).use { store ->
                assertEquals(record, store.records().single())
                assertFailsWith<StoreException> { store.readBlob(blob.ref, 0, 128) }
                // Quarantine cannot prevent bookkeeping or cold recovery of ledger evidence.
                store.save(record.copy(revision = record.revision + 1), record.revision)
                InvocationLedger(store, f.gate, ArgumentsValidator { _, _ -> true }, f.policy).recoverColdStart("metadata-recovery")
                assertFailsWith<StoreException> { store.readBlob(blob.ref, 0, 128) }
            }
            SqliteInvocationStore.open(file, driver).use { store ->
                assertEquals(record.revision + 1, store.records().single().revision)
                assertFailsWith<StoreException> { store.readBlob(blob.ref, 0, 128) }
            }
        } else {
            assertFailsWith<StoreException> { SqliteInvocationStore.open(file, driver).use { } }
        }
        assertTrue(driver.rows > 0)
        assertTrue(driver.largestRow <= 9000, "$table.$column=$value maxRow=${driver.largestRow}")
        println("SQLITE_METADATA_WINDOW_PROOF $table.$column=$value maxRow=${driver.largestRow} rows=${driver.rows}")
    }
}

class SqliteMetadataLiveReadTest {
    @Test fun integerProjectionPreservesSignedIntegersWithoutCoercingInvalidTypes() = Fixture().use { f ->
        val driver = WindowBoundDriver(windowBytes = 9000)
        driver.open(f.directory.resolve(AgentDatabase.FILE_NAME).toString()).use { db ->
            // A no-affinity scratch table exercises NULL too: production metadata is NOT NULL.
            db.execute("CREATE TEMP TABLE projection_test(value)")
            for (value in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                db.execute("DELETE FROM projection_test")
                db.prepare("INSERT INTO projection_test VALUES(?)").use { it.bindLong(1, value); it.step() }
                assertEquals(value, db.longValue("SELECT ${integerColumn("value")} FROM projection_test"))
            }
            for (value in listOf("NULL", "0.5", "'0'", "'1'", "zeroblob(3000000)", "replace(hex(zeroblob(1500000)),'0','x')", "9223372036854775808")) {
                db.execute("DELETE FROM projection_test")
                db.execute("INSERT INTO projection_test VALUES($value)")
                assertEquals(-1L, db.longValue("SELECT ${integerColumn("value")} FROM projection_test"), value)
            }
        }
        assertTrue(driver.largestRow <= 9000)
    }

    @Test fun validEmptyContentKeepsZeroSizeAndNoChunks() = Fixture().use { f ->
        val call = f.claim(f.ready())
        val old = assertIs<Mutation.Applied>(f.ledger.complete(call.record.callId, call.ticket!!.attemptId, Status.SUCCEEDED, "done")).record
        val blob = BlobWrite(byteArrayOf(), "application/octet-stream")
        val record = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
        f.store.saveWithBlobs(record, old.revision, listOf(blob)); f.store.close()
        val driver = WindowBoundDriver(windowBytes = 9000)
        SqliteInvocationStore.open(f.directory.resolve(AgentDatabase.FILE_NAME), driver).use {
            assertContentEquals(byteArrayOf(), it.readBlob(blob.ref, 0, 128))
            sql(f.directory) { db -> db.execute("UPDATE contents SET size=x'00'") }
            assertFailsWith<StoreException> { it.readBlob(blob.ref, 0, 128) }
        }
        assertTrue(driver.largestRow <= 9000)
    }

    @Test fun liveSizeCorruptionCannotReachWindowOrReturnContent() {
        for (value in listOf("zeroblob(3000000)", "replace(hex(zeroblob(1500000)),'0','x')", "0.5", "-1", "9223372036854775808")) Fixture().use { f ->
            val call = f.claim(f.ready())
            val old = assertIs<Mutation.Applied>(f.ledger.complete(call.record.callId, call.ticket!!.attemptId, Status.SUCCEEDED, "done")).record
            val blob = BlobWrite(ByteArray(128) { 65 }, "application/octet-stream")
            val record = old.copy(revision = old.revision + 1, result = old.result!!.copy(output = StoredToolOutput(blob.ref, emptyList())))
            f.store.saveWithBlobs(record, old.revision, listOf(blob)); f.store.close()
            val driver = WindowBoundDriver(windowBytes = 9000)
            SqliteInvocationStore.open(f.directory.resolve(AgentDatabase.FILE_NAME), driver).use { store ->
                assertContentEquals(blob.bytes(), store.readBlob(blob.ref, 0, 128))
                sql(f.directory) { it.execute("UPDATE contents SET size=$value") }
                assertFailsWith<StoreException> { store.readBlob(blob.ref, 0, 128) }
                store.save(record.copy(revision = record.revision + 1), record.revision)
                assertFailsWith<StoreException> { store.readBlob(blob.ref, 0, 128) }
            }
            assertTrue(driver.largestRow <= 9000, value)
        }
    }

    @Test fun productionSchemaRejectsNullMetadata() = Fixture().use { f ->
        f.record()
        sql(f.directory) { db ->
            for (column in listOf("call_id", "revision", "size", "hash")) {
                assertFails { db.execute("UPDATE invocations SET $column=NULL") }
            }
            assertFails { db.execute("UPDATE record_chunks SET ordinal=NULL") }
            assertFails { db.execute("INSERT INTO contents VALUES('hash',NULL)") }
            for (table in listOf("content_chunks", "content_manifests")) {
                assertFails { db.execute("INSERT INTO $table VALUES('hash',NULL,x'01')") }
            }
        }
    }
}
