package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens
import io.github.bytejuno.thetacore.ui.theme.MessageStyle

@Composable
internal fun ChatAssistantBody(content: @Composable () -> Unit) {
    val tokens = LocalThetaThemeTokens.current
    if (tokens.messageStyle == MessageStyle.BUBBLE) {
        Surface(color = tokens.composerContainer, contentColor = tokens.assistantText, shape = tokens.capsule,
            modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(tokens.contentGap)) { content() }
        }
    } else CompositionLocalProvider(LocalContentColor provides tokens.assistantText) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun ChatThemeChip(onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    val tokens = LocalThetaThemeTokens.current
    SuggestionChip(onClick = onClick, label = label, modifier = modifier, shape = tokens.capsule,
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = tokens.capsuleContainer, labelColor = tokens.onCapsule))
}
