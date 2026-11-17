package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class HistoryChunkBoundsTest {
    private fun frame(size: Int): ByteArray {
        val json = """{"version":1,"sessionId":"session","revision":0,"messageCount":0,"hash":"ROOT"}"""
        // JSON whitespace makes a complete, checksum-valid stream exactly the requested size.
        val payload = json + " ".repeat(size - 44 - json.length)
        return ByteArrayOutputStream().also { out ->
            HistoryFrameOutput(out).apply { write(payload.toByteArray()); finish() }
        }.toByteArray().also { assertEquals(size, it.size) }
    }

    @Test fun sqliteChunkRejectsTruncatedSentinelEvenWhenItsPrefixIsACompleteValidFrame() {
        for (extra in listOf(0, 1, 100)) {
            val file = Files.createTempDirectory("history-sentinel").resolve(AgentDatabase.FILE_NAME)
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                val bytes = frame(HISTORY_FRAME_BYTES + 1) + ByteArray(extra) { 42 }
                db.connection.prepare("UPDATE history_chunks SET bytes=? WHERE name='head'").use {
                    it.bindBlob(1, bytes); it.step()
                }
                assertFailsWith<HistoryStorageException>("sentinel plus $extra bytes") {
                    SqliteRunHistory.open(db, "session").close()
                }
            }
        }
    }

    @Test fun indexedChunkReadDoesNotSkipNegativeOrMissingOrdinals() {
        for (ordinal in listOf(-1, 1)) {
            val file = Files.createTempDirectory("history-ordinal").resolve(AgentDatabase.FILE_NAME)
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                db.connection.execute("UPDATE history_chunks SET ordinal=$ordinal WHERE name='head'")
                assertFailsWith<HistoryStorageException> { SqliteRunHistory.open(db, "session").close() }
            }
        }
    }

    @Test fun sqliteChunkChecksEmptyTypeAndExactMaximumBoundary() {
        for (kind in listOf("empty", "text", "integer", "max")) {
            val file = Files.createTempDirectory("history-bounds").resolve(AgentDatabase.FILE_NAME)
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").close()
                db.connection.prepare("UPDATE history_chunks SET bytes=? WHERE name='head'").use {
                    when (kind) {
                        "empty" -> it.bindBlob(1, byteArrayOf())
                        "text" -> it.bindText(1, String(frame(HISTORY_FRAME_BYTES), Charsets.ISO_8859_1))
                        "integer" -> it.bindLong(1, 1)
                        else -> it.bindBlob(1, frame(HISTORY_FRAME_BYTES))
                    }
                    it.step()
                }
                if (kind == "max") SqliteRunHistory.open(db, "session").use { assertEquals(0, it.revision()) }
                else assertFailsWith<HistoryStorageException>(kind) { SqliteRunHistory.open(db, "session").close() }
            }
        }
    }
}
