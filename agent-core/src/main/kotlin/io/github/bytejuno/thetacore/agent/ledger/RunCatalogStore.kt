package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.run.HistoryCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Durable storage for per-run frozen model-name -> FrozenDefinition mappings.
 * Once persisted for a runId, the mapping is immutable across process restarts.
 */
interface RunCatalogStore {
    fun saveRunCatalog(runId: String, definitions: List<FrozenDefinition>)
    fun loadRunCatalog(runId: String): List<FrozenDefinition>?
    fun loadAllRunCatalogs(): Map<String, List<FrozenDefinition>>
}

/** SQLite-backed durable run catalog store sharing [AgentDatabase]'s WAL/FULL transaction boundary.
 * Chunks payloads at [RECORD_CHUNK_SIZE] and bounds projected metadata columns to avoid CursorWindow
 * overflow on damaged rows. Corruption fails closed with [StoreException].
 */
class SqliteRunCatalogStore(
    private val database: AgentDatabase,
    private val fault: (SqliteWriteStage) -> Unit = {},
) : RunCatalogStore {
    private val db get() = database.connection
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true }

    override fun saveRunCatalog(runId: String, definitions: List<FrozenDefinition>): Unit = database.access {
        validateRunId(runId)
        val canonical = canonicalizeDefinitions(definitions)
        val existing = loadRunCatalogLocked(runId)
        if (existing != null) {
            check(existing == canonical) { "Run catalog is immutable for $runId" }
            return@access
        }
        val payload = escapeUnpairedSurrogatesForJson(json.encodeToString(canonical)).toByteArray(Charsets.UTF_8)
        val hash = sha256Hex(payload)
        try {
            database.transaction {
                fault(SqliteWriteStage.AFTER_BEGIN)
                db.prepare("INSERT INTO run_catalogs(run_id,size,hash) VALUES(?,?,?)").use {
                    it.bindText(1, runId)
                    it.bindLong(2, payload.size.toLong())
                    it.bindText(3, hash)
                    it.step()
                }
                db.prepare("INSERT INTO run_catalog_chunks(run_id,ordinal,bytes) VALUES(?,?,?)").use {
                    var start = 0
                    var ordinal = 0L
                    while (start < payload.size) {
                        val end = start + minOf(RECORD_CHUNK_SIZE, payload.size - start)
                        it.bindText(1, runId)
                        it.bindLong(2, ordinal++)
                        it.bindBlob(3, payload.copyOfRange(start, end))
                        it.step()
                        it.reset()
                        start = end
                        fault(SqliteWriteStage.AFTER_RECORD_CHUNK)
                    }
                }
                fault(SqliteWriteStage.BEFORE_COMMIT)
            }
            fault(SqliteWriteStage.AFTER_COMMIT)
        } catch (error: Throwable) {
            database.poison()
            if (error !is Exception) throw error
            if (error is StoreException) throw error
            throw StoreException("Run catalog transaction failed for $runId", uncertain = true, cause = error)
        }
    }

    override fun loadRunCatalog(runId: String): List<FrozenDefinition>? = database.access {
        validateRunId(runId)
        loadRunCatalogLocked(runId)
    }

    override fun loadAllRunCatalogs(): Map<String, List<FrozenDefinition>> = database.access {
        val ids = try {
            db.prepare("SELECT ${textColumn("run_id", MAX_RUN_ID_BYTES)} FROM run_catalogs ORDER BY run_id").use {
                buildList<String> {
                    while (it.step()) {
                        require(!it.isNull(0)) { "Invalid run_id metadata in run_catalogs" }
                        add(it.getText(0))
                    }
                }
            }
        } catch (error: Exception) {
            if (error is StoreException) throw error
            throw StoreException("Cannot list persisted run catalogs", cause = error)
        }
        linkedMapOf<String, List<FrozenDefinition>>().apply {
            for (id in ids) {
                put(id, loadRunCatalogLocked(id) ?: throw StoreException("Missing persisted run catalog $id"))
            }
        }
    }

    private fun loadRunCatalogLocked(runId: String): List<FrozenDefinition>? = try {
        val header = db.prepare(
            "SELECT ${integerColumn("size")}, ${textColumn("hash", 64)} FROM run_catalogs WHERE run_id=?",
        ).use {
            it.bindText(1, runId)
            if (!it.step()) return@use null
            require(!it.isNull(1)) { "Invalid run catalog hash metadata" }
            it.getLong(0) to it.getText(1)
        } ?: return null
        val (size, hash) = header
        require(size > 0 && hash.matches(HASH_PATTERN)) { "Invalid run catalog metadata" }
        val output = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var ordinal = 0L
        db.prepare(
            "SELECT ${integerColumn("ordinal")}, ${blobColumn("bytes", RECORD_CHUNK_SIZE)} FROM run_catalog_chunks WHERE run_id=? ORDER BY ordinal",
        ).use {
            it.bindText(1, runId)
            while (it.step()) {
                require(it.getLong(0) == ordinal) { "Run catalog chunk order/missing chunk" }
                require(!it.isNull(1)) { "Invalid run catalog chunk storage type" }
                val bytes = it.getBlob(1)
                require(count < size && bytes.size.toLong() == minOf(RECORD_CHUNK_SIZE.toLong(), size - count)) {
                    "Run catalog chunk size/extra chunk"
                }
                digest.update(bytes)
                output.write(bytes)
                count += bytes.size
                ordinal++
            }
        }
        require(count == size) { "Incomplete run catalog" }
        require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Run catalog checksum mismatch" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        val decoded = json.decodeFromString<List<FrozenDefinition>>(text)
        canonicalizeDefinitions(decoded)
    } catch (error: StoreException) {
        throw error
    } catch (error: Exception) {
        throw StoreException("Persisted run catalog for $runId is corrupt", cause = error)
    }

    private fun validateRunId(runId: String) {
        require(runId.isNotBlank()) { "Run id must not be blank" }
        HistoryCodec.requireUtf8(runId)
        require(runId.toByteArray(Charsets.UTF_8).size <= MAX_RUN_ID_BYTES) { "Run id too large" }
    }

    private fun canonicalizeDefinitions(definitions: List<FrozenDefinition>): List<FrozenDefinition> {
        val sorted = definitions.sortedBy { it.modelName }
        require(sorted.all { it.modelName.isNotBlank() && it.toolKey.isNotBlank() && it.name.isNotBlank() }) {
            "Frozen definition fields must not be blank"
        }
        require(sorted.map { it.modelName }.distinct().size == sorted.size) {
            "Duplicate model tool name in run catalog"
        }
        require(sorted.map { it.toolKey }.distinct().size == sorted.size) {
            "Duplicate tool key in run catalog"
        }
        return sorted
    }

    companion object {
        internal const val MAX_RUN_ID_BYTES = 256
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
