package io.github.bytejuno.thetacore.provider

import android.content.Context
import io.github.bytejuno.thetacore.agent.protocol.Credentials
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** No raw filesystem, parser or Keystore exception is retained as a cause/suppressed exception. */
class ProviderSettingsException(val failure: Failure) : Exception(when (failure) {
    Failure.NOT_CONFIGURED -> "Provider not configured"
    Failure.READ_FAILED -> "Provider settings could not be read"
    Failure.DECRYPT_FAILED -> "Saved credentials cannot be read; enter the credentials again"
    Failure.INVALID_DATA -> "Provider settings are invalid; re-enter the configuration"
    Failure.WRITE_FAILED -> "Provider settings could not be saved"
    Failure.ENDPOINT_CHANGED -> "Enter a new API key for the changed endpoint"
    Failure.CATALOG_RECOVERED -> "Saved profiles are readable again; reload to avoid overwriting existing profiles"
}) {
    enum class Failure {
        NOT_CONFIGURED, READ_FAILED, DECRYPT_FAILED, INVALID_DATA, WRITE_FAILED, ENDPOINT_CHANGED, CATALOG_RECOVERED,
    }
}

/** Host-only encrypted OpenAI-compatible profile catalog (or single image record).
 * All profiles, active selection, system prompts, and keys live in one authenticated atomic record:
 * concurrent updates cannot pair a new endpoint with an old key.
 * IO-worker API, not Main. No plaintext temporary files and no plugin-data/ledger integration.
 * Synchronization covers all instances in this process; no cross-process writers are supported.
 */
