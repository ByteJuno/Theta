package io.github.bytejuno.thetacore.ui.plugins

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.SettingsPage
import io.github.bytejuno.thetacore.sdk.THETA_API_LEVEL
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens

/** Host-owned presentation only. Import, recovery, admission and confirmations stay in the host. */
@Composable
internal fun PluginsScreen(
    plugins: List<ManagedPlugin>, dataInventory: List<ManagedDataRecord>,
    failures: List<String>, rejectedIds: List<String>,
    restorePolicy: PluginRestorePolicy, resumeRequested: Boolean,
    error: UiText?, recovery: Boolean, staging: Boolean, dataRecoveryBlocked: Boolean,
    hostEnvironment: HostEnvironmentStatus,
    onBack: () -> Unit, onResume: () -> Unit,
    onRefreshEnvironment: () -> Unit, onOpenAccessibility: () -> Unit,
    onTool: (HostToolSelection) -> Unit, onCancelReading: () -> Unit, onRetryCleanup: () -> Unit,
    onOpen: (SettingsPage) -> Unit, onPick: () -> Unit, onPickData: () -> Unit,
    onOpenData: (ManagedPluginData) -> Unit, onToggleData: (ManagedDataRecord) -> Unit,
    onUninstallData: (ManagedDataRecord) -> Unit, onToggle: (ManagedPlugin) -> Unit,
    onUninstall: (ManagedPlugin) -> Unit, onRollback: (ManagedPlugin) -> Unit,
    onRemoveRejected: (String) -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    val strings = uiStrings()
    val tokens = LocalThetaThemeTokens.current
    // A new route always starts on All, including skip_plugins cold-start recovery.
    var filter by rememberSaveable { mutableStateOf(PluginFilter.ALL) }
    var environmentExpanded by rememberSaveable { mutableStateOf(false) }
    var hookExpanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = tokens.pageInset),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_settings_back), strings.get(R.string.chat_back))
            }
            Column(Modifier.weight(1f).padding(vertical = tokens.smallGap)) {
                Text(strings.get(R.string.plugins_title), style = MaterialTheme.typography.titleLarge)
                Text(strings.get(R.string.api_level, THETA_API_LEVEL), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Exactly one vertical scroll owner; filters wrap instead of creating a second scroll node.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(tokens.pageInset),
            verticalArrangement = Arrangement.spacedBy(tokens.contentGap)) {
            PluginRecoveryBanner(restorePolicy, recovery, resumeRequested, onResume)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(tokens.smallGap)) {
                Button(onClick = onPick, enabled = !staging) { Text(strings.get(R.string.import_code)) }
                FilledTonalButton(onClick = onPickData, enabled = !staging && !dataRecoveryBlocked) {
                    Text(strings.get(R.string.import_data))
                }
                if (staging) TextButton(onClick = onCancelReading) { Text(strings.get(R.string.cancel_reading)) }
                TextButton(onClick = onRetryCleanup) { Text(strings.get(R.string.retry_import_cleanup)) }
            }
            if (dataRecoveryBlocked) Text(strings.get(R.string.data_recovery_notice))
            error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
            if (failures.isNotEmpty()) {
                LaunchedEffect(failures.toList()) { failures.forEach(::logUiDiagnostic) }
                Text(strings.get(R.string.plugin_diagnostics_failed, failures.size), color = MaterialTheme.colorScheme.error)
            }
            // Safety recovery controls are not hidden by a presentation filter.
            rejectedIds.forEach { id ->
                TextButton(onClick = { onRemoveRejected(id) }) { Text(strings.get(R.string.rejected_uninstall, id)) }
            }
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(tokens.smallGap)) {
                PluginFilter.entries.forEach { item ->
                    FilterChip(selected = filter == item, onClick = { filter = item },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Tab },
                        label = { Text(strings.get(item.label)) })
                }
            }
            if (filter.environment) {
                EnvironmentCard(hostEnvironment, environmentExpanded, { environmentExpanded = !environmentExpanded },
                    onRefreshEnvironment, onOpenAccessibility)
            }
            if (filter.data) DataPackages(dataInventory, dataRecoveryBlocked, onToggleData, onUninstallData, onOpenData)
            if (filter.code) CodePackages(plugins, hostEnvironment, onOpenAccessibility, onToggle, onUninstall,
                onRollback, onTool, onOpen)
            if (filter.environment) {
                io.github.bytejuno.thetacore.web.WebSearchSettingsEntry()
                PluginCard {
                    val state = strings.get(if (hookExpanded) R.string.plugins_expanded else R.string.plugins_collapsed)
                    TextButton(onClick = { hookExpanded = !hookExpanded },
                        modifier = Modifier.fillMaxWidth().semantics { stateDescription = state }) {
                        Text(strings.get(if (hookExpanded) R.string.plugins_hide_hook else R.string.plugins_show_hook))
                    }
                    if (hookExpanded) io.github.bytejuno.thetacore.hook.HookDiagnosticsPanel()
                }
            }
            Text(strings.get(R.string.plugin_env_advisory_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal enum class PluginFilter(val label: Int, val code: Boolean, val data: Boolean, val environment: Boolean) {
    ALL(R.string.plugins_filter_all, true, true, true),
    CODE(R.string.plugins_filter_code, true, false, false),
    DATA(R.string.plugins_filter_data, false, true, false),
    ENVIRONMENT(R.string.plugins_filter_environment, false, false, true),
}

@Composable
private fun PluginRecoveryBanner(policy: PluginRestorePolicy, recovery: Boolean, resumeRequested: Boolean, onResume: () -> Unit) {
    val strings = uiStrings()
    if (policy.skipCode || policy.skipData || policy.suspects.isNotEmpty()) {
        Surface(shape = LocalThetaThemeTokens.current.capsule, color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Column(Modifier.fillMaxWidth().padding(LocalThetaThemeTokens.current.pageInset)) {
                Text(strings.get(when {
                    policy.skipData -> R.string.safe_mode_all
                    policy.skipCode -> R.string.safe_mode_code
                    else -> R.string.suspects_quarantined
                }), style = MaterialTheme.typography.titleSmall)
                if (policy.suspects.isNotEmpty()) Text(strings.get(R.string.suspect_plugins, policy.suspects.sorted().joinToString()))
                TextButton(enabled = !resumeRequested, onClick = onResume) {
                    Text(strings.get(if (resumeRequested) R.string.resume_saved else R.string.resume_next_start))
                }
            }
        }
    }
    if (recovery) Text(strings.get(R.string.recovery_requested))
}

@Composable
private fun EnvironmentCard(environment: HostEnvironmentStatus, expanded: Boolean, onToggle: () -> Unit,
    onRefresh: () -> Unit, onOpenAccessibility: () -> Unit) {
    val strings = uiStrings()
    fun readinessText(key: HostRequirementKey): String = strings.get(when (environment.readiness(key)) {
        EnvironmentReadiness.READY -> R.string.plugin_env_state_ready
        EnvironmentReadiness.DETECTED_UNVERIFIED -> R.string.plugin_env_state_unverified
        EnvironmentReadiness.UNAVAILABLE -> R.string.plugin_env_state_unavailable
    })
    PluginCard {
        val state = strings.get(if (expanded) R.string.plugins_expanded else R.string.plugins_collapsed)
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().semantics { stateDescription = state }) {
            Text(strings.get(R.string.plugin_env_host_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(strings.get(if (expanded) R.string.plugins_collapse else R.string.plugins_expand))
        }
        Text(strings.get(R.string.plugin_env_host_summary, readinessText(HostRequirementKey.ACCESSIBILITY),
            readinessText(HostRequirementKey.SHIZUKU), readinessText(HostRequirementKey.ROOT), readinessText(HostRequirementKey.LSPOSED)),
            style = MaterialTheme.typography.bodySmall)
        if (expanded) {
            FlowRow {
                TextButton(onClick = onRefresh) { Text(strings.get(R.string.plugin_env_refresh)) }
                if (!environment.accessibilityEnabled) TextButton(onClick = onOpenAccessibility) {
                    Text(strings.get(R.string.plugin_env_open_accessibility))
                }
            }
        }
    }
}
