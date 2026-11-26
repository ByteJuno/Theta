package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.ByteArrayOutputStream

internal fun createComposerSchema(db: SQLiteConnection) {
    db.execute("""CREATE TABLE composer_parts (
        session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
        kind TEXT NOT NULL, id TEXT NOT NULL, position INTEGER NOT NULL, value TEXT,
        PRIMARY KEY(session_id,kind,id)) WITHOUT ROWID""")
    db.execute("""CREATE TABLE composer_chunks (
        session_id TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, ordinal INTEGER NOT NULL,
        bytes BLOB NOT NULL, PRIMARY KEY(session_id,kind,id,ordinal),
        FOREIGN KEY(session_id,kind,id) REFERENCES composer_parts(session_id,kind,id) ON DELETE CASCADE
        ) WITHOUT ROWID""")
}

class ComposerImage(val id: String, val mime: String, val bytes: ByteArray)
class ComposerSnapshot(val version: String?, val text: String, val images: List<ComposerImage>)
class ComposerConsumption(val versions: Set<String>, val imageIds: Set<String>)

class SessionComposerStore(private val database: AgentDatabase) {
    private val db get() = database.connection

    fun read(session: String): ComposerSnapshot = database.access {
        var version: String? = null
        var text = ""
        val images = mutableListOf<ComposerImage>()
        db.prepare("SELECT kind,id,value FROM composer_parts WHERE session_id=? AND value IS NOT NULL ORDER BY position").use { rows ->
            rows.bindText(1, session)
            while (rows.step()) {
                val id = rows.getText(1)
                if (rows.getText(0) == "text") {
                    version = id
                    text = rows.getText(2)
                } else {
                    val bytes = ByteArrayOutputStream()
                    db.prepare("SELECT bytes FROM composer_chunks WHERE session_id=? AND kind='image' AND id=? ORDER BY ordinal").use { chunks ->
                        chunks.bindText(1, session)
                        chunks.bindText(2, id)
                        while (chunks.step()) {
                            val chunk = chunks.getBlob(0)
                            check(chunk.size in 1..CHUNK_SIZE && bytes.size() <= ImageLimits.BYTES - chunk.size)
                            bytes.write(chunk)
                        }
                    }
                    images += ComposerImage(id, rows.getText(2), bytes.toByteArray())
                }
            }
        }
        ComposerSnapshot(version, text, images)
    }

    fun save(session: String, draft: ComposerSnapshot, previousImages: List<String>) = database.transaction {
        // A stale page can outlive its session. Never bootstrap a session while saving a draft.
        db.prepare("SELECT 1 FROM sessions WHERE session_id=?").use {
            it.bindText(1, session)
            if (!it.step()) return@transaction
        }
        val version = requireNotNull(draft.version)
        if (!consumed(session, "text", version)) {
            db.prepare("DELETE FROM composer_parts WHERE session_id=? AND kind='text' AND value IS NOT NULL").use {
                it.bindText(1, session)
                it.step()
            }
            put(session, "text", version, 0, draft.text)
        }
        val ids = draft.images.map { it.id }.toSet()
        for (removed in previousImages - ids) {
            db.prepare("DELETE FROM composer_parts WHERE session_id=? AND kind='image' AND id=? AND value IS NOT NULL").use {
                it.bindText(1, session)
                it.bindText(2, removed)
                it.step()
            }
        }
        draft.images.forEachIndexed { position, image ->
            if (image.id in previousImages) {
                if (previousImages.indexOf(image.id) != position) {
                    db.prepare("UPDATE composer_parts SET position=? WHERE session_id=? AND kind='image' AND id=? AND value IS NOT NULL").use {
                        it.bindLong(1, position.toLong())
                        it.bindText(2, session)
                        it.bindText(3, image.id)
                        it.step()
                    }
                }
            } else if (!consumed(session, "image", image.id)) {
                require(image.bytes.size in 1..ImageLimits.BYTES)
                put(session, "image", image.id, position, image.mime)
                db.prepare("INSERT INTO composer_chunks VALUES(?,'image',?,?,?)").use { chunks ->
                    for (offset in image.bytes.indices step CHUNK_SIZE) {
                        chunks.bindText(1, session)
                        chunks.bindText(2, image.id)
                        chunks.bindLong(3, (offset / CHUNK_SIZE).toLong())
                        chunks.bindBlob(4, image.bytes.copyOfRange(offset, minOf(offset + CHUNK_SIZE, image.bytes.size)))
                        chunks.step()
                        chunks.reset()
                    }
                }
            }
        }
    }

    private fun consumed(session: String, kind: String, id: String): Boolean =
        db.prepare("SELECT 1 FROM composer_parts WHERE session_id=? AND kind=? AND id=? AND value IS NULL").use {
            it.bindText(1, session)
            it.bindText(2, kind)
            it.bindText(3, id)
            it.step()
        }

    // Replacing an image row cascades to its chunks. A NULL value retains just the receipt identity.
    private fun put(session: String, kind: String, id: String, position: Int, value: String?) {
        db.prepare("INSERT OR REPLACE INTO composer_parts(session_id,kind,id,position,value) VALUES(?,?,?,?,?)").use {
            it.bindText(1, session)
            it.bindText(2, kind)
            it.bindText(3, id)
            it.bindLong(4, position.toLong())
            if (value == null) it.bindNull(5) else it.bindText(5, value)
            it.step()
        }
    }

    internal fun consumeInTransaction(session: String, receipt: ComposerConsumption) {
        check(db.inTransaction())
        for (version in receipt.versions) put(session, "text", version, 0, null)
        for (id in receipt.imageIds) put(session, "image", id, 0, null)
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}
