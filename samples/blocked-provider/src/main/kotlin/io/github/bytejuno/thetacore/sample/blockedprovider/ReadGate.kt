package io.github.bytejuno.thetacore.sample.blockedprovider

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A cooperative fixture gate, not a claim that Android can interrupt arbitrary provider code. */
internal class ReadGate(
    private val mode: ReadMode,
    private val delayMillis: Long = 15_000,
    private val lifetimeMillis: Long = 120_000,
) {
    private val created = System.nanoTime()
    private val wake = CountDownLatch(1)
    private val failure = AtomicReference<String?>()
    init { require(delayMillis >= 0 && lifetimeMillis > 0) }

    fun release() { if (mode != ReadMode.PREFIX_ABORT) wake.countDown() }
    fun failureWithDeadline(): String? {
        if (System.nanoTime() - created >= TimeUnit.MILLISECONDS.toNanos(lifetimeMillis)) abort("TIMEOUT")
        return failure.get()
    }
    fun abort(reason: String) { failure.compareAndSet(null, reason); wake.countDown() }
    fun failure(): String? = failure.get()

    // Called once, on the descriptor's dedicated callback thread. Abort wins over release.
    fun await(): String {
        failure.get()?.let { return it }
        val remaining = TimeUnit.MILLISECONDS.toNanos(lifetimeMillis) - (System.nanoTime() - created)
        if (remaining <= 0) { abort("TIMEOUT"); return checkNotNull(failure.get()) }
        if (mode == ReadMode.IMMEDIATE) return "IMMEDIATE"
        val delay = TimeUnit.MILLISECONDS.toNanos(delayMillis)
        val wait = if (mode == ReadMode.DELAY) minOf(delay, remaining) else remaining
        val signalled = try { wake.await(wait, TimeUnit.NANOSECONDS) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            abort("INTERRUPTED")
            true
        }
        if (!signalled && (mode == ReadMode.BLOCK || mode == ReadMode.PREFIX_ABORT || remaining <= delay)) abort("TIMEOUT")
        return failure.get() ?: if (signalled) "RELEASED" else "DELAY_DONE"
    }
}
