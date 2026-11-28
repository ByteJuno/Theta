package io.github.bytejuno.thetacore.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import java.util.concurrent.Executor

/** Enabled only by the user's Android Accessibility settings; never starts an enable prompt. */
class ThetaAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile internal var connected: ThetaAccessibilityService? = null
            private set
    }
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { if (connected === this) connected = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }

    internal suspend fun execute(action: ScreenAction): ScreenReply {
        currentCoroutineContext().ensureActive()
        return try {
            val result = when (action.name) {
                "screen_capture" -> capture()
                "screen_ui_tree" -> tree()
                "screen_tap", "screen_long_press", "screen_swipe" -> gesture(action)
                "screen_input_text" -> withContext(Dispatchers.Main.immediate) {
                    val root = rootInActiveWindow ?: return@withContext unavailableScreenReply(getString(R.string.screen_focus_required))
                    try {
                        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            ?: return@withContext unavailableScreenReply(getString(R.string.screen_focus_required))
                        try {
                            if (!focused.isEditable) return@withContext unavailableScreenReply(getString(R.string.screen_focus_required))
                            val applied = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text("text"))
                            })
                            actionReply(applied)
                        } finally { focused.recycle() }
                    } finally { root.recycle() }
                }
                "screen_key" -> withContext(Dispatchers.Main.immediate) {
                    if (action.text("key") == "enter") {
                        val root = rootInActiveWindow ?: return@withContext unavailableScreenReply(getString(R.string.screen_focus_required))
                        try {
                            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                                ?: return@withContext unavailableScreenReply(getString(R.string.screen_focus_required))
                            try { actionReply(focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) }
                            finally { focused.recycle() }
                        } finally { root.recycle() }
                    } else actionReply(performGlobalAction(when (action.text("key")) {
                        "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; else -> GLOBAL_ACTION_RECENTS
                    }))
                }
                else -> unavailableScreenReply(getString(R.string.screen_read_failed))
            }
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ScreenReply(ToolResult(getString(if (action.writes) R.string.screen_action_unknown else R.string.screen_read_failed),
            isError = true, effect = if (action.writes) ToolEffect.UNKNOWN else ToolEffect.NONE)) }
    }

    private fun actionReply(ok: Boolean) = ScreenReply(ToolResult(
        getString(if (ok) R.string.screen_action_sent else R.string.screen_action_unknown),
        isError = !ok, effect = if (ok) ToolEffect.APPLIED else ToolEffect.UNKNOWN,
    ))

    private suspend fun gesture(action: ScreenAction): ScreenReply {
        val done = CompletableDeferred<Boolean>()
        val path = Path().apply {
            if (action.name == "screen_swipe") {
                moveTo(action.number("x1").toFloat(), action.number("y1").toFloat())
                lineTo(action.number("x2").toFloat(), action.number("y2").toFloat())
            } else moveTo(action.number("x").toFloat(), action.number("y").toFloat())
        }
        val duration = when (action.name) { "screen_tap" -> 50; "screen_long_press" -> action.number("durationMs", 800); else -> action.number("durationMs", 400) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build()
        // Main-thread submission is atomic with respect to cancellation; once accepted we must
        // await onCompleted/onCancelled even if the invocation is already logically cancelled.
        return withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
            }, null)
            if (!accepted) return@withContext unavailableScreenReply(getString(R.string.screen_action_unavailable))
            try { actionReply(done.await()) }
            finally { withContext(NonCancellable) { done.await() } }
        }
    }

    private suspend fun capture(): ScreenReply {
        val done = CompletableDeferred<Result<Bitmap>>()
        return withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext().ensureActive()
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { it.run() }, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val result = runCatching {
                        val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace))
                        try { requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) } finally { hardware.recycle() }
                    }
                    buffer.close(); done.complete(result)
                }
                override fun onFailure(errorCode: Int) { done.complete(Result.failure(IllegalStateException("Capture unavailable"))) }
            })
            var bitmap: Bitmap? = null
            try {
                bitmap = done.await().getOrThrow()
                ScreenReply(withContext(Dispatchers.Default) { encodeScreenCapture(bitmap) })
            } finally {
                // Drain late callbacks and release their buffer before the worker releases screen.
                withContext(NonCancellable) { (bitmap ?: done.await().getOrNull())?.recycle() }
            }
        }
    }

    private suspend fun tree(): ScreenReply = withContext(Dispatchers.IO) {
        val root = rootInActiveWindow ?: return@withContext unavailableScreenReply(getString(R.string.screen_read_failed))
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val nodes = mutableListOf<ScreenNode>(); var count = 0; var truncated = false
        try {
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val node = queue.removeFirst()
                try {
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    nodes += ScreenNode(node.packageName?.toString().orEmpty(), node.className?.toString().orEmpty(),
                        if (node.isPassword) "" else shortScreenText(node.text?.toString().orEmpty()),
                        if (node.isPassword) "" else shortScreenText(node.contentDescription?.toString().orEmpty()),
                        bounds.left.coerceAtLeast(0), bounds.top.coerceAtLeast(0), bounds.right.coerceAtMost(32767), bounds.bottom.coerceAtMost(32767),
                        node.isVisibleToUser, node.isClickable, node.isEditable, node.isScrollable, node.isPassword)
                    count++
                    for (i in 0 until node.childCount) {
                        if (count + queue.size >= SCREEN_SCAN_LIMIT) { truncated = true; break }
                        node.getChild(i)?.let(queue::addLast)
                    }
                } finally { node.recycle() }
            }
            ScreenReply(ToolResult(structured = compactScreenTree(nodes, truncated), effect = ToolEffect.NONE))
        } finally { queue.forEach { it.recycle() } }
    }
}
