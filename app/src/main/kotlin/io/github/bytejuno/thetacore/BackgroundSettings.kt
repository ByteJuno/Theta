package io.github.bytejuno.thetacore

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

internal fun Context.openBackgroundSettings(): Boolean = openBackgroundSettings { action ->
    val intent = Intent(action)
    if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
        intent.data = Uri.fromParts("package", packageName, null)
    }
    startActivity(intent)
}

internal fun openBackgroundSettings(launch: (String) -> Unit): Boolean {
    for (action in listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) {
        try {
            launch(action)
            return true
        } catch (_: ActivityNotFoundException) {
            continue
        } catch (_: SecurityException) {
            continue
        }
    }
    return false
}
