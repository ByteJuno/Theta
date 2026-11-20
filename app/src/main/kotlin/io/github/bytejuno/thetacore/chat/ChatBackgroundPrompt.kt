package io.github.bytejuno.thetacore.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun rememberChatTaskLauncher(host: ThetaApplication): (() -> Unit) -> Unit {
    val strings = uiStrings()
    val preferences = remember(host) { ChatBackgroundPreferences(host) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showBattery by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    fun execute() {
        val action = pending
        pending = null
        action?.invoke()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) execute() else permissionDenied = true
    }
    fun notificationsThenExecute() {
        if (preferences.requestNotificationPrompt(
            host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else execute()
    }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsThenExecute()
    }
    if (showBattery) AlertDialog(
        onDismissRequest = { showBattery = false; pending = null },
        title = { Text(strings.get(R.string.chat_background_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(strings.get(R.string.chat_background_explanation))
            if (settingsFailed) Text(strings.get(R.string.chat_background_settings_failed))
        } },
        confirmButton = { TextButton(onClick = {
            try {
                battery.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                showBattery = false
            } catch (_: android.content.ActivityNotFoundException) { settingsFailed = true }
        }) { Text(strings.get(R.string.chat_background_settings)) } },
        dismissButton = { TextButton(onClick = { showBattery = false; notificationsThenExecute() }) {
            Text(strings.get(R.string.chat_background_continue))
        } },
    )
    if (permissionDenied) AlertDialog(
        onDismissRequest = { permissionDenied = false; pending = null },
        title = { Text(strings.get(R.string.chat_notifications_title)) },
        text = { Text(strings.get(R.string.chat_notifications_denied)) },
        confirmButton = { TextButton(onClick = { permissionDenied = false; execute() }) {
            Text(strings.get(R.string.chat_background_continue))
        } },
    )
    return { action ->
        if (pending == null) {
            pending = action
            val power = host.getSystemService(PowerManager::class.java)
            if (preferences.requestBatteryPrompt(power.isIgnoringBatteryOptimizations(host.packageName))) {
                showBattery = true
            } else notificationsThenExecute()
        }
    }
}
