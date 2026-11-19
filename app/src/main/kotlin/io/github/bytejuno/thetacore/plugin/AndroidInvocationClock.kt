package io.github.bytejuno.thetacore.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Approval slots are bounded by the execution budget. Each waiting slot owns one cancellable
 * absolute wait. Android's absolute park includes suspend. Wall time only selects a wakeup:
 * elapsed realtime alone decides expiry, and a wall-clock adjustment interrupts the wait.
 */
internal class AndroidInvocationClock(
    private val app: Context,
    execution: InvocationClock,
) : InvocationClock by execution {
    override fun elapsedRealtimeNanos() = SystemClock.elapsedRealtimeNanos()

    override fun armApproval(deadlineNanos: Long, action: () -> Unit): () -> Unit {
        val cancelled = AtomicBoolean(false)
        val waiter = thread(start = false, isDaemon = true, name = "theta-approval-deadline") {
            while (!cancelled.get()) {
                val wallMillis = System.currentTimeMillis()
                val remaining = deadlineNanos - elapsedRealtimeNanos()
                if (remaining <= 0) {
                    action()
                    break
                }
                LockSupport.parkUntil(wallMillis + (remaining + 999_999) / 1_000_000)
            }
        }
        val timeChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { LockSupport.unpark(waiter) }
        }
        app.registerReceiver(timeChanged, IntentFilter(Intent.ACTION_TIME_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        waiter.start()
        return {
            if (cancelled.compareAndSet(false, true)) {
                LockSupport.unpark(waiter)
                app.unregisterReceiver(timeChanged)
            }
        }
    }
}
