package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import java.nio.file.Files
import java.nio.file.Path

/** Test-only owner adapter. Production borrows one AgentDatabase for ledger and all sessions. */
internal class TestRunHistory private constructor(private val owner: AgentDatabase, id: String,
    fault: (HistoryWritePoint) -> Unit) : SqliteRunHistory(owner, id, fault) {
    override fun close() { try { super.close() } finally { owner.close() } }
    companion object {
        fun create(path: Path, sessionId: String = "session", durability: Durability,
            fault: (HistoryWritePoint) -> Unit = {}): TestRunHistory {
            Files.createDirectories(path)
            val db = AgentDatabase.open(path.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver())
            try {
                SqliteRunHistory.create(db, sessionId, fault).close()
                db.bindSession(sessionId)
                return TestRunHistory(db, sessionId, fault)
            } catch (error: Throwable) { db.close(); throw error }
        }
        fun open(path: Path, durability: Durability, fault: (HistoryWritePoint) -> Unit = {}): TestRunHistory {
            val db = AgentDatabase.open(path.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver())
            try {
                val id = db.connection.textValue("SELECT session_id FROM sessions")
                db.bindSession(id)
                return TestRunHistory(db, id, fault)
            } catch (error: Throwable) { db.close(); throw HistoryStorageException("Open failed", error) }
        }
        private fun <T> raw(path: Path, block: (androidx.sqlite.SQLiteConnection) -> T): T =
            BundledSQLiteDriver().open(path.resolve(AgentDatabase.FILE_NAME).toString()).use(block)
        fun exists(path: Path, name: String): Boolean = raw(path) { db ->
            db.prepare("SELECT 1 FROM history_values WHERE name=?").use { it.bindText(1, name); it.step() }
        }
        fun bytes(path: Path, name: String): ByteArray = raw(path) { db ->
            val out = java.io.ByteArrayOutputStream()
            db.prepare("SELECT bytes FROM history_chunks WHERE name=? ORDER BY ordinal").use {
                it.bindText(1, name); while (it.step()) out.write(it.getBlob(0))
            }
            out.toByteArray()
        }
        fun corrupt(path: Path, name: String) = raw(path) { db ->
            db.prepare("UPDATE history_chunks SET bytes=x'00' WHERE name=? AND ordinal=0").use { it.bindText(1, name); it.step() }
        }
        fun delete(path: Path, name: String) = raw(path) { db ->
            db.prepare("DELETE FROM history_chunks WHERE name=?").use { it.bindText(1, name); it.step() }
        }
    }
}
