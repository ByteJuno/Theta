package io.github.bytejuno.thetacore

import android.Manifest
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
internal fun BackgroundDiagnostics() {
    val context = LocalContext.current
    val strings = uiStrings()
    var exempt by remember(context) { mutableStateOf<Boolean?>(null) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    var notificationsGranted by remember(context) { mutableStateOf(true) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = it
    }
    LifecycleResumeEffect(context) {
        notificationsGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        exempt = try {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: SecurityException) {
            null
        }
        onPauseOrDispose {}
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings.get(R.string.background_title), style = MaterialTheme.typography.titleMedium)
        Text(strings.get(when (exempt) {
            true -> R.string.background_exempt
            false -> R.string.background_optimized
            null -> R.string.background_unknown
        }))
        Text(strings.get(R.string.background_vendor_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = {
            settingsUnavailable = !context.openBackgroundSettings()
        }) { Text(strings.get(R.string.background_open_settings)) }
        if (settingsUnavailable) {
            Text(strings.get(R.string.background_manual_path), color = MaterialTheme.colorScheme.error)
        }
        if (!notificationsGranted) {
            Text(strings.get(R.string.chat_notifications_denied), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(strings.get(R.string.background_enable_notifications))
            }
        }
    }
}
