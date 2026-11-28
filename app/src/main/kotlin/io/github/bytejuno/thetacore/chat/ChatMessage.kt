package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.chat.markdown.MarkdownText
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens

@Composable
internal fun ChatMessage(message: Message, snapshot: ChatUiSnapshot, actions: ChatUiActions) {
    val tokens = LocalThetaThemeTokens.current
    val state = snapshot.chat
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(tokens.smallGap)) {
            message.parts.forEach { part -> when (part) {
                is ContentPart.Text -> if (message.role == Role.USER) SelectionContainer { Text(part.text) } else MarkdownText(part.text)
                is ContentPart.Media -> UserChatImage(part.blob, state.sessionId, actions.userImage)
                is ContentPart.Reasoning -> part.summary?.let { ChatReasoning(it) }
                is ContentPart.ToolCall -> ToolCard(part, state.records[part.binding.callId], state.toolProgress[part.binding.callId],
                    state.toolInputs[part.binding.callId], state.recoveries[part.binding.callId], snapshot.approval.allowRootLongTerm, actions)
                is ContentPart.ToolResult -> Unit
            } }
        }
    }
    if (message.role == Role.USER) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = tokens.userBubbleContainer, contentColor = tokens.onUserBubble, shape = tokens.capsule,
            modifier = Modifier.widthIn(max = 560.dp).padding(start = 24.dp)) {
            Box(Modifier.padding(tokens.contentGap)) { body() }
        }
    } else Box(Modifier.fillMaxWidth()) { body() }
}
@Composable
internal fun ChatReasoning(summary: String) {
    val strings = uiStrings()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        SuggestionChip(onClick = { expanded = !expanded }, label = { Text(strings.get(
            if (expanded) R.string.chat_hide_reasoning else R.string.chat_show_reasoning)) })
        if (expanded) SelectionContainer { Text(summary, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable
internal fun ChatWelcome(actions: ChatUiActions) {
    val strings = uiStrings()
    val tokens = LocalThetaThemeTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = tokens.pageInset), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.smallGap)) {
        Image(painterResource(R.drawable.ic_theta_mark), null, Modifier.size(72.dp))
        Text(strings.get(R.string.chat_welcome), style = MaterialTheme.typography.headlineSmall)
        Text(strings.get(R.string.chat_welcome_hint), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(tokens.smallGap)) {
            SuggestionChip(onClick = actions.providers, label = { Text(strings.get(R.string.chat_provider)) })
            SuggestionChip(onClick = actions.plugins, label = { Text(strings.get(R.string.chat_explore_tools)) })
            SuggestionChip(onClick = actions.gallery, label = { Text(strings.get(R.string.chat_image_add_photo)) })
        }
    }
}
