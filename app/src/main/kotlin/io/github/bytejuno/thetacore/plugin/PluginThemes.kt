package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.ui.theme.PluginTheme
import io.github.bytejuno.thetacore.ui.theme.parsePluginTheme

internal data class PluginThemeLoadResult(val themes: List<PluginTheme>, val errors: List<String>)

/** Archive identity failures propagate to package quarantine; schema failures isolate one entry. */
internal fun PluginDataCatalog.readThemes(selection: SelectedPluginData): PluginThemeLoadResult {
    val entries = readEntries(selection) { it.startsWith("themes/") && it.endsWith(".json") }
    val themes = mutableListOf<PluginTheme>()
    val errors = mutableListOf<String>()
    entries.forEach { (path, bytes) ->
        try { themes += parsePluginTheme(selection, path, bytes) }
        catch (error: Exception) { errors += "${selection.manifest.id}/$path: ${error.message ?: "Invalid theme"}" }
    }
    return PluginThemeLoadResult(themes.toList(), errors.toList())
}
