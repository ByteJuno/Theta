package io.github.bytejuno.thetacore.plugin

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bytejuno.thetacore.sdk.Extension
import io.github.bytejuno.thetacore.sdk.EntryAdapter
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.PluginPrefs
import io.github.bytejuno.thetacore.sdk.PluginSecrets
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonObject
import io.github.bytejuno.thetacore.sdk.PluginContext
import io.github.bytejuno.thetacore.sdk.Registration
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.THETA_API_LEVEL
import io.github.bytejuno.thetacore.sdk.ThetaPlugin
import java.io.File
import java.io.IOException
import java.nio.file.Files
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val ENABLE_TIMEOUT_MS = 10_000L
private const val CLOSE_TIMEOUT_MS = 5_000L

internal class ManagedPlugin(val manifest: PluginManifest, state: PluginState) {
    var enabled by mutableStateOf(state.enabled)
        private set
    var status by mutableStateOf(if (state.enabled) "Not running" else "Disabled")
        private set
    var busy by mutableStateOf(false)
        private set
    var pendingDelete by mutableStateOf(state.pendingDelete)
        private set
    var canRollback by mutableStateOf(false)
        internal set
    var updateObservation by mutableStateOf<PluginUpdate?>(null)
        internal set
    internal var runtime by mutableStateOf<PluginRuntime?>(null)
    val pages: List<SettingsPage>
        get() = runtime?.takeIf { it.active && !it.context.faults.hasFault() }
            ?.context?.extensions?.filterIsInstance<SettingsPage>().orEmpty()

    val entryCapacityExceeded: Boolean
        get() = runtime?.takeIf { it.active }?.context?.entryCapacityExceeded == true
    val entries: List<PluginEntryRuntime>
        get() = runtime?.takeIf { it.active && !it.context.faults.hasFault() }?.context?.entryRuntimes.orEmpty()
    val tools: List<Tool>
        get() = runtime?.takeIf { it.active && !it.context.faults.hasFault() }
            ?.context?.extensions?.filterIsInstance<Tool>().orEmpty()

    internal fun state(state: PluginState) {
        enabled = state.enabled
        pendingDelete = state.pendingDelete
    }
    internal fun status(value: String) { status = value }
    internal fun busy(value: Boolean) { busy = value }
}

/** Retained even after failure/disable: dex cannot be unloaded safely in this process. */
internal class PluginRuntime(val context: HostPluginContext, val version: Long) {
    @Volatile var loader: ClassLoader? = null
    @Volatile var entry: ThetaPlugin? = null
    var active by mutableStateOf(false)
    var initialization: PluginWorker<Unit>? = null
    var closing: PluginWorker<List<Throwable>>? = null
    var closeFailed = false
}

internal class PluginManager(context: Context) {
    private val app = context.applicationContext
    private companion object {
        // Multiple manager instances must never multiply the process execution allowance.
        val executionBudget = PluginExecutionBudget()
        val hostToolBridge = HostToolBridgeRoot(executionBudget)
    }

