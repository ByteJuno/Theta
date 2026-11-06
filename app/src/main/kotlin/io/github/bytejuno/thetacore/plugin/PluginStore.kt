package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

private const val PACKAGE_MAX_BYTES = 256L * 1024 * 1024

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
) {
    // Resolve only trusted Android storage aliases; plugin-controlled descendants stay checked.
    private val root get() = pluginPath(filesDir.canonicalFile, "plugins")
    private val staging get() = pluginPath(cacheDir.canonicalFile, "plugin-staging")
    private val data get() = pluginPath(filesDir.canonicalFile, "plugin-data")
    private val trust = PluginTrustStore(filesDir)

    fun stage(source: InputStream): StagedPlugin {
        pluginPath(staging, ".")
        Files.createDirectories(staging.toPath())
        val file = File(staging, "${UUID.randomUUID()}.apk")
        try {
            file.outputStream().use { out -> copyLimited(source, out) }
            // Keep malformed-ZIP/manifest errors specific. This is metadata parsing,
            // never a data-plugin fallback; no confirmation or code before verification.
            val manifest = readPluginManifest(file)
            val signers = verifier.verify(file)
            checkTrustForInstall(manifest.id, signers)
            return StagedPlugin(file, manifest, sha256(file), signers)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    /** The caller must obtain user confirmation before entering this method. */
    fun install(staged: StagedPlugin): InstalledPlugin =
        withPluginCleanup(cleanup = { staged.file.delete() }) { installPackage(staged) }

    private fun checkTrustForInstall(id: String, signers: PluginSigners) {
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
                throw PluginPackageException("Missing signer binding for $id; clear-data uninstall required")
            }
        }
    }

    private fun installPackage(staged: StagedPlugin): InstalledPlugin {
        val manifest = staged.manifest
        checkId(manifest.id)
        val pluginDir = pluginPath(root, manifest.id)
        val highest = pluginDir.listFiles().orEmpty().filter { File(it, "plugin.apk").exists() }
            .mapNotNull { it.name.toLongOrNull() }.maxOrNull()
        if (highest != null && manifest.versionCode <= highest) {
            throw PluginPackageException("Install requires a higher version than $highest")
        }
        checkTrustForInstall(manifest.id, staged.signers)
        // Confirmation pins trust before creating any published APK. Failed first
        // publication may retain this binding; only the same signer can retry.
        trust.bindConfirmed(manifest.id, staged.signers)
        val dir = pluginPath(pluginDir, manifest.versionCode.toString())
        Files.createDirectories(dir.toPath())
        val target = pluginPath(dir, "plugin.apk")
        if (target.exists()) throw PluginPackageException("Package version already exists")
        val temp = File(dir, "${UUID.randomUUID()}.tmp")
        withPluginCleanup(cleanup = {
            withPluginCleanup(cleanup = { dir.delete() }) { temp.delete() }
            // Removes a failed empty version only, never a published package.
        }) {
            // Android 14 起不能加载可写的 dex。文件要先设成只读，内容再经已打开的描述符写入，
            // 写完核验过才改名发布，半截文件不会被当成可用版本。
            FileOutputStream(temp).use { out ->
                if (!temp.setReadOnly()) throw PluginPackageException("Cannot make the package read-only")
                staged.file.inputStream().use { copyLimited(it, out) }
                out.fd.sync()
            }
            if (sha256(temp) != staged.sha256) throw PluginPackageException("Package changed while installing")
            val copiedSigners = verifier.verify(temp)
            if (copiedSigners != staged.signers) throw PluginPackageException("Package signers changed while installing")
            trust.requireMatch(manifest.id, copiedSigners)
            if (readPluginManifest(temp) != manifest) {
                throw PluginPackageException("Package manifest changed while installing")
            }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE)
        }
        return InstalledPlugin(target, manifest)
    }

    /** Re-check at the actual class-loader boundary, including enable-after-restore. */
    fun verifyForLoad(installed: InstalledPlugin) {
        val manifest = installed.manifest
        checkId(manifest.id)
        val expected = pluginPath(root, "${manifest.id}/${manifest.versionCode}/plugin.apk")
        if (installed.apk.absoluteFile != expected.absoluteFile) throw PluginPackageException("Unexpected package path")
        val signers = verifier.verify(expected)
        trust.requireMatch(manifest.id, signers)
        if (readPluginManifest(expected) != manifest) throw PluginPackageException("Installed manifest changed")
    }

    /** Highest installed version only; a broken package never aborts other IDs. */
    fun installed(onFailure: (String) -> Unit): List<InstalledPlugin> = installed(onFailure, {})

    fun installed(onFailure: (String) -> Unit, onRejected: (String) -> Unit): List<InstalledPlugin> {
        pluginPath(root, ".")
        if (!root.exists()) return emptyList()
        return (root.listFiles() ?: throw IOException("Cannot list plugins")).mapNotNull { pluginDir ->
            try {
                checkId(pluginDir.name)
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
                if (validPluginId(pluginDir.name)) onRejected(pluginDir.name)
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
        return (children(root) + children(data) + trust.ids()).toSet()
    }

    fun delete(id: String, clearData: Boolean) {
        checkId(id)
        deletePluginTree(root, id)
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
