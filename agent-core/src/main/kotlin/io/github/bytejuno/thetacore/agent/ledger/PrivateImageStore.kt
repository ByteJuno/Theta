package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import io.github.bytejuno.thetacore.agent.run.HistoryCodec
import java.security.MessageDigest

class PrivateImageStore(
    private val database: AgentDatabase,
    private val fault: (SqliteWriteStage) -> Unit = {},
) {
    private val connection get() = database.connection
    private fun checkIdentity(sessionId: String, callId: String) {
        require(sessionId.isNotBlank() && callId.isNotBlank())
        HistoryCodec.requireUtf8(sessionId); HistoryCodec.requireUtf8(callId)
        check(!database.isSessionDeleted(sessionId)) { "Session has been deleted" }
    }
    fun claim(sessionId: String, callId: String): Boolean = database.access {
        checkIdentity(sessionId, callId)
        val exists = connection.prepare("SELECT 1 FROM private_image_attempts WHERE session_id=? AND call_id=?").use {
            it.bindText(1, sessionId); it.bindText(2, callId); it.step()
        }
        if (exists) return@access false
        database.transaction {
            connection.prepare("INSERT INTO private_image_attempts(session_id,call_id) VALUES(?,?)").use {
                it.bindText(1, sessionId); it.bindText(2, callId); it.step()
            }
            fault(SqliteWriteStage.BEFORE_COMMIT)
        }
        fault(SqliteWriteStage.AFTER_COMMIT)
        true
    }
    fun save(sessionId: String, callId: String, bytes: ByteArray): Unit = database.access {
        checkIdentity(sessionId, callId)
        require(bytes.size in 1..ImageLimits.BYTES)
        connection.prepare("SELECT size FROM private_image_attempts WHERE session_id=? AND call_id=?").use {
            it.bindText(1, sessionId); it.bindText(2, callId)
            check(it.step() && it.isNull(0)) { "Image attempt missing or already saved" }
        }
        val hash = digest(bytes)
        database.transaction {
            connection.prepare("INSERT INTO private_image_chunks(session_id,call_id,ordinal,bytes) VALUES(?,?,?,?)").use { statement ->
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(bytes.size, offset + CHUNK_BYTES)
                    statement.bindText(1, sessionId); statement.bindText(2, callId)
                    statement.bindLong(3, (offset / CHUNK_BYTES).toLong())
                    statement.bindBlob(4, bytes.copyOfRange(offset, end)); statement.step(); statement.reset()
                    fault(SqliteWriteStage.AFTER_CONTENT_CHUNK)
                    offset = end
                }
            }
            connection.prepare("UPDATE private_image_attempts SET size=?,hash=? WHERE session_id=? AND call_id=?").use {
                it.bindLong(1, bytes.size.toLong()); it.bindText(2, hash)
                it.bindText(3, sessionId); it.bindText(4, callId); it.step()
            }
            fault(SqliteWriteStage.BEFORE_COMMIT)
        }
        fault(SqliteWriteStage.AFTER_COMMIT)
    }
    fun read(sessionId: String, callId: String): ByteArray? = database.access {
        if (database.isSessionDeleted(sessionId)) return@access null
        val metadata = connection.prepare("SELECT size,hash FROM private_image_attempts WHERE session_id=? AND call_id=?").use {
            it.bindText(1, sessionId); it.bindText(2, callId)
            if (!it.step() || it.isNull(0)) return@access null
            val size = it.getLong(0)
            check(size in 1..ImageLimits.BYTES.toLong() && !it.isNull(1))
            size.toInt() to it.getText(1)
        }
        val bytes = ByteArray(metadata.first)
        var offset = 0
        connection.prepare("SELECT ordinal,length(bytes),bytes FROM private_image_chunks WHERE session_id=? AND call_id=? ORDER BY ordinal").use {
            it.bindText(1, sessionId); it.bindText(2, callId)
            while (it.step()) {
                check(offset < bytes.size && it.getLong(0) == (offset / CHUNK_BYTES).toLong())
                val length = minOf(CHUNK_BYTES, bytes.size - offset)
                check(it.getLong(1) == length.toLong())
                val chunk = it.getBlob(2); check(chunk.size == length)
                chunk.copyInto(bytes, offset); offset += length
            }
        }
        check(offset == bytes.size && digest(bytes) == metadata.second) { "Private image is corrupt" }
        bytes
    }
    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
