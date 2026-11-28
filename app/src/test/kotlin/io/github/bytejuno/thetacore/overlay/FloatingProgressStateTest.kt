package io.github.bytejuno.thetacore.overlay

import io.github.bytejuno.thetacore.chat.ChatState
import io.github.bytejuno.thetacore.chat.OutputTaskProgress
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.screen.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class FloatingProgressStateTest {
    private var visible: FloatingTask? = null
    private fun state() = FloatingProgressState { visible = it }.apply { enabled(true); permission(true) }
    private fun task(phase: TaskPhase = TaskPhase.MODEL, tool: String? = null, id: String = "run") = OutputTaskProgress(id, phase, tool)

    @Test fun idleAndForegroundChatStayHiddenButBackgroundAndScreenChainAreVisible() {
        val s = state(); assertNull(visible)
        s.foreground(true); s.present(task()); assertNull(visible)
        s.foreground(false); assertEquals(TaskPhase.MODEL, visible?.phase)
        s.foreground(true); assertNull(visible)
        s.present(task(TaskPhase.TOOL, "screen_tap")); assertEquals("screen_tap", visible?.toolName)
        s.present(task()); assertNotNull(visible)
        s.present(task(id = "next")); assertNull(visible)
        s.foreground(false); assertNotNull(visible)
        s.present(task(TaskPhase.COMPLETED, id = "next")); assertNull(visible)
        s.present(null); assertNull(visible)
    }

    @Test fun approvalAndRecoveryAreHighlightedAndPermissionOrDisableAlwaysHide() {
        val s = state(); s.foreground(true)
        for (phase in listOf(TaskPhase.APPROVAL, TaskPhase.RECOVERY)) {
            s.present(task(phase)); assertTrue(visible!!.needsAttention)
            s.permission(false); assertNull(visible)
            s.permission(true); assertEquals(phase, visible?.phase)
            s.enabled(false); assertNull(visible)
            s.enabled(true); assertNotNull(visible)
        }
    }

    @Test fun captureRestoresLatestStateAndCannotReviveCompletedOrDisabledTask() {
        val s = state(); s.present(task())
        s.beforeCapture(); s.beforeCapture(); assertNull(visible)
        s.present(task(TaskPhase.APPROVAL)); assertNull(visible)
        s.afterCapture(); assertNull(visible)
        s.afterCapture(); assertEquals(TaskPhase.APPROVAL, visible?.phase)
        s.beforeCapture(); s.present(null); s.afterCapture(); assertNull(visible)
        s.present(task()); s.beforeCapture(); s.enabled(false); s.afterCapture(); assertNull(visible)
        s.enabled(true); s.clear("older"); assertNotNull(visible)
        s.clear("run"); assertNull(visible)
    }

    @Test fun realCaptureControllerOrdersHideCaptureRestoreOnSuccessFailureAndCancellation() = runBlocking {
        for (outcome in listOf("success", "failure", "cancel")) {
            val events = mutableListOf<String>()
            val s = FloatingProgressState { events += if (it == null) "hidden" else "visible" }
            s.enabled(true); s.permission(true); s.present(task()); events.clear()
            val backend = object : ScreenControlBackend {
                override val ready = true
                override suspend fun execute(action: ScreenAction): ScreenReply {
                    events += "capture"
                    when (outcome) {
                        "failure" -> error("capture failed")
                        "cancel" -> throw CancellationException("cancelled capture")
                    }
                    return ScreenReply(ToolResult("ok"))
                }
            }
            val hook = object : ScreenCaptureVisibilityHook {
                override suspend fun beforeCapture() = s.beforeCapture()
                override suspend fun afterCapture() = s.afterCapture()
            }
            try { ScreenController(backend, backend, { "unavailable" }, hook).execute(ScreenAction("screen_capture", buildJsonObject {})) }
            catch (_: CancellationException) { assertEquals("cancel", outcome) }
            catch (_: IllegalStateException) { assertEquals("failure", outcome) }
            assertEquals(listOf("hidden", "capture", "visible"), events)
        }
    }

    @Test fun stopTargetsCurrentExecutionOrItsSavedPendingStepAndRejectsStaleWindow() {
        val calls = mutableListOf<Triple<String?, String?, String?>>()
        val stop: (String?, String?, String?) -> Unit = { execution, session, request -> calls.add(Triple(execution, session, request)) }
        val current = FloatingTask("run", TaskPhase.TOOL, "screen_tap")
        val active = ChatState(sessionId = "session", active = true, executionId = "run")
        stopFloatingTask(current, "session", active, stop)
        assertEquals(listOf(Triple("run", null, null)), calls)
        stopFloatingTask(current, "session", active.copy(executionId = "new"), stop)
        assertEquals(1, calls.size)
        val definition = FrozenDefinition("host/write", "write", "write", "test", "{}", Risk.WRITE,
            Source.FIRST_PARTY, "host", "1", 1L, false, null, false, null)
        val input = InvocationInput(BindingValue.copyOf(callBinding("test", "request", null, 0, "wire")),
            "chain", "session", "run", "step", 0, "{}", definition, false, Risk.WRITE)
        val record = InvocationRecord(2, 1L, input, definition.digest, Status.CANCELLED, Risk.WRITE, "{}",
            null, emptyList(), ExecutionInstance(java.util.UUID.randomUUID().toString(), "epoch", InstanceState.EXIT_CONFIRMED),
            LogicalResult(Status.CANCELLED, Effect.UNKNOWN, "cancelled", false), emptyList(),
            Isolation("unknown", null, null), false, false).also { it.validate() }
        val recovery = RecoveryRequest("recovery:${record.callId}:1", record.callId, 1L)
        val waiting = current.copy(taskId = "recovery:session:${recovery.id}", phase = TaskPhase.RECOVERY)
        val pending = active.copy(active = false, executionId = null, pending = true,
            records = mapOf(record.callId to record), recoveries = mapOf(record.callId to recovery))
        stopFloatingTask(waiting, "session", pending, stop)
        assertEquals(listOf(Triple("run", null, null), Triple(null, "session", recovery.id)), calls)
        stopFloatingTask(waiting, "old-session", pending, stop)
        stopFloatingTask(waiting, "session", pending.copy(pending = false), stop)
        assertEquals(2, calls.size)
        stopFloatingTask(waiting, "session", pending.copy(recoveries = mapOf(record.callId to recovery.copy(id = "replacement"))), stop)
        assertEquals(2, calls.size)
    }

    @Test fun draggingStaysInsideUsableDisplayAndSnapsToNearestEdge() {
        assertEquals(FloatingPosition(0, 0), floatingPosition(-30, -50, 400, 800, 320, 60, false))
        assertEquals(FloatingPosition(80, 740), floatingPosition(900, 900, 400, 800, 320, 60, false))
        assertEquals(FloatingPosition(0, 100), floatingPosition(30, 100, 400, 800, 320, 60, true))
        assertEquals(FloatingPosition(80, 100), floatingPosition(60, 100, 400, 800, 320, 60, true))
        assertEquals(FloatingPosition(0, 0), floatingPosition(10, 20, 200, 30, 320, 60, true))
    }
}