    /** Internal opt-in only: no UI/model path is changed. The caller supplies a tested durability
     * adapter; Android on-device persistence is not claimed by the JVM bridge. */
    suspend fun initializeHostTools(store: io.github.bytejuno.thetacore.agent.ledger.InvocationStore): HostToolExecutionService =
        hostToolBridge.initialize(store)
    private val stageCleanup = PluginStageCleanup()
    private val store = PluginStore(app.filesDir, app.cacheDir, PlatformPluginSignatureVerifier(app.packageManager), stageCleanup)
    private val states = PluginStateStore(app.filesDir)
    private val dataCatalog = PluginDataCatalog(app.filesDir, THETA_API_LEVEL, cleanup = stageCleanup)
    val dataPlugins = mutableStateListOf<ManagedPluginData>()
    val dataInventory = mutableStateListOf<ManagedDataRecord>()
    private val updates = PluginUpdates(app.filesDir, store)
    // Last-resort handling for application-owned launch jobs; never recursively launch
    // from this handler and never touch Compose state from an arbitrary thread.
    private val host = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error -> logHostFailure(error) })
    private val operations = Mutex()
    private val gate = PluginLoadGate()
    private val recoveryBlocked = mutableSetOf<String>()
    // 已安排更新或回滚的 ID：这个进程里不能再加载，等冷启动切换。
    private val deferred = mutableSetOf<String>()
    private var restored = false
    private var skipLoading = false
    private var skipDataLoading = false
    var dataRecoveryBlocked by mutableStateOf(false)
        private set
    private val crashRecovery get() = (app as? io.github.bytejuno.thetacore.ThetaApplication)?.crashRecovery
    private val crashSuspects = mutableSetOf<String>()

    val plugins = mutableStateListOf<ManagedPlugin>()
    val failures = mutableStateListOf<String>()
    // Rejected packages remain removable without executing or trusting their manifest.
    val rejectedIds = mutableStateListOf<String>()

    // Replaces the UI Job only for serialized durable transitions. External URI acquisition,
    // copy and provider close NEVER enter here; their bounded wait preserves the caller Job.
    private suspend fun <T> operation(block: suspend () -> T): T = withContext(host.coroutineContext) {
        operations.withLock {
            try { block() } catch (error: SecurityException) {
                throw IOException("Plugin storage access failed", error)
            }
        }
    }

    suspend fun restore(skipPlugins: Boolean = false) = operation { restoreInternal(skipPlugins) }

    private suspend fun restoreInternal(skipPlugins: Boolean = false) {
        if (restored) return
        val policy = crashRecovery?.restorePolicy(skipPlugins)
        skipLoading = policy?.skipCode ?: skipPlugins
        dataRecoveryBlocked = policy?.skipData == true
        skipDataLoading = skipPlugins || dataRecoveryBlocked
        crashSuspects.addAll(policy?.suspects.orEmpty())
        val blocked = recoveryBlocked.toMutableSet()
        val interrupted = mutableSetOf<String>()
        val messages = mutableListOf<String>()
        val rejected = mutableSetOf<String>()
        val restoredData = if (policy?.skipData == true) emptyList() else
            withContext(Dispatchers.IO) { dataCatalog.restoreInventory(messages::add, crashSuspects) }
        restoredData.forEach { replaceDataInventory(dataInventory, dataPlugins, it, skipDataLoading) }
        val installed = withContext(Dispatchers.IO) {
            try { store.cleanupColdStages() }
            catch (e: Exception) { messages += "Code stage cleanup failed: ${e.message}" }
            // Tombstones precede package parsing, including corrupt APKs.
            states.ids().forEach { id ->
                isolatePluginRecovery(id, blocked, messages::add) {
                    requireNoDataOwnership(app.filesDir, id)
                    var state = states.read(id)
                    if (id in crashSuspects && !state.pendingDelete) {
                        state = state.copy(enabled = false, resumeAfterRollback = false)
                        states.write(id, state)
                    }
                    if (state.pendingDelete) {
                        blocked += id
                        deletePluginRecovery(id, blocked) { deleteStored(id, state.clearData) }
                    } else if (recoverPluginUpdate(states, updates, id, state, skipLoading, messages::add)) {
                        // Recovery restores state before acknowledging its durable completion.
                    } else if (state.initializing) {
                        states.write(id, state.copy(enabled = false, initializing = false, resumeAfterRollback = false))
                        interrupted += id
                    }
                }
            }
            store.installed(messages::add) { rejected += it }.also { accepted ->
                rejected += store.retainedIds() - accepted.map { it.manifest.id }.toSet()
            }
        }
        // IO used local sets; publish quarantine changes only on serialized Main.
        recoveryBlocked.clear()
        recoveryBlocked.addAll(blocked)
        rejectedIds.addAll(rejected)
        failures.addAll(messages)
        installed.forEach { installedPlugin ->
            val id = installedPlugin.manifest.id
            val state = try {
                withContext(Dispatchers.IO) { states.read(id) }
            } catch (error: Exception) {
                blocked += id
                failures += "$id: ${pluginFailureIdentity(error)}"
                PluginState(enabled = false)
            }
            val plugin = ManagedPlugin(installedPlugin.manifest, state)
            plugin.canRollback = isolatePluginRecovery(id, blocked, failures::add) { canRollback(id) } ?: false
            plugin.updateObservation = isolatePluginRecovery(id, blocked, failures::add) {
                withContext(Dispatchers.IO) { updates.observation(id) }
            }
            plugins += plugin
            when {
                id in blocked -> plugin.status(if (state.pendingDelete) "Uninstall pending; restart required" else "State unavailable; restart required")
                id in interrupted -> {
                    plugin.status("Previous initialization interrupted; disabled")
                    failures += "$id: previous initialization interrupted; disabled conservatively (cause unknown)"
                }
                id in crashSuspects -> plugin.status("Crash suspect; disabled until explicitly enabled")
                skipLoading -> plugin.status("Safe mode; loading skipped until restart")
                !state.enabled -> plugin.status("Disabled")
                else -> busy(plugin) { load(plugin, installedPlugin) }
            }
        }
        recoveryBlocked.addAll(blocked)
        restored = true
    }

    /** Main/UI lifetime: signal the bounded source worker before waiting for mutation cleanup. */
    fun closeImport(session: PluginImportSession) {
        if (session.closed) return
        session.closed = true
        session.source?.cancel()
        // An in-flight stage has not transferred ownership yet and will discard itself.
        if (session.code == null && session.data == null) return
        host.launch { operation { discardImportInternal(session) } }
    }

    suspend fun discardImport(session: PluginImportSession) = withContext(Dispatchers.Main.immediate) {
        session.source?.cancel()
        operation { discardImportInternal(session) }
    }

    private suspend fun discardImportInternal(session: PluginImportSession) {
        val code = session.code
        val data = session.data
        // Store tickets already own every path. Even if a session is disposed or replaced,
        // failed deletion remains in the bounded shared registry and blocks new stages.
        try {
            withContext(Dispatchers.IO) {
                withPluginCleanup(cleanup = { code?.let(store::discard) }) {
                    data?.let(dataCatalog::discard)
                }
            }
        } finally {
            session.code = null
            session.data = null
        }
    }

    private fun openImport(uri: Uri): java.io.InputStream {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw PluginPackageException("Invalid file URI")
            if (File(path).canonicalFile.toPath().startsWith(app.dataDir.canonicalFile.toPath())) {
                throw PluginPackageException("Refusing to read app data")
            }
        }
        return try { app.contentResolver.openInputStream(uri) } catch (e: IllegalArgumentException) {
            throw PluginPackageException("Cannot open selected file")
        } ?: throw PluginPackageException("Cannot open selected file")
    }

    private suspend fun <T : Any> importSource(
        uri: Uri, session: PluginImportSession, maxBytes: Long,
        stageLocal: (File) -> T, discard: (T) -> Unit, assign: (T) -> Unit,
    ): T? = withContext(Dispatchers.Main.immediate) {
        if (session.closed) return@withContext null
        dataRequire(!stageCleanup.hasPending, "Import cleanup pending; use Retry import cleanup")
        session.source?.cancel()
        // Fail-fast process-wide admission BEFORE any host job/queue or provider acquisition.
        val copy = pluginImportSources.start(File(app.cacheDir, "plugin-import-sources"), maxBytes,
            { openImport(uri) }, ::logHostFailure)
        session.source = copy
        try {
            stagePluginCopy(copy,
                serialized = { action -> serializedPluginImport(operations, host.coroutineContext) {
                    restoreInternal()
                    if (session.closed || session.source !== copy) null
                    else { discardImportInternal(session); action() }
                } },
                stage = { file -> withContext(Dispatchers.IO) { stageLocal(file) } },
                discard = { value -> withContext(Dispatchers.IO) { discard(value) } },
                publish = { value ->
                    if (session.closed || session.source !== copy) false
                    else { assign(value); true }
                },
            )
        } catch (error: Throwable) {
            // Covers cancellation at the host-to-caller return boundary after publication.
            // A replacement import owns its own token; never discard its stage here.
            copy.cancel()
            transferImportFailure(session, copy, error,
                enqueue = { action -> host.launch { operation { action() } }; Unit },
                discard = { owned -> discardImportInternal(owned) }, report = ::logHostFailure)
            throw error
        } finally {
            if (session.source === copy) session.source = null
        }
    }

    suspend fun retryImportCleanup() = operation { withContext(Dispatchers.IO) { stageCleanup.retry() } }

    suspend fun stage(uri: Uri, session: PluginImportSession): StagedPlugin? = importSource(
        uri, session, PACKAGE_MAX_BYTES,
        stageLocal = { file -> stagePluginInput(
            open = { file.inputStream() }, stage = store::stage,
            discard = store::discard,
            validate = { dataRequire(it.manifest.apiLevel in 1..THETA_API_LEVEL, "Unsupported API level") },
        ) },
        discard = store::discard,
        assign = { session.code = it },
    )

    suspend fun stageData(uri: Uri, session: PluginImportSession): StagedPluginData? {
        operation {
            restoreInternal()
            dataRequire(!dataRecoveryBlocked, "Data recovery mode; resume on next cold start before importing")
        }
        return importSource(
            uri, session, PluginDataLimits().compressedBytes.toLong(),
            stageLocal = { file -> stagePluginInput(
                open = { file.inputStream() }, stage = dataCatalog::stage, discard = dataCatalog::discard,
            ) },
            discard = dataCatalog::discard,
            assign = { session.data = it },
        )
    }

    suspend fun installData(session: PluginImportSession, staged: StagedPluginData, digest: String, acknowledged: Boolean) = operation {
        restoreInternal()
        dataRequire(!dataRecoveryBlocked, "Data recovery mode; importing unavailable")
        dataRequire(!session.closed && session.source == null && session.data === staged, "Unknown data confirmation")
        session.data = null // ownership transfers to install, disposal cannot delete it.
        val selected = withContext(Dispatchers.IO) { dataCatalog.install(staged, digest, acknowledged) }
        if (stageCleanup.hasPending) failures += "Data installed; import cleanup pending; use Retry import cleanup"
        val record = if (selected.manifest.id in crashSuspects) withContext(Dispatchers.IO) {
            dataCatalog.managementRecord(selected.manifest.id, crashQuarantined = true)
        } else PluginDataRecord(selected.manifest.id, selected.state, selected)
        replaceDataInventory(dataInventory, dataPlugins, record, skipDataLoading)
    }

    suspend fun setDataEnabled(id: String, enabled: Boolean) = operation {
        restoreInternal()
        dataRequire(!dataRecoveryBlocked, "Data recovery mode; data selection unavailable")
        val previous = dataPlugins.firstOrNull { it.selection.manifest.id == id }?.selection
        withContext(Dispatchers.IO) { dataCatalog.setEnabled(id, enabled, previous) }
        // Durable disable precedes revocation; management survives invalid or missing bytes.
        dataPlugins.removeAll { it.selection.manifest.id == id }
        var acknowledgementFailed = false
        if (enabled && id in crashSuspects) {
            val acknowledged = withContext(Dispatchers.IO) { crashRecovery?.acknowledgeSuspect(id) == true }
            if (acknowledged) crashSuspects.remove(id) else acknowledgementFailed = true
        }
        val record = withContext(Dispatchers.IO) { dataCatalog.managementRecord(id, id in crashSuspects) }
        replaceDataInventory(dataInventory, dataPlugins, record, skipDataLoading)
        if (acknowledgementFailed) throw IOException("Cannot acknowledge data crash quarantine; page remains blocked")
    }

    suspend fun uninstallData(id: String) = operation {
        withContext(Dispatchers.IO) { dataCatalog.beginDelete(id) }
        dataPlugins.removeAll { it.selection.manifest.id == id }
        val pending = withContext(Dispatchers.IO) { dataCatalog.managementRecord(id) }
        replaceDataInventory(dataInventory, dataPlugins, pending, skipDataLoading)
        try {
            withContext(Dispatchers.IO) { dataCatalog.finishDelete(id) }
            dataInventory.removeAll { it.id == id }
        } catch (e: Exception) {
            failures += "$id: data uninstall pending; retry uninstall: ${e.message}"
            throw e
        }
    }

    fun isDataPageActive(page: ManagedPluginData): Boolean = isManagedDataPageActive(dataPlugins, page)

    suspend fun readDataPage(page: ManagedPluginData, path: String?): PluginDataContent = operation {
        dataRequire(isDataPageActive(page), "Data page revoked")
        try { withContext(Dispatchers.IO) { dataCatalog.content(page.selection, path) } }
        catch (e: Exception) {
            quarantineDataPage(dataInventory, dataPlugins, page, e.message ?: "Invalid data content")
            failures += "${page.selection.manifest.id}: data page isolated: ${e.message}"
            throw e
        }
    }

    suspend fun install(staged: StagedPlugin, session: PluginImportSession, allowCodeOnlyRollback: Boolean = false) = operation {
        restoreInternal()
        dataRequire(!session.closed && session.source == null && session.code === staged, "Unknown code confirmation")
        session.code = null
        val id = staged.manifest.id
        val old = plugins.firstOrNull { it.manifest.id == id }
        old?.busy(true)
        var published = false
        var updateRecoveryUncertain = false
        withPluginInstallRecovery(
            id = id,
            isManaged = { plugins.any { it.manifest.id == id } },
            retainedIds = { withContext(Dispatchers.IO) { store.retainedIds() } },
            rejectedIds = rejectedIds,
            cleanup = {
                withPluginCleanup(cleanup = { if (!published) withContext(Dispatchers.IO) { store.discard(staged) } }) {
                    old?.busy(false)
                    if (updateRecoveryUncertain) {
                        recoveryBlocked += id
                        deferred += id
                        old?.status("Update publication uncertain; restart required")
                        failures += "$id: update publication requires cold-start recovery"
                    }
                }
            },
        ) {
            val (installed, state) = withContext(Dispatchers.IO) {
                requireNoDataOwnership(app.filesDir, id)
                val state = states.read(id)
                if (state.pendingDelete) throw PluginPackageException("Uninstall pending; restart required")
                if (id in deferred) throw PluginPackageException("Update or rollback pending; restart required")
                if (updates.read(id)?.phase in setOf(UpdatePhase.ROLLBACK, UpdatePhase.RECOVERED)) {
                    throw PluginPackageException("Update or rollback pending; restart required")
                }
                if (staged.manifest.apiLevel !in 1..THETA_API_LEVEL) throw PluginPackageException("Unsupported API level")
                // Upgrades preserve desired state; first install defaults to enabled.
                states.write(id, state)
                val from = store.versions(id).lastOrNull()
                val intent = from?.let {
                    updates.prepareInstall(id, it, staged.manifest.versionCode, allowCodeOnlyRollback)
                }
                val installed = try {
                    store.install(staged).also {
                        published = true
                        intent?.let(updates::commitInstall)
                    }
                } catch (error: Exception) {
                    // A failed publication may already own a package or journal. Do not
                    // accept a second upgrade or reload this ID before cold recovery.
                    updateRecoveryUncertain = intent != null
                    try { intent?.let(updates::cancelInstall) }
                    catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                    throw error
                }
                installed to state
            }
            if (stageCleanup.hasPending) failures += "$id installed; import cleanup pending; use Retry import cleanup"
            rejectedIds.remove(id)
            val update = withContext(Dispatchers.IO) { store.versions(id).size > 1 }
            if (update) deferred += id
            val plugin = ManagedPlugin(installed.manifest, state)
            plugin.canRollback = canRollback(id)
            plugin.updateObservation = withContext(Dispatchers.IO) { updates.observation(id) }
            // State/update records and the replacement package have all validated durably.
            // This does not reset gate.wasAttempted or an accepted deferred transition.
            recoveryBlocked.remove(id)
            plugin.runtime = old?.runtime
            if (old == null) plugins += plugin else plugins[plugins.indexOf(old)] = plugin
            busy(plugin) {
                when {
                    update || gate.wasAttempted(id) -> plugin.status("Update installed; restart required")
                    skipLoading -> plugin.status("Safe mode; loading skipped until restart")
                    !state.enabled -> plugin.status("Disabled")
                    else -> load(plugin, installed)
                }
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = operation {
        restoreInternal()
        val plugin = managed(id)
        busy(plugin) {
            val state = withContext(Dispatchers.IO) {
                requireNoDataOwnership(app.filesDir, id)
                val previous = states.read(id)
                if (previous.pendingDelete) throw PluginPackageException("Uninstall pending; restart required")
                if (enabled && id in recoveryBlocked) throw PluginPackageException("State unavailable; restart required")
                previous.copy(enabled = enabled, resumeAfterRollback = false).also { states.write(id, it) }
            }
            plugin.state(state) // Durable BEFORE callbacks/cancellation, including acknowledgement failure.
            if (enabled && id in crashSuspects) {
                val acknowledged = withContext(Dispatchers.IO) { crashRecovery?.acknowledgeSuspect(id) == true }
                if (!acknowledged) {
                    plugin.status("Crash quarantine acknowledgement failed; loading blocked until restart")
                    throw IOException("Cannot acknowledge crash quarantine; restart required")
                }
                crashSuspects.remove(id)
            }
            if (!enabled) {
                close(plugin)
                plugin.status(when {
                    plugin.runtime?.closeFailed == true -> "Disabled; cleanup failed; restart required"
                    gate.wasAttempted(id) -> "Disabled; restart required before loading again"
                    else -> "Disabled"
                })
            } else when {
                skipLoading -> plugin.status("Safe mode; loading skipped until restart")
                plugin.runtime?.active == true -> Unit
                id in deferred || gate.wasAttempted(id) -> plugin.status("Enabled for next start; restart required")
                else -> {
                    val apk = withContext(Dispatchers.IO) {
                        pluginPath(pluginPath(app.filesDir.canonicalFile, "plugins"), "$id/${plugin.manifest.versionCode}/plugin.apk")
                    }
                    load(plugin, InstalledPlugin(apk, plugin.manifest))
                }
            }
        }
    }

    suspend fun uninstall(id: String, clearData: Boolean) = operation {
        restoreInternal()
        val plugin = managed(id)
        busy(plugin) {
            val state = withContext(Dispatchers.IO) {
                requireNoDataOwnership(app.filesDir, id)
                persistPluginUninstall(states, id, clearData)
            }
            plugin.state(state)
            plugin.status("Uninstall pending; restart required")
            close(plugin)
            if (!gate.wasAttempted(id)) {
                deletePluginRecovery(id, recoveryBlocked) {
                    withContext(Dispatchers.IO) { deleteStored(id, state.clearData) }
                }
                deferred -= id // Nothing is left to switch to, so a reinstall need not wait.
                plugins.remove(plugin)
            }
        }
    }

    /** Explicit clear-data recovery for packages excluded by signature/trust checks. */
    suspend fun uninstallRejected(id: String) = operation {
        restoreInternal()
        checkId(id)
        if (id !in rejectedIds || plugins.any { it.manifest.id == id } || gate.wasAttempted(id)) {
            throw PluginPackageException("Rejected package cannot be removed in this process")
        }
        deletePluginRecovery(id, recoveryBlocked) {
            withContext(Dispatchers.IO) {
                requireNoDataOwnership(app.filesDir, id)
                // Do not need to parse possibly corrupt old state to authorize deletion.
                states.write(id, PluginState(enabled = false, pendingDelete = true, clearData = true))
                deleteStored(id, clearData = true)
            }
        }
        deferred -= id
        rejectedIds.remove(id)
    }

    /** 观察期内手动回滚。数据要等冷启动才恢复，这里只保存决定并撤销当前实例。 */
    suspend fun rollback(id: String) = operation {
        restoreInternal()
        val plugin = managed(id)
        busy(plugin) {
            requestPluginRollback(
                request = {
                    withContext(Dispatchers.IO) {
                        if (states.read(id).pendingDelete) throw PluginPackageException("Uninstall pending; restart required")
                        updates.requestRollback(id)
                    }
                },
                refreshRejected = {
                    plugin.canRollback = false
                    plugin.canRollback = canRollback(id)
                },
                accepted = {
                    deferred += id
                    plugin.canRollback = false
                    plugin.updateObservation = null
                    plugin.status("Rollback scheduled; restart required")
                },
                close = { close(plugin) },
            )
        }
    }

    /** Admission is serialized, execution and waiting never hold the manager mutation gate. */
    suspend fun invokeTool(id: String, tool: Tool, args: JsonObject): PluginInvocation = operation {
        val plugin = managed(id)
        val runtime = plugin.runtime ?: throw PluginPackageException("Plugin is not running")
        if (!runtime.active || runtime.context.faults.hasFault() || plugin.tools.none { it === tool }) {
            throw PluginPackageException("Tool registration is no longer active")
        }
        runtime.context.startTool(tool, args)
    }

    private fun deleteStored(id: String, clearData: Boolean) {
        store.delete(id, clearData)
        updates.forget(id)
        states.remove(id)
    }

    private suspend fun canRollback(id: String): Boolean =
        withContext(Dispatchers.IO) { updates.canRollback(id) }

    private fun managed(id: String): ManagedPlugin {
        checkId(id)
        return plugins.firstOrNull { it.manifest.id == id } ?: throw PluginPackageException("Plugin is not installed")
    }

    private suspend fun busy(plugin: ManagedPlugin, action: suspend () -> Unit) {
        plugin.busy(true)
        try { action() } finally { plugin.busy(false) }
    }

    private suspend fun load(plugin: ManagedPlugin, installed: InstalledPlugin) {
        val manifest = installed.manifest
        if (skipLoading || manifest.id in crashSuspects || gate.wasAttempted(manifest.id)) return
        var phase = "prepare storage"
        try {
            withContext(Dispatchers.IO) {
                requireNoDataOwnership(app.filesDir, manifest.id)
                if (manifest.apiLevel !in 1..THETA_API_LEVEL) throw PluginPackageException("Unsupported API level")
                val data = pluginPath(pluginPath(app.filesDir.canonicalFile, "plugin-data"), manifest.id)
                Files.createDirectories(data.toPath())
                states.write(manifest.id, states.read(manifest.id).copy(initializing = true))
            }
            gate.begin(manifest.id)
            phase = "create settings owner"
            val dataDir = withContext(Dispatchers.IO) {
                // Android's trusted filesDir may start at /data/user/0 -> /data/data.
                // Resolve only that platform root; still reject links in plugin-owned descendants.
                pluginPath(pluginPath(app.filesDir.canonicalFile, "plugin-data"), manifest.id)
            }
            val settings = withContext(Dispatchers.IO) { PluginSettingsStorage.android(manifest.id, dataDir) }
            val context = HostPluginContext(
                manifest.id, app, dataDir, host, executionBudget, settings,
            ) { faultContext ->
                // Enqueue only: load/restore already holds operations. Never wait here.
                postPluginOperation(host, Dispatchers.Main, operations, ::logHostFailure) {
                    val current = plugins.firstOrNull { it.manifest.id == manifest.id }
                    val runtime = current?.runtime
                    if (current != null && runtime != null && runtime.context === faultContext) handleFault(current, runtime)
                }
            }
            val runtime = PluginRuntime(context, manifest.versionCode)
            plugin.runtime = runtime
            plugin.status("Initializing")
            phase = "verify and enable callback"
            val worker = PluginWorker("theta-enable-${manifest.id}") {
                // Covers first install, restore and re-enable, immediately before dex loading.
                store.verifyForLoad(installed)
                val loader = createPluginClassLoader(installed.apk, app.classLoader)
                runtime.loader = loader
                val entry = loader.loadClass(manifest.entry).getDeclaredConstructor().newInstance() as ThetaPlugin
                runtime.entry = entry
                entry.onEnable(runtime.context)
            }
            runtime.initialization = worker
            val result = worker.await(ENABLE_TIMEOUT_MS, context.faults)
            if (result == null) throw PluginPackageException("Initialization timed out; restart required")
            result.getOrThrow()
            phase = "persist successful start"
            val published = context.faults.completeStart(
                persist = {
                    withContext(Dispatchers.IO) {
                        updates.started(manifest.id, manifest.versionCode)
                        states.write(manifest.id, states.read(manifest.id).copy(initializing = false))
                    }
                },
                publish = {
                    runtime.active = true
                    context.activateExtensions()
                    plugin.status("Running")
                },
            )
            if (!published) handleFault(plugin, runtime)
            else try {
                plugin.updateObservation = withContext(Dispatchers.IO) { updates.observation(manifest.id) }
            } catch (error: Exception) {
                reportPluginFailure(failures::add, "${manifest.id}: observation notice unavailable", error)
            }
        } catch (error: Throwable) {
            val runtime = plugin.runtime
            if (runtime?.context?.faults?.hasFault() == true) {
                handleFault(plugin, runtime)
                return
            }
            reportPluginFailure(failures::add, "${manifest.name}: initialization failed ($phase)", error)
            close(plugin)
            // 写不进去也没关系：initializing 标记还在，下次启动同样会回滚。
            val rollback = try {
                withContext(Dispatchers.IO) { updates.failed(manifest.id, manifest.versionCode) }
            } catch (_: IOException) {
                false
            }
            if (rollback) plugin.canRollback = false
            plugin.status(if (rollback) "Update failed; rollback on next start" else "Initialization failed; restart required")
            // Keep the marker after timeout/failure: next start disables conservatively.
        }
    }

    private suspend fun handleFault(plugin: ManagedPlugin, runtime: PluginRuntime) {
        val fault = runtime.context.faults.takeFault(plugin.runtime?.context?.faults) ?: return
        // A user operation closed this instance first. Its durable decision and status stand.
        if (runtime.closing != null) {
            reportPluginFailure(failures::add, "${plugin.manifest.name}: managed scope failed", fault.error)
            return
        }
        runtime.active = false
        // Durable intent BEFORE scope cancellation and plugin callbacks. Persistence
        // failure still closes the runtime; it must never fall through to Running.
        val persisted = finishPluginFault(
            persist = {
                try {
                    withContext(Dispatchers.IO) {
                        persistPluginFault(states, updates, plugin.manifest.id, runtime.version, fault.duringInitialization)
                    }
                } catch (error: Throwable) {
                    PluginFaultPersistence(PluginState(enabled = false), false, listOf(error))
                }
            },
            report = { result ->
                plugin.state(result.state)
                reportPluginFailure(failures::add, "${plugin.manifest.name}: managed scope failed", fault.error)
                result.errors.forEach {
                    reportPluginFailure(failures::add, "${plugin.manifest.name}: fault persistence failed", it)
                }
            },
            close = { close(plugin) },
        )
        plugin.canRollback = try { canRollback(plugin.manifest.id) } catch (error: Throwable) {
            reportPluginFailure(failures::add, "${plugin.manifest.name}: update state unavailable", error)
            false
        }
        plugin.status(when {
            persisted.errors.isNotEmpty() -> "Managed scope failed; disabled in process; persistence failed; restart required"
            persisted.rollback -> "Update failed; rollback on next start"
            runtime.closeFailed -> "Managed scope failed; disabled; cleanup failed; restart required"
            else -> "Managed scope failed; disabled; restart required"
        })
    }

    private suspend fun close(plugin: ManagedPlugin) {
        val runtime = plugin.runtime ?: return
        runtime.active = false
        if (runtime.closing != null) return
        val actions = runtime.context.beginClose() // Admission closes BEFORE cancellation.
        val worker = pluginCleanupWorker(
            "theta-close-${plugin.manifest.id}",
            cancel = {
                try { runtime.context.scope.cancel() } finally { runtime.initialization?.cancel() }
            },
            onDisable = {
                // Do not race onDisable with onEnable; a late enable sees a closed registry.
                runtime.initialization?.join()
                runtime.context.drainSettings()
                runtime.entry?.onDisable()
            },
            actions = actions,
        )
        runtime.closing = worker
        val result = worker.await(CLOSE_TIMEOUT_MS)
        val errors = result?.getOrNull()
        runtime.closeFailed = result == null || result.isFailure || !errors.isNullOrEmpty()
        if (runtime.closeFailed) {
            failures += "${plugin.manifest.name}: cleanup failed or timed out; restart required"
            errors.orEmpty().forEach { reportPluginFailure(failures::add, "${plugin.manifest.name}: cleanup", it) }
        }
    }
}

/** Logging must itself be nonthrowing, including JVM tests with Android stubs. */
private fun logHostFailure(error: Throwable) {
    try { Log.e("ThetaPlugins", "Host plugin task failed: ${pluginFailureIdentity(error)}") } catch (_: Throwable) { }
}

internal class HostPluginContext(
    override val pluginId: String,
    override val appContext: Context,
    override val dataDir: File,
    private val host: CoroutineScope,
    private val executionBudget: PluginExecutionBudget,
    private val settings: PluginSettingsStorage,
    onFault: (HostPluginContext) -> Unit,
) : PluginContext {
    override val hostApiLevel = THETA_API_LEVEL
    override val prefs: PluginPrefs get() = settings.prefs
    override val secrets: PluginSecrets get() = settings.secrets
    private var extensionsActive = false
    private val entryAttempts = IdentityHashMap<EntryAdapter, PluginEntryRuntime>()
    private val calls = IdentityHashMap<PluginInvocation, Tool>()
    private val toolFailures = PluginToolFailureWindow()
    private val countedCalls = java.util.Collections.newSetFromMap(IdentityHashMap<PluginInvocation, Boolean>())
    var entryCapacityExceeded by mutableStateOf(false)
        private set
    var entryRuntimes by mutableStateOf<List<PluginEntryRuntime>>(emptyList())
        private set
    // Extension identity, not plugin-defined equals(), determines registration ownership.
    var extensions by pluginExtensionState()
        private set

    private val publication: PluginPublication<Extension> = PluginPublication<Extension> {
        // Always enqueue, even on Main: never acquire the registry lock in reverse
        // order from the fault boundary. Read latest when dispatched, not captured data.
        try {
            host.launch(Dispatchers.Main) {
                extensions = publicationSnapshot()
                synchronizeExtensions()
            }
        } catch (error: Throwable) { logHostFailure(error) }
    }
    private fun publicationSnapshot(): List<Extension> = publication.current()
    private val registrations = PluginRegistrations<Extension>(publication::update)
    private val closeLock = Any()
    private var pendingCleanup: List<() -> Unit>? = null
    val faults = PluginFaultBoundary(::closeAdmission, { onFault(this) }, ::logHostFailure)
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + faults.handler)

    fun activateExtensions() {
        extensionsActive = true
        extensions = publicationSnapshot()
        synchronizeExtensions()
    }

    private fun synchronizeExtensions() {
        val current = publicationSnapshot()
        entryAttempts.forEach { (adapter, controller) ->
            if (!extensionsActive || current.none { it === adapter }) controller.close()
        }
        calls.forEach { (call, tool) ->
            if (!extensionsActive || current.none { it === tool }) call.cancel()
        }
        if (extensionsActive && !faults.hasFault()) {
            current.filterIsInstance<EntryAdapter>().forEach { adapter ->
                if (!entryAttempts.containsKey(adapter) && entryAttempts.size < 32) {
                    val runtime = PluginEntryRuntime(executionBudget, pluginId, scope, adapter,
                        DeviceInfo(android.os.Build.VERSION.SDK_INT, android.os.Build.MANUFACTURER, android.os.Build.MODEL))
                    entryAttempts[adapter] = runtime
                    runtime.start()
                }
            }
        }
        entryCapacityExceeded = extensionsActive && current.filterIsInstance<EntryAdapter>().any {
            !entryAttempts.containsKey(it)
        }
        entryRuntimes = entryAttempts.entries.filter { (adapter, _) ->
            extensionsActive && current.any { it === adapter }
        }.map { it.value }
    }

    suspend fun startTool(tool: Tool, args: JsonObject): PluginInvocation {
        if (!extensionsActive || faults.hasFault() || publicationSnapshot().none { it === tool }) {
            throw PluginPackageException("Tool registration is no longer active")
        }
        // Await only already-completed host terminal signals; no plugin work or waiting
        // occurs under the manager operation mutex. Count before the next admission.
        calls.forEach { (call, ownerTool) ->
            if (call.terminal.isCompleted && countedCalls.add(call)) {
                toolFailures.record(ownerTool, call.terminal.await().status)
            }
        }
        val retired = calls.keys.filter { it.actualExited.isCompleted && it in countedCalls }
        retired.forEach { calls.remove(it); countedCalls.remove(it) }
        if (toolFailures.isQuarantined(tool)) {
            throw PluginPackageException("Tool quarantined after repeated failures or failure-tracking capacity exhaustion; restart required")
        }
        if (calls.size >= 8) throw PluginPackageException("Tool execution capacity unavailable")
        val invocation = PluginInvocation(executionBudget, pluginId, scope, tool, args,
            ToolInvocationRequest(runId = "s1-direct", sessionId = "s1-direct"))
        calls[invocation] = tool
        invocation.start()
        host.launch(Dispatchers.Main.immediate) {
            val outcome = invocation.terminal.await()
            if (calls.containsKey(invocation) && countedCalls.add(invocation)) {
                toolFailures.record(tool, outcome.status)
            }
        }
        return invocation
    }

    fun drainSettings() { settings.close() }

    override fun register(extension: Extension): Registration {
        val revoke = registrations.register(extension.id, extension)
        return Registration { revoke() }
    }

    override fun onCleanup(action: () -> Unit): Registration {
        val revoke = registrations.onCleanup(action)
        return Registration { revoke() }
    }

    // Memory only. The exception handler never cancels jobs or invokes cleanup.
    private fun closeAdmission() {
        settings.closeAdmission()
        synchronized(closeLock) {
            registrations.beginClose()?.let { pendingCleanup = it }
        }
    }

    fun beginClose(): List<() -> Unit> {
        faults.close()
        closeAdmission()
        extensionsActive = false
        synchronizeExtensions()
        extensions = emptyList() // Caller is the serialized Main host operation.
        return synchronized(closeLock) {
            pendingCleanup.orEmpty().also { pendingCleanup = null }
        }
    }
}
