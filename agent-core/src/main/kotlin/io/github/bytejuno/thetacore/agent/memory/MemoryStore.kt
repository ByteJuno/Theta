package io.github.bytejuno.thetacore.agent.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.run.HistoryCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.UUID

internal fun createMemorySchema(db: SQLiteConnection) {
    db.execute("""CREATE TABLE memories (
        id TEXT PRIMARY KEY NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL,
        pinned INTEGER NOT NULL CHECK(pinned IN (0,1)), created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL, source_session_id TEXT,
        UNIQUE(category, content))""")
    db.execute("CREATE INDEX memory_recency ON memories(pinned DESC, updated_at DESC, id)")
    db.execute("CREATE TABLE memory_settings (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL CHECK(enabled IN (0,1)))")
    db.execute("INSERT INTO memory_settings VALUES(1,1)")
}

data class MemoryEntry(val id: String, val category: String, val content: String, val pinned: Boolean,
    val createdAt: Long, val updatedAt: Long, val sourceSessionId: String?)
data class MemoryUsage(val count: Int, val bytes: Long)
class MemoryCapacityException : IllegalArgumentException("Memory capacity reached")
class MemoryDisabledException : IllegalStateException("Memory is disabled")

/** Borrows the process-owned WAL/FULL database. All checks and mutations share its monitor;
 * expected validation failures occur BEFORE BEGIN and do not poison unrelated agent storage.
 * Source sessions are provenance, not ownership: deleting a chat never silently erases memory.
 */
