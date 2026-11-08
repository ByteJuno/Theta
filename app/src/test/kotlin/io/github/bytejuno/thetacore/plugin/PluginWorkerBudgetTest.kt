package io.github.bytejuno.thetacore.plugin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class PluginWorkerBudgetTest {
    @Test fun blockedEnableTimeoutRetainsCapacityAndRejectsWithoutQueueing() = runBlocking {
        val pool = PluginWorkerPool(1, "test-enable")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejectedCalls = AtomicInteger()
        try {
            val worker = PluginWorker("enable", pool) { entered.countDown(); release.await(); "late" }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNull(worker.await(20))
            repeat(20) {
                val rejected = PluginWorker("overflow-$it", pool) { rejectedCalls.incrementAndGet() }
                assertTrue(rejected.await(1_000)!!.exceptionOrNull() is RejectedExecutionException)
            }
            assertEquals(0, rejectedCalls.get())
            release.countDown()
            assertEquals("late", worker.await(3_000)!!.getOrThrow())
            pool.close()
            assertTrue(pool.awaitTermination(3_000))
            assertEquals(0, rejectedCalls.get()) // Rejected work was not silently queued.
        } finally { release.countDown(); pool.close() }
    }

    @Test fun separateCleanupCapacitySurvivesEnableSaturationAndIsItselfBounded() = runBlocking {
        val enables = PluginWorkerPool(1, "test-enable")
        val cleanups = PluginWorkerPool(1, "test-cleanup")
        val enableEntered = CountDownLatch(1)
        val enableRelease = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val cleanupRelease = CountDownLatch(1)
        val rejectedCalls = AtomicInteger()
        try {
            val enable = PluginWorker("blocked-enable", enables) { enableEntered.countDown(); enableRelease.await() }
            assertTrue(enableEntered.await(3, TimeUnit.SECONDS))
            val cleanup = pluginCleanupWorker("blocked-cleanup", {
                cleanupEntered.countDown(); cleanupRelease.await()
            }, {}, emptyList(), cleanups)
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
            assertNull(cleanup.await(20))
            val rejected = pluginCleanupWorker("overflow-cleanup", { rejectedCalls.incrementAndGet() },
                { rejectedCalls.incrementAndGet() }, listOf({ rejectedCalls.incrementAndGet(); Unit }), cleanups)
            assertTrue(rejected.await(1_000)!!.exceptionOrNull() is RejectedExecutionException)
            assertEquals(0, rejectedCalls.get())
            cleanupRelease.countDown(); enableRelease.countDown()
            assertTrue(cleanup.await(3_000)!!.getOrThrow().isEmpty())
            assertTrue(enable.await(3_000)!!.isSuccess)
        } finally { cleanupRelease.countDown(); enableRelease.countDown(); enables.close(); cleanups.close() }
    }

    @Test fun blockingCancellationHandlerOccupiesCleanupWorkerNotCaller() = runBlocking {
        val enables = PluginWorkerPool(1, "test-enable")
        val cleanups = PluginWorkerPool(1, "test-cleanup")
        val registered = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val cancelRelease = CountDownLatch(1)
        val disabled = AtomicInteger()
        try {
            val enable = PluginWorker<Unit>("cancellable-enable", enables) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { cancelEntered.countDown(); cancelRelease.await() }
                    registered.countDown()
                }
            }
            assertTrue(registered.await(3, TimeUnit.SECONDS))
            val cleanup = pluginCleanupWorker("cancel-enable", { enable.cancel() },
                { disabled.incrementAndGet(); Unit }, emptyList(), cleanups)
            assertTrue(cancelEntered.await(3, TimeUnit.SECONDS))
            assertNull(cleanup.await(20))
            assertEquals(0, disabled.get())
            val rejected = pluginCleanupWorker("more-cleanup", {}, {}, emptyList(), cleanups)
            assertTrue(rejected.await(1_000)!!.exceptionOrNull() is RejectedExecutionException)
            cancelRelease.countDown()
            assertTrue(cleanup.await(3_000)!!.getOrThrow().isEmpty())
            assertEquals(1, disabled.get())
            assertTrue(enable.await(3_000)!!.exceptionOrNull() is CancellationException)
        } finally { cancelRelease.countDown(); enables.close(); cleanups.close() }
    }

    @Test fun shutdownDoesNotInterruptOrReleaseChildrenAndFinally() = runBlocking {
        val pool = PluginWorkerPool(1, "test-enable")
        val cleanupPool = PluginWorkerPool(1, "test-cleanup")
        val childEntered = CountDownLatch(1)
        val childRelease = CountDownLatch(1)
        val finallyEntered = CountDownLatch(1)
        val finallyRelease = CountDownLatch(1)
        try {
            val worker = PluginWorker<Unit>("children-finally", pool) {
                coroutineScope {
                    launch {
                        withContext(NonCancellable) { childEntered.countDown(); childRelease.await() }
                    }
                    try { awaitCancellation() }
                    finally { finallyEntered.countDown(); finallyRelease.await() }
                }
            }
            assertTrue(childEntered.await(3, TimeUnit.SECONDS))
            val cancel = pluginCleanupWorker("cancel", { worker.cancel() }, {}, emptyList(), cleanupPool)
            assertTrue(cancel.await(3_000)!!.getOrThrow().isEmpty())
            assertNull(worker.await(20))
            val rejected = PluginWorker("still-occupied", pool) { fail("No free slot") }
            assertTrue(rejected.await(1_000)!!.exceptionOrNull() is RejectedExecutionException)
            pool.close()
            assertFalse(pool.awaitTermination(20))
            childRelease.countDown()
            assertTrue(finallyEntered.await(3, TimeUnit.SECONDS))
            assertNull(worker.await(20))
            finallyRelease.countDown()
            assertTrue(worker.await(3_000)!!.exceptionOrNull() is CancellationException)
            assertTrue(pool.awaitTermination(3_000))
            val closed = PluginWorker("closed", pool) { fail("Closed pool") }
            assertTrue(closed.await(1_000)!!.exceptionOrNull() is RejectedExecutionException)
        } finally { childRelease.countDown(); finallyRelease.countDown(); pool.close(); cleanupPool.close() }
    }
}
