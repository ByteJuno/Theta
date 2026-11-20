package io.github.bytejuno.thetacore.chat

import android.content.Context

internal class ChatBackgroundPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("chat_background", Context.MODE_PRIVATE)
    fun requestBatteryPrompt(alreadyAllowed: Boolean): Boolean = request("battery_asked", alreadyAllowed)
    fun requestNotificationPrompt(alreadyAllowed: Boolean): Boolean = request("notification_asked", alreadyAllowed)
    private fun request(key: String, alreadyAllowed: Boolean): Boolean {
        if (alreadyAllowed || preferences.getBoolean(key, false)) return false
        preferences.edit().putBoolean(key, true).apply()
        return true
    }
}
