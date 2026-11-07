package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Properties

/** Separate from code lifecycle/trust/private data. The directory itself reserves the ID forever. */
internal data class PluginDataState(
    val version: Long = 0,
    val sha256: String = "",
    val confirmedSha256: String = "",
    val enabled: Boolean = false,
    val pendingDelete: Boolean = false,
)

internal class PluginDataStateStore(filesDir: File) {
    private val files = filesDir.canonicalFile
    private val root get() = pluginPath(files, "plugin-data-catalog")

    fun ids(): List<String> {
        if (!root.exists()) return emptyList()
        return (root.listFiles() ?: throw IOException("Cannot list data ownership"))
            .map { it.name }.filter(::validPluginId)
    }

    fun claim(id: String) {
        checkId(id)
        requireNoCodeOwnership(files, id)
        Files.createDirectories(pluginPath(root, id).toPath())
    }

    fun read(id: String): PluginDataState? {
        checkId(id)
        val owner = pluginPath(root, id)
        if (!owner.exists()) return null
        dataRequire(owner.isDirectory, "Invalid data ownership for $id")
        val file = pluginPath(owner, "selection.properties")
        // A reservation without a committed selection never activates a published archive.
        if (!file.exists()) return null
        dataRequire(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 4096, "Invalid data selection")
        val p = Properties()
        try { file.inputStream().use { p.load(it) } } catch (e: IllegalArgumentException) {
            throw IOException("Invalid data selection for $id", e)
        }
        dataRequire(p.stringPropertyNames() == setOf("schema", "version", "sha256", "confirmedSha256", "enabled", "pendingDelete"), "Invalid data selection fields")
        dataRequire(p.getProperty("schema") == "1", "Invalid data selection schema")
        fun flag(key: String): Boolean = when (p.getProperty(key)) {
            "true" -> true
            "false" -> false
            else -> throw PluginPackageException("Invalid data $key")
        }
        val version = p.getProperty("version").toLongOrNull()
            ?: throw PluginPackageException("Invalid selected version")
        val state = PluginDataState(version, p.getProperty("sha256"), p.getProperty("confirmedSha256"), flag("enabled"), flag("pendingDelete"))
        validate(state)
        return state
    }

    fun write(id: String, state: PluginDataState) {
        validate(state)
        claim(id)
        writePluginProperties(pluginPath(root, id), "selection.properties", Properties().apply {
            setProperty("schema", "1")
            setProperty("version", state.version.toString())
            setProperty("sha256", state.sha256)
            setProperty("confirmedSha256", state.confirmedSha256)
            setProperty("enabled", state.enabled.toString())
            setProperty("pendingDelete", state.pendingDelete.toString())
        })
    }

    private fun validate(state: PluginDataState) {
        dataRequire(state.version >= 0, "Invalid data version")
        if (state.version == 0L) {
            dataRequire(state.sha256.isEmpty() && state.confirmedSha256.isEmpty() && !state.enabled, "Invalid uninstalled selection")
        } else {
            dataRequire(Regex("[0-9a-f]{64}").matches(state.sha256) && state.sha256 == state.confirmedSha256, "Missing digest-bound confirmation")
        }
        dataRequire(!state.pendingDelete || !state.enabled, "Deletion cannot be enabled")
    }
}

internal fun requireNoCodeOwnership(files: File, id: String) {
    checkId(id)
    listOf("plugin-trust/$id.signers", "plugin-data/$id", "plugin-state/$id.properties",
        "plugin-updates/$id.properties", "plugin-snapshots/$id").forEach {
        dataRequire(Files.notExists(pluginPath(files.canonicalFile, it).toPath(), NOFOLLOW_LINKS), "Mixed/code ownership isolated: $id")
    }
}

/** Fail closed even for corrupt retained records or an orphan ZIP without a catalog record. */
internal fun hasDataOwnership(files: File, id: String): Boolean {
    checkId(id)
    val base = files.canonicalFile
    if (!Files.notExists(pluginPath(base, "plugin-data-catalog/$id").toPath(), NOFOLLOW_LINKS)) return true
    val dir = pluginPath(base, "plugins/$id")
    if (!dir.exists()) return false
    return (dir.listFiles() ?: throw IOException("Cannot inspect ownership for $id")).any { version ->
        !Files.notExists(pluginPath(version, "plugin.zip").toPath(), NOFOLLOW_LINKS) || version.name.startsWith(".data-")
    }
}

internal fun requireNoDataOwnership(files: File, id: String) {
    dataRequire(!hasDataOwnership(files, id), "Data-owned ID; code action refused: $id")
}
