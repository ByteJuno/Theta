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
    val edit = !state.loading && !state.active && !state.pending && !savingModel
    return ChatComposerAvailability(edit, edit && draft.images.size < MAX_USER_IMAGES_PER_MESSAGE,
        edit && state.sessionId != null && (draft.input.isNotBlank() || draft.images.isNotEmpty()), state.active || state.pending)
}

@Composable
internal fun ChatComposer(
    compact: Boolean,
    state: ChatState,
    savingModel: Boolean,
    draft: ChatComposerDraft,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val strings = uiStrings()
    val available = chatComposerAvailability(state, savingModel, draft)
    var attachments by remember { mutableStateOf(false) }
    if (draft.images.isNotEmpty()) {
        if (!compact) Text(strings.get(R.string.chat_image_pending_count, draft.images.size, MAX_USER_IMAGES_PER_MESSAGE),
            style = MaterialTheme.typography.labelMedium)
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(draft.images, key = { it.localId }) { image ->
                PendingImagePreview(image, available.edit, compact = compact, onRemove = {
                    draft.images = draft.images.filterNot { it.localId == image.localId }
                    draft.error = null
                })
            }
        }
    }
    val input: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(value = draft.input, onValueChange = draft::edit,
            label = { Text(strings.get(R.string.chat_message)) }, modifier = modifier,
            minLines = 1, maxLines = if (compact) 1 else 5, enabled = available.edit)
    }
    if (compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { attachments = true }, enabled = available.attach) {
                    Icon(painterResource(R.drawable.ic_chat_attach), strings.get(R.string.chat_add_attachment))
                }
                DropdownMenu(expanded = attachments && available.attach, onDismissRequest = { attachments = false }) {
                    DropdownMenuItem(text = { Text(strings.get(R.string.chat_image_add_photo)) },
                        onClick = { attachments = false; onGallery() })
                    DropdownMenuItem(text = { Text(strings.get(R.string.chat_image_take_photo)) },
                        onClick = { attachments = false; onCamera() })
                }
            }
            input(Modifier.weight(1f))
            FilledIconButton(onClick = if (available.stop) onStop else onSend, enabled = available.stop || available.send) {
                Icon(painterResource(if (available.stop) R.drawable.ic_chat_stop else R.drawable.ic_chat_send),
                    strings.get(if (available.stop) R.string.chat_stop else R.string.chat_send))
            }
        }
    } else {
        if (available.stop) TextButton(onClick = onStop) { Text(strings.get(R.string.chat_stop)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGallery, enabled = available.attach) { Text(strings.get(R.string.chat_image_add_photo)) }
            OutlinedButton(onClick = onCamera, enabled = available.attach) { Text(strings.get(R.string.chat_image_take_photo)) }
        }
        input(Modifier.fillMaxWidth())
        Button(onClick = onSend, enabled = available.send, modifier = Modifier.fillMaxWidth()) { Text(strings.get(R.string.chat_send)) }
    }
}
