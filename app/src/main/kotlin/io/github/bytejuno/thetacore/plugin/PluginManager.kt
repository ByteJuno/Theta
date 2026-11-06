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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread
import kotlin.coroutines.resume

internal class LoadedPlugin(val manifest: PluginManifest, private val context: HostPluginContext) {
    val pages: List<SettingsPage> get() = context.extensions.filterIsInstance<SettingsPage>()
}

internal class PluginManager(private val app: Context) {
    private val store = PluginStore(app.filesDir, app.cacheDir)
    private var restored = false

    val plugins = mutableStateListOf<LoadedPlugin>()
    val failures = mutableStateListOf<String>()

    suspend fun restore() {
        if (restored) return
        restored = true
        val installed = withContext(Dispatchers.IO) { store.installed() }
        installed.forEach { load(it) }
    }

    suspend fun stage(uri: Uri): StagedPlugin = withContext(Dispatchers.IO) {
        if (uri.scheme == "file" && File(requireNotNull(uri.path)).canonicalPath.startsWith(app.dataDir.canonicalPath)) {
            throw PluginPackageException("Refusing to read app data")
        }
        val input = app.contentResolver.openInputStream(uri) ?: throw PluginPackageException("Cannot open the file")
        val staged = input.use(store::stage)
        val apiLevel = staged.manifest.apiLevel
        if (apiLevel !in 1..THETA_API_LEVEL) {
            staged.file.delete()
            throw PluginPackageException("Unsupported API level $apiLevel")
        }
        staged
    }

    suspend fun install(staged: StagedPlugin) {
        load(withContext(Dispatchers.IO) { store.install(staged) })
    }

    private suspend fun load(plugin: InstalledPlugin) {
        val manifest = plugin.manifest
        val context = HostPluginContext(manifest.id, app, File(app.filesDir, "plugin-data/${manifest.id}"))
        var enabled = false
        try {
            withContext(Dispatchers.Default) {
                val loader = createPluginClassLoader(plugin.apk, app.classLoader)
                val entry = loader.loadClass(manifest.entry).getDeclaredConstructor().newInstance() as ThetaPlugin
                entry.onEnable(context)
            }
            plugins += LoadedPlugin(manifest, context)
            enabled = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 插件的代码什么都可能抛，包括链接错误。这里不能让它带崩宿主。
            failures += "${manifest.name}: $e"
        } finally {
            if (!enabled) {
                val actions = context.beginClose()
                val cleanupFailures = withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) {
                        suspendCancellableCoroutine<List<Throwable>> { continuation ->
                            thread(name = "theta-cleanup-${manifest.id}", isDaemon = true) {
                                val errors = actions.mapNotNull { runCatching(it).exceptionOrNull() }
                                continuation.resume(errors)
                            }
                        }
                    }
                }
                if (cleanupFailures == null) {
                    failures += "${manifest.name}: cleanup timed out; restart required"
                } else {
                    cleanupFailures.forEach { failures += "${manifest.name}: cleanup failed: $it" }
                }
            }
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
        return listOf<() -> Unit>({ scope.cancel() }) + actions
    }
}
