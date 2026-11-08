package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

internal data class SelectedPluginData(val manifest: PluginDataManifest, val state: PluginDataState) {
    fun active(skipPlugins: Boolean): Boolean = state.enabled && !state.pendingDelete && !skipPlugins
}
internal data class PluginDataContent(val paths: List<String>, val text: String?)

/** Filesystem-only lifecycle, called on IO under PluginManager's single-process operation mutex. */
internal class PluginDataCatalog(
    filesDir: File,
    private val api: Int,
    // Injection seams exercise real filesystem commit/delete failure paths without Android.
    private val commit: ((String, PluginDataState) -> Unit)? = null,
    private val deletePackages: ((File, String) -> Unit)? = null,
    private val cleanup: PluginStageCleanup = PluginStageCleanup(),
    deleteStage: (File, String) -> Unit = ::deletePluginTree,
    publicationBoundary: (PluginPublicationBoundary) -> Unit = {},
) {
    private val files = filesDir.canonicalFile
    private val states = PluginDataStateStore(files)
    private val store = PluginDataStore(files, api, cleanup = cleanup, deleteStage = deleteStage, publicationBoundary = publicationBoundary)
    fun retryCleanup() = store.retryCleanup()
    private val limits = PluginDataLimits()

    fun stage(input: java.io.InputStream): StagedPluginData = store.stageData(input).also { stage ->
        try {
            val state = states.read(stage.manifest.id)
            dataRequire(state?.pendingDelete != true, "Data deletion pending; restart to retry")
        } catch (e: Exception) {
            withPluginCleanup(cleanup = { store.discardData(stage) }) { throw e }
        }
    }

    fun discard(stage: StagedPluginData) = store.discardData(stage)

    fun install(stage: StagedPluginData, digest: String, acknowledged: Boolean): SelectedPluginData {
        val id = stage.manifest.id
        // Always consume a rejected stage too; callers must not clean a stage while install owns it.
        val old = try {
            dataRequire(acknowledged && digest == stage.sha256, "Fresh digest-bound confirmation required")
            requireNoCodeOwnership(files, id)
            states.read(id).also { dataRequire(it?.pendingDelete != true, "Data deletion pending") }
                .also { states.claim(id) }
        } catch (e: Exception) {
            withPluginCleanup(cleanup = { store.discardData(stage) }) { throw e }
        }
        val published = store.publishConfirmedData(stage, digest, acknowledged)
        val state = PluginDataState(published.manifest.versionCode, published.sha256, digest,
            enabled = old?.takeIf { it.version > 0 }?.enabled ?: true)
        // Publication is not activation. A failed commit leaves old selection intact and a non-active orphan.
        save(id, state)
        // Keep the new selection and the previous one. Best effort: the next update retries.
        try { prune(id, setOfNotNull(state.version, old?.version?.takeIf { it > 0 })) } catch (_: Exception) { }
        return SelectedPluginData(published.manifest, state)
    }

    /** Runs only after the new selection is durable, and only inside a verified data-only tree. */
    private fun prune(id: String, keep: Set<Long>) {
        ensureDataOnlyTree(id)
        val dir = pluginPath(files, "plugins/$id")
        if (!dir.exists()) return
        val stale = Files.newDirectoryStream(dir.toPath()).use { versions ->
            versions.map { it.fileName.toString() }.filter { it.toLong() !in keep }
        }
        // An interruption leaves an empty version directory, which checkNamespaceAndVersion accepts.
        stale.forEach { (deletePackages ?: ::deletePluginTree).invoke(dir, it) }
    }

    fun restore(onFailure: (String) -> Unit): List<SelectedPluginData> =
        restoreInventory(onFailure).mapNotNull { it.selection }

    // Keep existing trailing-lambda callers source-compatible.
    fun restoreInventory(onFailure: (String) -> Unit): List<PluginDataRecord> =
        restoreInventory(onFailure, emptySet())

    fun restoreInventory(
        onFailure: (String) -> Unit,
        crashSuspects: Set<String> = emptySet(),
    ): List<PluginDataRecord> {
        try { store.recoverPublications() }
        catch (e: Exception) { onFailure("Data publication cleanup failed: ${e.message}") }
        // Lost stages have no consent and may never be adopted. There are no live stages at startup.
        val stale = cleanup.reserveRecovery { deletePluginTree(files, "plugin-data-staging") }
        try { cleanup.discard(stale) }
        catch (e: Exception) { onFailure("Data stage cleanup failed: ${e.message}") }
        val result = mutableListOf<PluginDataRecord>()
        states.ids().forEach { id ->
            try {
                requireNoCodeOwnership(files, id)
                val state = states.read(id) ?: return@forEach
                if (state.pendingDelete) {
                    try { finishDelete(id) } catch (e: Exception) {
                        result += PluginDataRecord(id, state, error = "Uninstall pending: ${e.message}")
                        onFailure("$id: data uninstall pending: ${e.message}")
                    }
                } else if (state.version > 0) {
                    val record = managementRecord(id, crashQuarantined = id in crashSuspects)
                    result += record
                    record.error?.let { onFailure("$id: data isolated: $it") }
                }
            } catch (e: Exception) { onFailure("$id: data isolated: ${e.message}") }
        }
        return result
    }

    /** Crash quarantine consults lifecycle state only: never open the suspect archive.
     * The caller owns explicit re-enable/validation and crash-journal acknowledgement.
     */
    fun managementRecord(id: String, crashQuarantined: Boolean = false): PluginDataRecord {
        requireNoCodeOwnership(files, id)
        val state = states.read(id) ?: throw PluginPackageException("No selected data record")
        dataRequire(state.version > 0 || state.pendingDelete, "No installed data selection")
        if (state.pendingDelete) return PluginDataRecord(id, state, error = "Uninstall pending",
            crashQuarantined = crashQuarantined)
        if (crashQuarantined) {
            val disabled = state.copy(enabled = false)
            try {
                if (state.enabled) save(id, disabled)
            } catch (e: Exception) {
                // Preserve the actual durable intent for management, but never expose a page
                // or an enable action while the quarantine write remains uncommitted.
                return PluginDataRecord(id, state, error = "Crash quarantine disable failed: ${e.message}",
                    crashQuarantined = true)
            }
            return PluginDataRecord(id, disabled, crashQuarantined = true)
        }
        return try { PluginDataRecord(id, state, selected(id, state)) }
        catch (e: Exception) { PluginDataRecord(id, state, error = e.message ?: "Invalid data archive") }
    }

    /** Disable needs ownership + writable lifecycle state, NOT readable archive bytes.
     * A matching in-memory manifest is presentation-only on disable; otherwise return no card.
     * Enable still performs full archive validation before changing durable intent.
     */
    fun setEnabled(id: String, enabled: Boolean, previous: SelectedPluginData? = null): SelectedPluginData? {
        requireNoCodeOwnership(files, id)
        val state = states.read(id) ?: throw PluginPackageException("No selected data version")
        dataRequire(!state.pendingDelete && state.version > 0, "Data unavailable")
        val selection = if (enabled) selected(id, state) else previous?.takeIf {
            it.manifest.id == id && it.state.version == state.version &&
                it.state.sha256 == state.sha256 && it.state.confirmedSha256 == state.confirmedSha256
        }
        val next = state.copy(enabled = enabled)
        save(id, next)
        return selection?.copy(state = next)
    }

    fun beginDelete(id: String) {
        requireNoCodeOwnership(files, id)
        val state = states.read(id) ?: throw PluginPackageException("No data selection")
        save(id, state.copy(enabled = false, pendingDelete = true))
    }

    /** Caller revokes pages AFTER beginDelete commits and BEFORE this method deletes bytes. */
    fun finishDelete(id: String) {
        requireNoCodeOwnership(files, id)
        val state = states.read(id) ?: throw PluginPackageException("No deletion intent")
        dataRequire(state.pendingDelete, "Missing deletion intent")
        ensureDataOnlyTree(id)
        val root = pluginPath(files, "plugins")
        if (deletePackages == null) deletePluginTree(root, id) else deletePackages.invoke(root, id)
        save(id, PluginDataState()) // ownership directory retained; explicit release is not offered.
    }

    fun content(selection: SelectedPluginData, path: String?): PluginDataContent {
        val current = states.read(selection.manifest.id) ?: throw PluginPackageException("Data page revoked")
        dataRequire(current == selection.state && current.enabled && !current.pendingDelete, "Data page revoked")
        val paths = mutableListOf<String>()
        var text: String? = null
        validateSelection(selection.manifest.id, current) { name, bytes ->
            paths += name
            if (name == path) text = dataUtf8(bytes)
        }.also { dataRequire(it == selection.manifest, "Selected manifest changed") }
        dataRequire(path == null || text != null, "Unknown data resource")
        return PluginDataContent(paths.toList(), text)
    }

    private fun selected(id: String, state: PluginDataState): SelectedPluginData =
        SelectedPluginData(validateSelection(id, state), state)

    private fun validateSelection(id: String, state: PluginDataState, visit: (String, ByteArray) -> Unit = { _, _ -> }): PluginDataManifest {
        requireNoCodeOwnership(files, id)
        ensureDataOnlyTree(id)
        val zip = pluginPath(files, "plugins/$id/${state.version}/plugin.zip")
        dataRequire(Files.isRegularFile(zip.toPath(), NOFOLLOW_LINKS) && zip.length() <= limits.compressedBytes, "Invalid selected archive")
        val bytes = Files.newInputStream(zip.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                dataRequire(n > 0 && n <= limits.compressedBytes - out.size(), "Invalid archive size")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        dataRequire(digest == state.sha256 && digest == state.confirmedSha256, "Selected digest mismatch")
        val manifest = validatePluginDataZip(bytes, api, limits, visit).manifest
        dataRequire(manifest.id == id && manifest.versionCode == state.version, "Selected identity mismatch")
        return manifest
    }

    private fun ensureDataOnlyTree(id: String) {
        val dir = pluginPath(files, "plugins/$id")
        if (!dir.exists()) return
        dataRequire(dir.isDirectory, "Invalid data package tree")
        Files.newDirectoryStream(dir.toPath()).use { versions ->
            versions.forEach { version ->
                val name = version.fileName.toString()
                dataRequire(name.toLongOrNull()?.let { it > 0 && it.toString() == name } == true, "Ambiguous data package tree")
                val child = pluginPath(dir, name)
                dataRequire(Files.isDirectory(child.toPath(), NOFOLLOW_LINKS), "Invalid data version")
                Files.newDirectoryStream(child.toPath()).use { entries ->
                    // Empty version is valid only for resuming interrupted deletion. No code is ever deleted here.
                    dataRequire(entries.all { it.fileName.toString() == "plugin.zip" && Files.isRegularFile(pluginPath(child, "plugin.zip").toPath(), NOFOLLOW_LINKS) }, "Mixed data/code tree isolated")
                }
            }
        }
    }

    private fun save(id: String, state: PluginDataState) {
        if (commit == null) states.write(id, state) else commit.invoke(id, state)
    }
}
