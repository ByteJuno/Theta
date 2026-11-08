package io.github.bytejuno.thetacore.plugin

/** No runtime, entry point, PluginContext, SDK registration or private data directory. */
internal class ManagedPluginData(val selection: SelectedPluginData, skipPlugins: Boolean) {
    val active = selection.active(skipPlugins)
    val status = when {
        skipPlugins -> "Safe mode; data activation skipped until restart"
        !selection.state.enabled -> "Disabled"
        else -> "Enabled · host read-only content only"
    }
}

internal fun isManagedDataPageActive(pages: List<ManagedPluginData>, page: ManagedPluginData): Boolean =
    pages.any { it === page && it.active }
