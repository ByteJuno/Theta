package io.github.bytejuno.thetacore.plugin.tools

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Public Android APIs available before minSdk 33; no permissions or Activity lifetime required. */
internal class AndroidScreenBackend(context: Context) : ScreenBackend {
    private val app = context.applicationContext
    override fun readDefaultDisplay(): ScreenInfo? {
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: return null
        if (!display.isValid) return null
        val mode = display.mode
        return ScreenInfo(mode.physicalWidth, mode.physicalHeight, display.rotation, when (display.state) {
            Display.STATE_OFF -> "off"
            Display.STATE_ON -> "on"
            Display.STATE_DOZE -> "doze"
            Display.STATE_DOZE_SUSPEND -> "doze_suspend"
            Display.STATE_VR -> "vr"
            Display.STATE_ON_SUSPEND -> "on_suspend"
            else -> "unknown"
        })
    }
}
