package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

internal data class ChatComposerAvailability(val edit: Boolean, val attach: Boolean, val send: Boolean, val stop: Boolean)

internal fun chatComposerAvailability(state: ChatState, savingModel: Boolean, draft: ChatComposerDraft): ChatComposerAvailability {
    return chatComposerAvailability(state, savingModel, ChatDraftSnapshot(draft.input, draft.images, draft.error, draft.documents, draft.importing))
}
internal fun chatComposerAvailability(state: ChatState, savingModel: Boolean, draft: ChatDraftSnapshot): ChatComposerAvailability {
    val edit = !state.loading && !state.active && !state.pending && !savingModel && !draft.importing
    return ChatComposerAvailability(edit, edit && (draft.images.size < MAX_USER_IMAGES_PER_MESSAGE || draft.documents.size < io.github.bytejuno.thetacore.agent.ledger.DocumentLimits.PER_MESSAGE),
        edit && state.sessionId != null && (draft.text.isNotBlank() || draft.images.isNotEmpty() || draft.documents.isNotEmpty()), state.active || state.pending)
}

@Composable
internal fun ChatComposerSurface(compact: Boolean, snapshot: ChatUiSnapshot, actions: ChatUiActions) {
    val strings = uiStrings()
    val tokens = io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens.current
    val draft = snapshot.draft
    val state = snapshot.chat
    val available = chatComposerAvailability(state, snapshot.models.saving || snapshot.persona.busy, draft)
    val edit = available.edit
    val attach = available.attach
    val stop = available.stop
    val send = available.send
    var attachments by remember { mutableStateOf(false) }
    Surface(color = tokens.composerContainer, shape = tokens.capsule, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(tokens.smallGap)) {
            if (draft.images.isNotEmpty()) LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(tokens.smallGap)) {
                items(draft.images, key = { it.localId }) { image ->
                    PendingImagePreview(image, edit, compact = true, onRemove = { actions.removeImage(image.localId) })
                }
            }
            DocumentDraftPreview(draft, edit, actions.removeDocument)
            // One input composition location in portrait, landscape, split screen and with IME.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachments = true }, enabled = attach) {
                        Icon(painterResource(R.drawable.ic_chat_attach), strings.get(R.string.chat_add_attachment))
                    }
                    DropdownMenu(expanded = attachments && attach, onDismissRequest = { attachments = false }) {
                        DropdownMenuItem(text = { Text(strings.get(R.string.chat_image_add_photo)) },
                            enabled = draft.images.size < MAX_USER_IMAGES_PER_MESSAGE, onClick = { attachments = false; actions.gallery() })
                        DropdownMenuItem(text = { Text(strings.get(R.string.chat_image_take_photo)) },
                            enabled = draft.images.size < MAX_USER_IMAGES_PER_MESSAGE, onClick = { attachments = false; actions.camera() })
                        DropdownMenuItem(text = { Text(strings.get(R.string.document_choose)) },
                            enabled = draft.documents.size < io.github.bytejuno.thetacore.agent.ledger.DocumentLimits.PER_MESSAGE,
                            onClick = { attachments = false; actions.files() })
                    }
                }
                TextField(draft.text, actions.edit, Modifier.weight(1f), enabled = edit,
                    placeholder = { Text(strings.get(R.string.chat_message)) }, minLines = 1, maxLines = if (compact) 2 else 5,
                    colors = TextFieldDefaults.colors(focusedContainerColor = tokens.composerContainer,
                        unfocusedContainerColor = tokens.composerContainer, disabledContainerColor = tokens.composerContainer,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                FilledIconButton(onClick = if (stop) actions.stop else actions.send, enabled = stop || send) {
                    Icon(painterResource(if (stop) R.drawable.ic_chat_stop else R.drawable.ic_chat_send),
                        strings.get(if (stop) R.string.chat_stop else R.string.chat_send))
                }
            }
        }
    }
}
