package io.github.bytejuno.thetacore.plugin

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bytejuno.thetacore.sdk.Extension
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
    internal var runtime by mutableStateOf<PluginRuntime?>(null)
    val pages: List<SettingsPage>
        get() = runtime?.takeIf { it.active && !it.context.faults.hasFault() }
            ?.context?.extensions?.filterIsInstance<SettingsPage>().orEmpty()

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
    private val store = PluginStore(app.filesDir, app.cacheDir, PlatformPluginSignatureVerifier(app.packageManager))
    private val states = PluginStateStore(app.filesDir)
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

    val plugins = mutableStateListOf<ManagedPlugin>()
    val failures = mutableStateListOf<String>()
    // Rejected packages remain removable without executing or trusting their manifest.
    val rejectedIds = mutableStateListOf<String>()

    // Replaces the UI Job: caller cancellation cannot interrupt durable transitions.
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
        skipLoading = skipPlugins
        val blocked = recoveryBlocked.toMutableSet()
        val interrupted = mutableSetOf<String>()
        val messages = mutableListOf<String>()
        val rejected = mutableSetOf<String>()
        val installed = withContext(Dispatchers.IO) {
            // Tombstones precede package parsing, including corrupt APKs.
            states.ids().forEach { id ->
                isolatePluginRecovery(id, blocked, messages::add) {
                    val state = states.read(id)
                    if (state.pendingDelete) {
                        blocked += id
                        deletePluginRecovery(id, blocked) { deleteStored(id, state.clearData) }
                    } else if (recoverPluginUpdate(states, updates, id, state, skipPlugins, messages::add)) {
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
            plugins += plugin
            when {
                id in blocked -> plugin.status(if (state.pendingDelete) "Uninstall pending; restart required" else "State unavailable; restart required")
                id in interrupted -> {
                    plugin.status("Previous initialization interrupted; disabled")
                    failures += "$id: previous initialization interrupted; disabled conservatively (cause unknown)"
                }
                skipLoading -> plugin.status("Safe mode; loading skipped until restart")
                !state.enabled -> plugin.status("Disabled")
                else -> busy(plugin) { load(plugin, installedPlugin) }
            }
        }
        recoveryBlocked.addAll(blocked)
        restored = true
    }

    suspend fun stage(uri: Uri): StagedPlugin = operation {
        withContext(Dispatchers.IO) {
            if (uri.scheme == "file") {
                val path = uri.path ?: throw PluginPackageException("Invalid file URI")
                if (File(path).canonicalFile.toPath().startsWith(app.dataDir.canonicalFile.toPath())) {
                    throw PluginPackageException("Refusing to read app data")
                }
            }
            val input = try { app.contentResolver.openInputStream(uri) } catch (error: IllegalArgumentException) {
                throw PluginPackageException("Cannot open the file: ${pluginFailureIdentity(error)}")
            } ?: throw PluginPackageException("Cannot open the file")
            val staged = input.use(store::stage)
            if (staged.manifest.apiLevel !in 1..THETA_API_LEVEL) {
                staged.file.delete()
                throw PluginPackageException("Unsupported API level ${staged.manifest.apiLevel}")
            }
            staged
        }
    }

    suspend fun install(staged: StagedPlugin) = operation {
        restoreInternal()
        val id = staged.manifest.id
        val old = plugins.firstOrNull { it.manifest.id == id }
        old?.busy(true)
        withPluginInstallRecovery(
            id = id,
            isManaged = { plugins.any { it.manifest.id == id } },
            retainedIds = { withContext(Dispatchers.IO) { store.retainedIds() } },
            rejectedIds = rejectedIds,
            cleanup = {
                withPluginCleanup(cleanup = { withContext(Dispatchers.IO) { staged.file.delete() } }) {
                    old?.busy(false)
                }
            },
        ) {
            val (installed, state) = withContext(Dispatchers.IO) {
                val state = states.read(id)
                if (state.pendingDelete) throw PluginPackageException("Uninstall pending; restart required")
                if (id in deferred) throw PluginPackageException("Update or rollback pending; restart required")
                if (updates.read(id)?.phase in setOf(UpdatePhase.ROLLBACK, UpdatePhase.RECOVERED)) {
                    throw PluginPackageException("Update or rollback pending; restart required")
                }
                if (staged.manifest.apiLevel !in 1..THETA_API_LEVEL) throw PluginPackageException("Unsupported API level")
                // Upgrades preserve desired state; first install defaults to enabled.
                states.write(id, state)
                store.install(staged) to state
            }
            rejectedIds.remove(id)
            val update = withContext(Dispatchers.IO) { store.versions(id).size > 1 }
            if (update) deferred += id
            val plugin = ManagedPlugin(installed.manifest, state)
            plugin.canRollback = canRollback(id)
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
                val previous = states.read(id)
                if (previous.pendingDelete) throw PluginPackageException("Uninstall pending; restart required")
                if (enabled && id in recoveryBlocked) throw PluginPackageException("State unavailable; restart required")
                previous.copy(enabled = enabled, resumeAfterRollback = false).also { states.write(id, it) }
            }
            plugin.state(state) // Durable BEFORE callbacks/cancellation.
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
            val state = withContext(Dispatchers.IO) { persistPluginUninstall(states, id, clearData) }
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
                    plugin.status("Rollback scheduled; restart required")
                },
                close = { close(plugin) },
            )
        }
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
        if (skipLoading || gate.wasAttempted(manifest.id)) return
        try {
            withContext(Dispatchers.IO) {
                if (manifest.apiLevel !in 1..THETA_API_LEVEL) throw PluginPackageException("Unsupported API level")
                val data = pluginPath(pluginPath(app.filesDir.canonicalFile, "plugin-data"), manifest.id)
                Files.createDirectories(data.toPath())
                states.write(manifest.id, states.read(manifest.id).copy(initializing = true))
            }
            gate.begin(manifest.id)
            val context = HostPluginContext(
                manifest.id, app, File(app.filesDir, "plugin-data/${manifest.id}"), host,
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
            val published = context.faults.completeStart(
                persist = {
                    withContext(Dispatchers.IO) {
                        updates.started(manifest.id, manifest.versionCode)
                        states.write(manifest.id, states.read(manifest.id).copy(initializing = false))
                    }
                },
                publish = {
                    runtime.active = true
                    plugin.status("Running")
                },
            )
            if (!published) handleFault(plugin, runtime)
        } catch (error: Throwable) {
            val runtime = plugin.runtime
            if (runtime?.context?.faults?.hasFault() == true) {
                handleFault(plugin, runtime)
                return
            }
            reportPluginFailure(failures::add, "${manifest.name}: initialization failed", error)
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
    onFault: (HostPluginContext) -> Unit,
) : PluginContext {
    override val hostApiLevel = THETA_API_LEVEL
    var extensions by mutableStateOf<List<Extension>>(emptyList())
        private set

    private val publication: PluginPublication<Extension> = PluginPublication<Extension> {
        // Always enqueue, even on Main: never acquire the registry lock in reverse
        // order from the fault boundary. Read latest when dispatched, not captured data.
        try {
            host.launch(Dispatchers.Main) { extensions = publicationSnapshot() }
        } catch (error: Throwable) { logHostFailure(error) }
    }
    private fun publicationSnapshot(): List<Extension> = publication.current()
    private val registrations = PluginRegistrations<Extension>(publication::update)
    private val closeLock = Any()
    private var pendingCleanup: List<() -> Unit>? = null
    val faults = PluginFaultBoundary(::closeAdmission, { onFault(this) }, ::logHostFailure)
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + faults.handler)

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
        synchronized(closeLock) {
            registrations.beginClose()?.let { pendingCleanup = it }
        }
    }

    fun beginClose(): List<() -> Unit> {
        faults.close()
        closeAdmission()
        extensions = emptyList() // Caller is the serialized Main host operation.
        return synchronized(closeLock) {
            pendingCleanup.orEmpty().also { pendingCleanup = null }
        }
    }
}
