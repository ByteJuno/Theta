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
import kotlinx.coroutines.CoroutineScope
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
    internal var runtime by mutableStateOf<PluginRuntime?>(null)
    val pages: List<SettingsPage>
        get() = runtime?.takeIf { it.active }?.context?.extensions?.filterIsInstance<SettingsPage>().orEmpty()

    internal fun state(state: PluginState) {
        enabled = state.enabled
        pendingDelete = state.pendingDelete
    }
    internal fun status(value: String) { status = value }
    internal fun busy(value: Boolean) { busy = value }
}

/** Retained even after failure/disable: dex cannot be unloaded safely in this process. */
internal class PluginRuntime(val context: HostPluginContext) {
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
    private val host = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val gate = PluginLoadGate()
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
        val blocked = mutableSetOf<String>()
        val interrupted = mutableSetOf<String>()
        val messages = mutableListOf<String>()
        val rejected = mutableSetOf<String>()
        val installed = withContext(Dispatchers.IO) {
            // Tombstones precede package parsing, including corrupt APKs.
            states.ids().forEach { id ->
                try {
                    val state = states.read(id)
                    if (state.pendingDelete) {
                        blocked += id
                        store.delete(id, state.clearData)
                        states.remove(id)
                    } else if (state.initializing) {
                        states.write(id, state.copy(enabled = false, initializing = false))
                        interrupted += id
                    }
                } catch (error: Exception) {
                    blocked += id
                    messages += "$id: state recovery failed: ${error.message}"
                }
            }
            store.installed(messages::add) { rejected += it }.also { accepted ->
                rejected += store.retainedIds() - accepted.map { it.manifest.id }.toSet()
            }
        }
        rejectedIds.addAll(rejected)
        failures.addAll(messages)
        installed.forEach { installedPlugin ->
            val id = installedPlugin.manifest.id
            val state = try {
                withContext(Dispatchers.IO) { states.read(id) }
            } catch (error: Exception) {
                blocked += id
                failures += "$id: ${error.message}"
                PluginState(enabled = false)
            }
            val plugin = ManagedPlugin(installedPlugin.manifest, state)
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
                throw PluginPackageException("Cannot open the file: ${error.message}")
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
                if (staged.manifest.apiLevel !in 1..THETA_API_LEVEL) throw PluginPackageException("Unsupported API level")
                // Upgrades preserve desired state; first install defaults to enabled.
                states.write(id, state)
                store.install(staged) to state
            }
            rejectedIds.remove(id)
            val plugin = ManagedPlugin(installed.manifest, state)
            plugin.runtime = old?.runtime
            if (old == null) plugins += plugin else plugins[plugins.indexOf(old)] = plugin
            busy(plugin) {
                when {
                    gate.wasAttempted(id) -> plugin.status("Update installed; restart required")
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
                previous.copy(enabled = enabled).also { states.write(id, it) }
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
                gate.wasAttempted(id) -> plugin.status("Enabled for next start; restart required")
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
                val previous = states.read(id)
                previous.copy(enabled = false, pendingDelete = true, clearData = previous.clearData || clearData)
                    .also { states.write(id, it) }
            }
            plugin.state(state)
            plugin.status("Uninstall pending; restart required")
            close(plugin)
            if (!gate.wasAttempted(id)) {
                withContext(Dispatchers.IO) { store.delete(id, state.clearData); states.remove(id) }
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
        withContext(Dispatchers.IO) {
            // Do not need to parse possibly corrupt old state to authorize deletion.
            states.write(id, PluginState(enabled = false, pendingDelete = true, clearData = true))
            store.delete(id, clearData = true)
            states.remove(id)
        }
        rejectedIds.remove(id)
    }

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
            val runtime = PluginRuntime(HostPluginContext(manifest.id, app, File(app.filesDir, "plugin-data/${manifest.id}")))
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
            val result = worker.await(ENABLE_TIMEOUT_MS)
            if (result == null) throw PluginPackageException("Initialization timed out; restart required")
            result.getOrThrow()
            withContext(Dispatchers.IO) {
                states.write(manifest.id, states.read(manifest.id).copy(initializing = false))
            }
            runtime.active = true
            plugin.status("Running")
        } catch (error: Throwable) {
            failures += "${manifest.name}: initialization failed: $error"
            close(plugin)
            plugin.status("Initialization failed; restart required")
            // Keep the marker after timeout/failure: next start disables conservatively.
        }
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
            errors.orEmpty().forEach { failures += "${plugin.manifest.name}: cleanup: $it" }
        }
    }
}

internal class HostPluginContext(
    override val pluginId: String,
    override val appContext: Context,
    override val dataDir: File,
) : PluginContext {
    override val hostApiLevel = THETA_API_LEVEL
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var extensions by mutableStateOf<List<Extension>>(emptyList())
        private set

    private val registrations = PluginRegistrations<Extension> { extensions = it }

    override fun register(extension: Extension): Registration {
        val revoke = registrations.register(extension.id, extension)
        return Registration { revoke() }
    }

    override fun onCleanup(action: () -> Unit): Registration {
        val revoke = registrations.onCleanup(action)
        return Registration { revoke() }
    }

    fun beginClose(): List<() -> Unit> {
        val actions = registrations.beginClose() ?: return emptyList()
        return actions
    }
}
