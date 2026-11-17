package io.github.bytejuno.thetacore.plugin

internal enum class DataStatus { SAFE_MODE, DISABLED, ENABLED, QUARANTINED, CRASH_QUARANTINED, DELETE_PENDING }

/** No runtime, entry point, PluginContext, SDK registration or private data directory. */
internal class ManagedPluginData(val selection: SelectedPluginData, skipPlugins: Boolean) {
    val active = selection.active(skipPlugins)
    val status = when {
        skipPlugins -> DataStatus.SAFE_MODE
        !selection.state.enabled -> DataStatus.DISABLED
        else -> DataStatus.ENABLED
    }
}

internal fun isManagedDataPageActive(pages: List<ManagedPluginData>, page: ManagedPluginData): Boolean =
    pages.any { it === page && it.active }
