package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

// Each result row is <= 8 KiB of payload plus ordinal/column/row overhead. Metadata
// has only a UUID, revision, size and SHA-256; no unbounded record field is a SQL column.
internal const val RECORD_CHUNK_SIZE = 8192

internal fun SQLiteConnection.readRecordChunks(id: String, size: Long, hash: String): ByteArray {
    require(size > 0 && hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid record metadata" }
    val output = ByteArrayOutputStream()
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    var ordinal = 0L
    // Bound even a damaged row before it reaches Android's CursorWindow. Read one extra
    // byte to reject oversized chunks, not silently truncate them.
    prepare("SELECT ${integerColumn("ordinal")}, ${blobColumn("bytes", RECORD_CHUNK_SIZE)} FROM record_chunks WHERE call_id=? ORDER BY ordinal").use {
        it.bindText(1, id)
        while (it.step()) {
            require(it.getLong(0) == ordinal) { "Record chunk order/missing chunk" }
            require(!it.isNull(1)) { "Invalid chunk storage type" }
            val bytes = it.getBlob(1)
            require(count < size && bytes.size.toLong() == minOf(RECORD_CHUNK_SIZE.toLong(), size - count)) { "Record chunk size/extra chunk" }
            digest.update(bytes); output.write(bytes)
            count += bytes.size; ordinal++
        }
    }
    require(count == size) { "Incomplete record" }
    require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Record checksum mismatch" }
    return output.toByteArray()
}

internal fun SQLiteConnection.writeRecordChunks(id: String, payload: ByteArray, afterChunk: () -> Unit = {}) {
    prepare("DELETE FROM record_chunks WHERE call_id=?").use { it.bindText(1, id); it.step() }
    prepare("INSERT INTO record_chunks(call_id,ordinal,bytes) VALUES(?,?,?)").use {
        var start = 0
        var ordinal = 0L
        while (start < payload.size) {
            val end = start + minOf(RECORD_CHUNK_SIZE, payload.size - start)
            it.bindText(1, id); it.bindLong(2, ordinal++)
            it.bindBlob(3, payload.copyOfRange(start, end)); it.step(); it.reset()
            start = end
            afterChunk()
        }
    }
}
