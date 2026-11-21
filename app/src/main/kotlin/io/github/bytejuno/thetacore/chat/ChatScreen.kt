package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.RunState
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiModelsClient
import io.github.bytejuno.thetacore.agent.provider.openai.ApiKeyCredentials
import io.github.bytejuno.thetacore.agent.provider.openai.ModelsRequestException
import kotlinx.coroutines.CancellationException
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
    val launchTask = rememberChatTaskLauncher(host)
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
    var deleting by remember { mutableStateOf(false) }
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
            TextButton(onClick = { deleting = true }, enabled = !state.active && !state.pending && !state.loading) {
                Text(strings.get(R.string.chat_delete_session))
            }
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
                        is ContentPart.ToolCall -> ToolCard(part, state.records[part.binding.callId], state.recoveries[part.binding.callId], owner, launchTask)
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
                ChatRecoveryCard(state) { token, action, window, confirmed ->
                    launchTask { owner.recover(token, action, window, confirmed) }
                }
            }
            if (stream.isNotEmpty()) item {
                Text(strings.get(R.string.chat_assistant), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(stream) }
            }
            if (state.active) item { Text(strings.get(if (state.records.values.any { it.pending != null })
                R.string.chat_tool_approval else R.string.chat_running)) }
        }
        if (state.pending && !state.active && state.records.values.none { it.pending != null }) {
            TextButton(onClick = { launchTask { owner.continueSavedStep() } }) { Text(strings.get(R.string.chat_finish_saved)) }
        }
        if (state.active || state.pending) TextButton(onClick = { owner.stop() }) { Text(strings.get(R.string.chat_stop)) }
        OutlinedTextField(value = input, onValueChange = { if (it.length <= 32_000) input = it },
            label = { Text(strings.get(R.string.chat_message)) }, modifier = Modifier.fillMaxWidth(),
            minLines = 1, maxLines = 5, enabled = !state.loading && !state.active && !state.pending)
        Button(onClick = { val text = input; launchTask { owner.send(text) } }, enabled = input.isNotBlank() && !state.loading && !state.active && !state.pending,
            modifier = Modifier.fillMaxWidth()) { Text(strings.get(R.string.chat_send)) }
    }
    if (settings) ProviderScreen(host) { settings = false }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(strings.get(R.string.chat_delete_session)) },
        text = { Text(strings.get(R.string.chat_delete_session_confirm)) },
        confirmButton = { TextButton(onClick = { deleting = false; owner.deleteCurrentSession() }) {
            Text(strings.get(R.string.chat_delete_session))
        } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text(strings.get(R.string.chat_close)) } })
    if (sessions) AlertDialog(onDismissRequest = { sessions = false }, title = { Text(strings.get(R.string.chat_sessions)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(state.sessions, key = { _, entry -> entry.sessionId }) { index, entry ->
                    val current = entry.sessionId == state.sessionId
                    TextButton(onClick = { owner.select(entry.sessionId); sessions = false },
                        modifier = Modifier.fillMaxWidth().semantics { selected = current }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(entry.text ?: strings.get(R.string.chat_session_number, index + 1),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current) Text(strings.get(R.string.chat_current_session), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (state.moreSessionsAvailable) item {
                    TextButton(onClick = { owner.moreSessions() }) { Text(strings.get(R.string.chat_more_sessions)) }
                }
            }
        }, confirmButton = { TextButton(onClick = { sessions = false }) { Text(strings.get(R.string.chat_close)) } })
}

@Composable
private fun ToolCard(call: ContentPart.ToolCall, record: InvocationRecord?, recovery: RecoveryRequest?, owner: ChatSessionOwner,
    launchTask: (() -> Unit) -> Unit) {
    val strings = uiStrings()
    var expanded by rememberSaveable(call.binding.callId) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val imageCall = record?.input?.definition?.toolKey == IMAGE_TOOL_KEY
            Text(if (imageCall) strings.get(R.string.chat_image_title) else call.name, style = MaterialTheme.typography.titleSmall)
            Text(strings.get(toolState(record))) // never hidden by collapsing details
            if (imageCall && record != null) {
                record.result?.let { Text(strings.get(imageResultLabel(it.text))) }
                if (record.state.terminal) ChatImage(record, owner)
            }
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
                    Button(onClick = { launchTask { owner.decide(record.callId, approval.id, Decision.ALLOW_ONCE) } }) { Text(strings.get(R.string.chat_allow)) }
                    TextButton(onClick = { launchTask { owner.decide(record.callId, approval.id, Decision.DENY) } }) { Text(strings.get(R.string.chat_deny)) }
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
                    SelectionContainer { Text(if (imageCall) strings.get(imageResultLabel(it.text)) else it.text) }
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
    var imageProfile by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val modelLists = remember(scope) {
        val client = OpenAiModelsClient()
        ProviderModels(scope) { url, key -> client.fetch(url, ApiKeyCredentials(key)) }
    }
    val discovery by modelLists.state.collectAsState()
    DisposableEffect(modelLists) { onDispose { modelLists.invalidate() } }
    fun resetModels() { modelLists.invalidate(); filter = "" }
    AlertDialog(onDismissRequest = { if (!busy) { resetModels(); onClose() } }, title = { Text(strings.get(R.string.chat_provider)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.get(R.string.chat_provider_notice))
            FlowRow {
                TextButton(enabled = !busy, onClick = { resetModels(); imageProfile = false; endpoint = ""; model = ""; secret = ""; error = false }) {
                    Text(strings.get(R.string.chat_text_profile))
                }
                TextButton(enabled = !busy, onClick = { resetModels(); imageProfile = true; endpoint = ""; model = ""; secret = ""; error = false }) {
                    Text(strings.get(R.string.chat_image_profile))
                }
            }
            Text(strings.get(if (imageProfile) R.string.chat_image_profile else R.string.chat_text_profile),
                style = MaterialTheme.typography.titleMedium)
            if (imageProfile) Text(strings.get(R.string.chat_image_settings_notice))
            OutlinedTextField(endpoint, { resetModels(); endpoint = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), label = { Text(strings.get(R.string.chat_endpoint)) })
            OutlinedTextField(secret, { resetModels(); secret = it }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), label = { Text(strings.get(R.string.chat_key)) }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.get(R.string.chat_model)) })
            Text(strings.get(R.string.chat_models_manual), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy && !discovery.loading && endpoint.isNotBlank() && secret.isNotBlank(),
                onClick = { filter = ""; modelLists.load(endpoint.trim(), secret) }) {
                Text(strings.get(if (discovery.loading) R.string.chat_models_loading else R.string.chat_models_fetch))
            }
            discovery.failure?.let { failure ->
                Text(strings.get(when (failure) {
                    ModelsRequestException.Failure.INVALID_SETTINGS -> R.string.chat_models_invalid_settings
                    ModelsRequestException.Failure.HTTP -> R.string.chat_models_http_failed
                    ModelsRequestException.Failure.TIMEOUT -> R.string.chat_models_timeout
                    ModelsRequestException.Failure.NETWORK -> R.string.chat_models_network_failed
                    ModelsRequestException.Failure.TOO_LARGE -> R.string.chat_models_too_large
                    ModelsRequestException.Failure.INVALID_RESPONSE -> R.string.chat_models_invalid_response
                }), color = MaterialTheme.colorScheme.error)
            }
            discovery.models?.let { models ->
                if (models.isEmpty()) Text(strings.get(R.string.chat_models_empty))
                else {
                    OutlinedTextField(filter, { filter = it }, enabled = !busy, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), label = { Text(strings.get(R.string.chat_models_filter)) })
                    val matches = remember(models, filter) { models.filter { it.contains(filter, ignoreCase = true) } }
                    if (matches.isEmpty()) Text(strings.get(R.string.chat_models_no_matches))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        items(matches, key = { it }) { name ->
                            TextButton(enabled = !busy, onClick = { model = name },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            if (error) Text(strings.get(R.string.chat_provider_failed), color = MaterialTheme.colorScheme.error)
            AppLanguageSetting()
        }
    }, confirmButton = {
        Button(enabled = !busy && endpoint.isNotBlank() && model.isNotBlank() && secret.isNotBlank(), onClick = {
            resetModels(); busy = true; error = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val store = if (imageProfile) host.imageSettings else host.providerSettings
                        store.replace(OpenAiProviderSettings(if (imageProfile) IMAGE_PROVIDER_ID else CHAT_PROVIDER_ID, endpoint.trim(), model.trim()), secret)
                    }
                    secret = ""; onClose()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            }
        }) { Text(strings.get(R.string.chat_save)) }
    }, dismissButton = { TextButton(onClick = { resetModels(); onClose() }, enabled = !busy) { Text(strings.get(R.string.chat_close)) } })
}
