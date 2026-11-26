package io.github.bytejuno.thetacore.mcp

import io.github.bytejuno.thetacore.agent.mcp.*
import io.github.bytejuno.thetacore.storage.SecretCipher
import io.github.bytejuno.thetacore.storage.isDefinitiveKeyInvalidation
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID

internal class McpSettingsException(val replacementAllowed: Boolean = false) :
    Exception("MCP settings could not be read or saved")
internal class McpConnection(val id: String, val name: String, val url: String,
    val enabled: Boolean, val token: String = "", val header: String = "Authorization",
    val revision: String = UUID.randomUUID().toString()) {
    init {
        require(validMcpName(id) && name.isNotBlank() && name.length <= 128 && name.none { it.isISOControl() })
        McpEndpoint(url, token, header)
        require(UUID.fromString(revision).toString() == revision)
    }
    fun enabled(value: Boolean) = McpConnection(id, name, url, value, token, header, revision)
    override fun toString() = "McpConnection(redacted)"
}

/** One encrypted atomic catalog in Android noBackupFilesDir. A read failure never replaces it. */
internal class McpConnectionStore(directory: File, private val cipher: SecretCipher,
    private val invalidKey: (Throwable) -> Boolean = ::isDefinitiveKeyInvalidation) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val path = root.resolve("mcp-connections.bin")
    private val lock = locks[(path.toString().hashCode() and Int.MAX_VALUE) % locks.size]
    fun read(): List<McpConnection> = synchronized(lock) { sanitized { readLocked() } }
    private fun readLocked(): List<McpConnection> {
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        if (Files.notExists(path, NOFOLLOW_LINKS)) return emptyList()
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= MAX_BYTES)
        val encrypted = Files.readAllBytes(path)
        val plaintext = try { cipher.decrypt(encrypted) } catch (e: Exception) {
            throw McpSettingsException(invalidKey(e) || e is javax.crypto.AEADBadTagException)
        }
        try {
            val records = Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonArray.map { e ->
                val o = e.jsonObject
                McpConnection(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
                    o.getValue("url").jsonPrimitive.content, o.getValue("enabled").jsonPrimitive.boolean,
                    o.getValue("token").jsonPrimitive.content, o.getValue("header").jsonPrimitive.content,
                    o.getValue("revision").jsonPrimitive.content)
            }
            check(records.map { it.id }.distinct().size == records.size)
            return records
        } finally { plaintext.fill(0) }
    }
    fun save(value: McpConnection) = mutate { old -> old.filterNot { it.id == value.id } + value }
    fun delete(id: String) = mutate { old -> old.filterNot { it.id == id } }
    /** UI explicitly confirms replacing the unreadable catalog, never an ordinary save/read. */
    fun replaceUnreadable(value: McpConnection) = synchronized(lock) { sanitized {
        val failure = try { readLocked(); null } catch (e: McpSettingsException) { e }
        check(failure?.replacementAllowed == true)
        writeLocked(listOf(value), replacement = true)
    } }
    private fun mutate(action: (List<McpConnection>) -> List<McpConnection>) = synchronized(lock) {
        sanitized { writeLocked(action(readLocked()), replacement = false) }
    }
    private fun writeLocked(next: List<McpConnection>, replacement: Boolean) {
            val plaintext = JsonArray(next.map { c -> buildJsonObject {
                put("id", c.id); put("name", c.name); put("url", c.url); put("enabled", c.enabled)
                put("token", c.token); put("header", c.header)
                put("revision", c.revision)
            } }).toString().toByteArray(Charsets.UTF_8)
            val encrypted = try {
                check(plaintext.size <= MAX_BYTES - 1024)
                if (replacement) cipher.encryptForReplacement(plaintext) else cipher.encrypt(plaintext)
            }
                finally { plaintext.fill(0) }
            val temp = root.resolve("mcp-${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(encrypted)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
    }
    private fun <T> sanitized(action: () -> T): T = try { action() }
        catch (e: McpSettingsException) { throw e }
        catch (_: Exception) { throw McpSettingsException() }
    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private val locks = Array(32) { Any() }
    }
}
