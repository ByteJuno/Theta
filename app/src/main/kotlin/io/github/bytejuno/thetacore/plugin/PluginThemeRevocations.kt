package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Properties

/** A write-ahead intent independent of the preference file and archive readability.
 * Only PluginManager's operation gate owns these records. Retain until preference commit
 * succeeds; a crash on either side of that commit can safely repeat plugin-scoped clearing. */
internal class PluginThemeRevocations(filesDir: File) {
    private val root = pluginPath(filesDir, "plugin-theme-revocations")

    fun begin(id: String) {
        checkId(id)
        writePluginProperties(root, "$id.properties", Properties().apply { setProperty("plugin", id) })
    }

    fun pending(): List<String> {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return emptyList()
        dataRequire(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS), "Invalid theme revocation directory")
        return Files.newDirectoryStream(root.toPath(), "*.properties").use { entries ->
            entries.map { entry ->
                val id = entry.fileName.toString().removeSuffix(".properties")
                checkId(id)
                val file = pluginPath(root, entry.fileName.toString())
                dataRequire(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 4096,
                    "Invalid theme revocation intent")
                val value = Properties().apply { file.inputStream().use(::load) }
                dataRequire(value.stringPropertyNames() == setOf("plugin") && value.getProperty("plugin") == id,
                    "Invalid theme revocation identity")
                id
            }.toList()
        }
    }

    fun finish(id: String) {
        checkId(id)
        Files.deleteIfExists(pluginPath(root, "$id.properties").toPath())
    }
}
