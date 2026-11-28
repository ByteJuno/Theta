package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.RunState
import io.github.bytejuno.thetacore.chat.markdown.MarkdownText

@Composable
internal fun ColumnScope.ChatReadingFlow(snapshot: ChatUiSnapshot, actions: ChatUiActions) {
    val strings = uiStrings()
    val state = snapshot.chat
    val stream = snapshot.stream
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, stream.isNotEmpty(), state.active, state.error, snapshot.draft.error, state.pending) {
        if (!state.showingOlder && listState.layoutInfo.totalItemsCount > 0)
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.personaUnavailable) item { Text(strings.get(R.string.chat_persona_unavailable)) }
        if (state.personaRecovered) item { Text(strings.get(R.string.chat_persona_recovered)) }
        if (state.older != null && !state.active && !state.pending) item {
            TextButton(onClick = { actions.older() }) { Text(strings.get(R.string.chat_older)) }
        }
        if (state.showingOlder) item {
            TextButton(onClick = { actions.latest() }) { Text(strings.get(R.string.chat_latest)) }
        }
        if (!state.loading && state.messages.isEmpty()) item {
            ChatWelcome(actions)
        }
        itemsIndexed(state.messages) { _, message ->
            ChatMessage(message, snapshot, actions)
        }

        if (state.journal.isNotEmpty() || state.olderJournal != null) item {
            Text(strings.get(R.string.chat_reply_archive), style = MaterialTheme.typography.titleSmall)
            Text(strings.get(R.string.chat_reply_archive_notice), style = MaterialTheme.typography.bodySmall)
            if (state.olderJournal != null && !state.active) TextButton(onClick = { actions.olderReplies() }) {
                Text(strings.get(R.string.chat_reply_older))
            }
        }
        itemsIndexed(state.journal) { _, reply ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.get(replyReason(reply.reason)), style = MaterialTheme.typography.labelLarge)
                reply.partial.parts.forEach { part -> when (part) {
                    is ContentPart.Text -> MarkdownText(part.text)
                    is ContentPart.Reasoning -> part.summary?.let { ChatReasoning(it) }
                    else -> Unit
                } }
            }
        }
        if (!state.active && !state.pending && (state.checkpoint != null || state.reconciliation != null ||
                state.result?.state in setOf(RunState.PAUSED, RunState.FAILED))) item {
            ChatRecoveryCard(state) { token, action, window, confirmed ->
                actions.recover(token, action, window, confirmed)
            }
        }
        if (stream.isNotEmpty()) item {
            Text(strings.get(R.string.chat_assistant), style = MaterialTheme.typography.labelLarge)
            MarkdownText(stream)
        }
        state.reasoningAdjustment?.let { adjustment -> item { ChatReasoningNotice(adjustment) } }
        if (state.notice != null || chatDiagnostic(state) != null) item {
            Column {
                state.notice?.let { Text(strings.get(it), style = MaterialTheme.typography.bodyMedium) }
                ChatDiagnostics(state)
            }
        }
        if (state.active) item { Text(strings.get(if (state.records.values.any { it.pending != null })
            R.string.chat_tool_approval else R.string.chat_running)) }
        state.error?.let { error -> item { Text(strings.get(error), color = MaterialTheme.colorScheme.error) } }
        if (state.pending && !state.active && state.records.values.none { it.pending != null }) item {
            TextButton(onClick = { actions.continueSavedStep() }) { Text(strings.get(R.string.chat_finish_saved)) }
        }
        snapshot.draft.error?.let { error -> item {
            Text(if (error == R.string.chat_image_limit) strings.get(error, MAX_USER_IMAGES_PER_MESSAGE) else strings.get(error),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } }
    }
}
