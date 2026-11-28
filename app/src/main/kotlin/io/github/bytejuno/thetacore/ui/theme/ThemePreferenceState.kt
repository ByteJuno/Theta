package io.github.bytejuno.thetacore.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.bytejuno.thetacore.plugin.PluginManager
import kotlinx.coroutines.*

internal class ThemePreferenceState(private val store: ThemePreferenceStore, private val scope: CoroutineScope,
    private val manager: PluginManager? = null) {
    var preference by mutableStateOf(ThemePreference()); private set
    var loading by mutableStateOf(true); private set
    var saving by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var themeUnavailable by mutableStateOf(false); private set
    var revision by mutableLongStateOf(0L); private set
    init {
        scope.launch { store.updates.collect {
            preference = it.preference
            themeUnavailable = it.themeUnavailable
            revision = it.revision
        } }
    }
    fun load() {
        loading = true; failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { store.current() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    fun setMode(mode: ThemeMode) = save { store.setMode(mode) }
    fun setDynamic(dynamic: Boolean) { themeUnavailable = false; save {
        if (manager != null) manager.selectThemePalette(dynamic) else store.setDynamic(dynamic)
    } }
    fun setTheme(key: String) { themeUnavailable = false; save { requireNotNull(manager).selectTheme(key) } }
    fun clearUnavailableTheme(key: String, expectedRevision: Long = revision) {
        save { store.clearUnavailableTheme(key, expectedRevision) }
    }
    private fun save(change: suspend () -> Unit) {
        if (saving || loading) return
        saving = true; failed = false
        scope.launch {
            try { withContext(Dispatchers.IO) { change() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }
}

internal val LocalThemePreferenceState = staticCompositionLocalOf<ThemePreferenceState> { throw IllegalStateException() }

@Composable
internal fun rememberThemePreferenceState(manager: PluginManager? = null): ThemePreferenceState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state = remember(context, scope, manager) {
        ThemePreferenceState(themePreferenceStore(context), scope, manager)
    }
    LaunchedEffect(state) { state.load() }
    return state
}
