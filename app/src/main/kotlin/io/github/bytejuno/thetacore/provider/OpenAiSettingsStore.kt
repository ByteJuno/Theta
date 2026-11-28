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
import java.nio.file.Path
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
    Failure.RECOVERED -> "Saved settings are readable again; reload before editing"
}) {
    enum class Failure { NOT_CONFIGURED, READ_FAILED, DECRYPT_FAILED, INVALID_DATA, WRITE_FAILED, ENDPOINT_CHANGED, RECOVERED }
}

/** Host-only encrypted provider/model catalog, with legacy profile and image import.
 * Connections, both bindings, prompts and keys live in one authenticated atomic record:
 * concurrent updates cannot pair a new endpoint with an old key.
 * IO-worker API, not Main. No plaintext temporary files and no plugin-data/ledger integration.
 * Synchronization covers all instances in this process; no cross-process writers are supported.
 */
class OpenAiSettingsStore internal constructor(
    noBackupDirectory: File,
    private val cipher: SecretCipher,
    private val recordName: String = "active.bin",
    private val legacyImages: OpenAiSettingsStore? = null,
) {
    private val root = noBackupDirectory.absoluteFile.toPath().normalize()
    private val directory = root.resolve(DIRECTORY)
    init { require(recordName in setOf("active.bin", "images.bin")) }
    private val file = directory.resolve(recordName)
    // Publishing this file atomically also commits the migration boundary. Its presence
    // is authoritative even when credentials cannot be decrypted; legacy records stay inert.
    private val connectionsFile = directory.resolve("$recordName.v3")
    private val lock = locks[(directory.toString().hashCode() and Int.MAX_VALUE) % locks.size]
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    /** Explicit replacement may rebuild an unusable credential key; old snapshots remain independent.
     * Reads and settings-only updates never rotate the key. */
    fun replace(settings: OpenAiProviderSettings, apiKey: String) = synchronized(lock) {
        writeSingle(settings, apiKey, replaceKey = true)
    }

    private fun writeSingle(settings: OpenAiProviderSettings, apiKey: String, replaceKey: Boolean) {
        require(validApiKey(apiKey)) { "Invalid API key" }
        val migrated = hasConnectionsRecord()
        val plaintext = ProviderSettingsCodec.encode(settings, apiKey)
        if (migrated) {
            try { writeConnections(ProviderConnectionsCodec.migrate(ProviderSettingsCodec.decodeCatalog(plaintext)), replaceKey) }
            finally { plaintext.fill(0) }
        } else encryptAndPersist(plaintext, replaceKey)
    }

    private fun encryptAndPersist(plaintext: ByteArray, replaceKey: Boolean, target: Path = file) {
        try {
            val encrypted = sanitize(ProviderSettingsException.Failure.WRITE_FAILED) {
                if (replaceKey) cipher.encryptForReplacement(plaintext) else cipher.encrypt(plaintext)
            }
            sanitize(ProviderSettingsException.Failure.WRITE_FAILED) { persist(encrypted, target) }
            mutableRevision.value++
        } finally {
            plaintext.fill(0)
        }
    }

    /** Both slots are captured under the same storage lock before a new run is acknowledged. */
    fun snapshotForNewRun(): OpenAiRunSnapshot = synchronized(lock) {
        val catalog = readConnections()
        val chat = catalog.snapshot(catalog.bindings.activeChatModelRef)
        return chat.withImageSnapshot(catalog.bindings.activeImageModelRef?.let { catalog.snapshot(it, image = true) })
            .withReasoningCapabilityRecorder { recordUnsupportedReasoning(catalog, requireNotNull(catalog.bindings.activeChatModelRef)) }
    }

    private fun recordUnsupportedReasoning(admitted: ConnectionsRecord, ref: ModelRef) = synchronized(lock) {
        val current = readConnections()
        val (old, model) = admitted.resolve(ref)
        val provider = current.providers.firstOrNull { it.connection.providerId == ref.providerId } ?: return@synchronized
        val liveModel = provider.models.firstOrNull { it.modelId == ref.modelId } ?: return@synchronized
        // Never attach an observation to a concurrently edited endpoint, protocol or model.
        if (provider.connection.baseUrl != old.connection.baseUrl || provider.connection.protocol != old.connection.protocol ||
            liveModel.parameters.reasoningLevel != model.parameters.reasoningLevel || liveModel.reasoningLevels != model.reasoningLevels) return@synchronized
        val updated = ConnectionRecord(provider.connection, provider.models.map {
            if (it.modelId == ref.modelId) it.copy(reasoningLevels = emptyList()) else it
        }, provider.key)
        writeConnections(ConnectionsRecord(current.providers.map { if (it === provider) updated else it }, current.bindings), false)
    }

    fun snapshotForImage(): OpenAiRunSnapshot = synchronized(lock) {
        val catalog = readConnections()
        catalog.snapshot(catalog.bindings.activeImageModelRef, image = true)
    }

    fun readSettings(): OpenAiProviderSettings = synchronized(lock) {
        val c = readConnections(); c.snapshot(c.bindings.activeChatModelRef).settings
    }

    fun readProviderCatalog(): ProviderCatalog = synchronized(lock) {
        readOptionalConnections()?.summary() ?: ProviderCatalog()
    }

    fun readProfiles(): ProviderProfilesCatalog = synchronized(lock) {
        val catalog = readOptionalConnections()
        if (catalog == null || catalog.providers.none { p -> p.models.any { it.chat } })
            ProviderProfilesCatalog(null, emptyList())
        else legacyProjection(catalog).toSummaryCatalog()
    }

    private fun hasConnectionsRecord(): Boolean = sanitize(ProviderSettingsException.Failure.READ_FAILED) {
        checkDirectories(); !Files.notExists(connectionsFile, NOFOLLOW_LINKS)
    }

    /** Legacy entry points remain compatible; v3 editing uses saveProvider and bindModels. */
    fun updateSettings(settings: OpenAiProviderSettings) = synchronized(lock) {
        val c = readConnections()
        val (p, model) = c.resolve(requireNotNull(c.bindings.activeChatModelRef))
        checkSavedEndpoint(settings.baseUrl, p.connection.baseUrl)
        val updatedModel = model.copy(modelId = settings.model, parameters = settings.parameters,
            compat = settings.compat, systemPrompt = settings.systemPrompt)
        val updated = ConnectionRecord(p.connection, p.models.map { if (it === model) updatedModel else it }, p.key)
        fun updatedRef(ref: ModelRef?) = if (ref == ModelRef(p.connection.providerId, model.modelId))
            ModelRef(p.connection.providerId, settings.model) else ref
        writeConnections(ConnectionsRecord(c.providers.map { if (it === p) updated else it }, ModelBindings(
            updatedRef(c.bindings.activeChatModelRef), updatedRef(c.bindings.activeImageModelRef))), false)
    }

    fun saveProfile(
        profileId: String?, displayName: String, settings: OpenAiProviderSettings, apiKey: String,
        noAuth: Boolean = false, activate: Boolean = true, replaceUnreadable: Boolean = false,
    ): ProviderProfileSummary = synchronized(lock) {
        val id = profileId?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        val connection = ProviderConnection(id, displayName.trim().ifEmpty { legacyDisplayName(settings) },
            settings.baseUrl, noAuth = noAuth, compat = settings.compat)
        val model = EnabledModel(settings.model, systemPrompt = settings.systemPrompt,
            parameters = settings.parameters, runtimeProviderId = settings.providerId)
        saveProvider(connection, listOf(model), apiKey, replaceUnreadable, activate)
        ProviderProfileSummary(id, connection.displayName, settings, noAuth)
    }

    fun saveProvider(connection: ProviderConnection, models: List<EnabledModel>, apiKey: String,
        replaceUnreadable: Boolean = false, activateChat: Boolean = false): ProviderCatalog = synchronized(lock) {
        // Recovery consent applies only while the original record is still unreadable.
        val existing = readOptionalConnections(replaceUnreadable)
        val saved = existing?.providers?.firstOrNull { it.connection.providerId == connection.providerId }
        val key = when {
            connection.noAuth -> { require(apiKey.isEmpty()); "" }
            apiKey.isNotEmpty() -> { require(validApiKey(apiKey)); apiKey }
            else -> {
                val prior = saved ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
                require(!prior.connection.noAuth) { "API key required" }
                checkSavedEndpoint(connection.baseUrl, prior.connection.baseUrl)
                prior.key
            }
        }
        val updated = ConnectionRecord(connection, models.toList(), key)
        val providers = existing?.providers.orEmpty().filterNot { it.connection.providerId == connection.providerId } + updated
        var bindings = existing?.bindings ?: ModelBindings()
        fun valid(ref: ModelRef?, image: Boolean): Boolean = providers.any { p -> p.connection.providerId == ref?.providerId &&
            p.models.any { it.modelId == ref.modelId && if (image) it.image else it.chat } }
        val firstChat = providers.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.chat }?.let { ModelRef(p.connection.providerId, it.modelId) } }
        val preferred = models.firstOrNull { it.chat }?.let { ModelRef(connection.providerId, it.modelId) }
        bindings = ModelBindings(if (activateChat && preferred != null) preferred else
            bindings.activeChatModelRef.takeIf { valid(it, false) } ?: firstChat,
            bindings.activeImageModelRef.takeIf { valid(it, true) })
        val next = ConnectionsRecord(providers, bindings)
        // Empty catalogs and image-only legacy imports have no credentials encrypted
        // with this store's key to retain. Explicit no-auth setup may recover that key;
        // imported image credentials have already been read with their independent key.
        val initializesRecord = existing?.providers.isNullOrEmpty() ||
            (!hasConnectionsRecord() && sanitize(ProviderSettingsException.Failure.READ_FAILED) {
                Files.notExists(file, NOFOLLOW_LINKS)
            })
        writeConnections(next, replaceUnreadable || initializesRecord || apiKey.isNotEmpty())
        next.summary()
    }

    fun bindModels(bindings: ModelBindings): ProviderCatalog = synchronized(lock) {
        val c = readConnections()
        val next = ConnectionsRecord(c.providers, bindings)
        writeConnections(next, false)
        next.summary()
    }

    fun selectChatModel(ref: ModelRef): ProviderCatalog = synchronized(lock) {
        val c = readConnections(); bindModels(c.bindings.copy(activeChatModelRef = ref))
    }

    fun selectImageModel(ref: ModelRef?): ProviderCatalog = synchronized(lock) {
        val c = readConnections(); bindModels(c.bindings.copy(activeImageModelRef = ref))
    }

    fun renameProfile(profileId: String, displayName: String): ProviderProfileSummary = synchronized(lock) {
        val c = readConnections(); val p = c.providers.first { it.connection.providerId == profileId }
        val updated = ConnectionRecord(p.connection.copy(displayName = displayName.trim()), p.models, p.key)
        writeConnections(ConnectionsRecord(c.providers.map { if (it === p) updated else it }, c.bindings), false)
        val m = p.models.first(); ProviderProfileSummary(profileId, displayName.trim(), p.settings(m), p.connection.noAuth)
    }

    fun selectActiveProfile(profileId: String): ProviderProfileSummary = synchronized(lock) {
        val c = readConnections()
        val p = c.providers.firstOrNull { it.connection.providerId == profileId.trim() }
            ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
        val m = p.models.first { it.chat }
        selectChatModel(ModelRef(p.connection.providerId, m.modelId))
        ProviderProfileSummary(p.connection.providerId, p.connection.displayName, p.settings(m), p.connection.noAuth)
    }

    fun deleteProvider(providerId: String): ProviderCatalog = synchronized(lock) {
        val c = readConnections(); require(c.providers.any { it.connection.providerId == providerId })
        val remaining = c.providers.filterNot { it.connection.providerId == providerId }
        if (remaining.isEmpty()) { writeConnections(ConnectionsRecord(emptyList(), ModelBindings()), false); return ProviderCatalog() }
        val chat = c.bindings.activeChatModelRef?.takeUnless { it.providerId == providerId }
            ?: remaining.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.chat }?.let { ModelRef(p.connection.providerId, it.modelId) } }
        val image = c.bindings.activeImageModelRef?.takeUnless { it.providerId == providerId }
        val next = ConnectionsRecord(remaining, ModelBindings(chat, image)); writeConnections(next, false); next.summary()
    }

    fun deleteProfile(profileId: String): ProviderProfilesCatalog = synchronized(lock) {
        deleteProvider(profileId.trim()); readProfiles()
    }

    private fun writeConnections(record: ConnectionsRecord, replaceKey: Boolean) =
        encryptAndPersist(ProviderConnectionsCodec.encode(record), replaceKey, connectionsFile)

    private fun legacyProjection(record: ConnectionsRecord): ProviderSettingsCodec.CatalogRecord {
        val profiles = record.providers.mapNotNull { p -> p.models.firstOrNull { it.chat }?.let { m ->
            ProviderSettingsCodec.ProfileRecord(p.connection.providerId, p.connection.displayName, p.settings(m), p.key, p.connection.noAuth)
        } }
        if (profiles.isEmpty()) throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
        return ProviderSettingsCodec.CatalogRecord(record.bindings.activeChatModelRef?.providerId ?: profiles.first().id, profiles)
    }

    suspend fun fetchModels(
        endpoint: String,
        apiKey: String,
        profileId: String? = null,
        noAuth: Boolean = false,
        protocol: String = "openai-chat",
    ): List<String> = fetchModels(endpoint, apiKey, profileId, noAuth, providerModelsFetcher(protocol))

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
                val catalog = readConnections()
                val id = profileId ?: catalog.bindings.activeChatModelRef?.providerId
                val target = catalog.providers.firstOrNull { it.connection.providerId == id }
                    ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
                if (target.connection.noAuth) throw ProviderSettingsException(ProviderSettingsException.Failure.INVALID_DATA)
                checkSavedEndpoint(endpoint, target.connection.baseUrl)
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
    private fun <T> readRecord(target: Path = file, decode: (ByteArray) -> T): T {
        val encrypted = sanitize(ProviderSettingsException.Failure.READ_FAILED) {
            checkDirectories()
            if (Files.notExists(target, NOFOLLOW_LINKS)) {
                throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
            }
            check(Files.isRegularFile(target, NOFOLLOW_LINKS))
            Files.newInputStream(target, READ, NOFOLLOW_LINKS).use { input ->
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
                decode(plaintext)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun readOptionalConnections(replaceUnreadable: Boolean = false): ConnectionsRecord? = try { readConnections(replaceUnreadable) }
        catch (failure: ProviderSettingsException) {
            if (failure.failure == ProviderSettingsException.Failure.NOT_CONFIGURED) null else throw failure
        }

    private fun readConnections(replaceUnreadable: Boolean = false): ConnectionsRecord {
        var unreadable = false
        // Recovery is per encrypted record. A failed legacy image read must never discard
        // readable chat profiles, and a failed chat read must never discard image credentials.
        fun <T> readPart(read: () -> T): T? = try { read() }
        catch (failure: ProviderSettingsException) {
            if (failure.failure == ProviderSettingsException.Failure.NOT_CONFIGURED) null
            else {
                if (!replaceUnreadable) throw failure
                unreadable = true
                null
            }
        }
        val migrated = hasConnectionsRecord()
        // An explicit clear writes a durable empty tombstone without requiring a usable key.
        // A nonempty but corrupt v3 record must enter recovery, never fall back to images.bin.
        if (migrated && sanitize(ProviderSettingsException.Failure.READ_FAILED) {
            check(Files.isRegularFile(connectionsFile, NOFOLLOW_LINKS))
            Files.size(connectionsFile) == 0L
        }) {
            if (replaceUnreadable) throw ProviderSettingsException(ProviderSettingsException.Failure.RECOVERED)
            return ConnectionsRecord(emptyList(), ModelBindings())
        }
        var legacy = !migrated
        val initial = readPart { readRecord(if (migrated) connectionsFile else file) { bytes ->
            if (migrated) return@readRecord ProviderConnectionsCodec.decode(bytes)
            if (ProviderConnectionsCodec.isV3(bytes)) {
                legacy = false
                ProviderConnectionsCodec.decode(bytes)
            } else ProviderConnectionsCodec.migrate(ProviderSettingsCodec.decodeCatalog(bytes))
        } }
        val image = if (legacy && legacyImages != null)
            readPart { legacyImages.readRecord(decode = ProviderSettingsCodec::decodeRecord) } else null
        if (replaceUnreadable && !unreadable && (initial != null || image != null)) {
            throw ProviderSettingsException(ProviderSettingsException.Failure.RECOVERED)
        }
        val chat = initial ?: ConnectionsRecord(emptyList(), ModelBindings())
        if (initial == null && image == null && !unreadable) {
            throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
        }
        // Keep legacy ciphertext and aliases untouched until an explicit save writes v3.
        return if (image == null) chat else migrateImages(chat, image)
    }

    private fun migrateImages(c: ConnectionsRecord, image: ProviderSettingsCodec.Record): ConnectionsRecord {
        val same = c.providers.firstOrNull { it.connection.baseUrl == image.settings.baseUrl && it.key == image.key && it.connection.noAuth == image.noAuth }
        val model = EnabledModel(image.settings.model, chat = false, image = true, compat = image.settings.compat,
            parameters = image.settings.parameters, systemPrompt = image.settings.systemPrompt, runtimeProviderId = image.settings.providerId)
        val id = same?.connection?.providerId ?: generateSequence("legacy-images") { "$it-1" }
            .first { candidate -> c.providers.none { it.connection.providerId == candidate } }
        val updated = if (same == null) ConnectionRecord(ProviderConnection(id, legacyDisplayName(image.settings), image.settings.baseUrl,
            noAuth = image.noAuth, compat = image.settings.compat), listOf(model), image.key)
        else {
            val old = same.models.firstOrNull { it.modelId == model.modelId }
            // Different per-purpose parameters must not be silently lost during coalescing.
            ConnectionRecord(same.connection, if (old == null) same.models + model else same.models.map {
                if (it.modelId == model.modelId) it.copy(image = true, legacyImageSettings = image.settings) else it
            }, same.key)
        }
        return ConnectionsRecord(c.providers.filterNot { it.connection.providerId == id } + updated,
            c.bindings.copy(activeImageModelRef = ModelRef(id, model.modelId)))
    }

    /** Clears this store without re-enabling legacy import; Keystore aliases are left intact. */
    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        sanitize(ProviderSettingsException.Failure.WRITE_FAILED) {
            checkDirectories()
            if (hasConnectionsRecord() || legacyImages != null) persist(byteArrayOf(), connectionsFile)
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

    private fun persist(bytes: ByteArray, target: Path = file) {
        check(bytes.size <= MAX_BYTES)
        checkDirectories()
        Files.createDirectories(directory)
        check(Files.notExists(target, NOFOLLOW_LINKS) || Files.isRegularFile(target, NOFOLLOW_LINKS))
        removeAbandonedTemps()
        val temp = directory.resolve("$recordName-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp, WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
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
        internal const val LEGACY_MAX_BYTES = 256 * 1024
        // v3 combines two formerly independent records and repeats connection/settings
        // metadata. Reserve bounded migration headroom without changing legacy limits.
        internal const val MAX_BYTES = 2 * 1024 * 1024
        private val locks = Array(64) { Any() }
        /** Android excludes this directory from Auto Backup and device transfer. Never use
         * filesDir, plugin dataDir, external storage or cacheDir for this record. */
        fun android(context: Context): OpenAiSettingsStore = OpenAiSettingsStore(
            context.noBackupFilesDir,
            androidSecretCipher("theta.provider.credentials.v1", "theta-provider-credentials-v1", allowKeyRecovery = true),
            legacyImages = images(context),
        )
        fun images(context: Context): OpenAiSettingsStore = OpenAiSettingsStore(
            context.noBackupFilesDir,
            androidSecretCipher("theta.provider.images.v1", "theta-provider-images-v1", allowKeyRecovery = true),
            "images.bin",
        )
    }
}
