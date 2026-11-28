package io.github.bytejuno.thetacore.plugin

import android.content.Context
import io.github.bytejuno.thetacore.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Invoked under PluginManager.operations, independently of Activity/Compose observation. */
internal class PluginThemeLifecycle(context: Context) {
    private val preferences = ThemePreferenceStore(context.noBackupFilesDir)

    suspend fun revoke(id: String) = withContext(Dispatchers.IO) { preferences.clearPluginTheme(id) }

    suspend fun restore(pages: List<ManagedPluginData>) = withContext(Dispatchers.IO) {
        preferences.retainPluginThemes(pages.filter { it.active }.map { it.selection.manifest.id }.toSet())
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
