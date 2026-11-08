package io.github.bytejuno.thetacore.sample.fixture.s1

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.bytejuno.thetacore.sdk.PluginContext
import java.util.UUID
import java.util.concurrent.CountDownLatch

internal class BoundaryEvidence(private val ctx: PluginContext) {
    private val instance = UUID.randomUUID().toString()
    fun mark(event: String, detail: String = "") {
        Log.i("ThetaS1Fixture", "boundary|plugin=${ctx.pluginId}|instance=$instance|pid=${Process.myPid()}" +
            "|monoNs=${System.nanoTime()}|event=$event|$detail")
    }
}

/** Deliberately noncooperative, but bounded even if the runner disappears. No extra thread. */
internal fun awaitBoundaryLatch(latch: CountDownLatch, limitMs: Long): Boolean {
    val deadline = System.nanoTime() + limitMs * 1_000_000
    // Both an iteration cap and monotonic deadline; interruption cannot extend the deadline.
    repeat((limitMs / 10 + 1).toInt()) {
        if (latch.count == 0L) return true
        if (System.nanoTime() >= deadline) return false
        SystemClock.sleep(10)
    }
    return latch.count == 0L
}
