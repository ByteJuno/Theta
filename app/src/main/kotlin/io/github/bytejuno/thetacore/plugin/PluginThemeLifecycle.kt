package io.github.bytejuno.thetacore.plugin

import android.content.Context
import io.github.bytejuno.thetacore.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Invoked under PluginManager.operations, independently of Activity/Compose observation. */
internal class PluginThemeLifecycle(context: Context,
    private val preferences: ThemePreferenceStore = ThemePreferenceStore(context.noBackupFilesDir)) {

    suspend fun revoke(id: String) = withContext(Dispatchers.IO) { preferences.clearPluginTheme(id) }

    suspend fun reconcile(catalog: PluginDataCatalog, pages: List<ManagedPluginData>, pluginId: String? = null) = withContext(Dispatchers.IO) {
        val key = preferences.current().themeKey ?: return@withContext
        val owner = key.substringBefore('/')
        if (pluginId != null && owner != pluginId) return@withContext
        val page = pages.singleOrNull { it.active && it.selection.manifest.id == owner }
        val available = page != null && catalog.readThemes(page.selection).themes.any { it.key == key }
        // An active package may have removed or invalidated this particular theme. Comparing
        // the exact key again under the preference mutex preserves a newer user selection.
        if (!available) preferences.clearUnavailableTheme(key)
    }

    suspend fun quarantine(inventory: MutableList<ManagedDataRecord>, pages: MutableList<ManagedPluginData>,
        page: ManagedPluginData, error: String) {
        // Preserve the exact-page guard; a stale read must not revoke a replacement.
        if (pages.none { it === page }) return
        try { revoke(page.selection.manifest.id) }
        finally {
            // Even a preference IO failure cannot leave corrupt content admitted. Re-enable
            // retries revocation before making this package active again; cold restore does too.
            quarantineDataPage(inventory, pages, page, error)
        }
    }
}
