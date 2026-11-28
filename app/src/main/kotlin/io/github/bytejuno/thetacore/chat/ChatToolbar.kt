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
