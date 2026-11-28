package io.github.bytejuno.thetacore.chat

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Process owner, never a coroutine timeout or Thread.interrupt for uncooperative I/O. */
internal class DocumentProcessWatchdog(timeoutMillis: Long, private val terminateProcess: () -> Unit) {
    private val terminated = AtomicBoolean()
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "document-deadline").apply { isDaemon = true }
    }
    init { timer.schedule(::finish, timeoutMillis, TimeUnit.MILLISECONDS) }
    fun finish() {
        if (terminated.compareAndSet(false, true)) {
            timer.shutdownNow()
            terminateProcess()
        }
    }
}
