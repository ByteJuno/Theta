package io.github.bytejuno.thetacore.plugin

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-shared admission, independent of PluginWorker. No execution queue and no unbounded
 * cancellation threads. Timeout is a logical outcome, not a sandbox or a thread kill.
 * Only trusted, short host state transitions may be supplied as callbacks/timer actions.
 */
internal class PluginExecutionBudget(
    private val processSlots: Int = 8,
    private val perPluginSlots: Int = 2,
    cancellationThreads: Int = 2,
) : AutoCloseable {
    init { require(processSlots > 0 && perPluginSlots > 0 && cancellationThreads > 0) }
    // Shared only by short host-memory transitions in the entry/invocation controllers.
    internal val gate = Any()
    private val lock get() = gate
    private val sequence = AtomicInteger()
    private fun factory(kind: String) = java.util.concurrent.ThreadFactory { action ->
        Thread(action, "theta-$kind-${sequence.incrementAndGet()}").apply { isDaemon = true }
    }
    private val workers = ThreadPoolExecutor(0, processSlots, 30, TimeUnit.SECONDS,
        SynchronousQueue(), factory("execution"), ThreadPoolExecutor.AbortPolicy())
    private val cancels = ThreadPoolExecutor(0, cancellationThreads, 30, TimeUnit.SECONDS,
        SynchronousQueue(), factory("cancellation"), ThreadPoolExecutor.AbortPolicy())
    private val clock = ScheduledThreadPoolExecutor(1, factory("deadline")).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val active = mutableSetOf<Lease>()
    private var closed = false
    // Event-driven: a cancellation request or a finished delivery dispatches pending requests.
    // Only a rejected dispatch (both threads busy or still unwinding) arms one short retry,
    // so an idle process never polls.
    private var retryArmed = false

    internal inner class Lease(
        val pluginId: String,
        val execution: kotlinx.coroutines.CompletableJob,
        val ownerChild: kotlinx.coroutines.CompletableJob,
        val onCancel: () -> Unit,
        val onExit: () -> Unit,
    ) {
        val actualExited = CompletableDeferred<Unit>()
        internal var executionExited = false
        internal var cancelRequested = false
        internal var cancelRunning = false
        internal var cancelDelivered = false
        /** Safe on Main/owner cancellation: records a request; never calls Job.cancel inline. */
        fun cancel() {
            synchronized(lock) {
                if (this !in active || cancelRequested) return
                cancelRequested = true
                onCancel()
                pumpCancellation()
            }
        }
    }

    /** Null means immediate rejection (closed, stopped owner or exhausted process/plugin slots). */
    @OptIn(InternalCoroutinesApi::class)
    fun submit(
        pluginId: String,
        owner: CoroutineScope,
        onCancel: () -> Unit,
        onExit: () -> Unit,
        action: suspend CoroutineScope.() -> Unit,
    ): Lease? = synchronized(lock) {
        val ownerJob = requireNotNull(owner.coroutineContext[Job]) { "Owner scope requires a Job" }
        if (closed || !ownerJob.isActive || active.size >= processSlots ||
            active.count { it.pluginId == pluginId } >= perPluginSlots) return null
        // Only the host bridge is parented to owner. Direct parenting of the execution job would
        // run arbitrary plugin cancellation handlers on the thread calling owner.cancel().
        val bridge = Job(ownerJob)
        val lease = Lease(pluginId, Job(), bridge, onCancel, onExit)
        active += lease
        // Keep the owner subtree incomplete until actual exit, even after owner cancellation.
        // This host-only waiter never receives plugin code or its cancellation handlers.
        CoroutineScope(bridge + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { lease.actualExited.await() }
        }
        bridge.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) lease.cancel()
        }
        try {
            workers.execute {
                try {
                    runBlocking(lease.execution) {
                        if (synchronized(lock) { !lease.cancelRequested }) action()
                    }
                } catch (_: Throwable) {
                    // Actions own sanitized logical outcomes; cancellation can escape runBlocking.
                } finally {
                    // Completion handlers are plugin code too; keep them on this worker.
                    val completeNormally = synchronized(lock) { !lease.cancelRequested }
                    if (completeNormally) try { lease.execution.complete() } catch (_: Throwable) { }
                    synchronized(lock) {
                        lease.executionExited = true
                        releaseIfFinished(lease)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // SynchronousQueue can reject briefly while a previously released worker unwinds.
            lease.executionExited = true
            releaseIfFinished(lease)
            return null
        }
        lease
    }

    /** Timers run host-only transitions on an independent thread, never plugin callbacks. */
    fun schedule(delayMillis: Long, action: () -> Unit): java.util.concurrent.ScheduledFuture<*>? =
        synchronized(lock) {
            if (closed) null else clock.schedule({ action() }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }

    fun occupiedSlots(): Int = synchronized(lock) { active.size }

    private fun pumpCancellation(): Unit = synchronized(lock) {
        active.toList().forEach { lease ->
            if (!lease.cancelRequested || lease.cancelRunning || lease.cancelDelivered) return@forEach
            lease.cancelRunning = true
            try {
                cancels.execute {
                    try { lease.execution.cancel() } catch (_: Throwable) { /* plugin handler */ }
                    finally {
                        synchronized(lock) {
                            lease.cancelRunning = false
                            lease.cancelDelivered = true
                            releaseIfFinished(lease)
                            pumpCancellation()
                        }
                    }
                }
            } catch (_: RejectedExecutionException) {
                lease.cancelRunning = false // Retain pending request; bounded by active slots.
                armRetry()
            }
        }
    }

    private fun armRetry() {
        if (retryArmed) return
        try {
            clock.schedule(Runnable {
                synchronized(lock) { retryArmed = false }
                pumpCancellation()
            }, 5, TimeUnit.MILLISECONDS)
            retryArmed = true
        } catch (_: RejectedExecutionException) {
            // The clock stops only after every lease, including pending cancellations, released.
        }
    }

    private fun releaseIfFinished(lease: Lease) {
        if (!lease.executionExited || lease.cancelRunning ||
            (lease.cancelRequested && !lease.cancelDelivered)) return
        if (!active.remove(lease)) return
        // Do not complete/cancel execution here: Job callbacks can be arbitrary plugin code,
        // and must never execute while the host admission gate is held.
        lease.ownerChild.complete()
        lease.onExit()
        lease.actualExited.complete(Unit)
        finishShutdownIfEmpty()
    }

    /** Admission closes immediately; retained slots/pending cancellations continue draining. */
    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        active.toList().forEach { it.cancel() }
        workers.shutdown() // Never shutdownNow: interruption is not proof of actual exit.
        finishShutdownIfEmpty()
    }

    private fun finishShutdownIfEmpty() {
        if (closed && active.isEmpty()) {
            cancels.shutdown()
            clock.shutdown()
        }
    }
}
