package io.github.bytejuno.thetacore.ui.plugins

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens

import androidx.compose.foundation.clickable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.sdk.SettingsPage

@Composable
internal fun DataPackages(
    dataInventory: List<ManagedDataRecord>, dataRecoveryBlocked: Boolean,
    onToggleData: (ManagedDataRecord) -> Unit, onUninstallData: (ManagedDataRecord) -> Unit,
    onOpenData: (ManagedPluginData) -> Unit,
) {
    val strings = uiStrings()
    Text(strings.get(R.string.data_packages), style = MaterialTheme.typography.titleLarge)
    Text(strings.get(R.string.data_ownership_notice))
    if (dataInventory.isEmpty()) Text(strings.get(R.string.no_data_packages))
    dataInventory.forEach { plugin ->
        PluginCard {
            val manifest = plugin.record.selection?.manifest
            Text(manifest?.let { strings.get(R.string.package_name_version, it.name, it.version) }
                ?: strings.get(R.string.recorded_version, plugin.id, plugin.state.version), style = MaterialTheme.typography.titleMedium)
            Text(plugin.id)
            if (manifest != null) {
                Text(strings.get(
                    R.string.package_capabilities,
                    manifest.provides.takeIf { it.isNotEmpty() }?.joinToString() ?: strings.get(R.string.none),
                    manifest.declares.takeIf { it.isNotEmpty() }?.joinToString() ?: strings.get(R.string.none),
                ))
                PluginBadge(strings.get(R.string.plugin_env_none_required))
            }
            PluginBadge(strings.get(plugin.status))
            if (manifest == null) Text(strings.get(R.string.recorded_digest, plugin.state.sha256))
            FlowRow {
                TextButton(enabled = !plugin.state.pendingDelete && (plugin.state.enabled || (!dataRecoveryBlocked && plugin.canEnable)),
                    onClick = { onToggleData(plugin) }) {
                    Text(if (plugin.state.enabled) strings.get(R.string.disable_data) else strings.get(R.string.enable_data))
                }
                TextButton(onClick = { onUninstallData(plugin) }) {
                    Text(if (plugin.state.pendingDelete) strings.get(R.string.retry_data_uninstall) else strings.get(R.string.uninstall_data))
                }
            }
            plugin.page?.takeIf { it.active }?.let { page ->
                TextButton(onClick = { onOpenData(page) }) { Text(strings.get(R.string.view_data_content)) }
            }
        }
    }
}

@Composable
internal fun CodePackages(
    plugins: List<ManagedPlugin>, hostEnvironment: HostEnvironmentStatus,
    onOpenAccessibility: () -> Unit, onToggle: (ManagedPlugin) -> Unit,
    onUninstall: (ManagedPlugin) -> Unit, onRollback: (ManagedPlugin) -> Unit,
    onTool: (HostToolSelection) -> Unit, onOpen: (SettingsPage) -> Unit,
) {
    val strings = uiStrings()
    Text(strings.get(R.string.code_packages), style = MaterialTheme.typography.titleLarge)
    if (plugins.isEmpty()) Text(stringResource(R.string.no_plugins))
    plugins.forEach { plugin ->
        PluginCard {
            Text(strings.get(R.string.package_name_version, plugin.manifest.name, plugin.manifest.version), style = MaterialTheme.typography.titleMedium)
            PluginManifestEnvironmentSummary(
                manifest = plugin.manifest,
                environment = hostEnvironment,
                onOpenAccessibility = onOpenAccessibility,
                showAdvisoryNotice = false,
            )
            PluginBadge(strings.get(plugin.status))
            FlowRow {
                TextButton(
                    onClick = { onToggle(plugin) },
                    enabled = !plugin.busy && !plugin.pendingDelete,
                ) { Text(strings.get(R.string.toggle_plugin, if (plugin.enabled) strings.get(R.string.disable) else strings.get(R.string.enable), plugin.manifest.name)) }
                TextButton(
                    onClick = { onUninstall(plugin) },
                    enabled = !plugin.busy && !plugin.pendingDelete,
                ) { Text(strings.get(R.string.uninstall_plugin_named, plugin.manifest.name)) }
            }
            if (plugin.canRollback) {
                TextButton(
                    onClick = { onRollback(plugin) },
                    enabled = !plugin.busy && !plugin.pendingDelete,
                ) { Text(strings.get(R.string.rollback_plugin_named, plugin.manifest.name)) }
            }
            plugin.updateObservation?.let { HostUpdateObservation(it) }
            if (plugin.entryCapacityExceeded) Text(strings.get(R.string.entry_capacity_notice))
            val entries = plugin.entries
            if (entries.isNotEmpty()) Text(strings.get(R.string.entry_backend_notice))
            entries.forEachIndexed { index, entry -> HostEntryState(entry, index) }
            plugin.tools.forEachIndexed { index, tool ->
                TextButton(enabled = !plugin.busy && !plugin.pendingDelete,
                    onClick = { onTool(HostToolSelection(plugin.manifest.id, index, tool)) }) {
                    // No Tool.id/spec (or other plugin getter) on Main.
                    Text(strings.get(R.string.invoke_tool, index + 1))
                }
            }
            plugin.pages.forEach { page ->
                Text(
                    text = page.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(page) }.padding(vertical = LocalThetaThemeTokens.current.contentGap),
                )
            }
        }
    }
}

@Composable
internal fun PluginCard(content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalThetaThemeTokens.current
    Card(Modifier.fillMaxWidth(), shape = tokens.capsule,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(tokens.pageInset), verticalArrangement = Arrangement.spacedBy(tokens.smallGap), content = content)
    }
}

@Composable
internal fun PluginBadge(label: String) {
    val tokens = LocalThetaThemeTokens.current
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(label, Modifier.padding(tokens.smallGap), style = MaterialTheme.typography.labelMedium)
    }
}
