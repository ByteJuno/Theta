package io.github.bytejuno.thetacore.provider

import android.content.Context
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.storage.SecretCipher
import io.github.bytejuno.thetacore.storage.androidSecretCipher
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** No raw filesystem, parser or Keystore exception is retained as a cause/suppressed exception. */
class ProviderSettingsException(val failure: Failure) : Exception(when (failure) {
    Failure.NOT_CONFIGURED -> "Provider not configured"
    Failure.READ_FAILED -> "Provider settings could not be read"
    Failure.DECRYPT_FAILED -> "Provider credentials could not be decrypted; credential storage may be unavailable. Re-entering the API key may not restore access"
    Failure.INVALID_DATA -> "Provider settings are invalid; re-enter the configuration"
    Failure.WRITE_FAILED -> "Provider settings could not be saved"
}) {
    enum class Failure { NOT_CONFIGURED, READ_FAILED, DECRYPT_FAILED, INVALID_DATA, WRITE_FAILED }
}

/** Host-only single active OpenAI-compatible profile. All fields (including the key) are one
 * authenticated atomic record: concurrent updates cannot pair a new endpoint with an old key.
 * IO-worker API, not Main. No plaintext temporary files and no plugin-data/ledger integration.
 * Synchronization covers all instances in this process; no cross-process writers are supported.
 */
class OpenAiSettingsStore internal constructor(noBackupDirectory: File, private val cipher: SecretCipher) {
    private val root = noBackupDirectory.absoluteFile.toPath().normalize()
    private val directory = root.resolve(DIRECTORY)
    private val file = directory.resolve("active.bin")
    private val lock = locks[(file.toString().hashCode() and Int.MAX_VALUE) % locks.size]

    /** Explicit replacement repairs corrupt records/missing keys; old snapshots remain independent.
     * An unusable existing Keystore alias fails explicitly, never silently rotates on a read. */
    fun replace(settings: OpenAiProviderSettings, apiKey: String) = synchronized(lock) {
        require(validApiKey(apiKey)) { "Invalid API key" }
        val plaintext = ProviderSettingsCodec.encode(settings, apiKey)
        try {
            val encrypted = sanitize(ProviderSettingsException.Failure.WRITE_FAILED) { cipher.encrypt(plaintext) }
            sanitize(ProviderSettingsException.Failure.WRITE_FAILED) { persist(encrypted) }
        } finally { plaintext.fill(0) }
    }

    /** Decrypt once at run admission. No lazy store-backed credentials may escape this boundary. */
    fun snapshotForNewRun(): OpenAiRunSnapshot = synchronized(lock) {
        val encrypted = sanitize(ProviderSettingsException.Failure.READ_FAILED) {
            checkDirectories()
            if (Files.notExists(file, NOFOLLOW_LINKS)) {
                throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
            }
            check(Files.isRegularFile(file, NOFOLLOW_LINKS))
            Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= MAX_BYTES)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        }
        val plaintext = sanitize(ProviderSettingsException.Failure.DECRYPT_FAILED) { cipher.decrypt(encrypted) }
        try {
            sanitize(ProviderSettingsException.Failure.INVALID_DATA) { ProviderSettingsCodec.decode(plaintext) }
        } finally { plaintext.fill(0) }
    }

    /** Removes only the active record; Keystore aliases are not rotated/deleted during normal IO. */
    fun clear() = synchronized(lock) {
        sanitize(ProviderSettingsException.Failure.WRITE_FAILED) {
            checkDirectories()
            Files.deleteIfExists(file)
        }
        Unit
    }

    private fun checkDirectories() {
        // The Context-provided noBackup anchor is trusted. Android itself may alias ancestors
        // (/data/user/0 -> /data/data); reject links at our anchor and owned child, not OS parents.
        for (parent in listOf(root, directory)) {
            check(Files.notExists(parent, NOFOLLOW_LINKS) || Files.isDirectory(parent, NOFOLLOW_LINKS))
        }
    }

    private fun persist(bytes: ByteArray) {
        check(bytes.size <= MAX_BYTES)
        checkDirectories()
        Files.createDirectories(directory)
        check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
        val temp = directory.resolve("${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp, WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private inline fun <T> sanitize(failure: ProviderSettingsException.Failure, action: () -> T): T =
        try { action() } catch (error: ProviderSettingsException) { throw error }
        catch (_: Exception) { throw ProviderSettingsException(failure) }

    override fun toString() = "OpenAiSettingsStore([private storage])"

    companion object {
        internal const val DIRECTORY = "provider-credentials-v1"
        internal const val MAX_BYTES = 256 * 1024
        private val locks = Array(64) { Any() }
        /** Android excludes this directory from Auto Backup and device transfer. Never use
         * filesDir, plugin dataDir, external storage or cacheDir for this record. */
        fun android(context: Context): OpenAiSettingsStore = OpenAiSettingsStore(
            context.noBackupFilesDir,
            androidSecretCipher("theta.provider.credentials.v1", "theta-provider-credentials-v1"),
        )
    }
}
