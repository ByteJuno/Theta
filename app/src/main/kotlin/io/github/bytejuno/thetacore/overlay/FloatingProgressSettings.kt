package io.github.bytejuno.thetacore.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.settings.SettingsPage
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun FloatingProgressSettings(host: ThetaApplication, back: () -> Unit) {
    val runtime = host.floatingProgress
    val enabled by runtime.enabled.collectAsState()
    val permission by runtime.permission.collectAsState()
    val strings = uiStrings()
    val title = strings.get(R.string.floating_progress_title)
    var failed by remember { mutableStateOf(false) }
    SettingsPage(strings.get(R.string.floating_progress_title), back) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.get(R.string.floating_progress_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = enabled, onCheckedChange = runtime::setEnabled,
                modifier = Modifier.semantics { contentDescription = title })
        }
        Text(strings.get(R.string.floating_progress_description))
        Text(strings.get(if (permission) R.string.floating_progress_granted else R.string.floating_progress_fallback))
        if (enabled && !permission) {
            TextButton(onClick = {
                failed = !openFloatingPermissionSettings(host)
            }) { Text(strings.get(R.string.floating_progress_grant)) }
        }
        if (failed) Text(strings.get(R.string.floating_progress_settings_failed), color = MaterialTheme.colorScheme.error)
    }
}
