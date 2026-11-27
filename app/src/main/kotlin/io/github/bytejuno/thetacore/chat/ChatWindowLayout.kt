package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Use the space remaining after IME/insets, not orientation: split windows need this too.
internal fun compactChatWindow(availableHeightDp: Float, fontScale: Float): Boolean =
    availableHeightDp < 480f * fontScale.coerceAtLeast(1f)

@Composable
internal fun ChatWindowLayout(content: @Composable ColumnScope.(Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val compact = compactChatWindow(maxHeight.value, LocalDensity.current.fontScale)
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) { content(compact) }
    }
}
