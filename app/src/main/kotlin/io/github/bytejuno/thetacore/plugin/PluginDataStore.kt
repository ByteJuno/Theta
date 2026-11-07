package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal class StagedPluginData internal constructor(
    val manifest: PluginDataManifest,
    val sha256: String,
    val destinations: List<PluginDataDestination>,
) {
    val authorNotice: String get() = "作者身份未验证 / Author identity unverified"
    val digestNotice: String get() = "SHA-256 checks byte consistency, not author identity or update trust"
}

internal data class PublishedPluginData(val zip: File, val manifest: PluginDataManifest, val sha256: String)

/**
 * Explicit DATA import only. Never invoke as an APK verification fallback.
 *
 * The owner MUST hold the same operation gate used for ALL code/data install,
 * recovery, uninstall and activation operations. Integration uses the singleton
 * manager's single-process mutex; this store does NOT provide a cross-process lock.
 * These methods do not activate, update state, load classes, connect to endpoints,
 * trust authors, or consume extensions. Not a sandbox against malicious same-UID code.
 */
internal class PluginDataStore(
    filesDir: File,
    private val hostApiLevel: Int,
    private val limits: PluginDataLimits = PluginDataLimits(),
    private val cleanup: PluginStageCleanup = PluginStageCleanup(),
    private val deleteStage: (File, String) -> Unit = ::deletePluginTree,
    publicationBoundary: (PluginPublicationBoundary) -> Unit = {},
) {
    private val files = filesDir.canonicalFile // Only the trusted Android storage alias is resolved.
    private val root get() = pluginPath(files, "plugins")
    private val staging get() = pluginPath(files, "plugin-data-staging")
    private data class Pending(val dir: File, val ticket: PluginStageCleanup.Ticket)
    private val pending = java.util.IdentityHashMap<StagedPluginData, Pending>()
    private val publication = PluginPackagePublication(files, PluginPublicationKind.DATA, cleanup, deleteStage, publicationBoundary)
    fun retryCleanup() = cleanup.retry()
    fun recoverPublications() = publication.recoverOnce()

    init { require(hostApiLevel >= 1) }

    /** Caller owns/ closes source. Optional trusted-index digest is checked, not trusted by provenance inference. */
    @Synchronized
    fun stageData(source: InputStream, expectedSha256: String? = null): StagedPluginData {
        recoverPublications()
        if (expectedSha256 != null) dataRequire(Regex("[0-9a-f]{64}").matches(expectedSha256), "Invalid expected SHA-256")
        ensureDirectory(staging)
        val dir = pluginPath(staging, UUID.randomUUID().toString())
        var registered: StagedPluginData? = null
        val ticket = cleanup.reserve {
            deleteStage(staging, dir.name)
            registered?.let { pending.remove(it) }
        }
        try {
            Files.createDirectory(dir.toPath())
            val bytes = readBounded(source)
            val digest = digest(bytes)
            dataRequire(expectedSha256 == null || digest == expectedSha256, "Data ZIP index digest mismatch")
            val validated = validatePluginDataZip(bytes, hostApiLevel, limits)
            checkNamespaceAndVersion(validated.manifest)
            writeNew(pluginPath(dir, "plugin.zip"), bytes)
            return StagedPluginData(validated.manifest, digest, validated.destinations).also {
                registered = it
                pending[it] = Pending(dir, ticket)
            }
        } catch (error: Throwable) {
            withPluginCleanup(cleanup = { cleanup.discard(ticket) }) { throw error }
        }
    }

    /**
     * Confirmation must display this stage's metadata, destinations and unverified-author notice.
     * Requiring the reviewed digest makes stale/wrong selection an explicit failure, not consent.
     * Every unsigned update needs confirmation again. Returns a published but INACTIVE version.
     * The stage is consumed on success or failure. No old version or active state is touched.
     */
    @Synchronized
    fun publishConfirmedData(staged: StagedPluginData, confirmedSha256: String, authorUnverifiedAcknowledged: Boolean): PublishedPluginData {
        val owned = pending[staged] ?: throw PluginPackageException("Unknown or consumed data stage")
        dataRequire(!owned.ticket.abandoned, "Data stage awaits cleanup")
        val stageDir = owned.dir
        return withPluginCleanup(cleanup = { cleanup.discard(owned.ticket) }) {
            dataRequire(authorUnverifiedAcknowledged, "Unverified author confirmation required")
            dataRequire(confirmedSha256 == staged.sha256, "Confirmation does not match data stage")
            val bytes = readRegular(pluginPath(stageDir, "plugin.zip"))
            dataRequire(digest(bytes) == staged.sha256, "Data ZIP changed after confirmation")
            val validated = validatePluginDataZip(bytes, hostApiLevel, limits)
            dataRequire(validated.manifest == staged.manifest && validated.destinations == staged.destinations, "Data metadata changed")
            checkNamespaceAndVersion(validated.manifest)
            // Reserve data identity before the durable publication intent. This never selects
            // an archive, and cannot adopt an existing unowned package directory (checked above).
            PluginDataStateStore(files).claim(staged.manifest.id)
            val target = publication.publish(staged.manifest.id, staged.manifest.versionCode,
                write = { copied ->
                    writeNew(copied, bytes)
                    val written = readRegular(copied)
                    dataRequire(digest(written) == staged.sha256, "Written data ZIP digest mismatch")
                    dataRequire(validatePluginDataZip(written, hostApiLevel, limits) == validated, "Written data ZIP metadata mismatch")
                },
                beforeMove = {
                    checkCodeResidue(staged.manifest.id)
                    // Stage cleanup remains BEFORE visibility; failure leaves selection intact.
                    cleanup.discard(owned.ticket)
                },
            )
            PublishedPluginData(pluginPath(target, "plugin.zip"), staged.manifest, staged.sha256)
        }
    }

    @Synchronized
    fun discardData(staged: StagedPluginData) {
        val owned = pending[staged] ?: throw PluginPackageException("Unknown or consumed data stage")
        cleanup.discard(owned.ticket)
    }

    // Existence, NOT successful parsing, reserves code identity. Corrupt retained trust/data
    // or lifecycle records must never be adopted by an unsigned package.
    private fun checkCodeResidue(id: String) = requireNoCodeOwnership(files, id)

    private fun checkNamespaceAndVersion(manifest: PluginDataManifest) {
        checkCodeResidue(manifest.id)
        val idDir = pluginPath(root, manifest.id)
        if (Files.notExists(idDir.toPath(), NOFOLLOW_LINKS)) return
        requireDirectory(idDir)
        val versions = Files.newDirectoryStream(idDir.toPath()).use { it.toList() }
        // Empty or interrupted directories have unknown ownership; explicit recovery is required.
        dataRequire(versions.isNotEmpty(), "Unowned plugin directory; recovery required")
        var highest = 0L
        // An empty version is an interrupted prune, but only for an ID with a committed selection.
        val selected = PluginDataStateStore(files).read(manifest.id)?.let { it.version > 0 } == true
        for (path in versions) {
            val version = path.fileName.toString().toLongOrNull()
            dataRequire(version != null && version > 0 && version.toString() == path.fileName.toString(), "Unrecognized plugin storage")
            val dir = pluginPath(idDir, path.fileName.toString())
            requireDirectory(dir)
            val children = Files.newDirectoryStream(dir.toPath()).use { it.map { child -> child.fileName.toString() }.toList() }
            if (children.isEmpty() && selected) continue
            dataRequire(children == listOf("plugin.zip"), "Code/data namespace collision or incomplete publication")
            val previous = validatePluginDataZip(readRegular(pluginPath(dir, "plugin.zip")), hostApiLevel, limits).manifest
            dataRequire(previous.id == manifest.id && previous.versionCode == version, "Stored data identity mismatch")
            highest = maxOf(highest, previous.versionCode)
        }
        dataRequire(manifest.versionCode > highest, "Data install requires a higher version than $highest")
    }

    private fun requireDirectory(dir: File) {
        pluginPath(dir, ".")
        dataRequire(Files.readAttributes(dir.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory, "Expected data storage directory")
    }

    private fun ensureDirectory(dir: File) {
        pluginPath(dir, ".")
        Files.createDirectories(dir.toPath())
        requireDirectory(dir)
    }

    private fun readRegular(file: File): ByteArray {
        pluginPath(file.parentFile, file.name)
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        dataRequire(attrs.isRegularFile && attrs.size() <= limits.compressedBytes, "Invalid stored data ZIP")
        return Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use(::readBounded)
    }

    private fun writeNew(file: File, bytes: ByteArray) {
        pluginPath(file.parentFile, file.name)
        FileChannel.open(file.toPath(), WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun readBounded(source: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) throw java.io.IOException("Data source made no progress")
            dataRequire(count <= limits.compressedBytes - result.size(), "Compressed data ZIP exceeds limit")
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