class OpenAiSettingsStore internal constructor(
    noBackupDirectory: File,
    private val cipher: SecretCipher,
    private val recordName: String = "active.bin",
) {
    private val root = noBackupDirectory.absoluteFile.toPath().normalize()
    private val directory = root.resolve(DIRECTORY)
    init { require(recordName in setOf("active.bin", "images.bin")) }
    private val file = directory.resolve(recordName)
    private val lock = locks[(file.toString().hashCode() and Int.MAX_VALUE) % locks.size]
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    /** Explicit replacement may rebuild an unusable credential key; old snapshots remain independent.
     * Reads and settings-only updates never rotate the key. */
    fun replace(settings: OpenAiProviderSettings, apiKey: String) = synchronized(lock) {
        writeSingle(settings, apiKey, replaceKey = true)
    }

    private fun writeSingle(settings: OpenAiProviderSettings, apiKey: String, replaceKey: Boolean) {
        require(validApiKey(apiKey)) { "Invalid API key" }
        val plaintext = ProviderSettingsCodec.encode(settings, apiKey)
        encryptAndPersist(plaintext, replaceKey)
    }

    private fun writeCatalog(catalog: ProviderSettingsCodec.CatalogRecord, replaceKey: Boolean) {
        val plaintext = ProviderSettingsCodec.encodeCatalog(catalog)
        encryptAndPersist(plaintext, replaceKey)
    }

    private fun encryptAndPersist(plaintext: ByteArray, replaceKey: Boolean) {
        try {
            val encrypted = sanitize(ProviderSettingsException.Failure.WRITE_FAILED) {
                if (replaceKey) cipher.encryptForReplacement(plaintext) else cipher.encrypt(plaintext)
            }
            sanitize(ProviderSettingsException.Failure.WRITE_FAILED) { persist(encrypted) }
            mutableRevision.value++
        } finally {
            plaintext.fill(0)
        }
    }

    /** Decrypt once at run admission. No lazy store-backed credentials may escape this boundary. */
    fun snapshotForNewRun(): OpenAiRunSnapshot = synchronized(lock) {
        readCatalog().active.toRecord().toSnapshot()
    }

    /** Only non-secret fields leave the IO boundary for editing. A successful read implies configured state. */
    fun readSettings(): OpenAiProviderSettings = synchronized(lock) { readCatalog().active.settings }

    /** Returns non-secret profile summaries and active selection; empty when unconfigured. */
    fun readProfiles(): ProviderProfilesCatalog = synchronized(lock) {
        val absent = sanitize(ProviderSettingsException.Failure.READ_FAILED) {
            checkDirectories()
            Files.notExists(file, NOFOLLOW_LINKS)
        }
        if (absent) return ProviderProfilesCatalog(activeProfileId = null, profiles = emptyList())
        readCatalog().toSummaryCatalog()
    }

    /** Check and reuse the active profile's key under the same process-wide lock as replacement. */
    fun updateSettings(settings: OpenAiProviderSettings) = synchronized(lock) {
        val catalog = readCatalog()
        val active = catalog.active
        checkSavedEndpoint(settings.baseUrl, active.settings.baseUrl)
        if (recordName == "images.bin" && catalog.profiles.size == 1 && !active.noAuth && settings.systemPrompt == null) {
            writeSingle(settings, active.key, replaceKey = false)
        } else {
            val updated = ProviderSettingsCodec.ProfileRecord(
                id = active.id,
                displayName = active.displayName,
                settings = settings,
                key = active.key,
                noAuth = active.noAuth,
            )
            val profiles = catalog.profiles.map { if (it.id == active.id) updated else it }
            writeCatalog(ProviderSettingsCodec.CatalogRecord(catalog.activeProfileId, profiles), replaceKey = false)
        }
    }

    /** Create or edit a named chat profile with strict per-profile key isolation (#56/#57/#110). */
    fun saveProfile(
        profileId: String?,
        displayName: String,
        settings: OpenAiProviderSettings,
        apiKey: String,
        noAuth: Boolean = false,
        activate: Boolean = true,
        replaceUnreadable: Boolean = false,
    ): ProviderProfileSummary = synchronized(lock) {
        val id = profileId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        require(validProfileId(id)) { "Invalid profile identifier" }
        val resolvedName = displayName.trim().ifEmpty { fallbackProfileDisplayName(settings.model) }
        require(validProfileDisplayName(resolvedName)) { "Invalid profile display name" }
        if (noAuth) {
            require(apiKey.isEmpty()) { "Do not supply an API key when no-auth is enabled" }
        }

        if (replaceUnreadable) {
            if (!noAuth) require(validApiKey(apiKey)) { "Invalid API key" }
            // Re-read inside the same storage lock before discarding the catalog: if the catalog
            // became readable again (e.g. transient IO/Keystore recovery), preserve all profiles.
            val recoveredReadable = try {
                readCatalog()
                true
            } catch (error: ProviderSettingsException) {
                when (error.failure) {
                    ProviderSettingsException.Failure.NOT_CONFIGURED,
                    ProviderSettingsException.Failure.READ_FAILED,
                    ProviderSettingsException.Failure.DECRYPT_FAILED,
                    ProviderSettingsException.Failure.INVALID_DATA -> false
                    else -> throw error
                }
            }
            if (recoveredReadable) {
                throw ProviderSettingsException(ProviderSettingsException.Failure.CATALOG_RECOVERED)
            }
            val record = ProviderSettingsCodec.ProfileRecord(
                id = id,
                displayName = resolvedName,
                settings = settings,
                key = if (noAuth) "" else apiKey,
                noAuth = noAuth,
            )
            writeCatalog(ProviderSettingsCodec.CatalogRecord(id, listOf(record)), replaceKey = true)
            return record.summary()
        }

        val exists = sanitize(ProviderSettingsException.Failure.READ_FAILED) {
            checkDirectories()
            !Files.notExists(file, NOFOLLOW_LINKS)
        }
        val existing = if (exists) readCatalog() else null
        val saved = existing?.profiles?.firstOrNull { it.id == id }

        val (resolvedKey, allowKeyRebuild) = when {
            noAuth -> "" to (existing == null)
            apiKey.isNotEmpty() -> {
                require(validApiKey(apiKey)) { "Invalid API key" }
                apiKey to true
            }
            else -> {
                val prior = saved ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
                if (prior.noAuth) throw IllegalArgumentException("API key required when disabling no-auth")
                checkSavedEndpoint(settings.baseUrl, prior.settings.baseUrl)
                prior.key to false
            }
        }

        val updatedRecord = ProviderSettingsCodec.ProfileRecord(
            id = id,
            displayName = resolvedName,
            settings = settings,
            key = resolvedKey,
            noAuth = noAuth,
        )
        val nextProfiles = when {
            existing == null -> listOf(updatedRecord)
            saved != null -> existing.profiles.map { if (it.id == id) updatedRecord else it }
            else -> {
                require(existing.profiles.size < MAX_PROFILES) { "Too many profiles" }
                existing.profiles + updatedRecord
            }
        }
        val nextActive = if (activate || existing == null) id else existing.activeProfileId
        writeCatalog(ProviderSettingsCodec.CatalogRecord(nextActive, nextProfiles), replaceKey = allowKeyRebuild)
        updatedRecord.summary()
    }

    fun renameProfile(profileId: String, displayName: String): ProviderProfileSummary = synchronized(lock) {
        val trimmedId = profileId.trim()
        val trimmedName = displayName.trim()
        require(validProfileDisplayName(trimmedName)) { "Invalid profile display name" }
        val catalog = readCatalog()
        val target = catalog.profiles.firstOrNull { it.id == trimmedId }
            ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
        val updated = ProviderSettingsCodec.ProfileRecord(
            id = target.id,
            displayName = trimmedName,
            settings = target.settings,
            key = target.key,
            noAuth = target.noAuth,
        )
        val profiles = catalog.profiles.map { if (it.id == trimmedId) updated else it }
        writeCatalog(ProviderSettingsCodec.CatalogRecord(catalog.activeProfileId, profiles), replaceKey = false)
        updated.summary()
    }

    fun selectActiveProfile(profileId: String): ProviderProfileSummary = synchronized(lock) {
        val trimmedId = profileId.trim()
        val catalog = readCatalog()
        val target = catalog.profiles.firstOrNull { it.id == trimmedId }
            ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
        if (catalog.activeProfileId != trimmedId) {
            writeCatalog(ProviderSettingsCodec.CatalogRecord(trimmedId, catalog.profiles), replaceKey = false)
        }
        target.summary()
    }

    fun deleteProfile(profileId: String): ProviderProfilesCatalog = synchronized(lock) {
        val trimmedId = profileId.trim()
        val catalog = readCatalog()
        require(catalog.profiles.any { it.id == trimmedId }) { "Unknown profile" }
        val remaining = catalog.profiles.filterNot { it.id == trimmedId }
        if (remaining.isEmpty()) {
            clearLocked()
            ProviderProfilesCatalog(activeProfileId = null, profiles = emptyList())
        } else {
            val nextActive = if (catalog.activeProfileId == trimmedId) remaining.first().id else catalog.activeProfileId
            val updated = ProviderSettingsCodec.CatalogRecord(nextActive, remaining)
            writeCatalog(updated, replaceKey = false)
            updated.toSummaryCatalog()
        }
    }

    suspend fun fetchModels(
        endpoint: String,
        apiKey: String,
        profileId: String? = null,
        noAuth: Boolean = false,
    ): List<String> = fetchModels(endpoint, apiKey, profileId, noAuth, OpenAiModelsClient()::fetch)

    /** Credentials are constructed and consumed in IO, never returned to a UI caller. */
    internal suspend fun fetchModels(
        endpoint: String,
        apiKey: String,
        fetch: suspend (String, Credentials) -> List<String>,
    ): List<String> = fetchModels(endpoint, apiKey, profileId = null, noAuth = false, fetch = fetch)

    internal suspend fun fetchModels(
        endpoint: String,
        apiKey: String,
        profileId: String?,
        noAuth: Boolean,
        fetch: suspend (String, Credentials) -> List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val credentials: Credentials = when {
            noAuth -> NoAuthCredentials
            apiKey.isEmpty() -> synchronized(lock) {
                val catalog = readCatalog()
                val target = if (profileId == null) catalog.active else {
                    catalog.profiles.firstOrNull { it.id == profileId }
                        ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
                }
                if (target.noAuth) {
                    throw ProviderSettingsException(ProviderSettingsException.Failure.INVALID_DATA)
                }
                checkSavedEndpoint(endpoint, target.settings.baseUrl)
                ApiKeyCredentials(target.key)
            }
            else -> ApiKeyCredentials(apiKey)
        }
        currentCoroutineContext().ensureActive()
        fetch(endpoint.trim(), credentials)
    }

    private fun checkSavedEndpoint(endpoint: String, savedEndpoint: String) {
        if (endpoint.trim() != savedEndpoint) {
            throw ProviderSettingsException(ProviderSettingsException.Failure.ENDPOINT_CHANGED)
        }
    }

    /** Caller holds lock; all decrypted/encoded byte arrays are cleared even on failure. */
    private fun readCatalog(): ProviderSettingsCodec.CatalogRecord {
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
            return sanitize(ProviderSettingsException.Failure.INVALID_DATA) {
                ProviderSettingsCodec.decodeCatalog(plaintext)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /** Removes only the active record; Keystore aliases are not rotated/deleted during normal IO. */
    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        sanitize(ProviderSettingsException.Failure.WRITE_FAILED) {
            checkDirectories()
            Files.deleteIfExists(file)
            removeAbandonedTemps()
        }
        mutableRevision.value++
    }

    /** A process death before the rename leaves a decryptable copy of an earlier key behind.
     * Callers hold [lock], so no temp file of a live write in this process can be matched. */
    private fun removeAbandonedTemps() {
        if (Files.notExists(directory, NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name.endsWith(".tmp") && (name.startsWith("$recordName-") ||
                        (recordName == "active.bin" && !name.startsWith("images.bin-"))) &&
                    Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry)
                }
            }
        }
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
        removeAbandonedTemps()
        val temp = directory.resolve("$recordName-${UUID.randomUUID()}.tmp")
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
            androidSecretCipher("theta.provider.credentials.v1", "theta-provider-credentials-v1", allowKeyRecovery = true),
        )
        fun images(context: Context): OpenAiSettingsStore = OpenAiSettingsStore(
            context.noBackupFilesDir,
            androidSecretCipher("theta.provider.images.v1", "theta-provider-images-v1", allowKeyRecovery = true),
            "images.bin",
        )
    }
}
