package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import java.security.MessageDigest

internal fun createDocumentBlobSchema(db: SQLiteConnection) {
    db.execute("""CREATE TABLE document_blobs (
        session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
        id TEXT NOT NULL, hash TEXT NOT NULL REFERENCES contents(hash), mime TEXT NOT NULL,
        size INTEGER NOT NULL, chunks_hash TEXT NOT NULL,
        PRIMARY KEY(session_id,id)) WITHOUT ROWID""")
    db.execute("CREATE INDEX document_content_owners ON document_blobs(hash)")
}

/** Session-owned roots in the existing chunked BlobStore, independent of tool invocations. */
class SessionDocumentBlobs(private val database: AgentDatabase) {
    private val db get() = database.connection

    fun put(session: String, text: String): BlobRef {
        require(text.isNotBlank() && text.length <= DocumentLimits.CHARACTERS)
        val blob = BlobWrite(text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        val ref = blob.ref
        return database.transaction {
            require(!database.isSessionDeleted(session))
            require(db.prepare("SELECT 1 FROM sessions WHERE session_id=?").use { it.bindText(1, session); it.step() })
            require(!db.prepare("SELECT 1 FROM quarantined_contents WHERE hash=?").use { it.bindText(1, ref.contentHash); it.step() })
            val exists = db.prepare("SELECT 1 FROM contents WHERE hash=?").use { it.bindText(1, ref.contentHash); it.step() }
            if (!exists) {
                db.prepare("INSERT INTO contents(hash,size) VALUES(?,?)").use {
                    it.bindText(1, ref.contentHash); it.bindLong(2, ref.sizeBytes); it.step()
                }
                db.writeManifest(ref.contentHash, blob.manifest())
                val bytes = blob.bytes()
                db.prepare("INSERT INTO content_chunks VALUES(?,?,?)").use {
                    for (offset in bytes.indices step BLOB_CHUNK_SIZE) {
                        it.bindText(1, ref.contentHash); it.bindLong(2, (offset / BLOB_CHUNK_SIZE).toLong())
                        it.bindBlob(3, bytes.copyOfRange(offset, minOf(offset + BLOB_CHUNK_SIZE, bytes.size)))
                        it.step(); it.reset()
                    }
                }
            }
            // Reusing a hash never silently repairs previously damaged content.
            val digest = MessageDigest.getInstance("SHA-256")
            for (offset in 0 until ref.sizeBytes step BLOB_CHUNK_SIZE.toLong())
                digest.update(readRange(ref, offset, BLOB_CHUNK_SIZE))
            require(digest.digest().joinToString("") { "%02x".format(it) } == ref.contentHash)
            db.prepare("INSERT OR IGNORE INTO document_blobs VALUES(?,?,?,?,?,?)").use {
                it.bindText(1, session); it.bindText(2, ref.id); it.bindText(3, ref.contentHash)
                it.bindText(4, ref.mimeType); it.bindLong(5, ref.sizeBytes); it.bindText(6, requireNotNull(ref.chunksHash)); it.step()
            }
            ref
        }
    }

    fun read(session: String, id: String, offset: Long, limit: Int = 8192): Pair<BlobRef, ByteArray>? = database.access {
        if (database.isSessionDeleted(session)) return@access null
        val ref = db.prepare("SELECT hash,mime,size,chunks_hash FROM document_blobs WHERE session_id=? AND id=?").use {
            it.bindText(1, session); it.bindText(2, id)
            if (!it.step()) return@access null
            BlobRef(id, it.getText(1), it.getLong(2), it.getText(0), it.getText(3))
        }
        if (offset !in 0..ref.sizeBytes || limit !in 1..8192)
            throw BlobReadException("Invalid result range: offset must be within the content and limit must be 1..8192 bytes.")
        try { ref to readRange(ref, offset, limit) }
        catch (error: Exception) { throw StoreException("Document content is missing or corrupt", cause = error) }
    }

    private fun readRange(ref: BlobRef, offset: Long, limit: Int): ByteArray {
        require(ref.sizeBytes <= DocumentLimits.CHARACTERS * 4L)
        require(!db.prepare("SELECT 1 FROM quarantined_contents WHERE hash=?").use { it.bindText(1, ref.contentHash); it.step() })
        val manifest = db.readManifest(ref)
        val end = minOf(ref.sizeBytes, offset + limit)
        val result = java.io.ByteArrayOutputStream()
        var position = offset
        while (position < end) {
            val ordinal = (position / BLOB_CHUNK_SIZE).toInt()
            val chunk = db.prepare("SELECT ${blobColumn("bytes", BLOB_CHUNK_SIZE)} FROM content_chunks WHERE hash=? AND ordinal=?").use {
                it.bindText(1, ref.contentHash); it.bindLong(2, ordinal.toLong()); require(it.step() && !it.isNull(0)); it.getBlob(0)
            }
            require(chunk.size.toLong() == minOf(BLOB_CHUNK_SIZE.toLong(), ref.sizeBytes - ordinal.toLong() * BLOB_CHUNK_SIZE))
            require(MessageDigest.getInstance("SHA-256").digest(chunk).contentEquals(manifest.copyOfRange(ordinal * 32, ordinal * 32 + 32)))
            val start = (position % BLOB_CHUNK_SIZE).toInt()
            val count = minOf(chunk.size - start, (end - position).toInt())
            result.write(chunk, start, count); position += count
        }
        return result.toByteArray()
    }

    internal fun hashes(session: String): Set<String> = db.prepare("SELECT hash FROM document_blobs WHERE session_id=?").use {
        it.bindText(1, session); buildSet { while (it.step()) add(it.getText(0)) }
    }
}

object DocumentLimits {
    const val BYTES = 5 * 1024 * 1024
    const val PAGES = 100
    const val CHARACTERS = 200_000
    const val PER_MESSAGE = 4
    const val EXCERPT = 4_000
}

class ComposerDocument(val id: String, val name: String, val mime: String, val size: Long, val text: String)
