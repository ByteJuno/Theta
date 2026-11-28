package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteConnection
import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

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
class ComposerSnapshot(val version: String?, val text: String, val images: List<ComposerImage>, val documents: List<ComposerDocument> = emptyList())
class ComposerConsumption(
    val versions: Set<String>,
    val imageIds: Set<String>,
    val submittedVersions: Set<String> = versions,
    val submittedImageIds: Set<String> = imageIds,
    val documentIds: Set<String> = emptySet(),
    val submittedDocumentIds: Set<String> = documentIds,
)

class SessionComposerStore(private val database: AgentDatabase) {
    private val db get() = database.connection

    fun read(session: String): ComposerSnapshot = database.access {
        // Older checkpoints could merge several pages beyond the send limit.
        val overflow = """SELECT id FROM composer_parts WHERE session_id=? AND kind='image'
            AND value IS NOT NULL ORDER BY position,id LIMIT -1 OFFSET ${ImageLimits.PER_MESSAGE}"""
        if (db.prepare(overflow).use { it.bindText(1, session); it.step() }) {
            database.transaction {
                db.prepare("DELETE FROM composer_parts WHERE session_id=? AND kind='image' AND id IN ($overflow)").use {
                    it.bindText(1, session)
                    it.bindText(2, session)
                    it.step()
                }
            }
        }
        var version: String? = null
        var text = ""
        val images = mutableListOf<ComposerImage>()
        val documents = mutableListOf<ComposerDocument>()
        db.prepare("SELECT kind,id,value FROM composer_parts WHERE session_id=? AND value IS NOT NULL ORDER BY position,id").use { rows ->
            rows.bindText(1, session)
            while (rows.step()) {
                val id = rows.getText(1)
                if (rows.getText(0) == "text") {
                    version = id
                    text = rows.getText(2)
                } else if (rows.getText(0) == "document") {
                    if (documents.size < DocumentLimits.PER_MESSAGE) documents += readDocument(session, id, rows.getText(2))
                } else if (rows.getText(0) == "image") {
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
        ComposerSnapshot(version, text, images, documents)
    }

    fun save(session: String, draft: ComposerSnapshot, previousImages: List<String>, textChanged: Boolean = true, previousDocuments: List<String> = emptyList()) = database.transaction {
        // A stale page can outlive its session. Never bootstrap or resurrect a deleted session.
        if (database.isSessionDeleted(session)) return@transaction
        db.prepare("SELECT 1 FROM sessions WHERE session_id=?").use {
            it.bindText(1, session)
            if (!it.step()) return@transaction
        }
        val version = requireNotNull(draft.version)
        if (textChanged && !consumed(session, "text", version)) {
            db.prepare("DELETE FROM composer_parts WHERE session_id=? AND kind='text' AND value IS NOT NULL").use {
                it.bindText(1, session)
                it.step()
            }
            put(session, "text", version, 0, draft.text)
        } else if (!textChanged && draft.text.isEmpty() && !consumed(session, "text", version)) {
            // An image-only draft still needs a stable initial receipt identity.
            db.prepare("""INSERT INTO composer_parts SELECT ?, 'text', ?, 0, ''
                WHERE NOT EXISTS (SELECT 1 FROM composer_parts WHERE session_id=? AND kind='text' AND value IS NOT NULL)""").use {
                it.bindText(1, session)
                it.bindText(2, version)
                it.bindText(3, session)
                it.step()
            }
        }
        if (draft.images.map { it.id } != previousImages) saveImages(session, draft.images, previousImages)
        if (draft.documents.map { it.id } != previousDocuments) saveDocuments(session, draft.documents, previousDocuments)
    }

    private fun saveImages(session: String, images: List<ComposerImage>, previous: List<String>) {
        val stored = db.prepare("""SELECT id FROM composer_parts WHERE session_id=?
            AND kind='image' AND value IS NOT NULL ORDER BY position,id""").use { rows ->
            rows.bindText(1, session)
            buildList { while (rows.step()) add(rows.getText(0)) }
        }
        val selected = images.associateBy { it.id }
        // Keep this edit in picker order, then other pages' live additions in storage order.
        // Unchanged ids removed or consumed by another page must stay removed.
        val live = selected.keys.filter { it in stored || (it !in previous && !consumed(session, "image", it)) }
        val order = (live + (stored - previous.toSet() - selected.keys)).take(ImageLimits.PER_MESSAGE)
        for (removed in stored - order.toSet()) {
            db.prepare("DELETE FROM composer_parts WHERE session_id=? AND kind='image' AND id=? AND value IS NOT NULL").use {
                it.bindText(1, session)
                it.bindText(2, removed)
                it.step()
            }
        }
        order.forEachIndexed { position, id ->
            if (id in stored) {
                db.prepare("UPDATE composer_parts SET position=? WHERE session_id=? AND kind='image' AND id=? AND value IS NOT NULL").use {
                    it.bindLong(1, position.toLong())
                    it.bindText(2, session)
                    it.bindText(3, id)
                    it.step()
                }
            } else {
                val image = selected.getValue(id)
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

    private fun saveDocuments(session: String, documents: List<ComposerDocument>, previous: List<String>) {
        val stored = db.prepare("""SELECT id FROM composer_parts WHERE session_id=?
            AND kind='document' AND value IS NOT NULL ORDER BY position,id""").use { rows ->
            rows.bindText(1, session)
            buildList { while (rows.step()) add(rows.getText(0)) }
        }
        val selected = documents.associateBy { it.id }
        // Keep this edit in picker order, then other pages' live additions in storage order.
        // Unchanged ids removed or consumed by another page must stay removed.
        val live = selected.keys.filter { it in stored || (it !in previous && !consumed(session, "document", it)) }
        val order = (live + (stored - previous.toSet() - selected.keys)).take(DocumentLimits.PER_MESSAGE)
        for (removed in stored - order.toSet()) {
            db.prepare("DELETE FROM composer_parts WHERE session_id=? AND kind='document' AND id=? AND value IS NOT NULL").use {
                it.bindText(1, session)
                it.bindText(2, removed)
                it.step()
            }
        }
        order.forEachIndexed { position, id ->
            if (id in stored) {
                db.prepare("UPDATE composer_parts SET position=? WHERE session_id=? AND kind='document' AND id=? AND value IS NOT NULL").use {
                    it.bindLong(1, position.toLong())
                    it.bindText(2, session)
                    it.bindText(3, id)
                    it.step()
                }
            } else {
                val doc = selected.getValue(id)
                require(doc.text.isNotBlank() && doc.text.length <= DocumentLimits.CHARACTERS)
                require(doc.name.length in 1..160 && doc.mime.length in 1..128 && doc.size in 1..DocumentLimits.BYTES)
                val bytes = doc.text.toByteArray(Charsets.UTF_8)
                val metadata = buildJsonObject { put("name", doc.name); put("mime", doc.mime); put("size", doc.size) }.toString()
                put(session, "document", doc.id, position, metadata)
                db.prepare("INSERT INTO composer_chunks VALUES(?,'document',?,?,?)").use { chunks ->
                    for (offset in bytes.indices step CHUNK_SIZE) {
                        chunks.bindText(1, session)
                        chunks.bindText(2, doc.id)
                        chunks.bindLong(3, (offset / CHUNK_SIZE).toLong())
                        chunks.bindBlob(4, bytes.copyOfRange(offset, minOf(offset + CHUNK_SIZE, bytes.size)))
                        chunks.step()
                        chunks.reset()
                    }
                }
            }
        }
    }

    private fun readDocument(session: String, id: String, metadata: String): ComposerDocument {
        val value = Json.parseToJsonElement(metadata).jsonObject
        val output = ByteArrayOutputStream()
        db.prepare("SELECT bytes FROM composer_chunks WHERE session_id=? AND kind='document' AND id=? ORDER BY ordinal").use {
            it.bindText(1, session); it.bindText(2, id)
            while (it.step()) {
                val bytes = it.getBlob(0)
                check(bytes.size in 1..CHUNK_SIZE && output.size() <= DocumentLimits.CHARACTERS * 4 - bytes.size)
                output.write(bytes)
            }
        }
        return ComposerDocument(id, value.getValue("name").jsonPrimitive.content, value.getValue("mime").jsonPrimitive.content,
            value.getValue("size").jsonPrimitive.long, output.toString(Charsets.UTF_8.name()))
    }

    fun isSessionDeleted(session: String): Boolean = database.access {
        database.isSessionDeleted(session)
    }

    fun deletedSessions(sessionIds: Collection<String>): Set<String> = database.access {
        sessionIds.filter { database.isSessionDeleted(it) }.toSet()
    }

    fun consumed(session: String, version: String?, imageIds: Collection<String>, documentIds: Collection<String> = emptyList()): ComposerConsumption = database.access {
        val versions = if (version != null && consumed(session, "text", version)) setOf(version) else emptySet()
        val images = imageIds.filter { consumed(session, "image", it) }.toSet()
        ComposerConsumption(versions, images, documentIds = documentIds.filter { consumed(session, "document", it) }.toSet())
    }

    fun isSubmittedConsumed(session: String, receipt: ComposerConsumption): Boolean = database.access {
        receipt.submittedVersions.any { consumed(session, "text", it) } ||
            receipt.submittedImageIds.any { consumed(session, "image", it) } ||
            receipt.submittedDocumentIds.any { consumed(session, "document", it) }
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
        for (id in receipt.documentIds) put(session, "document", id, 0, null)
    }

    private companion object {
        const val CHUNK_SIZE = 64 * 1024
    }
}
