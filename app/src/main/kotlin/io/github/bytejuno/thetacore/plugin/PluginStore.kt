package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

internal const val PACKAGE_MAX_BYTES = 256L * 1024 * 1024

internal class StagedPlugin(
    val file: File,
    val manifest: PluginManifest,
    val sha256: String,
    val signers: PluginSigners,
)

internal class InstalledPlugin(val apk: File, val manifest: PluginManifest)

/** 插件包的落盘：`plugins/<id>/<versionCode>/plugin.apk`。 */
internal class PluginStore(
    private val filesDir: File,
    private val cacheDir: File,
    private val verifier: PluginSignatureVerifier,
    private val cleanup: PluginStageCleanup = PluginStageCleanup(),
    private val deleteStage: (File) -> Unit = { Files.deleteIfExists(it.toPath()); Unit },
    publicationBoundary: (PluginPublicationBoundary) -> Unit = {},
    deletePublicationIntent: (File) -> Unit = { Files.delete(it.toPath()) },
    private val deletePackages: (File, String) -> Unit = ::deletePluginTree,
) {
    // Resolve only trusted Android storage aliases; plugin-controlled descendants stay checked.
    private val root get() = pluginPath(filesDir.canonicalFile, "plugins")
    private val staging get() = pluginPath(cacheDir.canonicalFile, "plugin-staging")
    private val data get() = pluginPath(filesDir.canonicalFile, "plugin-data")
    private val trust = PluginTrustStore(filesDir)

    private val publication = PluginPackagePublication(filesDir, PluginPublicationKind.CODE, cleanup,
        deleteTemporary = { parent, name ->
            val dir = pluginPath(parent, name)
            deleteStage(pluginPath(dir, "plugin.apk"))
            Files.deleteIfExists(dir.toPath())
        }, boundary = publicationBoundary, deleteIntent = deletePublicationIntent)
    private val pending = java.util.IdentityHashMap<StagedPlugin, PluginStageCleanup.Ticket>()
    fun retryCleanup() = cleanup.retry()
    fun cleanupColdStages() {
        check(pending.isEmpty())
        withPluginCleanup(cleanup = {
            val stale = cleanup.reserveRecovery { deletePluginTree(cacheDir.canonicalFile, "plugin-staging") }
            cleanup.discard(stale)
        }) { publication.recoverOnce() }
    }
    fun discard(stage: StagedPlugin) { pending[stage]?.let(cleanup::discard) }

    fun stage(source: InputStream): StagedPlugin {
        publication.recoverOnce()
        pluginPath(staging, ".")
        Files.createDirectories(staging.toPath())
        val file = File(staging, "${UUID.randomUUID()}.apk")
        var registered: StagedPlugin? = null
        val ticket = cleanup.reserve {
            deleteStage(file)
            registered?.let { pending.remove(it) }
        }
        try {
            file.outputStream().use { out -> copyLimited(source, out) }
            // Keep malformed-ZIP/manifest errors specific. This is metadata parsing,
            // never a data-plugin fallback; no confirmation or code before verification.
            val manifest = readPluginManifest(file)
            val signers = verifier.verify(file)
            checkTrustForInstall(manifest.id, signers)
            return StagedPlugin(file, manifest, sha256(file), signers).also {
                registered = it
                pending[it] = ticket
            }
        } catch (e: Throwable) {
            withPluginCleanup(cleanup = { cleanup.discard(ticket) }) { throw e }
        }
    }

    /** The caller must obtain user confirmation before entering this method. */
    fun install(staged: StagedPlugin): InstalledPlugin =
        withCommittedPluginCleanup(cleanup = { discard(staged) }) {
            val ticket = pending[staged] ?: throw PluginPackageException("Unknown code stage")
            dataRequire(!ticket.abandoned, "Code stage awaits cleanup")
            installPackage(staged)
        }

    private fun checkTrustForInstall(id: String, signers: PluginSigners) {
        requireNoDataOwnership(filesDir, id)
        val bound = trust.read(id)
        if (bound != null) {
            trust.requireMatch(id, signers)
        } else {
            // Any old package tree OR retained data requires an existing binding.
            // Never silently migrate an unsigned install or adopt orphaned data.
            val packageDir = pluginPath(root, id)
            val dataDir = pluginPath(data, id)
            if (!Files.notExists(packageDir.toPath(), NOFOLLOW_LINKS) ||
                !Files.notExists(dataDir.toPath(), NOFOLLOW_LINKS)) {
                throw PluginPackageException("Missing signer binding for $id; clear-data uninstall required", PluginFailureReason.SIGNER_BINDING_MISSING)
            }
        }
    }

    private fun installPackage(staged: StagedPlugin): InstalledPlugin {
        val manifest = staged.manifest
        checkId(manifest.id)
        requireNoDataOwnership(filesDir, manifest.id)
        val highest = versions(manifest.id).lastOrNull()
        if (highest != null && manifest.versionCode <= highest) {
            throw PluginPackageException("Install requires a higher version than $highest", PluginFailureReason.HIGHER_VERSION_REQUIRED)
        }
        checkTrustForInstall(manifest.id, staged.signers)
        // Confirmation pins trust before creating any published APK. Failed first
        // publication may retain this binding; only the same signer can retry.
        trust.bindConfirmed(manifest.id, staged.signers)
        val dir = publication.publish(manifest.id, manifest.versionCode,
            write = { temp ->
                // Read-only before writing dex; only a verified complete directory is published.
                FileOutputStream(temp).use { out ->
                    if (!temp.setReadOnly()) throw PluginPackageException("Cannot make the package read-only")
                    staged.file.inputStream().use { copyLimited(it, out) }
                    out.fd.sync()
                }
                if (sha256(temp) != staged.sha256) throw PluginPackageException("Package changed while installing", PluginFailureReason.PACKAGE_DIGEST_MISMATCH)
                val copiedSigners = verifier.verify(temp)
                if (copiedSigners != staged.signers) throw PluginPackageException("Package signers changed while installing", PluginFailureReason.SIGNER_MISMATCH)
                trust.requireMatch(manifest.id, copiedSigners)
                if (readPluginManifest(temp) != manifest) {
                    throw PluginPackageException("Package manifest changed while installing")
                }
            }, beforeMove = { requireNoDataOwnership(filesDir, manifest.id) },
        )
        val target = pluginPath(dir, "plugin.apk")
        return InstalledPlugin(target, manifest)
    }

    /** Re-check at the actual class-loader boundary, including enable-after-restore. */
    fun verifyForLoad(installed: InstalledPlugin) {
        val manifest = installed.manifest
        checkId(manifest.id)
        requireNoDataOwnership(filesDir, manifest.id)
        val expected = pluginPath(root, "${manifest.id}/${manifest.versionCode}/plugin.apk")
        if (installed.apk.absoluteFile != expected.absoluteFile) throw PluginPackageException("Unexpected package path")
        val signers = verifier.verify(expected)
        trust.requireMatch(manifest.id, signers)
        if (readPluginManifest(expected) != manifest) throw PluginPackageException("Installed manifest changed")
    }

    /** Published versions of one ID, lowest first. */
    fun versions(id: String): List<Long> {
        checkId(id)
        return pluginPath(root, id).listFiles().orEmpty().filter { File(it, "plugin.apk").exists() }
            .mapNotNull { it.name.toLongOrNull() }.sorted()
    }

    fun verifyVersion(id: String, version: Long) {
        val apk = pluginPath(root, "$id/$version/plugin.apk")
        verifyForLoad(InstalledPlugin(apk, readPluginManifest(apk)))
    }

    fun deleteVersion(id: String, version: Long) {
        checkId(id)
        requireNoDataOwnership(filesDir, id)
        deletePackages(pluginPath(root, id), version.toString())
    }

    /** Highest installed version only; a broken package never aborts other IDs. */
    fun installed(onFailure: (String) -> Unit): List<InstalledPlugin> = installed(onFailure, {})

    fun installed(onFailure: (String) -> Unit, onRejected: (String) -> Unit): List<InstalledPlugin> {
        pluginPath(root, ".")
        if (!root.exists()) return emptyList()
        return (root.listFiles() ?: throw IOException("Cannot list plugins")).mapNotNull { pluginDir ->
            try {
                checkId(pluginDir.name)
                if (hasDataOwnership(filesDir, pluginDir.name)) {
                    return@mapNotNull null // Data (including ambiguous ownership) is never code cleanup input.
                }
                pluginPath(root, pluginDir.name)
                val versions = pluginDir.listFiles() ?: throw IOException("Cannot list ${pluginDir.name}")
                val dir = versions.filter { it.name.toLongOrNull() != null && File(it, "plugin.apk").exists() }.maxByOrNull { it.name.toLong() }
                    ?: return@mapNotNull null
                val apk = pluginPath(dir, "plugin.apk")
                val manifest = readPluginManifest(apk)
                if (manifest.id != pluginDir.name || manifest.versionCode.toString() != dir.name) {
                    throw PluginPackageException("Package path does not match manifest")
                }
                InstalledPlugin(apk, manifest).also(::verifyForLoad)
            } catch (error: Exception) {
                onFailure("${pluginDir.name}: ${error.message}")
                if (validPluginId(pluginDir.name) && runCatching { !hasDataOwnership(filesDir, pluginDir.name) }.getOrDefault(false)) onRejected(pluginDir.name)
                null
            }
        }
    }

    /** Also expose retained data/trust after uninstall or failed first publication. */
    fun retainedIds(): Set<String> {
        fun children(dir: File): List<String> {
            if (!dir.exists()) return emptyList()
            return (dir.listFiles() ?: throw IOException("Cannot list retained plugin storage"))
                .map { it.name }.filter(::validPluginId)
        }
        return (children(root) + children(data) + trust.ids()).toSet().filter { id ->
            runCatching { !hasDataOwnership(filesDir, id) }.getOrDefault(false)
        }.toSet()
    }

    fun delete(id: String, clearData: Boolean) {
        checkId(id)
        requireNoDataOwnership(filesDir, id)
        // Manager keeps its tombstone until this returns. Never release the signer needed
        // by a pending publication: retry its exact journal/ticket before deleting packages.
        publication.recoverBeforeDelete(id)
        deletePackages(root, id)
        if (clearData) {
            deletePluginTree(data, id)
            trust.removeAfterClearData(id)
        }
    }

    private fun copyLimited(source: InputStream, out: FileOutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) return
            total += read
            if (total > PACKAGE_MAX_BYTES) throw PluginPackageException("Package is too large")
            out.write(buffer, 0, read)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
