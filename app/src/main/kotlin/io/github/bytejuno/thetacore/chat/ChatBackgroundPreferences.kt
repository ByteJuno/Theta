package io.github.bytejuno.thetacore.chat

import android.content.Context

internal class ChatBackgroundPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("chat_background", Context.MODE_PRIVATE)
    fun takeBackgroundHint(): Boolean {
        if (preferences.getBoolean("battery_asked", false)) return false
        preferences.edit().putBoolean("battery_asked", true).apply()
        return true
    }
}
