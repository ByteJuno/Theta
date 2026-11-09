package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException
import io.github.bytejuno.thetacore.sdk.PluginPrefs
import io.github.bytejuno.thetacore.sdk.PluginSecrets
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

internal typealias PluginSettingsCipher = io.github.bytejuno.thetacore.storage.SecretCipher

/** Keep the existing plugin envelope and authenticated namespace unchanged. */
internal fun AesGcmPluginSettingsCipher(
    pluginId: String,
    key: (create: Boolean) -> SecretKey,
): PluginSettingsCipher {
    checkId(pluginId)
    return io.github.bytejuno.thetacore.storage.AesGcmSecretCipher("theta-plugin-secrets-v1:" + pluginId, key)
}

/** Never delete/rotate this alias on update, rollback or ordinary clear-data. */
internal fun pluginSettingsKeyAlias(pluginId: String): String {
    checkId(pluginId)
    val hash = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "theta.plugin.secrets.v1.$hash"
}

/** Key lookup remains lazy. */
internal fun androidPluginSettingsCipher(pluginId: String): PluginSettingsCipher =
    io.github.bytejuno.thetacore.storage.androidSecretCipher(
        pluginSettingsKeyAlias(pluginId), "theta-plugin-secrets-v1:" + pluginId,
    )

/**
 * One host-owned lifecycle object per PluginContext. closeAdmission is memory-only and
 * may run in the Main/fault-handler closure path. Already-admitted operations may still
 * finish. Run close on an IO/cleanup worker before plugin cleanup callbacks and before
 * replacing/deleting dataDir: it drains admitted operations, after which no handle can
 * write again. Only internal host cipher code runs under the storage lock.
 *
 * Both stores are ordinary files under one reserved directory, included by the existing
 * plugin-data snapshot/rollback/delete tree. No plaintext secret temp files, cache, or
 * second rollback journal. Files are re-read for every operation, including after restore.
 * Striped process locks also serialize multiple owners of the same normalized path;
 * this is not cross-process coordination or a sandbox against full-trust native plugins.
 * Host snapshot/restore must run while owners are closed/quiescent, as for other dataDir IO.
 */
