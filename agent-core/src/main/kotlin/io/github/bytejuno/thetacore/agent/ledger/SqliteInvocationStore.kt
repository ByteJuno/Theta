package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.SQLiteDriver
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest

/** Fault points are transaction boundaries, not simulated filesystem publication. */
enum class SqliteWriteStage { AFTER_BEGIN, AFTER_MANIFEST_CHUNK, AFTER_CONTENT_CHUNK, AFTER_CONTENT, AFTER_RECORD_CHUNK, AFTER_RECORD, BEFORE_COMMIT, AFTER_COMMIT }

/** Synchronous SQLite store: confirmation follows FULL WAL commit, never precedes it.
 * Content is chunked inside SQLite so a range read costs O(requested chunks), not O(full result).
 * Full hashes are checked on open/publication; every returned chunk is checked against the
 * verified manifest from that same immutable reference. Damaged content is unavailable, but
 * cannot erase ledger evidence or prevent cold recovery of unrelated invocations.
 */
class SqliteInvocationStore private constructor(
    private val database: AgentDatabase,
    private val fault: (SqliteWriteStage) -> Unit,
    private val ownsDatabase: Boolean,
) : InvocationStore {
    private val db = database.connection
    private val codec = InvocationRecordCodec(Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true })
    private var closed = false
    private var poisoned = false
    private var bound = false
    /** Test-only fatal failure seam before publishing the confirmed record projection. */
    internal var afterCacheUpdate: () -> Unit = {}
    private val recordsById = loadRecords().associateByTo(linkedMapOf()) { it.callId }
    private var roots = BlobReferenceIndex(recordsById.values.filterNot { isSessionDeleted(it.input.sessionId) })
    private val manifests = mutableMapOf<String, ByteArray>()
    private val unavailable = mutableSetOf<String>()
    internal var blobBytesRead = 0L
        private set

    init {
        roots.liveReferences().distinct().forEach { ref ->
            try { verifyContent(ref) } catch (_: Exception) { unavailable += ref.contentHash }
        }
    }

    override fun checkAdmission() = synchronized(database) {
        database.ready()
        if (closed || poisoned) throw StoreException("Store closed or uncertain; reopen and recover", poisoned)
    }
    override fun bindCoordinator() = synchronized(database) {
        check(!closed && !bound) { "Store already has a coordinator" }
        bound = true
    }
    override fun records(): List<InvocationRecord> { synchronized(database) {
        // Immutable last-confirmed projection remains available after an uncertain commit.
        check(!closed)
        return recordsById.values.map { it.detached() }
    } }
    private fun loadRecords(): List<InvocationRecord> = try {
        val records = db.prepare("SELECT ${textColumn("call_id", 36)}, ${integerColumn("revision")}, ${integerColumn("size")}, ${textColumn("hash", 64)} FROM invocations ORDER BY rowid").use { statement ->
            buildList {
                while (statement.step()) {
                    require(!statement.isNull(0) && !statement.isNull(3)) { "Invalid record identity/hash type or size" }
                    val id = statement.getText(0)
                    try {
                        require(id.length <= 36 && statement.getLong(1) > 0) { "Invalid call identity or revision" }
                        val bytes = db.readRecordChunks(id, statement.getLong(2), statement.getText(3))
                        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                        val record = codec.decode(text)
                        record.validate()
                        require(record.callId == id && record.revision == statement.getLong(1)) { "Record binding mismatch" }
                        add(record)
                    } catch (error: Exception) { throw StoreException("Invalid invocation $id", cause = error) }
                }
            }
        }
        require(records.map { listOf(it.input.sessionId, it.input.runId, it.input.stepId, it.input.callOrder.toString()) }.distinct().size == records.size) { "Duplicate step order" }
        require(db.longValue("SELECT count(*) FROM record_chunks c WHERE NOT EXISTS(SELECT 1 FROM invocations i WHERE i.call_id=c.call_id)") == 0L) { "Orphan record chunks" }
        val leases = records.filter { it.instance?.state == InstanceState.UNCONFIRMED }.mapNotNull { it.input.definition?.exclusiveResource }
        require(leases.distinct().size == leases.size) { "Contradictory active resource leases" }
        val owners = db.prepare("SELECT ${textColumn("call_id", 36)}, ${textColumn("hash", 64)} FROM invocation_contents").use { statement ->
            buildSet {
                while (statement.step()) {
                    require(!statement.isNull(0) && !statement.isNull(1)) { "Invalid content ownership metadata" }
                    add(statement.getText(0) to statement.getText(1))
                }
            }
        }
        require(owners == records.filterNot { isSessionDeleted(it.input.sessionId) }.flatMap { record -> record.blobReferences().map { record.callId to it.contentHash } }.toSet()) {
            "Corrupt content ownership index"
        }
        records
    } catch (error: StoreException) { throw error }
    catch (error: Exception) { throw StoreException("Cannot read invocation ledger", cause = error) }

    private fun verifyContent(ref: BlobRef) {
        val manifest = db.readManifest(ref)
        val digest = MessageDigest.getInstance("SHA-256")
        db.prepare("SELECT ${integerColumn("ordinal")}, ${blobColumn("bytes", BLOB_CHUNK_SIZE)} FROM content_chunks WHERE hash=? ORDER BY ordinal").use {
            it.bindText(1, ref.contentHash)
            var count = 0L
            var ordinal = 0
            while (it.step()) {
                require(it.getLong(0) == ordinal.toLong()) { "Result chunk is missing" }
                require(!it.isNull(1)) { "Invalid chunk storage type" }
                val bytes = it.getBlob(1)
                blobBytesRead += bytes.size
                require(count < ref.sizeBytes && bytes.size.toLong() == minOf(BLOB_CHUNK_SIZE.toLong(), ref.sizeBytes - count)) { "Result chunk size is corrupt" }
                require(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(manifest.copyOfRange(ordinal * 32, ordinal * 32 + 32))) { "Result chunk is corrupt" }
                digest.update(bytes); count += bytes.size; ordinal++
            }
            require(count == ref.sizeBytes) { "Result content is incomplete" }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == ref.contentHash) { "Result content hash is corrupt" }
        manifests[ref.contentHash] = manifest
    }

    override fun readBlob(ref: BlobRef, offset: Long, limit: Int): ByteArray { synchronized(database) {
        database.ready(); check(!closed && !poisoned) { "Reopen and recover required" }
        require(offset in 0..ref.sizeBytes && limit in 1..8192)
        require(roots.contains(ref)) { "Unreferenced blob" }
        if (ref.contentHash in unavailable) throw StoreException("Result content is unavailable: missing or corrupt")
        try {
            // Trusted manifest is anchored to the full content hash on open/publication.
            // Do not reread O(total chunks) metadata on every bounded range request.
            val manifest = requireNotNull(manifests[ref.contentHash]) { "Result content not verified" }
            db.prepare("SELECT ${integerColumn("size")} FROM contents WHERE hash=?").use {
                it.bindText(1, ref.contentHash)
                require(it.step() && it.getLong(0) == ref.sizeBytes) { "Result content missing or corrupt" }
            }
            val end = offset + minOf(limit.toLong(), ref.sizeBytes - offset)
            val result = java.io.ByteArrayOutputStream()
            var start = offset / BLOB_CHUNK_SIZE * BLOB_CHUNK_SIZE
            db.prepare("SELECT ${blobColumn("bytes", BLOB_CHUNK_SIZE)} FROM content_chunks WHERE hash=? AND ordinal=?").use { statement ->
                while (start < end) {
                    val ordinal = start / BLOB_CHUNK_SIZE
                    statement.bindText(1, ref.contentHash); statement.bindLong(2, ordinal)
                    require(statement.step()) { "Result chunk is missing" }
                    require(!statement.isNull(0)) { "Invalid chunk storage type" }
                    val bytes = statement.getBlob(0)
                    blobBytesRead += bytes.size
                    require(bytes.size.toLong() == minOf(BLOB_CHUNK_SIZE.toLong(), ref.sizeBytes - start)) { "Result chunk size is corrupt" }
                    val index = (ordinal * 32).toInt()
                    require(MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(manifest.copyOfRange(index, index + 32))) { "Result chunk is corrupt" }
                    val from = maxOf(offset - start, 0).toInt()
                    val to = minOf(end - start, bytes.size.toLong()).toInt()
                    result.write(bytes, from, to - from)
                    start += bytes.size
                    statement.reset()
                }
            }
            return result.toByteArray()
        } catch (error: Exception) {
            unavailable += ref.contentHash
            throw StoreException("Result content is unavailable: missing or corrupt", cause = error)
        }
    } }

    override fun save(record: InvocationRecord, expectedRevision: Long?) = saveWithBlobs(record, expectedRevision, emptyList())

    override fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) { synchronized(database) {
        check(!closed); database.ready()
        if (poisoned) throw StoreException("Reopen and recover required", true)
        record.validate()
        val previous = recordsById[record.callId]
        require(previous?.revision == expectedRevision && record.revision == (expectedRevision ?: 0) + 1) { "Revision conflict" }
        val snapshot = record.detached()
        val isDeleted = isSessionDeleted(record.input.sessionId)
        require(!isDeleted || (previous != null && blobs.isEmpty() &&
            snapshot.blobReferences().toSet() == previous.blobReferences().toSet())) {
            "Deleted sessions cannot publish content or new calls"
        }
        val refs = if (isDeleted) emptySet() else snapshot.blobReferences().toSet()
        require(blobs.all { it.ref in refs }) { "Unowned blob publication" }
        val oldRefs = if (isDeleted) emptySet() else previous?.blobReferences().orEmpty().toSet()
        val published = mutableSetOf<String>()
        var commitAttempted = false
        var begun = false
        try {
            db.execute("BEGIN IMMEDIATE"); begun = true
            db.verifyWriteConfiguration()
            fault(SqliteWriteStage.AFTER_BEGIN)
            for (blob in blobs.distinctBy { it.ref.contentHash }) {
                val ref = blob.ref
                val exists = db.prepare("SELECT 1 FROM contents WHERE hash=?").use { it.bindText(1, ref.contentHash); it.step() }
                // Content damaged after startup verification is quarantined here, then repaired
                // by this explicit publication instead of blocking the identical result forever.
                val healthy = exists && ref.contentHash !in unavailable &&
                    runCatching { verifyContent(ref) }.onFailure { unavailable += ref.contentHash }.isSuccess
                if (!healthy) {
                    // Explicit identical-byte publication repairs quarantined content, not a
                    // bookkeeping save. Child rows are replaced in this same transaction.
                    if (exists) {
                        for (table in listOf("content_manifests", "content_chunks")) {
                            db.prepare("DELETE FROM $table WHERE hash=?").use { it.bindText(1, ref.contentHash); it.step() }
                        }
                    }
                    db.prepare("INSERT INTO contents(hash,size) VALUES(?,?) ON CONFLICT(hash) DO UPDATE SET size=excluded.size").use {
                        it.bindText(1, ref.contentHash); it.bindLong(2, ref.sizeBytes); it.step()
                    }
                    db.writeManifest(ref.contentHash, blob.manifest()) { fault(SqliteWriteStage.AFTER_MANIFEST_CHUNK) }
                    val bytes = blob.bytes()
                    db.prepare("INSERT INTO content_chunks(hash,ordinal,bytes) VALUES(?,?,?)").use {
                        var start = 0
                        while (start < bytes.size) {
                            val end = minOf(start + BLOB_CHUNK_SIZE, bytes.size)
                            it.bindText(1, ref.contentHash); it.bindLong(2, (start / BLOB_CHUNK_SIZE).toLong())
                            it.bindBlob(3, bytes.copyOfRange(start, end)); it.step(); it.reset(); start = end
                            fault(SqliteWriteStage.AFTER_CONTENT_CHUNK)
                        }
                    }
                    verifyContent(ref)
                }
                published += ref.contentHash
            }
            // Existing evidence may reference quarantined content. Do not prevent cold recovery,
            // actual-exit or approval bookkeeping; new references must be verified before commit.
            (refs - oldRefs).forEach { verifyContent(it) }
            fault(SqliteWriteStage.AFTER_CONTENT)
            val payload = codec.encode(snapshot).toByteArray(Charsets.UTF_8)
            if (expectedRevision == null) {
                db.prepare("INSERT INTO invocations(call_id,revision,size,hash) VALUES(?,?,?,?)").use {
                    it.bindText(1, snapshot.callId); it.bindLong(2, snapshot.revision)
                    it.bindLong(3, payload.size.toLong()); it.bindText(4, blobDigest(payload)); it.step()
                }
            } else {
                db.prepare("UPDATE invocations SET revision=?,size=?,hash=? WHERE call_id=? AND revision=?").use {
                    it.bindLong(1, snapshot.revision); it.bindLong(2, payload.size.toLong()); it.bindText(3, blobDigest(payload))
                    it.bindText(4, snapshot.callId); it.bindLong(5, expectedRevision); it.step()
                }
                require(db.longValue("SELECT changes()") == 1L) { "Revision conflict" }
            }
            db.writeRecordChunks(snapshot.callId, payload) { fault(SqliteWriteStage.AFTER_RECORD_CHUNK) }
            db.prepare("DELETE FROM invocation_contents WHERE call_id=?").use { it.bindText(1, snapshot.callId); it.step() }
            db.prepare("INSERT INTO invocation_contents(call_id,hash) VALUES(?,?)").use {
                for (hash in refs.map { ref -> ref.contentHash }.distinct()) {
                    it.bindText(1, snapshot.callId); it.bindText(2, hash); it.step(); it.reset()
                }
            }
            for (hash in (oldRefs - refs).map { it.contentHash }.distinct()) {
                db.prepare("DELETE FROM contents WHERE hash=? AND NOT EXISTS(SELECT 1 FROM invocation_contents WHERE hash=?)").use {
                    it.bindText(1, hash); it.bindText(2, hash); it.step()
                }
            }
            fault(SqliteWriteStage.AFTER_RECORD)
            fault(SqliteWriteStage.BEFORE_COMMIT)
            commitAttempted = true
            db.execute("COMMIT"); begun = false
            fault(SqliteWriteStage.AFTER_COMMIT)
            if (!isDeleted) roots.replace(previous, snapshot)
            // Only a confirmed, fully verified publication repairs quarantine. Failed or
            // uncertain commits never unquarantine previously damaged evidence.
            unavailable.removeAll(published)
            for (ref in oldRefs - refs) if (!roots.ownsFile("${ref.contentHash}.blob")) {
                manifests.remove(ref.contentHash)
                unavailable.remove(ref.contentHash)
            }
            afterCacheUpdate()
            // Publish the read-only projection last, after all other fallible cache work.
            recordsById[snapshot.callId] = snapshot
        } catch (error: Throwable) {
            val rolledBack = !begun || runCatching { db.execute("ROLLBACK") }.isSuccess
            // Fatal failures may interrupt cache maintenance too. Even a confirmed rollback
            // cannot establish that the in-memory owner is safe for further admission.
            poisoned = error !is Exception || commitAttempted || !rolledBack
            if (poisoned) database.poison()
            if (error !is Exception) throw error
            throw StoreException("Invocation transaction failed", poisoned, error)
        }
    } }

    override fun isSessionDeleted(sessionId: String): Boolean = synchronized(database) {
        checkAdmission()
        try { database.isSessionDeleted(sessionId) }
        catch (error: Exception) { throw StoreException("Cannot check session deletion", cause = error) }
    }

    override fun deleteSession(sessionId: String) = synchronized(database) {
        checkAdmission()
        database.checkSessionDeletion(sessionId)
        // A previous attempt may have committed the tombstone before scratch cleanup.
        if (isSessionDeleted(sessionId)) {
            database.cleanSessionScratch(sessionId)
            return@synchronized
        }
        val members = recordsById.values.filter { it.input.sessionId == sessionId }
        require(members.all { it.state.terminal && it.instance?.state != InstanceState.UNCONFIRMED }) {
            "Session has unfinished invocations"
        }
        val hashes = members.flatMap { it.blobReferences() }.map { it.contentHash }.toSet()
        try {
            database.transaction {
                fault(SqliteWriteStage.AFTER_BEGIN)
                db.prepare("INSERT INTO deleted_sessions(session_id) VALUES(?)").use {
                    it.bindText(1, sessionId); it.step()
                }
                db.prepare("DELETE FROM invocation_contents WHERE call_id=?").use { statement ->
                    members.forEach { statement.bindText(1, it.callId); statement.step(); statement.reset() }
                }
                db.prepare("DELETE FROM contents WHERE hash=? AND NOT EXISTS(SELECT 1 FROM invocation_contents WHERE hash=?)").use { statement ->
                    hashes.forEach { statement.bindText(1, it); statement.bindText(2, it); statement.step(); statement.reset() }
                }
                for (table in listOf("history_values", "sessions")) db.prepare("DELETE FROM $table WHERE session_id=?").use {
                    it.bindText(1, sessionId); it.step()
                }
                fault(SqliteWriteStage.BEFORE_COMMIT)
            }
            fault(SqliteWriteStage.AFTER_COMMIT)
            database.cleanSessionScratch(sessionId)
            roots = BlobReferenceIndex(recordsById.values.filterNot { isSessionDeleted(it.input.sessionId) })
            hashes.filter { hash -> !roots.ownsFile("$hash.blob") }.forEach {
                manifests.remove(it); unavailable.remove(it)
            }
        } catch (error: Throwable) {
            poisoned = true; database.poison()
            if (error !is Exception) throw error
            throw StoreException("Session deletion acknowledgement uncertain; reopen and recover", true, error)
        }
    }

    override fun close() = synchronized(database) {
        if (!closed) {
            // Admission and owner teardown share the pin monitor. A rejected close must
            // leave this handle and its ledger binding intact, including after poison.
            if (ownsDatabase) database.close()
            closed = true
            database.releaseLedger()
        }
    }
    companion object {
        fun open(database: AgentDatabase, fault: (SqliteWriteStage) -> Unit = {}): SqliteInvocationStore =
            attach(database, fault, false)
        private fun attach(database: AgentDatabase, fault: (SqliteWriteStage) -> Unit, owned: Boolean): SqliteInvocationStore = database.access {
            database.bindLedger()
            try { SqliteInvocationStore(database, fault, owned) }
            catch (error: Throwable) { database.releaseLedger(); throw error }
        }

        fun open(file: Path, driver: SQLiteDriver, fault: (SqliteWriteStage) -> Unit = {}): SqliteInvocationStore {
            val database = AgentDatabase.open(file, driver)
            try { return attach(database, fault, true) }
            catch (error: Throwable) {
                runCatching { database.close() }
                if (error !is Exception || error is StoreException) throw error
                throw StoreException("Cannot open invocation ledger", cause = error)
            }
        }
    }
}
