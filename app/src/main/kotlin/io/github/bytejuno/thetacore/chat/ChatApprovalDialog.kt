package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.agent.ledger.ApprovalMode
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatApprovalDialog(host: ThetaApplication, onClose: () -> Unit) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    val store = host.approvalSettings
    val saved by store.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pendingFullAuto by remember { mutableStateOf(false) }
    var confirmFullAuto by remember { mutableStateOf(false) }
    var manageTools by remember { mutableStateOf(false) }

    if (manageTools) {
        ToolApprovalDialog(host) { manageTools = false }
        return
    }

    LaunchedEffect(store) {
        withContext(Dispatchers.IO) { runCatching { store.ensureLoaded() } }
    }

    fun applyMode(target: ApprovalMode) {
        if (busy) return
        pendingFullAuto = false
        confirmFullAuto = false
        error = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.setMode(target) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.chat_approval_save_failed
            } finally {
                busy = false
            }
        }
    }

    fun applyRootLongTerm(allowed: Boolean) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.setAllowRootLongTerm(allowed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.chat_approval_save_failed
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(strings.get(R.string.chat_approval_settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(strings.get(R.string.chat_approval_mode_intro), style = MaterialTheme.typography.bodySmall)
                ApprovalModeOptionCard(
                    selected = saved.mode == ApprovalMode.BALANCED && !pendingFullAuto,
                    enabled = !busy,
                    title = strings.get(R.string.chat_approval_mode_balanced),
                    description = strings.get(R.string.chat_approval_mode_balanced_desc),
                    onClick = { applyMode(ApprovalMode.BALANCED) },
                )
                ApprovalModeOptionCard(
                    selected = saved.mode == ApprovalMode.FULL_AUTO || pendingFullAuto,
                    enabled = !busy,
                    title = strings.get(R.string.chat_approval_mode_full_auto),
                    description = strings.get(R.string.chat_approval_mode_full_auto_desc),
                    onClick = {
                        if (saved.mode != ApprovalMode.FULL_AUTO) {
                            pendingFullAuto = true
                            confirmFullAuto = false
                        }
                    },
                )
                if (pendingFullAuto && saved.mode != ApprovalMode.FULL_AUTO) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                strings.get(R.string.chat_approval_full_auto_confirm_warning),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = confirmFullAuto,
                                    onCheckedChange = { confirmFullAuto = it },
                                    enabled = !busy,
                                )
                                Text(
                                    strings.get(R.string.chat_approval_full_auto_confirm_check),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy && confirmFullAuto,
                                    onClick = { applyMode(ApprovalMode.FULL_AUTO) },
                                ) {
                                    Text(strings.get(R.string.chat_approval_full_auto_enable))
                                }
                                TextButton(
                                    enabled = !busy,
                                    onClick = { pendingFullAuto = false; confirmFullAuto = false },
                                ) {
                                    Text(strings.get(R.string.cancel))
                                }
                            }
                        }
                    }
                }
                ApprovalModeOptionCard(
                    selected = saved.mode == ApprovalMode.STRICT && !pendingFullAuto,
                    enabled = !busy,
                    title = strings.get(R.string.chat_approval_mode_strict),
                    description = strings.get(R.string.chat_approval_mode_strict_desc),
                    onClick = { applyMode(ApprovalMode.STRICT) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.get(R.string.chat_approval_root_long_term), style = MaterialTheme.typography.labelLarge)
                        Text(strings.get(R.string.chat_approval_root_long_term_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = saved.allowRootLongTerm,
                        onCheckedChange = ::applyRootLongTerm,
                        enabled = !busy,
                    )
                }
                Text(strings.get(R.string.chat_approval_safety_invariants), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { manageTools = true }, enabled = !busy) {
                    Text(strings.get(R.string.tool_rules_title))
                }
                error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, enabled = !busy) { Text(strings.get(R.string.chat_close)) }
        },
    )
}

@Composable
private fun ApprovalModeOptionCard(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
