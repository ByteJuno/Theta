package io.github.bytejuno.thetacore.ui.theme

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

internal class ThemePreferenceState(private val store: ThemePreferenceStore, private val scope: CoroutineScope) {
    var preference by mutableStateOf(ThemePreference()); private set
    var loading by mutableStateOf(true); private set
    var saving by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var themeUnavailable by mutableStateOf(false); private set
    init {
        scope.launch { store.updates.collect { preference = it } }
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
    fun setDynamic(dynamic: Boolean) { themeUnavailable = false; save { store.setDynamic(dynamic) } }
    fun setTheme(key: String) { themeUnavailable = false; save { store.setTheme(key) } }
    fun clearUnavailableTheme(key: String) {
        themeUnavailable = true
        save { store.clearUnavailableTheme(key) }
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
internal fun rememberThemePreferenceState(): ThemePreferenceState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state = remember(context, scope) {
        val legacy = runCatching { context.getSharedPreferences(APPEARANCE_STORE, Context.MODE_PRIVATE)
            .getBoolean(DYNAMIC_KEY, true) }.getOrDefault(true)
        ThemePreferenceState(ThemePreferenceStore(context.noBackupFilesDir, legacy), scope)
    }
    LaunchedEffect(state) { state.load() }
    return state
}
