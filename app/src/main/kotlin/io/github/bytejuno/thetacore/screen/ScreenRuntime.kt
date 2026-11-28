package io.github.bytejuno.thetacore.screen

import android.content.Context
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.root.*
import io.github.bytejuno.thetacore.sdk.Tool
import kotlinx.coroutines.*
import java.io.File

internal object ScreenRuntime {
    @Volatile var captureVisibilityHook: ScreenCaptureVisibilityHook? = null

    fun tools(context: Context): List<Tool> {
        val app = context.applicationContext
        val apps = ScreenApps(app)
        val root = RootScreenBackend(app, RootBridge(SuRootBackend(File(app.noBackupFilesDir, "root-calls"))))
        val accessibility = object : ScreenControlBackend {
            override val ready get() = ThetaAccessibilityService.connected != null
            override suspend fun execute(action: ScreenAction): ScreenReply {
                val service = ThetaAccessibilityService.connected
                    ?: return unavailableScreenReply(app.getString(R.string.screen_permission_required))
                return if (action.name == "app_launch") ScreenReply(withContext(Dispatchers.Main.immediate) {
                    ScreenApps(service).launch(action.text("packageName"))
                }) else service.execute(action)
            }
        }
        // Pair before/after against the same hook even if the overlay is replaced during capture.
        val visibility = object : ScreenCaptureVisibilityHook {
            private var active: ScreenCaptureVisibilityHook? = null
            override suspend fun beforeCapture() { active = captureVisibilityHook; active?.beforeCapture() }
            override suspend fun afterCapture() { try { active?.afterCapture() } finally { active = null } }
        }
        val control = ScreenController(root, accessibility, { app.getString(R.string.screen_permission_required) }, visibility)
        val appBackend = object : ScreenControlBackend {
            override val ready = true
            override suspend fun execute(action: ScreenAction) = ScreenReply(withContext(Dispatchers.IO) { apps.list() })
        }
        val appControl = ScreenController(appBackend, accessibility, { app.getString(R.string.screen_permission_required) })
        return ScreenTool.descriptions.keys.map { ScreenTool(it, if (it == "app_list") appControl else control) }
    }
}
