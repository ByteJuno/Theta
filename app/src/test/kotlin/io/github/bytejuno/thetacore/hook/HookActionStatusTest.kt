package io.github.bytejuno.thetacore.hook

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

class HookActionStatusTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test fun completedPublicationSurvivesCancellationOnReturnToUi() {
        completedOperationSurvivesCancellation(uiText(R.string.hook_publication_status,
            uiText(R.string.status_hook_published, "sample.hook"), uiText(R.string.empty)))
    }

    @Test fun completedDisableSurvivesCancellationOnReturnToUi() {
        completedOperationSurvivesCancellation(uiText(R.string.status_hook_disabled))
    }

    private fun completedOperationSurvivesCancellation(success: io.github.bytejuno.thetacore.UiText) {
        val status = HookActionStatus()
        status.begin(1)
        val ui = QueueDispatcher()
        val io = QueueDispatcher()
        var returned = false
        var failures = 0
        val job = CoroutineScope(ui).launch {
            status.run(1, io, { failures++ }) { status.complete(1, success) }
            returned = true
        }
        ui.drain()
        io.drain() // Side effect and success state complete; the UI continuation is still queued.
        assertEquals(success, status.status.value)
        job.cancel()
        ui.drain()
        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(0, failures)
        assertEquals(success, status.status.value)
    }

    @Test fun cancellingScopeWaitClearsPendingAndLateApprovalCannotPublish() {
        val status = HookActionStatus()
        status.begin(1)
        val ui = QueueDispatcher()
        val io = QueueDispatcher()
        val approval = CompletableDeferred<Unit>()
        var published = false
        var failures = 0
        val job = CoroutineScope(ui).launch {
            status.run(1, io, { failures++ }) {
                status.waiting(1)
                approval.await()
                currentCoroutineContext().ensureActive()
                published = true
                status.complete(1, uiText(R.string.status_hook_published, "sample.hook"))
            }
        }
        ui.drain(); io.drain()
        assertEquals(R.string.status_scope_requested, status.status.value.key)
        job.cancel()
        io.drain(); ui.drain()
        approval.complete(Unit)
        io.drain(); ui.drain()
        assertTrue(job.isCancelled)
        assertTrue(job.isCompleted)
        assertFalse(published)
        assertEquals(0, failures)
        assertEquals(R.string.status_hook_action_cancelled, status.status.value.key)
    }

    @Test fun cancellationBeforeIoStartsNeverRunsOperationOrLeavesWaitState() {
        val status = HookActionStatus()
        status.begin(1)
        status.waiting(1)
        val ui = QueueDispatcher()
        val io = QueueDispatcher()
        var called = false
        val job = CoroutineScope(ui).launch {
            status.run(1, io, { fail("Cancellation is not failure") }) { called = true }
        }
        ui.drain()
        job.cancel()
        io.drain(); ui.drain()
        assertFalse(called)
        assertTrue(job.isCancelled)
        assertEquals(R.string.status_hook_action_cancelled, status.status.value.key)
    }

    @Test fun alreadyCancelledUiScopeStillClearsActionAndBusyWithoutRunningIo() {
        val status = HookActionStatus()
        status.begin(1)
        status.waiting(1)
        val ui = QueueDispatcher()
        val io = QueueDispatcher()
        val parent = Job().apply { cancel() }
        var busy = true
        var called = false
        val job = CoroutineScope(ui + parent).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                status.run(1, io, { fail("Cancellation is not failure") }) { called = true }
            } finally { busy = false }
        }
        io.drain(); ui.drain()
        assertTrue(job.isCompleted)
        assertTrue(job.isCancelled)
        assertFalse(called)
        assertFalse(busy)
        assertEquals(R.string.status_hook_action_cancelled, status.status.value.key)
    }

    @Test fun oldCancellationCannotOverwriteNewDisable() {
        val status = HookActionStatus()
        status.begin(1)
        status.waiting(1)
        status.begin(2)
        val disabled = uiText(R.string.status_hook_disabled)
        status.complete(2, disabled)
        status.cancel(1)
        status.waiting(1)
        assertEquals(disabled, status.status.value)
    }

    @Test fun cancellationOfNewPublicationDoesNotClaimPreviousPublicationAsNewSuccess() {
        val status = HookActionStatus()
        status.begin(1)
        status.complete(1, uiText(R.string.status_hook_published, "old.hook"))
        status.begin(2)
        status.cancel(2)
        assertEquals(R.string.status_hook_action_cancelled, status.status.value.key)
    }

    @Test fun disconnectRemainsVisibleAfterOldCancellationOrCompletion() {
        val status = HookActionStatus()
        status.begin(1)
        status.waiting(1)
        val disconnected = uiText(R.string.status_framework_disconnected)
        status.connection(2, disconnected)
        status.cancel(1)
        status.complete(1, uiText(R.string.status_hook_published, "sample.hook"))
        assertEquals(disconnected, status.status.value)
    }

    @Test fun actualFailureIsReportedAndClearsWait() = runBlocking {
        val status = HookActionStatus()
        status.begin(1)
        val expected = IllegalStateException("scope denied")
        var observed: Throwable? = null
        status.run(1, kotlinx.coroutines.Dispatchers.Unconfined, { observed = it }) {
            status.waiting(1)
            throw expected
        }
        assertSame(expected, observed)
        assertEquals(uiText(R.string.operation_failed, "IllegalStateException"), status.status.value)
    }

    @Test fun scopeTimeoutRemainsFailureWhileCallerIsActive() = runBlocking {
        val status = HookActionStatus()
        status.begin(1)
        var failures = 0
        status.run(1, kotlinx.coroutines.Dispatchers.Unconfined, { failures++ }) {
            status.waiting(1)
            withTimeout(1) { CompletableDeferred<Unit>().await() }
        }
        currentCoroutineContext().ensureActive()
        assertEquals(1, failures)
        assertEquals(uiText(R.string.operation_failed, "TimeoutCancellationException"), status.status.value)
    }
}
