package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.RunState
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@Composable
internal fun ChatScreen(host: ThetaApplication, onPlugins: () -> Unit) {
    val strings = uiStrings()
    val owner = remember(host) { host.chat }
    val state by owner.state.collectAsState()
    var input by rememberSaveable(state.sessionId) { mutableStateOf("") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var stream by remember { mutableStateOf("") }
    LaunchedEffect(state.active) {
        if (state.active) while (true) withFrameNanos { stream = owner.previewText() }
        else stream = ""
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, stream.isNotEmpty(), state.active) {
        if (!state.showingOlder && listState.layoutInfo.totalItemsCount > 0)
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    var sessions by remember { mutableStateOf(false) }
    LaunchedEffect(owner) { owner.open() }
    LaunchedEffect(state.acceptedMessages) { if (state.acceptedMessages > 0) input = "" }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.get(R.string.chat_title), style = MaterialTheme.typography.headlineMedium)
        // Wrapping actions keep navigation available at large font scales and narrow widths.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPlugins) { Text(strings.get(R.string.chat_plugins)) }
            TextButton(onClick = { settings = true }) { Text(strings.get(R.string.chat_provider)) }
            TextButton(onClick = { sessions = true }, enabled = !state.active && !state.loading) { Text(strings.get(R.string.chat_sessions)) }
            TextButton(onClick = { owner.newSession() }, enabled = !state.active && !state.loading) { Text(strings.get(R.string.chat_new)) }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(strings.get(it), style = MaterialTheme.typography.bodyMedium) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.older != null && !state.active && !state.pending) item {
                TextButton(onClick = { owner.older() }) { Text(strings.get(R.string.chat_older)) }
            }
            if (state.showingOlder) item {
                TextButton(onClick = { owner.latest() }) { Text(strings.get(R.string.chat_latest)) }
            }
            if (!state.loading && state.messages.isEmpty()) item {
                Text(strings.get(R.string.chat_empty), style = MaterialTheme.typography.bodyLarge)
            }
            itemsIndexed(state.messages) { _, message ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (message.role != Role.TOOL) Text(strings.get(if (message.role == Role.USER) R.string.chat_you else R.string.chat_assistant),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    message.parts.forEach { part -> when (part) {
                        is ContentPart.Text -> SelectionContainer { Text(part.text) }
                        is ContentPart.Reasoning -> part.summary?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        is ContentPart.ToolCall -> ToolCard(part, state.records[part.binding.callId], state.recoveries[part.binding.callId], owner)
                        is ContentPart.ToolResult -> Unit // result lives inside the associated tool row
                    } }
                }
            }
            if (state.journal.isNotEmpty() || state.olderJournal != null) item {
                Text(strings.get(R.string.chat_reply_archive), style = MaterialTheme.typography.titleSmall)
                Text(strings.get(R.string.chat_reply_archive_notice), style = MaterialTheme.typography.bodySmall)
                if (state.olderJournal != null && !state.active) TextButton(onClick = { owner.olderReplies() }) {
                    Text(strings.get(R.string.chat_reply_older))
                }
            }
            itemsIndexed(state.journal) { _, reply ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.get(replyReason(reply.reason)), style = MaterialTheme.typography.labelLarge)
                    reply.partial.parts.forEach { part -> when (part) {
                        is ContentPart.Text -> SelectionContainer { Text(part.text) }
                        is ContentPart.Reasoning -> part.summary?.let { SelectionContainer { Text(it) } }
                        else -> Unit
                    } }
                }
            }
            if (!state.active && !state.pending && (state.checkpoint != null || state.reconciliation != null ||
                    state.result?.state in setOf(RunState.PAUSED, RunState.FAILED))) item {
                ChatRecoveryCard(state) { token, action, window, confirmed -> owner.recover(token, action, window, confirmed) }
            }
            if (stream.isNotEmpty()) item {
                Text(strings.get(R.string.chat_assistant), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(stream) }
            }
            if (state.active) item { Text(strings.get(if (state.records.values.any { it.pending != null })
                R.string.chat_tool_approval else R.string.chat_running)) }
        }
        if (state.pending && !state.active && state.records.values.none { it.pending != null }) {
            TextButton(onClick = { owner.continueSavedStep() }) { Text(strings.get(R.string.chat_finish_saved)) }
        }
        if (state.active || state.pending) TextButton(onClick = { owner.stop() }) { Text(strings.get(R.string.chat_stop)) }
        OutlinedTextField(value = input, onValueChange = { if (it.length <= 32_000) input = it },
            label = { Text(strings.get(R.string.chat_message)) }, modifier = Modifier.fillMaxWidth(),
            minLines = 1, maxLines = 5, enabled = !state.loading && !state.active && !state.pending)
        Button(onClick = { owner.send(input) }, enabled = input.isNotBlank() && !state.loading && !state.active && !state.pending,
            modifier = Modifier.fillMaxWidth()) { Text(strings.get(R.string.chat_send)) }
    }
    if (settings) ProviderScreen(host) { settings = false }
    if (sessions) AlertDialog(onDismissRequest = { sessions = false }, title = { Text(strings.get(R.string.chat_sessions)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(state.sessions) { index, id ->
                    TextButton(onClick = { owner.select(id); sessions = false }) {
                        Text(strings.get(R.string.chat_session_number, index + 1))
                    }
                }
                item { TextButton(onClick = { owner.moreSessions() }) { Text(strings.get(R.string.chat_more_sessions)) } }
            }
        }, confirmButton = { TextButton(onClick = { sessions = false }) { Text(strings.get(R.string.chat_close)) } })
}

