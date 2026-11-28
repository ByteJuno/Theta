package io.github.bytejuno.thetacore.overlay

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.bytejuno.thetacore.MainActivity
import io.github.bytejuno.thetacore.sdk.TaskPhase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FloatingProgressViewTest {
    @Test fun windowActuallyDetachesDuringCaptureAndWhenIdleOrPermissionIsRevoked() {
        val app = RuntimeEnvironment.getApplication()
        val manager = Shadows.shadowOf(app.getSystemService(WindowManager::class.java)) as org.robolectric.shadows.ShadowWindowManagerImpl
        val window = FloatingProgressWindow(app, {}, {})
        val state = FloatingProgressState(window::render)
        state.enabled(true)
        state.present(io.github.bytejuno.thetacore.chat.OutputTaskProgress("run", TaskPhase.MODEL, null))
        assertTrue(manager.views.isEmpty())
        state.permission(true); assertEquals(1, manager.views.size)
        val first = manager.views.single()
        state.beforeCapture(); assertTrue(manager.views.isEmpty())
        state.afterCapture(); assertEquals(1, manager.views.size); assertNotSame(first, manager.views.single())
        state.permission(false); assertTrue(manager.views.isEmpty())
        state.permission(true); assertEquals(1, manager.views.size)
        state.enabled(false); assertTrue(manager.views.isEmpty())
        state.enabled(true); state.present(null); assertTrue(manager.views.isEmpty())
    }

    @Test fun bilingualSmallScreenLargeTextKeepsStopReachableAndAttentionOpensHost() {
        val app = RuntimeEnvironment.getApplication()
        for (language in listOf("en", "zh")) for (night in listOf(false, true)) {
            val configuration = Configuration(app.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language)); fontScale = 2f; screenWidthDp = 320
                uiMode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = app.createConfigurationContext(configuration)
            var stopped: FloatingTask? = null; var opened = 0
            val view = FloatingProgressView(context, { stopped = it }, { opened++ })
            val task = FloatingTask("run", TaskPhase.APPROVAL, null)
            view.bind(task)
            view.measure(View.MeasureSpec.makeMeasureSpec(context.dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(context.dp(640), View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            assertTrue(view.stopButton.width >= context.dp(48))
            assertTrue(view.stopButton.height >= context.dp(48))
            assertTrue(view.stopButton.right <= view.width)
            assertTrue(view.status.width > 0)
            assertTrue(view.status.height >= context.dp(48))
            assertFalse(view.status.text.isBlank()); assertFalse(view.stopButton.text.isBlank())
            assertEquals(0, view.status.layout.getEllipsisCount(view.status.layout.lineCount - 1))
            view.stopButton.performClick(); assertEquals(task, stopped)
            view.status.performClick(); assertEquals(1, opened)
            view.bind(task.copy(phase = TaskPhase.RECOVERY)); view.status.performClick(); assertEquals(2, opened)
            val intent = floatingTaskIntent(app)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        }
    }

    @Test fun draggingStatusSnapsWithoutOpeningOrStoppingAndTapStillOpens() {
        val app = RuntimeEnvironment.getApplication()
        var opens = 0; var stops = 0
        val moves = mutableListOf<Triple<Int, Int, Boolean>>()
        val view = FloatingProgressView(app, { stops++ }, { opens++ })
        view.bind(FloatingTask("run", TaskPhase.MODEL, null))
        view.drag = { x, y, snap -> moves += Triple(x, y, snap) }
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(0, 10, action, x, y, 0)
            try { view.status.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 0f, 0f); touch(MotionEvent.ACTION_MOVE, 100f, 200f); touch(MotionEvent.ACTION_UP, 100f, 200f)
        assertEquals(listOf(Triple(100, 200, false), Triple(0, 0, true)), moves)
        assertEquals(0, opens); assertEquals(0, stops)
        touch(MotionEvent.ACTION_DOWN, 0f, 0f); touch(MotionEvent.ACTION_UP, 0f, 0f)
        assertEquals(1, opens)
    }
}
