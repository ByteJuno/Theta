package io.github.bytejuno.thetacore.plugin

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PluginExecutionBudgetTest {
    @Test fun processAndPluginLimitsHaveNoQueueAndReleaseOnlyOnExit() = runBlocking {
        val budget = PluginExecutionBudget(2, 1, 1)
        val owner = CoroutineScope(SupervisorJob())
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val exits = AtomicInteger()
        try {
            fun submit(id: String) = budget.submit(id, owner, {}, { exits.incrementAndGet(); Unit }) {
                entered.countDown(); release.await()
            }
            val first = submit("a")!!
            assertNull(submit("a"))
            val second = submit("b")!!
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertNull(submit("c"))
            first.cancel()
            assertEquals(2, budget.occupiedSlots())
            assertFalse(first.actualExited.isCompleted)
            release.countDown()
            withTimeout(3_000) { first.actualExited.await(); second.actualExited.await() }
            assertEquals(0, budget.occupiedSlots())
            assertEquals(2, exits.get())
        } finally { release.countDown(); budget.close(); owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test fun saturatedCancellationRetainsRequestsAndSlotsUntilHandlerReturns() = runBlocking {
        val budget = PluginExecutionBudget(2, 1, 1)
        val owner = CoroutineScope(SupervisorJob())
        val running = CountDownLatch(2)
        val returnActions = CountDownLatch(1)
        val inCancel = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val secondCancelled = CountDownLatch(1)
        try {
            val first = budget.submit("a", owner, {}, {}) { running.countDown(); returnActions.await() }!!
            val second = budget.submit("b", owner, {}, {}) { running.countDown(); returnActions.await() }!!
            assertTrue(running.await(3, TimeUnit.SECONDS))
            first.execution.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                if (it != null) { inCancel.countDown(); releaseCancel.await() }
            }
            second.execution.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                if (it != null) secondCancelled.countDown()
            }
            first.cancel()
            assertTrue(inCancel.await(3, TimeUnit.SECONDS))
            second.cancel()
            // close must return despite a malicious cancellation handler; pending second request
            // remains in the slot registry instead of being dropped by executor rejection.
            budget.close()
            returnActions.countDown()
            assertEquals(2, budget.occupiedSlots())
            assertFalse(first.actualExited.isCompleted)
            assertFalse(second.actualExited.isCompleted)
            assertEquals(1L, secondCancelled.count)
            assertNull(budget.submit("c", owner, {}, {}) {})
            releaseCancel.countDown()
            assertTrue(secondCancelled.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { first.actualExited.await(); second.actualExited.await() }
            assertEquals(0, budget.occupiedSlots())
        } finally {
            returnActions.countDown(); releaseCancel.countDown(); budget.close()
            owner.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun ownerCancellationIsNonblockingButOwnerWaitsForActualExit() = runBlocking {
        val budget = PluginExecutionBudget(1, 1, 1)
        val ownerJob = SupervisorJob()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        try {
            val lease = budget.submit("a", CoroutineScope(ownerJob), { cancelled.countDown() }, {}) {
                entered.countDown(); release.await()
            }!!
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            ownerJob.cancel()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            assertFalse(ownerJob.isCompleted)
            assertFalse(lease.actualExited.isCompleted)
            release.countDown()
            withTimeout(3_000) { lease.actualExited.await(); ownerJob.join() }
        } finally { release.countDown(); budget.close(); ownerJob.cancel() }
    }
}
