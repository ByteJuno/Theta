package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.mcp.MCP_PROGRESS_RUNNING
import io.github.bytejuno.thetacore.plugin.PluginToolProgress
import io.github.bytejuno.thetacore.plugin.ToolInputRequest

internal fun toolState(record: InvocationRecord?): Int = when {
    record == null -> R.string.chat_tool_pending
    record.pending != null -> R.string.chat_tool_approval
    record.result?.effect == Effect.UNKNOWN || record.recoveryPending -> R.string.chat_tool_unknown
    record.state == Status.RUNNING -> R.string.chat_tool_running
    record.state == Status.SUCCEEDED -> R.string.chat_tool_complete
    record.state == Status.REJECTED -> R.string.chat_tool_rejected
    record.state == Status.CANCELLED -> R.string.chat_stopped
    record.state in setOf(Status.FAILED, Status.TIMED_OUT, Status.LOST) -> R.string.chat_tool_failed
    else -> R.string.chat_tool_pending
}
internal fun showApproval(record: InvocationRecord): Boolean = record.pending != null && !record.input.unattended

internal fun forceToolExpanded(record: InvocationRecord?, inputRequired: Boolean = false): Boolean =
    record?.let { showApproval(it) || it.result?.effect == Effect.UNKNOWN || it.recoveryPending } == true || inputRequired

@Composable
internal fun ToolCard(
    call: ContentPart.ToolCall,
    record: InvocationRecord?,
    progress: PluginToolProgress?,
    inputRequest: ToolInputRequest?,
    recovery: RecoveryRequest?,
    allowRootLongTerm: Boolean,
    actions: ChatUiActions,
) {
    val strings = uiStrings()
    var expanded by rememberSaveable(call.binding.callId) { mutableStateOf(false) }
    val forced = forceToolExpanded(record, inputRequest != null) || recovery != null
    val visible = expanded || forced
    if (!visible) {
        SuggestionChip(onClick = { expanded = true }, label = {
            Text(strings.get(R.string.chat_tool_capsule, call.name, strings.get(toolState(record))), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, modifier = Modifier.fillMaxWidth())
        return
    }
    val details: @Composable () -> Unit = {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val imageCall = record?.input?.definition?.toolKey == IMAGE_TOOL_KEY
            Text(if (imageCall) strings.get(R.string.chat_image_title) else call.name, style = MaterialTheme.typography.titleSmall)
            Text(strings.get(toolState(record))) // never hidden by collapsing details
            if (visible && record?.state == Status.RUNNING && progress != null) {
                if (progress.text.isNotBlank()) {
                    Text(if (record.input.definition?.source == Source.MCP && progress.text == MCP_PROGRESS_RUNNING)
                        strings.get(R.string.mcp_running) else progress.text, style = MaterialTheme.typography.bodySmall)
                }
                progress.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (record?.state == Status.RUNNING && inputRequest != null) {
                ToolInputForm(inputRequest) { answer -> actions.input(call.binding.callId, inputRequest.id, answer) }
            }
            if (visible && imageCall && record != null) {
                imageResultLabel(record)?.let { Text(strings.get(it)) }
                if (shouldLoadChatImage(record)) ChatImage(record, actions.generatedImage)
            }
            if (record != null && showApproval(record)) {
                val approval = requireNotNull(record.pending)
                val canGrantMultiCall = canGrantMultiCallApproval(approval, allowRootLongTerm)
                Text(strings.get(R.string.chat_approval_warning))
                Text(strings.get(when (approval.risk) {
                    Risk.READ -> R.string.chat_risk_read
                    Risk.WRITE -> R.string.chat_risk_write
                    Risk.DEVICE_CONTROL -> R.string.chat_risk_device
                    Risk.ROOT -> R.string.chat_risk_root
                }))
                SelectionContainer { Text(approval.summary.ifBlank { call.argsJson }) }
                if (approval.unknownCalls.isNotEmpty()) Text(strings.get(R.string.chat_unknown_warning), color = MaterialTheme.colorScheme.error)
                if (approval.risk == Risk.ROOT && !allowRootLongTerm) {
                    Text(strings.get(R.string.chat_root_long_term_hint), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.decide(record.callId, approval.id, Decision.ALLOW_ONCE) }) {
                        Text(strings.get(R.string.chat_allow))
                    }
                    if (canGrantMultiCall) {
                        OutlinedButton(onClick = { actions.decide(record.callId, approval.id, Decision.ALLOW_SESSION) }) {
                            Text(strings.get(R.string.chat_allow_session))
                        }
                        OutlinedButton(onClick = { actions.decide(record.callId, approval.id, Decision.ALLOW_ALWAYS) }) {
                            Text(strings.get(R.string.chat_allow_always))
                        }
                    }
                    TextButton(onClick = { actions.decide(record.callId, approval.id, Decision.DENY) }) {
                        Text(strings.get(R.string.chat_deny))
                    }
                }
            }
            if (recovery != null && record != null && !record.input.unattended) {
                Text(strings.get(R.string.chat_recovery_warning))
                FlowRow {
                    TextButton(onClick = { actions.resolve(record.callId, recovery.id, RecoveryResolution.VERIFIED_CLEAR) }) {
                        Text(strings.get(R.string.chat_verified_clear))
                    }
                    TextButton(onClick = { actions.resolve(record.callId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY) }) {
                        Text(strings.get(R.string.chat_accept_unknown))
                    }
                }
            }
            if (!forced) TextButton(onClick = { expanded = !expanded }) { Text(strings.get(if (expanded) R.string.chat_collapse else R.string.chat_expand)) }
            if (visible) {
                Text(strings.get(R.string.chat_arguments), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(call.argsJson) }
                record?.result?.let {
                    Text(strings.get(R.string.chat_result), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(if (imageCall) strings.get(imageResultLabel(record) ?: R.string.chat_image_failed) else it.text) }
                }
            }
        }
    }
    if (forced) OutlinedCard(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer)) { details() }
    else Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) { details() }
}
