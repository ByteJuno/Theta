package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID

/** Immutable per-ID trust, independent of enabled state and retained plugin data. */
internal class PluginTrustStore(private val filesDir: File) {
    private val root get() = pluginPath(filesDir.canonicalFile, "plugin-trust")

    fun ids(): List<String> {
        val dir = root
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: throw java.io.IOException("Cannot list plugin trust"))
            .mapNotNull { file -> file.name.takeIf { it.endsWith(".signers") }?.removeSuffix(".signers") }
            .filter(::validPluginId)
    }

    fun read(id: String): PluginSigners? {
        checkId(id)
        val file = pluginPath(root, "$id.signers")
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return null
        if (!file.isFile || file.length() !in 1..64 * 1024L) {
            throw PluginPackageException("Corrupt signer binding for $id")
        }
        val lines = file.readLines(Charsets.US_ASCII)
        if (lines.size < 2 || lines.first() != "theta-signers-v1") {
            throw PluginPackageException("Corrupt signer binding for $id")
        }
        val signers = PluginSigners(lines.drop(1))
        if (lines.drop(1) != signers.sha256) throw PluginPackageException("Corrupt signer binding for $id")
        return signers
    }

    fun requireMatch(id: String, signers: PluginSigners) {
        val bound = read(id) ?: throw PluginPackageException("Missing signer binding for $id; clear-data uninstall required")
        if (bound != signers) throw PluginPackageException("APK signer does not match trusted signer set for $id")
    }

    /** Called only by the confirmed publication path; never by staging or loading. */
    fun bindConfirmed(id: String, signers: PluginSigners) {
        checkId(id)
        if (read(id) != null) {
            requireMatch(id, signers)
            return
        }
        Files.createDirectories(root.toPath())
        val target = pluginPath(root, "$id.signers")
        val temp = pluginPath(root, "${UUID.randomUUID()}.tmp")
        withPluginCleanup(cleanup = { Files.deleteIfExists(temp.toPath()); Unit }) {
            FileOutputStream(temp).use { out ->
                out.write(("theta-signers-v1\n" + signers.sha256.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII))
                out.fd.sync()
            }
            // PluginManager serializes all operations, including recovery and uninstall.
            // Do not replace or repair an existing binding.
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                requireMatch(id, signers)
            } else {
                Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE)
            }
        }
    }

    /** Only AFTER successful package AND data deletion, also during tombstone recovery. */
    fun removeAfterClearData(id: String) {
        checkId(id)
        Files.deleteIfExists(pluginPath(root, "$id.signers").toPath())
    }
}