class MemoryStore(private val database: AgentDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val db get() = database.connection
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun enabled(): Boolean = database.access { db.longValue("SELECT enabled FROM memory_settings WHERE id=1") == 1L }
    fun setEnabled(value: Boolean) = database.access {
        database.transaction { db.execute("UPDATE memory_settings SET enabled=${if (value) 1 else 0} WHERE id=1") }
        changed()
    }
    fun usage(): MemoryUsage = database.access {
        db.prepare("SELECT count(*), coalesce(sum(length(CAST(content AS BLOB))+length(CAST(category AS BLOB))+coalesce(length(CAST(source_session_id AS BLOB)),0)),0) FROM memories").use {
            check(it.step()); MemoryUsage(it.getLong(0).toInt(), it.getLong(1))
        }
    }
    fun get(id: String): MemoryEntry? = database.access {
        requireId(id)
        db.prepare("SELECT * FROM memories WHERE id=?").use { it.bindText(1, id); if (it.step()) it.entry() else null }
    }
    /** Literal substring matching (including '%' and '_'), bounded paging, stable ordering. */
    fun search(query: String = "", category: String? = null, limit: Int = 50, offset: Int = 0,
        requireEnabled: Boolean = false): List<MemoryEntry> = database.access {
        require(query.length <= MAX_QUERY_CHARS && HistoryCodec.isUtf8(query))
        require(limit in 1..100 && offset in 0..MAX_ENTRIES)
        val keyword = normalize(query)
        val filter = category?.let(::category)
        if (requireEnabled) checkEnabled()
        db.prepare("""SELECT * FROM memories WHERE (instr(lower(content),lower(?))>0 OR instr(lower(category),lower(?))>0)
            AND (? IS NULL OR category=?) ORDER BY pinned DESC, updated_at DESC, id LIMIT ? OFFSET ?""").use {
            it.bindText(1, keyword); it.bindText(2, keyword)
            if (filter == null) { it.bindNull(3); it.bindNull(4) } else { it.bindText(3, filter); it.bindText(4, filter) }
            it.bindLong(5, limit.toLong()); it.bindLong(6, offset.toLong())
            buildList { while (it.step()) add(it.entry()) }
        }
    }

    /** Same normalized category/content updates the existing ID. Omitted pinned preserves it.
     * An explicit edit cannot silently overwrite a second entry with matching content. */
    fun save(content: String, category: String = "general", pinned: Boolean? = null,
        sourceSessionId: String? = null, id: String? = null, requireEnabled: Boolean = false): MemoryEntry = database.access {
        val text = normalize(content)
        require(text.isNotBlank() && text.length <= MAX_CONTENT_CHARS && HistoryCodec.isUtf8(text) && '\u0000' !in text)
        val group = category(category)
        sourceSessionId?.let { require(it.isNotBlank() && it.length <= 256 && HistoryCodec.isUtf8(it) && '\u0000' !in it) }
        if (requireEnabled) checkEnabled()
        val editing = id?.let { requireNotNull(get(it)) { "Memory no longer exists" } }
        val duplicate = db.prepare("SELECT * FROM memories WHERE category=? AND content=?").use {
            it.bindText(1, group); it.bindText(2, text); if (it.step()) it.entry() else null
        }
        require(editing == null || duplicate == null || duplicate.id == editing.id) { "Duplicate memory" }
        val previous = editing ?: duplicate
        val now = maxOf(0, clock(), previous?.updatedAt ?: 0)
        val entry = MemoryEntry(previous?.id ?: UUID.randomUUID().toString(), group, text,
            pinned ?: previous?.pinned ?: false, previous?.createdAt ?: now, now,
            if (editing != null) editing.sourceSessionId else sourceSessionId ?: previous?.sourceSessionId)
        val usage = usage()
        if ((previous == null && usage.count >= MAX_ENTRIES) ||
            usage.bytes - (previous?.bytes() ?: 0) + entry.bytes() > MAX_TOTAL_BYTES) throw MemoryCapacityException()
        database.transaction {
            db.prepare("""INSERT INTO memories VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                category=excluded.category,content=excluded.content,pinned=excluded.pinned,
                updated_at=excluded.updated_at,source_session_id=excluded.source_session_id""").use {
                it.bindText(1, entry.id); it.bindText(2, entry.category); it.bindText(3, entry.content)
                it.bindLong(4, if (entry.pinned) 1 else 0); it.bindLong(5, entry.createdAt); it.bindLong(6, entry.updatedAt)
                if (entry.sourceSessionId == null) it.bindNull(7) else it.bindText(7, entry.sourceSessionId)
                it.step()
            }
        }
        changed(); entry
    }
    fun pin(id: String, pinned: Boolean) = database.access {
        val old = requireNotNull(get(id)) { "Memory no longer exists" }
        save(old.content, old.category, pinned, id = id)
    }
    fun delete(id: String, requireEnabled: Boolean = false): Boolean = database.access {
        requireId(id); if (requireEnabled) checkEnabled()
        if (get(id) == null) return@access false
        database.transaction { db.prepare("DELETE FROM memories WHERE id=?").use { it.bindText(1, id); it.step() } }
        changed(); true
    }
    fun clear() = database.access { database.transaction { db.execute("DELETE FROM memories") }; changed() }

    /** A consistent enabled/content snapshot; search itself never changes recency. */
    fun promptEntries(): List<MemoryEntry> = database.access {
        if (!enabled()) emptyList() else search(limit = 100)
    }
    private fun checkEnabled() { if (!enabled()) throw MemoryDisabledException() }
    private fun changed() { revision.value += 1 }
    private fun SQLiteStatement.entry() = MemoryEntry(getText(0), getText(1), getText(2), getLong(3) == 1L,
        getLong(4), getLong(5), if (isNull(6)) null else getText(6))
    private fun MemoryEntry.bytes(): Long = listOf(category, content, sourceSessionId.orEmpty()).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
    companion object {
        const val MAX_CONTENT_CHARS = 2000
        const val MAX_QUERY_CHARS = 200
        const val MAX_ENTRIES = 500
        const val MAX_TOTAL_BYTES = 512 * 1024L
        fun normalize(value: String): String = Normalizer.normalize(value.trim().replace("\r\n", "\n"), Normalizer.Form.NFC)
        private fun category(value: String): String = normalize(value).also {
            require(it.isNotBlank() && it.length <= 64 && HistoryCodec.isUtf8(it) && it.none(Char::isISOControl))
        }
        private fun requireId(id: String) { require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) }
    }
}
