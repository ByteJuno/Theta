package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

private const val PACKAGE_MAX_BYTES = 256L * 1024 * 1024

internal class StagedPlugin(val file: File, val manifest: PluginManifest, val sha256: String)

internal class InstalledPlugin(val apk: File, val manifest: PluginManifest)

/** 插件包的落盘：`plugins/<id>/<versionCode>/plugin.apk`。 */
internal class PluginStore(private val filesDir: File, private val cacheDir: File) {
    // Resolve only trusted Android storage aliases; plugin-controlled descendants stay checked.
    private val root get() = pluginPath(filesDir.canonicalFile, "plugins")
    private val staging get() = pluginPath(cacheDir.canonicalFile, "plugin-staging")
    private val data get() = pluginPath(filesDir.canonicalFile, "plugin-data")

    fun stage(source: InputStream): StagedPlugin {
        pluginPath(staging, ".")
        Files.createDirectories(staging.toPath())
        val file = File(staging, "${UUID.randomUUID()}.apk")
        try {
            file.outputStream().use { out -> copyLimited(source, out) }
            return StagedPlugin(file, readPluginManifest(file), sha256(file))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun install(staged: StagedPlugin): InstalledPlugin = try {
        installPackage(staged)
    } finally {
        staged.file.delete()
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
        val dir = pluginPath(pluginDir, manifest.versionCode.toString())
        Files.createDirectories(dir.toPath())
        val target = pluginPath(dir, "plugin.apk")
        if (target.exists()) throw PluginPackageException("Package version already exists")
        val temp = File(dir, "${UUID.randomUUID()}.tmp")
        try {
            // Android 14 起不能加载可写的 dex。文件要先设成只读，内容再经已打开的描述符写入，
            // 写完核验过才改名发布，半截文件不会被当成可用版本。
            FileOutputStream(temp).use { out ->
                if (!temp.setReadOnly()) throw PluginPackageException("Cannot make the package read-only")
                staged.file.inputStream().use { it.copyTo(out) }
                out.fd.sync()
            }
            if (sha256(temp) != staged.sha256) throw PluginPackageException("Package changed while installing")
            val copied = readPluginManifest(temp)
            if (copied.id != manifest.id || copied.versionCode != manifest.versionCode) {
                throw PluginPackageException("Package manifest changed while installing")
            }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE)
        } finally {
            temp.delete()
            dir.delete() // Removes a failed empty version only, never a published package.
        }
        return InstalledPlugin(target, manifest)
    }

    /** Highest installed version only; a broken package never aborts other IDs. */
    fun installed(onFailure: (String) -> Unit): List<InstalledPlugin> {
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
                InstalledPlugin(apk, manifest)
            } catch (error: Exception) {
                onFailure("${pluginDir.name}: ${error.message}")
                null
            }
        }
    }

    fun delete(id: String, clearData: Boolean) {
        checkId(id)
        deletePluginTree(root, id)
        if (clearData) deletePluginTree(data, id)
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
