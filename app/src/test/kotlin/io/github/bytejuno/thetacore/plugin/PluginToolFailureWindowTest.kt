package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class PluginToolFailureWindowTest {
    private fun hostileTool() = object : Tool {
        override val id: String get() = error("Do not read plugin id")
        override val spec: ToolSpec get() = error("Do not read plugin metadata")
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = error("Do not invoke plugin")
        override fun equals(other: Any?): Boolean = error("Do not invoke plugin equals")
        override fun hashCode(): Int = error("Do not invoke plugin hashCode")
    }

    @Test fun thirdFailureWithinWindowQuarantinesAndNeverRevives() {
        val clock = AtomicLong()
        val window = PluginToolFailureWindow(nanoTime = clock::get)
        val tool = hostileTool()
        assertFalse(window.isQuarantined(tool))
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED))
        clock.set(TimeUnit.SECONDS.toNanos(10))
        assertFalse(window.record(tool, PluginInvocationStatus.TIMED_OUT))
        clock.set(TimeUnit.SECONDS.toNanos(29))
        assertTrue(window.record(tool, PluginInvocationStatus.FAILED))
        clock.set(TimeUnit.DAYS.toNanos(10))
        assertTrue(window.isQuarantined(tool))
        assertTrue(window.record(tool, PluginInvocationStatus.SUCCEEDED))
        assertTrue(window.record(tool, PluginInvocationStatus.CANCELLED))
        assertTrue(window.record(tool, PluginInvocationStatus.REJECTED))
    }

    @Test fun rollingWindowExpiresOldFailuresIncludingExactBoundary() {
        val clock = AtomicLong()
        val window = PluginToolFailureWindow(nanoTime = clock::get)
        val tool = hostileTool()
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED)) // t=0
        clock.set(TimeUnit.SECONDS.toNanos(1))
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED))
        clock.set(TimeUnit.SECONDS.toNanos(30))
        assertFalse(window.record(tool, PluginInvocationStatus.TIMED_OUT)) // t=0 expires
        clock.set(TimeUnit.SECONDS.toNanos(31))
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED)) // t=1 expires
        clock.set(TimeUnit.SECONDS.toNanos(32))
        assertTrue(window.record(tool, PluginInvocationStatus.FAILED))
    }

    @Test fun successCancelAndRejectionDoNotCountOrResetFailures() {
        val window = PluginToolFailureWindow(nanoTime = { 0L })
        val tool = hostileTool()
        repeat(10) {
            assertFalse(window.record(tool, PluginInvocationStatus.CANCELLED))
            assertFalse(window.record(tool, PluginInvocationStatus.REJECTED))
            assertFalse(window.record(tool, PluginInvocationStatus.SUCCEEDED))
        }
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED))
        assertFalse(window.record(tool, PluginInvocationStatus.SUCCEEDED))
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED))
        assertFalse(window.record(tool, PluginInvocationStatus.CANCELLED))
        assertTrue(window.record(tool, PluginInvocationStatus.TIMED_OUT))
    }

    @Test fun identityAndOwnerIsolationNeverExecutePluginGettersOrEquality() {
        val first = hostileTool()
        val second = hostileTool()
        val owner = PluginToolFailureWindow(nanoTime = { 0L })
        repeat(3) { owner.record(first, PluginInvocationStatus.FAILED) }
        assertTrue(owner.isQuarantined(first))
        assertFalse(owner.isQuarantined(second))
        assertFalse(owner.record(second, PluginInvocationStatus.FAILED))
        assertFalse(PluginToolFailureWindow().isQuarantined(first)) // A replacement owner resets policy.
    }

    @Test fun identityCapFailsClosedWithoutEvictionOrUnboundedTombstones() {
        val window = PluginToolFailureWindow(maxTrackedTools = 2, nanoTime = { 0L })
        val first = hostileTool()
        val second = hostileTool()
        repeat(3) { window.record(first, PluginInvocationStatus.FAILED) }
        assertFalse(window.record(second, PluginInvocationStatus.FAILED))
        repeat(100) {
            val unknown = hostileTool()
            assertTrue(window.isQuarantined(unknown))
            assertTrue(window.record(unknown, PluginInvocationStatus.FAILED))
        }
        assertTrue(window.isQuarantined(first))
        assertFalse(window.isQuarantined(second))
        assertFalse(window.record(second, PluginInvocationStatus.FAILED))
        assertTrue(window.record(second, PluginInvocationStatus.TIMED_OUT))
    }

    @Test fun concurrentTerminalObserversCannotLoseFailureIncrements() {
        val window = PluginToolFailureWindow(nanoTime = { 0L })
        val tool = hostileTool()
        val pool = Executors.newFixedThreadPool(3)
        val ready = CountDownLatch(3)
        val start = CountDownLatch(1)
        try {
            val futures = (1..3).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    start.await()
                    window.record(tool, PluginInvocationStatus.FAILED)
                }
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, futures.count { it.get(3, TimeUnit.SECONDS) })
            assertTrue(window.isQuarantined(tool))
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun monotonicCounterWrapDoesNotResetRollingWindow() {
        val clock = AtomicLong(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(5))
        val window = PluginToolFailureWindow(nanoTime = clock::get)
        val tool = hostileTool()
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED))
        clock.addAndGet(TimeUnit.SECONDS.toNanos(10))
        assertFalse(window.record(tool, PluginInvocationStatus.FAILED))
        clock.addAndGet(TimeUnit.SECONDS.toNanos(10))
        assertTrue(window.record(tool, PluginInvocationStatus.TIMED_OUT))
    }
}
