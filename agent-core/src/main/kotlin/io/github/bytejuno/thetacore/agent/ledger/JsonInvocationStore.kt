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
enum class WriteStage { BEFORE_TEMP, AFTER_WRITE, AFTER_FILE_SYNC, BEFORE_REPLACE, AFTER_REPLACE, AFTER_DIRECTORY_SYNC }

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
    private val recordsById = loadRecords().associateByTo(linkedMapOf()) { it.callId }

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
            recordsById[record.callId] = snapshot
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
