package io.github.bytejuno.thetacore.overlay

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import io.github.bytejuno.thetacore.MainActivity
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.chat.AndroidChatTaskPresence
import io.github.bytejuno.thetacore.chat.ChatSessionOwner
import io.github.bytejuno.thetacore.chat.ChatState
import io.github.bytejuno.thetacore.chat.OutputTaskProgress
import io.github.bytejuno.thetacore.screen.ScreenCaptureVisibilityHook
import io.github.bytejuno.thetacore.screen.ScreenRuntime
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.OutputSurface
import io.github.bytejuno.thetacore.sdk.TaskProgress
import io.github.bytejuno.thetacore.sdk.TaskPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume

internal fun floatingTaskPresence(host: ThetaApplication) = AndroidChatTaskPresence(host) { task ->
    host.floatingProgress.present(task)
    host.plugins.outputSurfaces.present(task)
}

internal fun floatingTaskIntent(host: Application) = Intent(host, MainActivity::class.java)
    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

internal fun openFloatingPermissionSettings(context: Context): Boolean = runCatching {
    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/** Built-in OutputSurface, independent of third-party callbacks and their execution budget. */
internal class FloatingProgressRuntime(private val host: Application,
    private val chat: () -> ChatSessionOwner = { (host as ThetaApplication).chat },
) : OutputSurface, ScreenCaptureVisibilityHook {
    override val id = "theta.floating-progress"
    override val displayName get() = host.getString(R.string.floating_progress_title)
    override val priority = Int.MIN_VALUE
    private val preferences = host.getSharedPreferences("floating_progress", Application.MODE_PRIVATE)
    private val enabledState = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = enabledState.asStateFlow()
    private val permissionState = MutableStateFlow(false)
    val permission = permissionState.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val window = FloatingProgressWindow(host, ::stop, {
        runCatching { host.startActivity(floatingTaskIntent(host)) }
            .onFailure { android.util.Log.w("ThetaOverlay", "Unable to open task: ${it.javaClass.simpleName}") }
    })
    private val state = FloatingProgressState(window::render)
    private var sessionId: String? = null
    private var progress: TaskProgress? = null
    private val started = mutableSetOf<Activity>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        state.enabled(enabled.value)
        refreshPermission()
        scope.launch { chat().state.collect(::refreshTask) }
        ScreenRuntime.captureVisibilityHook = this
        // Like AndroidX ProcessLifecycleOwner, count started activities rather than retaining
        // one Activity's resumed flag. No Activity or Compose lifetime owns an active task.
        host.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started += activity; state.foreground(true) }
            override fun onActivityStopped(activity: Activity) { started -= activity; state.foreground(started.isNotEmpty()) }
            override fun onActivityResumed(activity: Activity) { refreshPermission() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { started -= activity; state.foreground(started.isNotEmpty()) }
        })
        runCatching { host.getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, host.packageName,
            AppOpsManager.OnOpChangedListener { _, _ -> main.post { refreshPermission() } }) }
            .onFailure { android.util.Log.w("ThetaOverlay", "Permission watch unavailable: ${it.javaClass.simpleName}") }
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        enabledState.value = value
        state.enabled(value)
        refreshPermission()
    }

    private fun refreshPermission() {
        permissionState.value = Settings.canDrawOverlays(host)
        state.permission(permission.value)
    }

    override fun isAvailable(device: DeviceInfo) = enabled.value && permission.value
    override fun show(task: TaskProgress) { onMain { present(task) } }
    override fun clear(taskId: String) { onMain { state.clear(taskId) } }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    fun present(task: TaskProgress?) {
        sessionId = chat().state.value.sessionId
        progress = task
        refreshTask(chat().state.value)
    }

    private fun refreshTask(chat: ChatState) {
        if (sessionId != chat.sessionId || progress?.taskId != chat.executionId) progress = null
        sessionId = chat.sessionId
        // Runtime events describe an admitted execution. Saved approvals and late exits
        // are durable chat state and can arrive without any runtime event at all.
        state.present(if (chat.active) progress ?: chat.executionId?.let {
            OutputTaskProgress(it, TaskPhase.MODEL, null)
        } else floatingAttention(chat))
    }

    private fun stop(task: FloatingTask) {
        refreshTask(chat().state.value)
        if (task != state.task) return
        stopFloatingTask(task, sessionId, chat().state.value) { execution, pendingSession -> chat().stop(execution, pendingSession) }
    }

    override suspend fun beforeCapture() = withContext(Dispatchers.Main.immediate) {
        state.beforeCapture()
        // removeViewImmediate detaches the surface; cross two vsync boundaries before capture.
        // This is ordering, not a claim that OEM compositor latency has been device-verified.
        repeat(2) { awaitFrame() }
    }

    override suspend fun afterCapture() = withContext(Dispatchers.Main.immediate) {
        refreshPermission()
        state.afterCapture()
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        choreographer.postFrameCallback(callback)
    }
}