@Composable
private fun ToolCard(call: ContentPart.ToolCall, record: InvocationRecord?, recovery: RecoveryRequest?, owner: ChatSessionOwner) {
    val strings = uiStrings()
    var expanded by rememberSaveable(call.binding.callId) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(call.name, style = MaterialTheme.typography.titleSmall)
            Text(strings.get(toolState(record))) // never hidden by collapsing details
            if (record != null && showApproval(record)) {
                val approval = requireNotNull(record.pending)
                Text(strings.get(R.string.chat_approval_warning))
                Text(strings.get(when (approval.risk) {
                    Risk.READ -> R.string.chat_risk_read
                    Risk.WRITE -> R.string.chat_risk_write
                    Risk.DEVICE_CONTROL -> R.string.chat_risk_device
                    Risk.ROOT -> R.string.chat_risk_root
                }))
                SelectionContainer { Text(approval.summary.ifBlank { call.argsJson }) }
                if (approval.unknownCalls.isNotEmpty()) Text(strings.get(R.string.chat_unknown_warning), color = MaterialTheme.colorScheme.error)
                FlowRow {
                    Button(onClick = { owner.decide(record.callId, approval.id, Decision.ALLOW_ONCE) }) { Text(strings.get(R.string.chat_allow)) }
                    TextButton(onClick = { owner.decide(record.callId, approval.id, Decision.DENY) }) { Text(strings.get(R.string.chat_deny)) }
                }
            }
            if (recovery != null && record != null && !record.input.unattended) {
                Text(strings.get(R.string.chat_recovery_warning))
                FlowRow {
                    TextButton(onClick = { owner.recover(record.callId, recovery.id, RecoveryResolution.VERIFIED_CLEAR) }) {
                        Text(strings.get(R.string.chat_verified_clear))
                    }
                    TextButton(onClick = { owner.recover(record.callId, recovery.id, RecoveryResolution.ACCEPTED_UNCERTAINTY) }) {
                        Text(strings.get(R.string.chat_accept_unknown))
                    }
                }
            }
            TextButton(onClick = { expanded = !expanded }) { Text(strings.get(if (expanded) R.string.chat_collapse else R.string.chat_expand)) }
            if (expanded) {
                Text(strings.get(R.string.chat_arguments), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(call.argsJson) }
                record?.result?.let {
                    Text(strings.get(R.string.chat_result), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(it.text) }
                }
            }
        }
    }
}

@Composable
private fun ProviderScreen(host: ThetaApplication, onClose: () -> Unit) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    // Credentials intentionally never use rememberSaveable or a persisted UI state.
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(strings.get(R.string.chat_provider)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.get(R.string.chat_provider_notice))
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text(strings.get(R.string.chat_endpoint)) })
            OutlinedTextField(model, { model = it }, label = { Text(strings.get(R.string.chat_model)) })
            OutlinedTextField(secret, { secret = it }, label = { Text(strings.get(R.string.chat_key)) }, visualTransformation = PasswordVisualTransformation())
            if (error) Text(strings.get(R.string.chat_provider_failed), color = MaterialTheme.colorScheme.error)
            AppLanguageSetting()
        }
    }, confirmButton = {
        Button(enabled = !busy && endpoint.isNotBlank() && model.isNotBlank() && secret.isNotBlank(), onClick = {
            busy = true; error = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { host.providerSettings.replace(OpenAiProviderSettings(CHAT_PROVIDER_ID, endpoint.trim(), model.trim()), secret) }
                    secret = ""; onClose()
                } catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(strings.get(R.string.chat_save)) }
    }, dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(strings.get(R.string.chat_close)) } })
}