internal class PluginSettingsStorage(
    dataDir: File,
    private val cipher: PluginSettingsCipher,
) : AutoCloseable {
    private val data = pluginPath(dataDir, ".")
    private val lock = locks[(data.path.hashCode() and Int.MAX_VALUE) % locks.size]
    private val closed = AtomicBoolean(false)

    val prefs: PluginPrefs = object : PluginPrefs {
        override fun get(key: String): String? = read(false, key)
        override fun put(key: String, value: String?) = change(false, key, value)
        override fun remove(key: String) = change(false, key, null)
    }
    val secrets: PluginSecrets = object : PluginSecrets {
        override fun get(key: String): String? = read(true, key)
        override fun put(key: String, value: String?) = change(true, key, value)
        override fun remove(key: String) = change(true, key, null)
    }

    /** Nonblocking admission cutoff; does not wait for in-flight disk/cipher work. */
    fun closeAdmission() { closed.set(true) }

    /**
     * IO/cleanup worker only, never Main: close admission and drain admitted operations.
     * Once this returns, this owner's handles cannot perform any further storage IO.
     * A write admitted before closeAdmission may commit before the drain completes.
     */
    override fun close() {
        closeAdmission()
        synchronized(lock) { /* Wait for the admitted operation to leave the lock. */ }
    }

    private fun checkOpen() {
        if (closed.get()) throw PluginClosedException()
    }

    private fun <T> withAdmission(action: () -> T): T {
        checkOpen() // Closed handles fail promptly even while another operation holds the lock.
        return synchronized(lock) {
            checkOpen() // A queued operation may have lost admission while waiting.
            action()
        }
    }

    private fun read(secret: Boolean, key: String): String? = withAdmission {
        inputBytes(key, PluginPrefs.MAX_KEY_BYTES, key = true)
        load(secret)[key]
    }

    private fun change(secret: Boolean, key: String, value: String?) = withAdmission {
        inputBytes(key, PluginPrefs.MAX_KEY_BYTES, key = true)
        if (value != null) inputBytes(value, PluginPrefs.MAX_VALUE_BYTES)
        val entries = load(secret) // Read and validate actual bytes even on remove/overwrite.
        if (value == null) {
            if (entries.remove(key) == null) return@withAdmission
        } else {
            entries[key] = value
        }
        if (entries.size > PluginPrefs.MAX_ENTRIES) throw IOException("Too many settings entries")
        val plain = encode(entries)
        val bytes = if (secret) crypt { cipher.encrypt(plain) } else plain
        requireStoredSize(bytes.size)
        persist(secret, bytes) // Deletion is also an atomic rewrite, including the last key.
    }

    private fun directory(): File {
        val dir = pluginPath(data, DIRECTORY)
        for (parent in listOf(data, dir)) {
            if (!Files.notExists(parent.toPath(), NOFOLLOW_LINKS) &&
                !Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid settings directory")
        }
        return dir
    }
    private fun target(secret: Boolean): File = pluginPath(directory(), if (secret) "secrets.bin" else "prefs.bin")

    private fun load(secret: Boolean): LinkedHashMap<String, String> {
        val file = target(secret)
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return linkedMapOf()
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid settings file")
        val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                requireStoredSize(out.size() + count)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val plain = if (secret) crypt { cipher.decrypt(bytes) } else bytes
        requireStoredSize(plain.size)
        return decode(plain)
    }

    private fun persist(secret: Boolean, bytes: ByteArray) {
        requireStoredSize(bytes.size)
        val dir = directory()
        Files.createDirectories(dir.toPath())
        val target = target(secret)
        val temp = pluginPath(dir, "${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp.toPath(), WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // No non-atomic fallback: a failed publication must leave the old file intact.
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Throwable) {
            try { Files.deleteIfExists(temp.toPath()) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    private fun encode(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { output ->
            output.writeInt(PREFS_MAGIC)
            output.writeInt(entries.size)
            for ((key, value) in entries) {
                val k = inputBytes(key, PluginPrefs.MAX_KEY_BYTES, key = true)
                val v = inputBytes(value, PluginPrefs.MAX_VALUE_BYTES)
                requireStoredSize(out.size() + 8 + k.size + v.size)
                output.writeInt(k.size)
                output.write(k)
                output.writeInt(v.size)
                output.write(v)
            }
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): LinkedHashMap<String, String> {
        val input = ByteBuffer.wrap(bytes)
        fun number(): Int {
            if (input.remaining() < 4) throw IOException("Truncated settings")
            return input.int
        }
        fun text(limit: Int, key: Boolean = false): String {
            val size = number()
            if (size < (if (key) 1 else 0) || size > limit || size > input.remaining()) {
                throw IOException("Invalid settings field size")
            }
            val field = ByteArray(size)
            input.get(field)
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(field)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw IOException("Invalid settings UTF-8", error)
            }
        }
        if (number() != PREFS_MAGIC) throw IOException("Invalid settings format")
        val count = number()
        if (count !in 0..PluginPrefs.MAX_ENTRIES) throw IOException("Invalid settings entry count")
        val entries = linkedMapOf<String, String>()
        repeat(count) {
            val key = text(PluginPrefs.MAX_KEY_BYTES, key = true)
            val value = text(PluginPrefs.MAX_VALUE_BYTES)
            if (entries.put(key, value) != null) throw IOException("Duplicate settings key")
        }
        if (input.hasRemaining()) throw IOException("Trailing settings bytes")
        return entries
    }

    private fun inputBytes(value: String, limit: Int, key: Boolean = false): ByteArray {
        require(value.length <= limit && (!key || value.isNotEmpty())) { "Invalid settings field size" }
        val encoded = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Invalid settings Unicode", error)
        }
        require(encoded.remaining() <= limit) { "Settings field exceeds UTF-8 limit" }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun requireStoredSize(size: Int) {
        if (size > PluginPrefs.MAX_STORED_BYTES) throw IOException("Settings store exceeds size limit")
    }

    private fun crypt(action: () -> ByteArray): ByteArray = try { action() }
    catch (error: Exception) { throw IOException("Settings encryption/authentication failed", error) }

    companion object {
        const val DIRECTORY = ".theta-settings"
        private const val PREFS_MAGIC = 0x54535031
        private val locks = Array(64) { Any() }

        fun android(pluginId: String, dataDir: File): PluginSettingsStorage =
            PluginSettingsStorage(dataDir, androidPluginSettingsCipher(pluginId))
    }
}
