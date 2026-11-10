package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** PROCESS_CRASH_ONLY explicitly makes no power-loss durability promise (Windows JVM has no
 * portable directory fsync). REQUIRE_DIRECTORY_SYNC refuses unsupported filesystems/platforms.
 * Android must supply and validate its own platform durability adapter before integration.
 */
enum class Durability { PROCESS_CRASH_ONLY, REQUIRE_DIRECTORY_SYNC }
enum class WriteStage { BEFORE_TEMP, AFTER_WRITE, AFTER_FILE_SYNC, BEFORE_REPLACE, AFTER_REPLACE, AFTER_DIRECTORY_SYNC,
    AFTER_BLOB_WRITE, AFTER_BLOB_PUBLISH, AFTER_CHUNKS_WRITE, AFTER_CHUNKS_PUBLISH, AFTER_BLOB_DIRECTORY_SYNC }

/** Files.readString does not exist below Android API 36.1. A fresh decoder still rejects malformed UTF-8. */
private fun readUtf8(path: Path): String =
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()

class JsonInvocationStore private constructor(
    private val directory: Path, private val durability: Durability,
    private val fault: (WriteStage) -> Unit,
    private val lockChannel: FileChannel, private val lock: FileLock,
) : InvocationStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = true }
    private var poisoned = false
    private var closed = false
    private var coordinatorBound = false
    @Synchronized override fun bindCoordinator() {
        check(!closed && !coordinatorBound) { "Store already has a coordinator" }
        coordinatorBound = true
    }
    private val codec = InvocationRecordCodec(json)
    private val chunkTables = mutableMapOf<String, ByteArray>()
    internal var blobBytesRead: Long = 0
        private set
    private val recordsById = loadRecords().associateByTo(linkedMapOf()) { it.callId }
    private val roots = BlobReferenceIndex(recordsById.values)
    internal val blobRootVisits get() = roots.rootVisits
    internal val blobRecordVisits get() = roots.recordVisits
    internal var orphanDirectoryScans = 0L
        private set
    internal var orphanCandidates = 0L
        private set

    init {
        // The OS writer lock is already held. Verify every root before deleting any orphan;
        // corrupt/missing roots fail closed, including terminal and late-return evidence.
        roots.liveReferences().distinctBy { Triple(it.contentHash, it.sizeBytes, it.chunksHash) }.forEach { verifyBlob(it) }
        collectOrphans()
    }

    // Trusted immutable digest tables, NOT a cache of file validity. Each requested chunk is
    // read once, hashed, then returned from that same buffer (no check/use second read).
    // Startup validates all unique roots in O(total live bytes), including legacy roots.
    private fun blobPath(ref: BlobRef) = directory.resolve("${ref.contentHash}.blob")
    private fun chunksPath(ref: BlobRef) = directory.resolve("${ref.contentHash}.chunks")
    private fun verifyBlob(ref: BlobRef) {
        val path = blobPath(ref)
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path)) { "Missing blob" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val table = java.io.ByteArrayOutputStream()
        FileChannel.open(path, READ).use { channel ->
            require(channel.size() == ref.sizeBytes) { "Corrupt blob size" }
            var offset = 0L
            while (offset < ref.sizeBytes) {
                val bytes = readBytes(channel, minOf(BLOB_CHUNK_SIZE.toLong(), ref.sizeBytes - offset).toInt())
                digest.update(bytes)
                table.write(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                offset += bytes.size
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == ref.contentHash) { "Corrupt blob hash" }
        val hashes = table.toByteArray()
        if (ref.chunksHash != null) {
            val manifest = chunksPath(ref)
            require(!Files.isSymbolicLink(manifest) && Files.isRegularFile(manifest)) { "Missing chunk manifest" }
            require(Files.size(manifest) == hashes.size.toLong()) { "Corrupt chunk manifest size" }
            val persisted = Files.readAllBytes(manifest)
            require(blobDigest(persisted) == ref.chunksHash && persisted.contentEquals(hashes)) { "Corrupt chunk manifest" }
        }
        chunkTables[ref.contentHash] = hashes
    }
    private fun readBytes(channel: FileChannel, count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            check(read > 0) { "Short blob read" }
            blobBytesRead += read
        }
        return buffer.array()
    }
    private fun collectOrphans() {
        check(!poisoned)
        val live = roots.liveFiles()
        chunkTables.keys.retainAll(roots.liveReferences().map { it.contentHash }.toSet())
        orphanDirectoryScans++
        Files.newDirectoryStream(directory).use { paths -> paths.forEach { path ->
            val name = path.fileName.toString()
            if ((name.matches(Regex("[0-9a-f]{64}\\.(blob|chunks)")) && name !in live) ||
                (name.startsWith(".") && name.endsWith(".tmp"))) Files.delete(path)
        } }
        syncDirectory(directory, durability)
    }
    private fun collectCandidates(candidates: Set<String>) {
        check(!poisoned)
        if (candidates.isEmpty()) return
        for (name in candidates) {
            orphanCandidates++
            if (!roots.ownsFile(name)) {
                Files.deleteIfExists(directory.resolve(name))
                if (name.endsWith(".blob")) chunkTables.remove(name.removeSuffix(".blob"))
            }
        }
        syncDirectory(directory, durability)
    }
    @Synchronized override fun readBlob(ref: BlobRef, offset: Long, limit: Int): ByteArray {
        check(!closed && !poisoned)
        require(offset in 0..ref.sizeBytes && limit in 1..8192)
        require(roots.contains(ref)) { "Unreferenced blob" }
        val hashes = requireNotNull(chunkTables[ref.contentHash]) { "Blob not validated at publication/startup" }
        require(!Files.isSymbolicLink(blobPath(ref))) { "Invalid blob path" }
        return FileChannel.open(blobPath(ref), READ).use { channel ->
            require(channel.size() == ref.sizeBytes) { "Corrupt blob size" }
            val end = offset + minOf(limit.toLong(), ref.sizeBytes - offset)
            val result = java.io.ByteArrayOutputStream()
            var start = offset / BLOB_CHUNK_SIZE * BLOB_CHUNK_SIZE
            while (start < end) {
                channel.position(start)
                val bytes = readBytes(channel, minOf(BLOB_CHUNK_SIZE.toLong(), ref.sizeBytes - start).toInt())
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                val index = (start / BLOB_CHUNK_SIZE * 32).toInt()
                require(digest.contentEquals(hashes.copyOfRange(index, index + 32))) { "Corrupt blob chunk" }
                val from = maxOf(offset - start, 0).toInt()
                val to = minOf(end - start, bytes.size.toLong()).toInt()
                result.write(bytes, from, to - from)
                start += bytes.size
            }
            result.toByteArray()
        }
    }
    @Synchronized override fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) {
        check(!closed)
        if (poisoned) throw StoreException("Reopen and recover required", true)
        record.validate()
        require(recordsById[record.callId]?.revision == expectedRevision && record.revision == (expectedRevision ?: 0) + 1) { "Revision conflict" }
        val refs = record.blobReferences()
        require(blobs.all { it.ref in refs }) { "Unowned blob publication" }
        try {
            for (blob in blobs.distinctBy { it.ref.id }) {
                val destination = blobPath(blob.ref)
                if (!Files.exists(destination)) {
                    val temporary = directory.resolve(".${blob.ref.id}-${UUID.randomUUID()}.tmp")
                    try {
                        FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
                            val bytes = ByteBuffer.wrap(blob.bytes())
                            while (bytes.hasRemaining()) channel.write(bytes)
                            channel.force(true)
                            fault(WriteStage.AFTER_BLOB_WRITE)
                        }
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
                        fault(WriteStage.AFTER_BLOB_PUBLISH)
                    } finally { runCatching { Files.deleteIfExists(temporary) } }
                }
                val manifest = chunksPath(blob.ref)
                if (!Files.exists(manifest)) {
                    val temporary = directory.resolve(".${blob.ref.contentHash}-${UUID.randomUUID()}.tmp")
                    try {
                        FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
                            val bytes = ByteBuffer.wrap(blob.manifest())
                            while (bytes.hasRemaining()) channel.write(bytes)
                            channel.force(true)
                            fault(WriteStage.AFTER_CHUNKS_WRITE)
                        }
                        Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE)
                        fault(WriteStage.AFTER_CHUNKS_PUBLISH)
                    } finally { runCatching { Files.deleteIfExists(temporary) } }
                }
                verifyBlob(blob.ref)
            }
            // Also re-sync existing files after an earlier uncertain publication.
            syncDirectory(directory, durability)
            fault(WriteStage.AFTER_BLOB_DIRECTORY_SYNC)
            save(record, expectedRevision)
        } catch (error: Exception) {
            // An uncertain ledger replacement may already reference any published blob. Never
            // collect from the stale index in that case. Reopen reads the authoritative files.
            if (!poisoned) runCatching { collectCandidates(roots.candidates(blobs.map { it.ref })) }
            if (error is StoreException) throw error
            throw StoreException("Blob publication failed", false, error)
        }
    }

    @Synchronized override fun records(): List<InvocationRecord> {
        check(!closed)
        return recordsById.values.map { it.detached() }
    }

    private fun loadRecords(): List<InvocationRecord> {
        try {
            val records = Files.newDirectoryStream(directory, "*.json").use { paths ->
                paths.map { path ->
                    try {
                        val record = codec.decode(readUtf8(path))
                        record.validate()
                        require(path.fileName.toString() == "${record.callId}.json") { "Filename/binding mismatch" }
                        record
                    } catch (e: Exception) { throw StoreException("Invalid ledger file $path: ${e.message}", cause = e) }
                }
            }
            require(records.map { it.callId }.distinct().size == records.size)
            require(records.map { listOf(it.input.sessionId, it.input.runId, it.input.stepId, it.input.callOrder.toString()) }.distinct().size == records.size) {
                "Duplicate step order"
            }
            val activeResources = records.filter { it.instance?.state == InstanceState.UNCONFIRMED }
                .mapNotNull { it.input.definition?.exclusiveResource }
            require(activeResources.distinct().size == activeResources.size) { "Contradictory active resource leases" }
            return records
        } catch (e: StoreException) { throw e }
        catch (e: Exception) { throw StoreException("Cannot read ledger $directory: ${e.message}", cause = e) }
    }

    @Synchronized override fun save(record: InvocationRecord, expectedRevision: Long?) {
        check(!closed)
        if (poisoned) throw StoreException("Reopen and recover required", true)
        record.validate()
        require(recordsById[record.callId]?.revision == expectedRevision && record.revision == (expectedRevision ?: 0) + 1) { "Revision conflict" }
        val snapshot = record.detached()
        val temporary = directory.resolve(".${record.callId}-${UUID.randomUUID()}.tmp")
        val destination = directory.resolve("${record.callId}.json")
        var replacementAttempted = false
        try {
            // Disk failures are storage failures, but validation/revision mistakes above remain
            // programmer errors. No ledger replacement has been attempted at this point.
            snapshot.blobReferences().distinct().forEach { verifyBlob(it) }
            fault(WriteStage.BEFORE_TEMP)
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(codec.encode(snapshot).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                fault(WriteStage.AFTER_WRITE)
                channel.force(true)
                fault(WriteStage.AFTER_FILE_SYNC)
            }
            fault(WriteStage.BEFORE_REPLACE)
            replacementAttempted = true
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            fault(WriteStage.AFTER_REPLACE)
            syncDirectory(directory, durability)
            fault(WriteStage.AFTER_DIRECTORY_SYNC)
            val candidates = roots.replace(recordsById[record.callId], snapshot)
            recordsById[record.callId] = snapshot
            // No global history/directory walk on normal transitions or new retrieval records.
            // Cleanup failure is recoverable on reopen and must not undo an acknowledged commit.
            runCatching { collectCandidates(candidates) }
        } catch (e: Exception) {
            // Even move() throwing may leave outcome unknown on a remote/provider filesystem.
            poisoned = replacementAttempted
            throw StoreException("Write failed for ${record.callId} at revision ${record.revision}", replacementAttempted, e)
        } finally {
            // Never promote a temporary file, and never mask the primary storage exception.
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; try { lock.release() } finally { lockChannel.close() } }
    }
    companion object {
        private const val MARKER = "theta-invocation-ledger-v1"
        /** Explicit fresh initialization: refuses any nonempty existing directory. */
        fun create(directory: Path, durability: Durability, fault: (WriteStage) -> Unit = {}): JsonInvocationStore {
            Files.createDirectories(directory)
            Files.newDirectoryStream(directory).use { require(!it.iterator().hasNext()) { "Ledger directory is not empty" } }
            val marker = directory.resolve("ledger.identity")
            FileChannel.open(marker, CREATE_NEW, WRITE).use {
                val buffer = ByteBuffer.wrap(MARKER.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) it.write(buffer)
                it.force(true)
            }
            syncDirectory(directory, durability)
            return open(directory, durability, fault)
        }
        /** Missing marker/directory is NOT treated as a new installation. */
        fun open(directory: Path, durability: Durability, fault: (WriteStage) -> Unit = {}): JsonInvocationStore {
            require(readUtf8(directory.resolve("ledger.identity")) == MARKER) { "Missing/invalid ledger identity" }
            syncDirectory(directory, durability)
            val channel = FileChannel.open(directory.resolve("writer.lock"), CREATE, WRITE)
            var lock: FileLock? = null
            try {
                lock = channel.tryLock() ?: throw StoreException("Ledger already has a writer: $directory")
                return JsonInvocationStore(directory, durability, fault, channel, lock)
            } catch (e: Exception) {
                lock?.release(); channel.close()
                throw StoreException("Cannot open ledger $directory: ${e.message}", cause = e)
            }
        }
        private fun syncDirectory(directory: Path, durability: Durability) {
            if (durability == Durability.REQUIRE_DIRECTORY_SYNC) {
                FileChannel.open(directory, READ).use { it.force(true) }
            }
        }
    }
}
