package io.github.bytejuno.thetacore.ui.theme

import androidx.compose.runtime.*
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.SelectedPluginData
import kotlinx.coroutines.CancellationException

internal data class ThemeChoices(val themes: List<PluginTheme> = emptyList(), val loading: Boolean = false,
    val failed: Boolean = false, val reload: () -> Unit = {})
internal val LocalThemeChoices = staticCompositionLocalOf { ThemeChoices() }

internal fun activeThemeChoices(themes: List<PluginTheme>, selections: List<SelectedPluginData>): List<PluginTheme> =
    themes.filter { it.selection in selections }

/** Loading is keyed by exact durable selections. Revocation filters synchronously, before IO reload. */
@Composable
internal fun rememberPluginThemeChoices(manager: PluginManager?): ThemeChoices {
    if (manager == null) return ThemeChoices()
    val ready = manager.dataThemesReady
    val selections = manager.dataPlugins.filter { it.active }.map { it.selection }
    var themes by remember(manager) { mutableStateOf(emptyList<PluginTheme>()) }
    var loaded by remember(manager) { mutableStateOf<List<SelectedPluginData>?>(null) }
    var failed by remember(manager) { mutableStateOf(false) }
    var reload by remember(manager) { mutableIntStateOf(0) }
    LaunchedEffect(manager, ready, selections, reload) {
        if (!ready) return@LaunchedEffect
        try {
            themes = manager.activeThemes()
            failed = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { themes = emptyList(); failed = true }
        loaded = selections
    }
    return ThemeChoices(activeThemeChoices(themes, selections), !ready || loaded != selections, failed) { loaded = null; reload++ }
}
