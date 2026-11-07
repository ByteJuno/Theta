package io.github.bytejuno.thetacore.plugin

/** Management identity is a validated durable record, never a manifest guessed from bad bytes.
 * Page capability is optional and independently revoked. Quarantine cannot activate content.
 */
internal data class PluginDataRecord(
    val id: String,
    val state: PluginDataState,
    val selection: SelectedPluginData? = null,
    val error: String? = null,
)
internal class ManagedDataRecord(val record: PluginDataRecord, val page: ManagedPluginData?) {
    val id get() = record.id
    val state get() = record.state
    val canEnable get() = record.error == null && !state.pendingDelete
    val status get() = record.error?.let { "Quarantined: $it" }
        ?: page?.status ?: "Deletion pending; retry uninstall"
}
internal fun replaceDataInventory(
    inventory: MutableList<ManagedDataRecord>, pages: MutableList<ManagedPluginData>,
    record: PluginDataRecord, skipPlugins: Boolean,
) {
    inventory.removeAll { it.id == record.id }
    pages.removeAll { it.selection.manifest.id == record.id }
    val page = record.selection?.takeIf { record.error == null && !record.state.pendingDelete }
        ?.let { ManagedPluginData(it, skipPlugins) }
    if (page != null) pages += page
    inventory += ManagedDataRecord(record, page)
}
internal fun quarantineDataPage(
    inventory: MutableList<ManagedDataRecord>, pages: MutableList<ManagedPluginData>,
    page: ManagedPluginData, error: String,
) {
    // A late failure can revoke only the exact selection it read, never a replacement.
    if (pages.none { it === page }) return
    replaceDataInventory(inventory, pages,
        PluginDataRecord(page.selection.manifest.id, page.selection.state, error = error), false)
}
