package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.settings.SettingsPage
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.agent.ledger.Risk
import io.github.bytejuno.thetacore.plugin.HostToolExecutionService
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ToolApprovalDialog(host: ThetaApplication, onClose: () -> Unit) {
    val strings = uiStrings()
    val store = host.approvalSettings
    val saved by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<ToolApprovalSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var confirmRevokeAll by remember { mutableStateOf(false) }

    fun update(change: ((HostToolExecutionService) -> Unit)? = null) {
        if (busy) return
        busy = true
        error = false
        completed = false
        scope.launch {
            try {
                snapshot = withContext(Dispatchers.IO) {
                    store.ensureLoaded()
                    val service = host.hostTools()
                    service.catalog.gate.withGate {
                        change?.invoke(service)
                        toolApprovalSnapshot(service, store.current())
                    }
                }
                completed = change != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(store) { update() }
    SettingsPage(
        onBack = onClose, canGoBack = !busy,
        title = strings.get(R.string.tool_rules_title),
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.get(R.string.tool_rules_explanation), style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error || saved.rulesUnavailable) Text(strings.get(R.string.tool_rules_failed), color = MaterialTheme.colorScheme.error)
                if (completed) Text(strings.get(R.string.tool_rules_saved))
                TextButton(onClick = { update() }, enabled = !busy) { Text(strings.get(R.string.tool_rules_refresh)) }
                snapshot?.let { data ->
                    Text(strings.get(R.string.tool_rules_grants), style = MaterialTheme.typography.titleMedium)
                    if (data.grants.isEmpty()) Text(strings.get(R.string.tool_rules_no_grants))
                    for ((key, grants) in data.grants.groupBy { it.definition.toolKey to it.definition.digest }) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(grants.first().definition.name, style = MaterialTheme.typography.titleSmall)
                                Text(key.first, style = MaterialTheme.typography.bodySmall)
                                Text(strings.get(R.string.tool_rules_grant_detail,
                                    strings.get(riskLabel(grants.maxOf { it.risk })), key.second.take(12)))
                                TextButton(enabled = !busy && !saved.rulesUnavailable, onClick = {
                                    update { service ->
                                        store.revoke(service.ledger.permanentGrants().filter {
                                            it.definition.toolKey == key.first && it.definition.digest == key.second
                                        }.map { it.receiptId }.toSet())
                                    }
                                }) { Text(strings.get(R.string.tool_rules_revoke)) }
                            }
                        }
                    }
                    TextButton(enabled = !busy && !saved.rulesUnavailable, onClick = { confirmRevokeAll = true }) {
                        Text(strings.get(R.string.tool_rules_revoke_all))
                    }
                    if (confirmRevokeAll) {
                        Text(strings.get(R.string.tool_rules_revoke_confirm))
                        TextButton(enabled = !busy, onClick = {
                            confirmRevokeAll = false
                            update { service -> store.revoke(service.ledger.permanentGrants().map { it.receiptId }.toSet()) }
                        }) { Text(strings.get(R.string.tool_rules_revoke_all)) }
                        TextButton(enabled = !busy, onClick = { confirmRevokeAll = false }) { Text(strings.get(R.string.cancel)) }
                    }
                    Text(strings.get(R.string.tool_rules_tools), style = MaterialTheme.typography.titleMedium)
                    if (data.tools.isEmpty()) Text(strings.get(R.string.tool_rules_no_tools))
                    data.tools.forEach { tool ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(tool.name, style = MaterialTheme.typography.titleSmall)
                                Text(tool.toolKey, style = MaterialTheme.typography.bodySmall)
                                Text(strings.get(R.string.tool_rules_declared, strings.get(riskLabel(tool.declaredRisk))))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(strings.get(R.string.tool_rules_deny), Modifier.weight(1f))
                                    Switch(checked = tool.toolKey in saved.deniedTools, enabled = !busy && !saved.rulesUnavailable,
                                        onCheckedChange = { denied -> update {
                                            store.setToolRule(tool.toolKey, denied, store.current().riskOverrides[tool.toolKey])
                                        } })
                                }
                                Text(strings.get(R.string.tool_rules_minimum_risk), style = MaterialTheme.typography.labelLarge)
                                val choices = listOf<Risk?>(null) + Risk.entries.filter { it >= tool.declaredRisk }
                                choices.forEach { risk ->
                                    val selected = saved.riskOverrides[tool.toolKey] == risk
                                    OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy && !saved.rulesUnavailable,
                                        onClick = { update {
                                            store.setToolRule(tool.toolKey, tool.toolKey in store.current().deniedTools, risk)
                                        } }) {
                                        RadioButton(selected = selected, onClick = null, enabled = !busy && !saved.rulesUnavailable)
                                        Spacer(Modifier.width(8.dp))
                                        Text(strings.get(risk?.let(::riskLabel) ?: R.string.tool_rules_default), Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun riskLabel(risk: Risk): Int = when (risk) {
    Risk.READ -> R.string.tool_rules_risk_read
    Risk.WRITE -> R.string.tool_rules_risk_write
    Risk.DEVICE_CONTROL -> R.string.tool_rules_risk_device
    Risk.ROOT -> R.string.tool_rules_risk_root
}
