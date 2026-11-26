package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

internal fun recoveryLabel(action: ChatRecoveryAction): Int = when (action) {
    ChatRecoveryAction.RETRY -> R.string.chat_retry_confirm
    ChatRecoveryAction.READMIT -> R.string.checkpoint_readmit
    ChatRecoveryAction.SUMMARIZE -> R.string.chat_summary_resume
    ChatRecoveryAction.CONTINUE_SUMMARY -> R.string.checkpoint_continue_summary
    ChatRecoveryAction.CONTINUE -> R.string.chat_retry
}

@Composable
internal fun ChatRecoveryCard(state: ChatState,
    recover: (ChatRecoveryToken, ChatRecoveryAction, Long?, Boolean) -> Unit) {
    val strings = uiStrings()
    val token = state.recovery
    val actions = recoveryActions(state)
    var window by rememberSaveable(token) { mutableStateOf(state.contextWindowTokens?.toString() ?: "") }
    var confirm by remember(token) { mutableStateOf<ChatRecoveryAction?>(null) }
    var details by remember(state.sessionId, token) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.get(R.string.checkpoint_title), style = MaterialTheme.typography.titleMedium)
            state.result?.let { Text(strings.get(chatOutcome(it))) }
            state.checkpoint?.let { Text(strings.get(R.string.chat_checkpoint_counts, it.steps, it.retries)) }
            if (token == null || token.identity.unattended) {
                Text(strings.get(R.string.checkpoint_not_attended))
            } else {
                if (state.checkpointSettingsChanged)
                    Text(strings.get(R.string.checkpoint_settings_changed))
                if (ChatRecoveryAction.RETRY in actions) Text(strings.get(R.string.chat_retry_warning))
                if (ChatRecoveryAction.SUMMARIZE in actions || ChatRecoveryAction.CONTINUE_SUMMARY in actions)
                    Text(strings.get(R.string.checkpoint_summary_warning))
                if (ChatRecoveryAction.READMIT in actions || ChatRecoveryAction.SUMMARIZE in actions) {
                    Text(strings.get(R.string.chat_summary_notice), style = MaterialTheme.typography.bodySmall)
                    if (state.contextWindowTokens == null) Text(strings.get(R.string.checkpoint_window_unknown))
                    else Text(strings.get(R.string.checkpoint_current_window, state.contextWindowTokens))
                    OutlinedTextField(window, { window = it.filter(Char::isDigit).take(7) },
                        label = { Text(strings.get(R.string.chat_summary_window)) },
                        supportingText = { Text(strings.get(R.string.checkpoint_window_help)) },
                        isError = window.isNotEmpty() && !validRecoveryWindow(ChatRecoveryAction.SUMMARIZE, window.toLongOrNull(), null),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    actions.forEach { action ->
                        val needsWindow = action in setOf(ChatRecoveryAction.READMIT, ChatRecoveryAction.SUMMARIZE)
                        OutlinedButton(onClick = {
                            if (action == ChatRecoveryAction.CONTINUE) recover(token, action, null, false)
                            else confirm = action
                        }, enabled = !needsWindow || validRecoveryWindow(action, window.toLongOrNull(), state.contextWindowTokens)) {
                            Text(strings.get(recoveryLabel(action)))
                        }
                    }
                }
            }
            TextButton(onClick = { details = !details }) { Text(strings.get(R.string.checkpoint_details)) }
            if (details) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.checkpoint?.let {
                        Text(strings.get(R.string.checkpoint_identifier, it.id))
                        Text(strings.get(R.string.checkpoint_run, it.previousRunId))
                        Text(strings.get(R.string.checkpoint_diagnostic, strings.get(diagnosticReason(it.reason))))
                    }
                    state.reconciliation?.let { Text(strings.get(R.string.checkpoint_diagnostic, strings.get(diagnosticReason(it.reason)))) }
                }
            }
        }
    }
    val selected = confirm
    if (selected != null && token != null && selected in actions) AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text(strings.get(recoveryLabel(selected))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.get(when (selected) {
                    ChatRecoveryAction.RETRY -> R.string.chat_retry_warning
                    ChatRecoveryAction.READMIT -> R.string.checkpoint_readmit_warning
                    else -> R.string.checkpoint_summary_warning
                }))
                if (selected in setOf(ChatRecoveryAction.READMIT, ChatRecoveryAction.SUMMARIZE))
                    Text(strings.get(R.string.checkpoint_selected_window, requireNotNull(window.toLongOrNull())))
            }
        },
        confirmButton = { TextButton(onClick = {
            confirm = null
            recover(token, selected, window.toLongOrNull(), true)
        }) { Text(strings.get(R.string.checkpoint_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text(strings.get(R.string.checkpoint_cancel)) } },
    )
}
