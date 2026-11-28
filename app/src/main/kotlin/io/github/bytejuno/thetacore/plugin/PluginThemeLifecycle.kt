package io.github.bytejuno.thetacore.plugin

import android.content.Context
import io.github.bytejuno.thetacore.ui.theme.ThemePreferenceStore
import io.github.bytejuno.thetacore.ui.theme.themePreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Invoked under PluginManager.operations, independently of Activity/Compose observation. */
internal class PluginThemeLifecycle(context: Context,
    private val preferences: ThemePreferenceStore = themePreferenceStore(context)) {
    private val revocations = PluginThemeRevocations(context.filesDir)

    suspend fun revoke(id: String) = withContext(Dispatchers.IO) {
        revocations.begin(id)
        preferences.clearPluginTheme(id)
        revocations.finish(id)
    }

    /** Must run before cold inventory admission, even when a damaged archive became readable. */
    suspend fun recover(pluginId: String? = null) = withContext(Dispatchers.IO) {
        revocations.pending().filter { pluginId == null || it == pluginId }.forEach { id ->
            preferences.clearPluginTheme(id)
            revocations.finish(id)
        }
    }

    /** Discover isolation without consulting preferences. Journal every unavailable identity
     * before any preference IO; keep pages local until recovery and reconciliation succeed. */
    suspend fun restore(catalog: PluginDataCatalog, onFailure: (String) -> Unit,
        crashSuspects: Set<String>, skipPlugins: Boolean, skipInventory: Boolean): List<PluginDataRecord> =
        withContext(Dispatchers.IO) {
            val unavailable = mutableSetOf<String>()
            val records = if (skipInventory) emptyList() else
                catalog.restoreInventory(onFailure, crashSuspects, unavailable::add)
            records.filter { it.error != null || it.crashQuarantined || it.selection?.active(skipPlugins) != true }
                .forEach { unavailable += it.id }
            unavailable.forEach(revocations::begin)
            recover()
            val inventory = mutableListOf<ManagedDataRecord>()
            val pages = mutableListOf<ManagedPluginData>()
            records.forEach { replaceDataInventory(inventory, pages, it, skipPlugins) }
            reconcile(catalog, inventory, pages)
            inventory.map { it.record }
        }

    suspend fun reconcile(catalog: PluginDataCatalog, inventory: MutableList<ManagedDataRecord>,
        pages: MutableList<ManagedPluginData>, pluginId: String? = null) = withContext(Dispatchers.IO) {
        recover(pluginId)
        val key = preferences.current().themeKey ?: return@withContext
        val owner = key.substringBefore('/')
        if (pluginId != null && owner != pluginId) return@withContext
        val page = pages.singleOrNull { it.active && it.selection.manifest.id == owner }
        val available = page != null && try { catalog.readThemes(page.selection).themes.any { it.key == key } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Invalid old bytes must revoke their page and selection, not permanently block
            // a separately validated, digest-confirmed replacement. Failed revocation throws
            // with its durable intent retained and the confirmation still owned by the caller.
            quarantine(inventory, pages, page, error.message ?: "Invalid theme archive")
            return@withContext
        }
        // An active package may have removed or invalidated this particular theme. Comparing
        // the exact key again under the preference mutex preserves a newer user selection.
        if (!available) {
            revocations.begin(owner)
            preferences.clearUnavailableTheme(key)
            revocations.finish(owner)
        }
    }

    suspend fun quarantine(inventory: MutableList<ManagedDataRecord>, pages: MutableList<ManagedPluginData>,
        page: ManagedPluginData, error: String) {
        // Preserve the exact-page guard; a stale read must not revoke a replacement.
        if (pages.none { it === page }) return
        try { revoke(page.selection.manifest.id) }
        finally {
            // Even a preference IO failure cannot leave corrupt content admitted. Re-enable
            // retries revocation before making this package active again. The write-ahead
            // intent survives process loss and is recovered before cold inventory admission.
            quarantineDataPage(inventory, pages, page, error)
        }
    }
}
