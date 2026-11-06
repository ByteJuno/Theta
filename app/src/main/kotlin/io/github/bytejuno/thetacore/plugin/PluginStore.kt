package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

private const val PACKAGE_MAX_BYTES = 256L * 1024 * 1024

internal class StagedPlugin(val file: File, val manifest: PluginManifest, val sha256: String)

internal class InstalledPlugin(val apk: File, val manifest: PluginManifest)

/** 插件包的落盘：`plugins/<id>/<versionCode>/plugin.apk`。 */
internal class PluginStore(filesDir: File, cacheDir: File) {
    private val root = File(filesDir, "plugins")
    private val staging = File(cacheDir, "plugin-staging")

    fun stage(source: InputStream): StagedPlugin {
        staging.mkdirs()
        val file = File(staging, "${UUID.randomUUID()}.apk")
        try {
            file.outputStream().use { out -> copyLimited(source, out) }
            return StagedPlugin(file, readPluginManifest(file), sha256(file))
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun install(staged: StagedPlugin): InstalledPlugin {
        val manifest = staged.manifest
        val dir = File(root, "${manifest.id}/${manifest.versionCode}")
        dir.mkdirs()
        val target = File(dir, "plugin.apk")
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
            if (!temp.renameTo(target)) throw PluginPackageException("Cannot publish the package")
        } finally {
            temp.delete()
            staged.file.delete()
        }
        return InstalledPlugin(target, manifest)
    }

    /** 每个插件取最高版本。 */
    fun installed(): List<InstalledPlugin> =
        root.listFiles().orEmpty().mapNotNull { pluginDir ->
            pluginDir.listFiles().orEmpty()
                .map { File(it, "plugin.apk") }
                .filter(File::isFile)
                .map { InstalledPlugin(it, readPluginManifest(it)) }
                .maxByOrNull { it.manifest.versionCode }
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
