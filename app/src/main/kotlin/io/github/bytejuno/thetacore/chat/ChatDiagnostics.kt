package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun ChatDiagnostics(state: ChatState) {
    val diagnostic = chatDiagnostic(state) ?: return
    val strings = uiStrings()
    // Do not restore expanded state or raw reasons from saved instance state. Switching sessions,
    // a new input or a changed result always starts collapsed, including equal results in two sessions.
    var expanded by remember(state.sessionId, state.acceptedMessages, state.result) { mutableStateOf(false) }
    val description = strings.get(if (expanded) R.string.chat_diagnostic_expanded else R.string.chat_diagnostic_collapsed)
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = description }) {
            Text(strings.get(if (expanded) R.string.chat_diagnostic_hide else R.string.chat_diagnostic_details))
        }
        if (expanded) SelectionContainer {
            Text(strings.get(diagnostic), style = MaterialTheme.typography.bodySmall)
        }
    }
}
