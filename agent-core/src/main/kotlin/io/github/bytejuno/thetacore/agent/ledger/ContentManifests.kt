package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import java.io.ByteArrayOutputStream

internal const val MANIFEST_CHUNK_SIZE = 8192

internal fun SQLiteConnection.writeManifest(hash: String, manifest: ByteArray, afterChunk: () -> Unit = {}) {
    prepare("INSERT INTO content_manifests(hash,ordinal,bytes) VALUES(?,?,?)").use {
        var start = 0
        while (start < manifest.size) {
            val end = start + minOf(MANIFEST_CHUNK_SIZE, manifest.size - start)
            it.bindText(1, hash); it.bindLong(2, (start / MANIFEST_CHUNK_SIZE).toLong())
            it.bindBlob(3, manifest.copyOfRange(start, end)); it.step(); it.reset()
            start = end
            afterChunk()
        }
    }
}

internal fun SQLiteConnection.readManifest(ref: BlobRef): ByteArray {
    prepare("SELECT ${integerColumn("size")} FROM contents WHERE hash=?").use {
        it.bindText(1, ref.contentHash)
        require(it.step() && it.getLong(0) == ref.sizeBytes) { "Result content missing or size corrupt" }
    }
    val size = (ref.sizeBytes / BLOB_CHUNK_SIZE + if (ref.sizeBytes % BLOB_CHUNK_SIZE == 0L) 0 else 1) * 32
    val output = ByteArrayOutputStream()
    var count = 0L
    var ordinal = 0L
    // +1 detects oversize without putting an unbounded damaged BLOB in CursorWindow.
    prepare("SELECT ${integerColumn("ordinal")}, ${blobColumn("bytes", MANIFEST_CHUNK_SIZE)} FROM content_manifests WHERE hash=? ORDER BY ordinal").use {
        it.bindText(1, ref.contentHash)
        while (it.step()) {
            require(it.getLong(0) == ordinal++) { "Result manifest order/missing chunk" }
            require(!it.isNull(1)) { "Invalid chunk storage type" }
            val bytes = it.getBlob(1)
            require(count < size && bytes.size.toLong() == minOf(MANIFEST_CHUNK_SIZE.toLong(), size - count)) { "Result manifest chunk size/extra chunk" }
            output.write(bytes); count += bytes.size
        }
    }
    require(count == size) { "Result manifest incomplete" }
    return output.toByteArray().also {
        require(ref.chunksHash == null || blobDigest(it) == ref.chunksHash) { "Result manifest checksum mismatch" }
    }
}
