package io.github.bytejuno.thetacore.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.RunState
import io.github.bytejuno.thetacore.plugin.PluginToolProgress
import io.github.bytejuno.thetacore.plugin.ToolInputRequest
import io.github.bytejuno.thetacore.agent.mcp.MCP_PROGRESS_RUNNING
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
internal fun ChatScreen(host: ThetaApplication, drafts: ChatComposerDrafts, resumed: Boolean = true, onPlugins: () -> Unit) {
    val strings = uiStrings()
    val context = LocalContext.current
    val owner = remember(host) { host.chat }
    val launchTask = rememberChatTaskLauncher(host)
    val state by owner.state.collectAsState()
    val composer = drafts[state.sessionId]
    var pickerSession by rememberSaveable { mutableStateOf<String?>(null) }
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
    var profilePicker by remember { mutableStateOf(false) }
    var approvalDialog by rememberSaveable { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    val modelSelection = io.github.bytejuno.thetacore.provider.rememberChatModelSelection(host.providerSettings)
    val modelState by modelSelection.state.collectAsState()
    val approvalSettings by host.approvalSettings.state.collectAsState()
    LaunchedEffect(host.approvalSettings) {
        withContext(Dispatchers.IO) { runCatching { host.approvalSettings.ensureLoaded() } }
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_USER_IMAGES_PER_MESSAGE),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val id = pickerSession
        uiScope.launch {
            val target = drafts.pickerTarget(id) { owner.open().join() } ?: return@launch
            target.error = null
            val remaining = MAX_USER_IMAGES_PER_MESSAGE - target.images.size
            if (remaining <= 0) {
                target.error = R.string.chat_image_limit
                return@launch
            }
            val selected = uris.take(remaining)
            if (uris.size > remaining) target.error = R.string.chat_image_limit
            val loaded = withContext(Dispatchers.IO) {
                val added = mutableListOf<PendingUserImage>()
                var invalid = false
                for (uri in selected) {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { readBoundedImageStream(it) }
                            ?: throw IllegalArgumentException()
                        val valid = validateAndDecodeAndroidUserImage(bytes)
                        added += PendingUserImage(mimeType = valid.mimeType, bytes = valid.bytes)
                    } catch (_: Exception) {
                        invalid = true
                    }
                }
                added to invalid
            }
            if (loaded.first.isNotEmpty() && drafts.isLiveDraft(id, target)) {
                target.images = (target.images + loaded.first).take(MAX_USER_IMAGES_PER_MESSAGE)
            }
            if (loaded.second && drafts.isLiveDraft(id, target)) target.error = R.string.chat_image_invalid
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        val id = pickerSession
        uiScope.launch {
            val target = drafts.pickerTarget(id) { owner.open().join() } ?: return@launch
            target.error = null
            if (target.images.size >= MAX_USER_IMAGES_PER_MESSAGE) {
                target.error = R.string.chat_image_limit
                return@launch
            }
            val captured = withContext(Dispatchers.IO) {
                runCatching { encodeCameraBitmapToPng(bitmap) }.getOrNull()
            }
            if (captured != null && drafts.isLiveDraft(id, target)) {
                target.images = (target.images + PendingUserImage(mimeType = captured.mimeType, bytes = captured.bytes))
                    .take(MAX_USER_IMAGES_PER_MESSAGE)
            } else if (captured == null && drafts.isLiveDraft(id, target)) {
                target.error = R.string.chat_image_invalid
            }
        }
    }
    LaunchedEffect(owner) { owner.open() }
    LaunchedEffect(drafts, state.sessionId, state.acceptedMessages, state.acceptedDraft, state.draftSync, resumed) {
        reconcileDraftsAsync(drafts, state.sessionId, state.acceptedDraft)
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.get(R.string.chat_title), style = MaterialTheme.typography.headlineMedium)
            val activeProfile = modelState.catalog.model(modelState.catalog.bindings.activeChatModelRef)
            OutlinedButton(
                onClick = {
                    if (modelState.catalog.providers.isEmpty()) settings = true else profilePicker = true
                },
                enabled = !state.active && !state.loading,
            ) {
                Text(
                    text = activeProfile?.label ?: strings.get(R.string.chat_profile_unconfigured),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Wrapping actions keep navigation available at large font scales and narrow widths.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPlugins) { Text(strings.get(R.string.chat_plugins)) }
            TextButton(onClick = { settings = true }) { Text(strings.get(R.string.chat_provider)) }
            TextButton(onClick = { approvalDialog = true }) { Text(strings.get(R.string.chat_approval_settings)) }
            TextButton(onClick = { sessions = true }, enabled = !state.active && !state.loading) { Text(strings.get(R.string.chat_sessions)) }
            TextButton(onClick = { owner.newSession() }, enabled = !state.active && !state.loading) { Text(strings.get(R.string.chat_new)) }
            TextButton(onClick = { deleting = true }, enabled = !state.active && !state.pending && !state.loading) {
                Text(strings.get(R.string.chat_delete_session))
            }
        }
        if (approvalSettings.mode == ApprovalMode.FULL_AUTO) {
            Surface(
                onClick = { approvalDialog = true },
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.get(R.string.chat_full_auto_banner), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            uiScope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { host.approvalSettings.setMode(ApprovalMode.BALANCED) }
                                }
                            }
                        }) {
                            Text(strings.get(R.string.chat_full_auto_switch_balanced))
                        }
                        TextButton(onClick = { approvalDialog = true }) {
                            Text(strings.get(R.string.chat_approval_settings))
                        }
                    }
                }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
        if (state.personaUnavailable) Text(strings.get(R.string.chat_persona_unavailable))
        if (state.personaRecovered) Text(strings.get(R.string.chat_persona_recovered))
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
                        is ContentPart.Media -> UserChatImage(part.blob, state.sessionId, owner)
                        is ContentPart.Reasoning -> part.summary?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        is ContentPart.ToolCall -> ToolCard(
                            call = part,
                            record = state.records[part.binding.callId],
                            progress = state.toolProgress[part.binding.callId],
                            inputRequest = state.toolInputs[part.binding.callId],
                            recovery = state.recoveries[part.binding.callId],
                            allowRootLongTerm = approvalSettings.allowRootLongTerm,
                            owner = owner,
                            launchTask = launchTask,
                        )
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
            if (state.notice != null || chatDiagnostic(state) != null) item {
                Column {
                    state.notice?.let { Text(strings.get(it), style = MaterialTheme.typography.bodyMedium) }
                    ChatDiagnostics(state)
                }
            }
            if (state.active) item { Text(strings.get(if (state.records.values.any { it.pending != null })
                R.string.chat_tool_approval else R.string.chat_running)) }
        }
        if (state.pending && !state.active && state.records.values.none { it.pending != null }) {
            TextButton(onClick = { launchTask { owner.continueSavedStep() } }) { Text(strings.get(R.string.chat_finish_saved)) }
        }
        if (state.active || state.pending) TextButton(onClick = { owner.stop() }) { Text(strings.get(R.string.chat_stop)) }
        composer.error?.let { err ->
            val errText = if (err == R.string.chat_image_limit) strings.get(err, MAX_USER_IMAGES_PER_MESSAGE) else strings.get(err)
            Text(errText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (composer.images.isNotEmpty()) {
            Text(
                strings.get(R.string.chat_image_pending_count, composer.images.size, MAX_USER_IMAGES_PER_MESSAGE),
                style = MaterialTheme.typography.labelMedium,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(composer.images, key = { it.localId }) { item ->
                    PendingImagePreview(
                        item = item,
                        enabled = !state.loading && !state.active && !state.pending && !modelState.saving,
                        onRemove = {
                            composer.images = composer.images.filterNot { it.localId == item.localId }
                            composer.error = null
                        },
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !state.loading && !state.active && !state.pending && !modelState.saving && composer.images.size < MAX_USER_IMAGES_PER_MESSAGE,
                onClick = {
                    composer.error = null
                    pickerSession = state.sessionId
                    photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            ) {
                Text(strings.get(R.string.chat_image_add_photo))
            }
            OutlinedButton(
                enabled = !state.loading && !state.active && !state.pending && !modelState.saving && composer.images.size < MAX_USER_IMAGES_PER_MESSAGE,
                onClick = {
                    composer.error = null
                    pickerSession = state.sessionId
                    cameraLauncher.launch(null)
                },
            ) {
                Text(strings.get(R.string.chat_image_take_photo))
            }
        }
        OutlinedTextField(value = composer.input, onValueChange = composer::edit,
            label = { Text(strings.get(R.string.chat_message)) }, modifier = Modifier.fillMaxWidth(),
            minLines = 1, maxLines = 5, enabled = !state.loading && !state.active && !state.pending && !modelState.saving)
        Button(
            onClick = {
                val text = composer.input
                val images = composer.images
                composer.error = null
                val receipt = drafts.receipt(requireNotNull(state.sessionId))
                if (!modelState.saving) launchTask { owner.send(text, images, receipt) }
            },
            enabled = state.sessionId != null && (composer.input.isNotBlank() || composer.images.isNotEmpty()) && !state.loading && !state.active && !state.pending && !modelState.saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(strings.get(R.string.chat_send)) }
    }
    if (settings) ProviderScreen(host) { settings = false; owner.latest() }
    if (profilePicker) io.github.bytejuno.thetacore.provider.ChatModelPicker(modelSelection, !state.active && !state.pending && !state.loading,
        { profilePicker = false }, { profilePicker = false; settings = true })
    if (approvalDialog) ChatApprovalDialog(host) { approvalDialog = false }
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
private fun ToolCard(
    call: ContentPart.ToolCall,
    record: InvocationRecord?,
    progress: PluginToolProgress?,
    inputRequest: ToolInputRequest?,
    recovery: RecoveryRequest?,
    allowRootLongTerm: Boolean,
    owner: ChatSessionOwner,
    launchTask: (() -> Unit) -> Unit,
) {
    val strings = uiStrings()
    var expanded by rememberSaveable(call.binding.callId) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val imageCall = record?.input?.definition?.toolKey == IMAGE_TOOL_KEY
            Text(if (imageCall) strings.get(R.string.chat_image_title) else call.name, style = MaterialTheme.typography.titleSmall)
            Text(strings.get(toolState(record))) // never hidden by collapsing details
            if (record?.state == Status.RUNNING && progress != null) {
                if (progress.text.isNotBlank()) {
                    Text(if (record.input.definition?.source == Source.MCP && progress.text == MCP_PROGRESS_RUNNING)
                        strings.get(R.string.mcp_running) else progress.text, style = MaterialTheme.typography.bodySmall)
                }
                progress.fraction?.let { fraction ->
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (record?.state == Status.RUNNING && inputRequest != null) {
                ToolInputForm(inputRequest) { answer -> owner.respondInput(call.binding.callId, inputRequest.id, answer) }
            }
            if (imageCall && record != null) {
                imageResultLabel(record)?.let { Text(strings.get(it)) }
                if (shouldLoadChatImage(record)) ChatImage(record, owner)
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
                    Button(onClick = { launchTask { owner.decide(record.callId, approval.id, Decision.ALLOW_ONCE) } }) {
                        Text(strings.get(R.string.chat_allow))
                    }
                    if (canGrantMultiCall) {
                        OutlinedButton(onClick = { launchTask { owner.decide(record.callId, approval.id, Decision.ALLOW_SESSION) } }) {
                            Text(strings.get(R.string.chat_allow_session))
                        }
                        OutlinedButton(onClick = { launchTask { owner.decide(record.callId, approval.id, Decision.ALLOW_ALWAYS) } }) {
                            Text(strings.get(R.string.chat_allow_always))
                        }
                    }
                    TextButton(onClick = { launchTask { owner.decide(record.callId, approval.id, Decision.DENY) } }) {
                        Text(strings.get(R.string.chat_deny))
                    }
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
                    SelectionContainer { Text(if (imageCall) strings.get(imageResultLabel(record) ?: R.string.chat_image_failed) else it.text) }
                }
            }
        }
    }
}
