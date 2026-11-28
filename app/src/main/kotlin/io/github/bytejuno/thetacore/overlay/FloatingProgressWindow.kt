package io.github.bytejuno.thetacore.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.sdk.TaskPhase
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class FloatingPosition(val x: Int, val y: Int)
internal fun floatingPosition(x: Int, y: Int, availableWidth: Int, availableHeight: Int,
    width: Int, height: Int, snap: Boolean): FloatingPosition {
    val maxX = (availableWidth - width).coerceAtLeast(0)
    val maxY = (availableHeight - height).coerceAtLeast(0)
    return FloatingPosition(if (snap) { if (x < maxX / 2) 0 else maxX } else x.coerceIn(0, maxX), y.coerceIn(0, maxY))
}

/** A bounded native window avoids giving an overlay its own Activity or Compose lifecycle. */
internal class FloatingProgressWindow(private val context: Context, private val stop: (FloatingTask) -> Unit,
    private val open: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var view: FloatingProgressView? = null
    private var configuration: Configuration? = null
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        y = context.dp(80)
        setTitle(context.getString(R.string.floating_progress_title))
    }

    fun render(task: FloatingTask?) {
        if (task == null) { remove(); return }
        try {
            if (configuration != context.resources.configuration) remove()
            val current = view ?: FloatingProgressView(context, stop, open).also { created ->
                created.drag = { dx, dy, snap -> move(dx, dy, snap) }
                created.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> move(0, 0, false) }
                created.bind(task)
                configuration = Configuration(context.resources.configuration)
                manager.addView(created, params)
                view = created
            }
            current.bind(task)
        } catch (error: RuntimeException) {
            android.util.Log.w("ThetaOverlay", "Overlay unavailable: ${error.javaClass.simpleName}")
            remove() // Notification progress continues even if permission was revoked mid-update.
        }
    }

    private fun remove() {
        val previous = view ?: return
        view = null
        try { manager.removeViewImmediate(previous) }
        catch (error: RuntimeException) { android.util.Log.w("ThetaOverlay", "Overlay already detached: ${error.javaClass.simpleName}") }
    }

    private fun move(dx: Int, dy: Int, snap: Boolean) {
        val current = view ?: return
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val next = floatingPosition(params.x + dx, params.y + dy,
            metrics.bounds.width() - insets.left - insets.right,
            metrics.bounds.height() - insets.top - insets.bottom, current.width, current.height, snap)
        if (params.x == next.x && params.y == next.y) return
        params.x = next.x; params.y = next.y
        try { manager.updateViewLayout(current, params) }
        catch (_: RuntimeException) { remove() }
    }
}

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

internal class FloatingProgressView(context: Context, stop: (FloatingTask) -> Unit, open: () -> Unit) : LinearLayout(context) {
    internal val status = TextView(context)
    internal val stopButton = Button(context)
    private var task: FloatingTask? = null
    var drag: (Int, Int, Boolean) -> Unit = { _, _, _ -> }
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        elevation = context.dp(6).toFloat()
        status.apply {
            textSize = 14f
            minHeight = context.dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            isClickable = true
            isFocusable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { open() }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; lastX = downX; downY = event.rawY; lastY = downY; dragging = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) dragging = true
                        if (dragging) drag((event.rawX - lastX).roundToInt(), (event.rawY - lastY).roundToInt(), false)
                        lastX = event.rawX; lastY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP -> { if (dragging) drag(0, 0, true) else performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { if (dragging) drag(0, 0, true); true }
                    else -> false
                }
            }
        }
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        stopButton.apply {
            text = context.getString(R.string.floating_progress_stop)
            textSize = 14f
            isAllCaps = false
            minHeight = context.dp(48); minimumHeight = context.dp(48)
            minWidth = context.dp(64); minimumWidth = context.dp(64)
            setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(179, 38, 30)); cornerRadius = context.dp(24).toFloat() }
            setOnClickListener { task?.let(stop) }
        }
        addView(stopButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(value: FloatingTask) {
        task = value
        val text = when (value.phase) {
            TaskPhase.TOOL -> context.getString(R.string.floating_progress_tool, value.toolName.orEmpty().take(80))
            TaskPhase.APPROVAL -> context.getString(R.string.floating_progress_approval)
            TaskPhase.RECOVERY -> context.getString(R.string.floating_progress_recovery)
            else -> context.getString(R.string.floating_progress_model)
        }
        if (status.text != text) status.text = text
        status.contentDescription = context.getString(R.string.floating_progress_open, text)
        status.setTextColor(if (dark) Color.rgb(237, 237, 244) else Color.rgb(27, 27, 33))
        background = GradientDrawable().apply {
            setColor(if (dark) Color.rgb(40, 40, 48) else Color.rgb(243, 242, 250))
            cornerRadius = context.dp(28).toFloat()
            if (value.needsAttention) setStroke(context.dp(2), if (dark) Color.rgb(255, 209, 128) else Color.rgb(126, 82, 0))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screen = resources.configuration.screenWidthDp.takeIf { it > 0 }?.let(context::dp)
            ?: resources.displayMetrics.widthPixels
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) screen else minOf(screen, MeasureSpec.getSize(widthMeasureSpec))
        val width = minOf(context.dp(320), limit).coerceAtLeast(1)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
