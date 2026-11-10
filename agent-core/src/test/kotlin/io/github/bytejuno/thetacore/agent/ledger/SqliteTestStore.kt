package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

/** All ledger/bridge/history integration fixtures use real bundled SQLite, never a mock. */
object TestInvocationStore {
    fun create(directory: Path, fault: (SqliteWriteStage) -> Unit = {}): SqliteInvocationStore {
        Files.createDirectories(directory)
        require(!Files.exists(directory.resolve(AgentDatabase.FILE_NAME)))
        return SqliteInvocationStore.open(directory.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver(), fault)
    }
    fun open(directory: Path, fault: (SqliteWriteStage) -> Unit = {}): SqliteInvocationStore {
        require(Files.isRegularFile(directory.resolve(AgentDatabase.FILE_NAME)))
        return SqliteInvocationStore.open(directory.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver(), fault)
    }
}

internal fun <T> sql(directory: Path, block: (SQLiteConnection) -> T): T =
    BundledSQLiteDriver().open(directory.resolve(AgentDatabase.FILE_NAME).toString()).use(block)
internal fun payload(directory: Path, id: String): ByteArray = sql(directory) { db ->
    db.prepare("SELECT size,hash FROM invocations WHERE call_id=?").use {
        it.bindText(1, id); check(it.step()); db.readRecordChunks(id, it.getLong(0), it.getText(1))
    }
}
internal fun replacePayload(directory: Path, id: String, bytes: ByteArray) = sql(directory) { db ->
    db.execute("BEGIN IMMEDIATE")
    try {
        db.prepare("UPDATE invocations SET size=?,hash=? WHERE call_id=?").use {
            it.bindLong(1, bytes.size.toLong()); it.bindText(2, blobDigest(bytes)); it.bindText(3, id); it.step()
        }
        db.writeRecordChunks(id, bytes)
        db.execute("COMMIT")
    } catch (e: Exception) { db.execute("ROLLBACK"); throw e }
}
internal fun hasContent(directory: Path, hash: String): Boolean = sql(directory) { db ->
    db.prepare("SELECT 1 FROM contents WHERE hash=?").use { it.bindText(1, hash); it.step() }
}
internal fun damageContent(directory: Path, hash: String, damage: String, ordinal: Long = 0) = sql(directory) { db ->
    val query = when (damage) {
        "missing" -> "DELETE FROM content_chunks WHERE hash=? AND ordinal=?"
        "manifest" -> "UPDATE content_manifests SET bytes=zeroblob(length(bytes)) WHERE hash=? AND ordinal=?"
        "size" -> "UPDATE contents SET size=size+1 WHERE hash=? AND ?=0"
        else -> "UPDATE content_chunks SET bytes=zeroblob(length(bytes)) WHERE hash=? AND ordinal=?"
    }
    db.prepare(query).use { it.bindText(1, hash); it.bindLong(2, ordinal); it.step(); Unit }
}
