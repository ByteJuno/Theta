package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Properties
import java.util.UUID

internal enum class PluginPublicationKind(val directory: String, val archive: String) {
    DATA("plugin-data-publication", "plugin.zip"), CODE("plugin-code-publication", "plugin.apk"),
}
internal enum class PluginPublicationBoundary { INTENT, TEMP_CREATED, PAYLOAD_READY, PARENT_READY, MOVED }

/** One serialized publication per kind. The synced intent precedes ALL package directory creation.
 * Temporary versions live outside plugins/, on the same filesystem. Recovery never selects a
 * version, deletes a published version, or guesses ownership from a .data-* / *.tmp filename.
 * Process-death recovery only; not a power-loss or hostile same-UID transaction protocol.
 */
internal class PluginPackagePublication(
    private val files: File,
    private val kind: PluginPublicationKind,
    private val cleanup: PluginStageCleanup,
    private val deleteTemporary: (File, String) -> Unit = ::deletePluginTree,
    private val boundary: (PluginPublicationBoundary) -> Unit = {},
    private val deleteIntent: (File) -> Unit = { Files.delete(it.toPath()) },
) {
    private val journal get() = pluginPath(files.canonicalFile, kind.directory)
    private val intent get() = pluginPath(journal, "intent.properties")
    private var recovered = false
    private var recoveryTicket: PluginStageCleanup.Ticket? = null
    private data class Intent(val id: String, val version: Long, val token: String, val newParent: Boolean)

    fun recoverOnce() {
        if (recovered) return
        // One retry ticket owns enumeration/parsing too, so a read or removal failure cannot
        // disappear merely because startup has already run. No per-orphan capacity expansion.
        val ticket = cleanup.reserveRecovery { recoverTrackedIntent() }
        recoveryTicket = ticket
        recovered = true
        cleanup.discard(ticket)
    }

    /** A clear-data uninstall must finish this ID's journal while its owner still exists.
     * Other IDs' intents grant no cleanup rights and must not be consumed here.
     */
    fun recoverBeforeDelete(id: String) {
        checkId(id)
        val record = readIntent() ?: return
        if (record.id != id) return
        val ticket = recoveryTicket ?: cleanup.reserveRecovery { recoverTrackedIntent() }.also {
            recoveryTicket = it
        }
        cleanup.discard(ticket) // failure retains both the ticket and durable signer ownership
        dataRequire(Files.notExists(intent.toPath(), NOFOLLOW_LINKS), "Publication cleanup pending")
    }

    private fun recoverTrackedIntent() {
        recoverIntent()
        recoveryTicket = null
    }

    fun publish(id: String, version: Long, write: (File) -> Unit, beforeMove: () -> Unit): File {
        recoverOnce()
        checkId(id)
        dataRequire(version > 0, "Invalid publication version")
        val parent = pluginPath(files.canonicalFile, "plugins/$id")
        val target = pluginPath(parent, version.toString())
        dataRequire(Files.notExists(intent.toPath(), NOFOLLOW_LINKS), "Publication cleanup pending")
        dataRequire(Files.notExists(target.toPath(), NOFOLLOW_LINKS), "Package version already exists")
        val record = Intent(id, version, UUID.randomUUID().toString(), Files.notExists(parent.toPath(), NOFOLLOW_LINKS))
        val temporary = pluginPath(journal, record.token)
        val ticket = cleanup.reserve { recoverTrackedIntent() }
        recoveryTicket = ticket
        var committed = false
        return withCommittedPluginCleanup(cleanup = {
            withPluginCleanup(cleanup = { cleanup.discard(ticket) }) {
                if (committed) boundary(PluginPublicationBoundary.MOVED)
            }
        }) {
            writeIntent(Properties().apply {
                setProperty("schema", "1")
                setProperty("kind", kind.name)
                setProperty("id", id)
                setProperty("version", version.toString())
                setProperty("token", record.token)
                setProperty("newParent", record.newParent.toString())
            })
            boundary(PluginPublicationBoundary.INTENT)
            Files.createDirectory(temporary.toPath())
            boundary(PluginPublicationBoundary.TEMP_CREATED)
            write(pluginPath(temporary, kind.archive))
            boundary(PluginPublicationBoundary.PAYLOAD_READY)
            Files.createDirectories(pluginPath(files.canonicalFile, "plugins").toPath())
            if (record.newParent) Files.createDirectory(parent.toPath())
            boundary(PluginPublicationBoundary.PARENT_READY)
            beforeMove()
            dataRequire(Files.notExists(target.toPath(), NOFOLLOW_LINKS), "Package version already exists")
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
            committed = true
            target
        }
    }

    private fun writeIntent(properties: Properties) {
        Files.createDirectories(journal.toPath())
        val preparing = pluginPath(journal, "intent.preparing")
        val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
        FileChannel.open(preparing.toPath(), WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(preparing.toPath(), intent.toPath(), ATOMIC_MOVE)
    }

    private fun recoverIntent() {
        // Exact protocol scratch file, not a glob. No package paths can have been created
        // before intent rename. A partial scratch file therefore carries no deletion rights.
        val preparing = pluginPath(journal, "intent.preparing")
        if (!Files.notExists(preparing.toPath(), NOFOLLOW_LINKS)) {
            dataRequire(Files.isRegularFile(preparing.toPath(), NOFOLLOW_LINKS) && preparing.length() <= 4096,
                "Invalid preparing publication intent")
            Files.delete(preparing.toPath())
        }
        val record = readIntent() ?: return
        val (id, _, token, newParent) = record
        when (kind) {
            PluginPublicationKind.DATA -> {
                requireNoCodeOwnership(files, id)
                dataRequire(Files.isDirectory(pluginPath(files.canonicalFile, "plugin-data-catalog/$id").toPath(), NOFOLLOW_LINKS), "Missing data publication owner")
            }
            PluginPublicationKind.CODE -> {
                requireNoDataOwnership(files, id)
                dataRequire(PluginTrustStore(files).read(id) != null, "Missing code publication owner")
            }
        }
        val parent = pluginPath(files.canonicalFile, "plugins/$id")
        // A changed/mixed tree is quarantined, never cleanup input, even with a valid intent.
        if (!Files.notExists(parent.toPath(), NOFOLLOW_LINKS)) {
            dataRequire(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS), "Invalid publication parent")
            Files.newDirectoryStream(parent.toPath()).use { versions -> versions.forEach {
                val name = it.fileName.toString()
                dataRequire(name.toLongOrNull()?.let { n -> n > 0 && n.toString() == name } == true, "Ambiguous publication tree")
                requirePayload(pluginPath(parent, name))
            } }
        }
        val temporary = pluginPath(journal, token)
        if (!Files.notExists(temporary.toPath(), NOFOLLOW_LINKS)) {
            requirePayload(temporary)
            deleteTemporary(journal, token)
        }
        if (newParent && Files.exists(parent.toPath(), NOFOLLOW_LINKS)) {
            val empty = Files.newDirectoryStream(parent.toPath()).use { !it.iterator().hasNext() }
            if (empty) Files.delete(parent.toPath()) // ONLY this intent's initially absent, still-empty parent.
        }
        deleteIntent(intent) // last: failed cleanup retains durable AND in-memory ownership
    }

    private fun readIntent(): Intent? {
        if (Files.notExists(intent.toPath(), NOFOLLOW_LINKS)) return null
        dataRequire(Files.isRegularFile(intent.toPath(), NOFOLLOW_LINKS) && intent.length() <= 4096, "Invalid publication intent")
        val p = Properties().apply { intent.inputStream().use { load(it) } }
        dataRequire(p.stringPropertyNames() == setOf("schema", "kind", "id", "version", "token", "newParent"), "Invalid publication fields")
        dataRequire(p.getProperty("schema") == "1" && p.getProperty("kind") == kind.name, "Invalid publication kind")
        val id = p.getProperty("id").also(::checkId)
        val versionText = p.getProperty("version")
        val version = versionText.toLongOrNull()
        dataRequire(version != null && version > 0 && version.toString() == versionText, "Invalid publication version")
        val token = p.getProperty("token")
        dataRequire(runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false), "Invalid publication token")
        val newParent = when (p.getProperty("newParent")) {
            "true" -> true
            "false" -> false
            else -> throw PluginPackageException("Invalid publication parent ownership")
        }
        return Intent(id, version!!, token, newParent)
    }

    private fun requirePayload(dir: File) {
        dataRequire(Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS), "Invalid publication directory")
        Files.newDirectoryStream(dir.toPath()).use { entries -> entries.forEach {
            dataRequire(it.fileName.toString() == kind.archive &&
                Files.isRegularFile(pluginPath(dir, kind.archive).toPath(), NOFOLLOW_LINKS), "Mixed publication tree isolated")
        } }
    }
}
