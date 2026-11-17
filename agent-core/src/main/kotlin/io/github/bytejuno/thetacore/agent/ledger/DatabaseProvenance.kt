package io.github.bytejuno.thetacore.agent.ledger

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

/** Two monotonic bytes in the lifetime-lock inode, never replaced/unlinked: P (first
 * initialization pending), then E (established). Force P before SQLite can create files;
 * checkpoint the committed schema before forcing E, and never expose a writable store
 * before E is durable. A killed first initialization can retry P without losing evidence.
 * This is provenance, not a second ledger or a corruption-recovery mechanism.
 * Loss of BOTH the database and provenance is indistinguishable from a fresh install.
 * Directory fsync is supported on Android/POSIX. The Windows JVM has no directory-force
 * API: its tests establish process-crash behavior, not sudden-power-loss durability.
 */
internal class DatabaseProvenance(private val file: Path, private val channel: FileChannel) {
    private var established = false
    fun beforeOpen() {
        require(channel.size() <= 2) { "Invalid database provenance" }
        val bytes = ByteBuffer.allocate(channel.size().toInt())
        while (bytes.hasRemaining()) check(channel.read(bytes) > 0)
        val state = String(bytes.array(), Charsets.US_ASCII)
        require(state in listOf("", "P", "PE")) { "Invalid database provenance" }
        established = state == "PE"
        if (established) verifyHeader()
        // An unmarked, nonempty file is not an interrupted initialization. Never let an
        // Android helper turn its version-zero header into a fresh schema before checking it.
        if (state.isEmpty() && Files.exists(file) && Files.size(file) != 0L) verifyHeader()
        if (state.isEmpty()) {
            channel.position(0)
            writeByte('P')
            syncParent()
        }
    }
    fun establish() {
        if (!established) {
            verifyHeader()
            channel.position(1)
            writeByte('E')
            syncParent()
        }
    }
    private fun writeByte(value: Char) {
        val byte = ByteBuffer.wrap(byteArrayOf(value.code.toByte()))
        while (byte.hasRemaining()) channel.write(byte)
        channel.force(true)
    }
    private fun syncParent() {
        if (java.io.File.separatorChar != '\\') FileChannel.open(file.parent, READ).use { it.force(true) }
    }
    private fun verifyHeader() {
        require(Files.isRegularFile(file) && Files.size(file) >= 100) { "Established agent database is missing or truncated" }
        val header = ByteBuffer.allocate(100)
        FileChannel.open(file, READ).use { while (header.hasRemaining()) require(it.read(header) > 0) }
        require(header.array().copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)) &&
            header.getInt(60) in 1..AgentDatabase.SCHEMA_VERSION && header.getInt(68) == 0x54485441) {
            "Established agent database header is corrupt or unsupported"
        }
    }
}
