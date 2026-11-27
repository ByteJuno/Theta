package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun ChatToolbar(
    compact: Boolean,
    state: ChatState,
    modelLabel: String?,
    onModel: () -> Unit,
    onPlugins: () -> Unit,
    onSettings: () -> Unit,
    onApproval: () -> Unit,
    onSessions: () -> Unit,
    onNew: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = uiStrings()
    val model: @Composable () -> Unit = {
        OutlinedButton(onClick = onModel, enabled = !state.active && !state.loading) {
            Text(modelLabel ?: strings.get(R.string.chat_profile_unconfigured),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val actions: @Composable () -> Unit = {
        TextButton(onClick = onPlugins) { Text(strings.get(R.string.chat_plugins)) }
        TextButton(onClick = onSettings) { Text(strings.get(R.string.chat_provider)) }
        TextButton(onClick = onApproval) { Text(strings.get(R.string.chat_approval_settings)) }
        TextButton(onClick = onSessions, enabled = !state.active && !state.loading) { Text(strings.get(R.string.chat_sessions)) }
        TextButton(onClick = onNew, enabled = !state.active && !state.loading) { Text(strings.get(R.string.chat_new)) }
        TextButton(onClick = onDelete, enabled = !state.active && !state.pending && !state.loading) {
            Text(strings.get(R.string.chat_delete_session))
        }
    }
    if (compact) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.get(R.string.chat_title), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.widthIn(max = 200.dp)) { model() }
            actions()
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.get(R.string.chat_title), style = MaterialTheme.typography.headlineMedium)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { model() }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
    }
}

@Composable
internal fun ChatFullAutoBanner(compact: Boolean, onApproval: () -> Unit, onBalanced: () -> Unit) {
    val strings = uiStrings()
    Surface(onClick = onApproval, color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()) {
        val actions: @Composable () -> Unit = {
            TextButton(onClick = onBalanced) { Text(strings.get(R.string.chat_full_auto_switch_balanced)) }
            TextButton(onClick = onApproval) { Text(strings.get(R.string.chat_approval_settings)) }
        }
        if (compact) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.get(R.string.chat_full_auto_banner), style = MaterialTheme.typography.labelLarge)
                actions()
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.get(R.string.chat_full_auto_banner), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
            }
        }
    }
}
