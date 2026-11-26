package io.github.bytejuno.thetacore.chat

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication

@Composable
internal fun rememberChatTaskLauncher(host: ThetaApplication): (() -> Unit) -> Unit {
    val preferences = remember(host) { ChatBackgroundPreferences(host) }
    return { action ->
        if (preferences.takeBackgroundHint()) {
            Toast.makeText(host, R.string.background_first_task_hint, Toast.LENGTH_LONG).show()
        }
        action()
    }
}
