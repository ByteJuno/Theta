package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID

internal data class PluginState(
    val enabled: Boolean = true,
    val pendingDelete: Boolean = false,
    val clearData: Boolean = false,
    val initializing: Boolean = false,
    // Only a failed first initialization may resume the restored OLD version.
    val resumeAfterRollback: Boolean = false,
)

/** Desired state is independent of whether this process has a running instance. */
internal class PluginStateStore(private val filesDir: File) {
    private val root get() = pluginPath(filesDir.canonicalFile, "plugin-state")

    fun ids(): List<String> {
        val dir = root
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: throw IOException("Cannot list plugin state"))
            .filter { it.name.endsWith(".properties") }
            .map { it.name.removeSuffix(".properties") }
            .filter(::validPluginId)
    }

    fun read(id: String): PluginState {
        checkId(id)
        val file = pluginPath(root, "$id.properties")
        if (!file.exists()) return PluginState()
        val properties = Properties()
        try {
            file.inputStream().use(properties::load)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid state for $id", e)
        }
        fun flag(key: String): Boolean = when (properties.getProperty(key)) {
            "true" -> true
            "false" -> false
            else -> throw IOException("Invalid $key state for $id")
        }
        return PluginState(
            flag("enabled"), flag("pendingDelete"), flag("clearData"), flag("initializing"),
            if (properties.containsKey("resumeAfterRollback")) flag("resumeAfterRollback") else false,
        )
    }

    fun write(id: String, state: PluginState) {
        checkId(id)
        writePluginProperties(root, "$id.properties", Properties().apply {
            setProperty("enabled", state.enabled.toString())
            setProperty("pendingDelete", state.pendingDelete.toString())
            setProperty("clearData", state.clearData.toString())
            setProperty("initializing", state.initializing.toString())
            setProperty("resumeAfterRollback", state.resumeAfterRollback.toString())
        })
    }

    fun remove(id: String) {
        checkId(id)
        Files.deleteIfExists(pluginPath(root, "$id.properties").toPath())
    }
}

internal fun writePluginProperties(dir: File, name: String, properties: Properties) {
    Files.createDirectories(dir.toPath())
    val target = pluginPath(dir, name)
    val temp = pluginPath(dir, "${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temp).use { out -> properties.store(out, null); out.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp.toPath())
    }
}

/** Once entered, even construction failure consumes the ID until process restart. */
internal class PluginLoadGate {
    private val attempted = mutableSetOf<String>()
    fun wasAttempted(id: String): Boolean = id in attempted
    fun begin(id: String): Boolean = attempted.add(id)
}

internal fun validPluginId(id: String): Boolean = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""").matches(id)

internal fun checkId(id: String) {
    if (!validPluginId(id)) throw PluginPackageException("Invalid plugin id")
}

/** Reject linked ancestors, not just a string-prefix match of canonical paths. */
internal fun pluginPath(parent: File, child: String): File {
    val base = parent.toPath().toAbsolutePath().normalize()
    val path = base.resolve(child).normalize()
    if (!path.startsWith(base)) throw IOException("Path escapes plugin storage")
    var cursor: Path? = path
    while (cursor != null) {
        if (Files.isSymbolicLink(cursor)) throw IOException("Linked plugin storage is not supported: $cursor")
        cursor = cursor.parent
    }
    return path.toFile()
}

/** Walk without FOLLOW_LINKS: links inside the tree are unlinked, never traversed. */
internal fun deletePluginTree(parent: File, name: String) {
    val safeParent = pluginPath(parent, ".").toPath()
    val target = safeParent.resolve(name).normalize()
    if (target.parent != safeParent) throw IOException("Invalid deletion path")
    if (!Files.exists(target, NOFOLLOW_LINKS)) return
    Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
