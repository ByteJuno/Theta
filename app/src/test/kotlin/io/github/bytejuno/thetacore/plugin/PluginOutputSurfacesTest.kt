package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.chat.ChatTaskProgress
import io.github.bytejuno.thetacore.chat.outputTask
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.OutputSurface
import io.github.bytejuno.thetacore.sdk.TaskPhase
import io.github.bytejuno.thetacore.sdk.TaskProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PluginOutputSurfacesTest {
    private val budget = PluginExecutionBudget()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val router = PluginOutputSurfaces(budget, owner, DeviceInfo(33), timeoutMillis = 400)
    private val events = LinkedBlockingQueue<String>()
    private val failures = LinkedBlockingQueue<String>()

    @After fun close() { owner.cancel(); budget.close() }

    private fun add(id: String, rank: Int, available: Boolean = true, onRank: () -> Unit = {},
        clear: () -> Unit = {}, show: () -> Unit = {}):
        PluginOutputSurfaces.Binding {
        val surface = object : OutputSurface {
            override val id = id
            override val displayName = id
            override val priority get() = rank.also { onRank() }
            override fun isAvailable(device: DeviceInfo) = available && device.sdkInt == 33
            override fun show(task: TaskProgress) {
                show()
                events.add("$id show ${task.taskId} ${task.phase} ${task.toolName} ${task.waitingUser}")
            }
            override fun clear(taskId: String) {
                clear()
                events.add("$id clear $taskId")
            }
        }
        return router.binding(id) { failures.add(id) }.apply {
            update(listOf(RegisteredExtension(id, surface, 1)), false)
            activate()
        }
    }

    private fun expect(event: String) = assertEquals(event, events.poll(4, TimeUnit.SECONDS))

    private fun occupyBothSlots(id: String) = runBlocking {
        withTimeout(4_000) {
            List(2) {
                var lease: PluginExecutionBudget.Lease?
                do {
                    check(budget.awaitCapacity(id))
                    lease = budget.reserve(id, owner, {}, {})
                } while (lease == null)
                lease
            }
        }
    }

    private fun disable(high: PluginOutputSurfaces.Binding): PluginWorker<List<Throwable>> {
        high.update(emptyList(), true)
        return pluginCleanupWorker("surface-disable", {},
            { high.disableAfterCleanup { events.add("disabled") } },
            listOf({ events.add("resources closed"); Unit }))
    }

    @Test fun disableWaitsForQueuedClearWhileFallbackShows() = runBlocking {
        val high = add("high", 2)
        add("low", 1)
        router.present(ChatTaskProgress.Model.outputTask("a"))
        expect("high show a MODEL null false")
        val slots = occupyBothSlots("high")
        val close = disable(high)
        try {
            expect("low show a MODEL null false")
        } finally { slots.forEach { it.abandon() } }
        assertTrue(close.awaitPluginClose(4_000, high)!!.getOrThrow().isEmpty())
        expect("high clear a")
        expect("disabled")
        expect("resources closed")
    }

    @Test fun disableDeadlineReportsExhaustionAndRetiresQueuedClear() = runBlocking {
        val high = add("high", 2)
        add("low", 1)
        router.present(ChatTaskProgress.Model.outputTask("a"))
        expect("high show a MODEL null false")
        val slots = occupyBothSlots("high")
        try {
            val close = disable(high)
            expect("low show a MODEL null false")
            assertNull(close.awaitPluginClose(100, high))
            assertTrue(close.await(4_000)!!.getOrThrow().isNotEmpty())
            expect("disabled")
            expect("resources closed")
        } finally { slots.forEach { it.abandon() } }
        router.present(null)
        expect("low clear a")
        assertTrue(events.isEmpty())
    }

    @Test fun retiringQueuedClearsKeepsRoutingLaterTasks() = runBlocking {
        add("low", 1)
        repeat(100) { round ->
            val id = "high$round"
            val task = "task$round"
            val high = add(id, 2)
            router.present(ChatTaskProgress.Model.outputTask(task))
            expect("$id show $task MODEL null false")
            val slots = occupyBothSlots(id)
            try {
                val close = disable(high)
                expect("low show $task MODEL null false")
                // Queue the clear, then retire it concurrently with the router's retry.
                // Keep admission occupied so an expired clear cannot execute later.
                high.expireCleanup()
                assertTrue(close.await(4_000)!!.getOrThrow().isNotEmpty())
                expect("disabled")
                expect("resources closed")
            } finally { slots.forEach { it.abandon() } }
            router.present(null)
            expect("low clear $task")
            assertTrue(events.isEmpty())
        }
        assertTrue(failures.isEmpty())
    }

    @Test fun disableFinishesWhileUnrelatedRankingExceedsCloseDeadline() = runBlocking {
        val high = add("high", 100)
        val slow = AtomicBoolean(false)
        val ranking = CountDownLatch(1)
        repeat(22) { index ->
            add("backup$index", index, onRank = {
                if (slow.get()) {
                    ranking.countDown()
                    Thread.sleep(260)
                }
            })
        }
        router.present(ChatTaskProgress.Model.outputTask("a"))
        expect("high show a MODEL null false")
        slow.set(true)
        val start = System.nanoTime()
        router.present(ChatTaskProgress.Approval.outputTask("a"))
        assertTrue(ranking.await(2, TimeUnit.SECONDS))
        val close = disable(high)
        assertTrue(close.awaitPluginClose(5_000, high)!!.getOrThrow().isEmpty())
        expect("high clear a")
        expect("disabled")
        expect("resources closed")
        assertEquals("backup21 show a APPROVAL null true", events.poll(8, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(5))
        slow.set(false)
    }

    @Test fun supersededRankingStopsBeforeCallingRemainingSurfaces() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ranks = LinkedBlockingQueue<String>()
        val firstRead = AtomicBoolean(true)
        add("first", 2, onRank = {
            ranks.add("first")
            if (firstRead.getAndSet(false)) {
                entered.countDown()
                release.await()
            }
        })
        add("second", 1, onRank = { ranks.add("second") })
        try {
            router.present(ChatTaskProgress.Model.outputTask("a"))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("first", ranks.poll())
            router.present(ChatTaskProgress.Approval.outputTask("a"))
        } finally { release.countDown() }

        // Restart at the latest task before invoking any remaining stale ranking callbacks.
        assertEquals("first", ranks.poll(2, TimeUnit.SECONDS))
        assertEquals("second", ranks.poll(2, TimeUnit.SECONDS))
        expect("first show a APPROVAL null true")
        assertTrue(events.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test fun lateShowCompensationFailureIsReturnedByDisable() = runBlocking {
        val release = CountDownLatch(1)
        val drawn = AtomicBoolean(false)
        val high = add("high", 2, clear = { check(!drawn.get()) { "late clear failed" } }) {
            release.await()
            drawn.set(true)
        }
        add("low", 1)
        router.present(ChatTaskProgress.Model.outputTask("a"))
        val close = try {
            expect("high clear a")
            expect("low show a MODEL null false")
            disable(high)
        } finally { release.countDown() }
        val errors = close.awaitPluginClose(5_000, high)!!.getOrThrow()
        assertEquals("Output surface cleanup failed", errors.single().message)
        expect("high show a MODEL null false")
        expect("disabled")
        expect("resources closed")
    }

    @Test fun expiredInFlightClearRetainsResourcesUntilActualExit() = runBlocking {
        val release = CountDownLatch(1)
        val high = add("high", 2, clear = { release.await() })
        add("low", 1)
        router.present(ChatTaskProgress.Model.outputTask("a"))
        expect("high show a MODEL null false")
        val close = disable(high)
        try {
            expect("low show a MODEL null false")
            assertNull(close.awaitPluginClose(100, high))
            assertTrue(events.isEmpty())
        } finally { release.countDown() }
        assertTrue(close.await(4_000)!!.getOrThrow().isNotEmpty())
        expect("high clear a")
        expect("disabled")
        expect("resources closed")
    }

    @Test fun revokedBusySurfaceClearsAfterTaskEndsWithoutAnotherEvent() {
        val high = add("high", 2)
        add("low", 1)
        router.present(ChatTaskProgress.Approval.outputTask("a"))
        expect("high show a APPROVAL null true")
        val slots = occupyBothSlots("high")
        try {
            high.update(emptyList(), true)
            expect("low show a APPROVAL null true")
            router.present(null)
            expect("low clear a")
        } finally { slots.forEach { it.abandon() } }
        expect("high clear a")
        assertTrue(failures.isEmpty())
    }

    @Test fun busyFirstChoiceTakesOverPendingApprovalOnReleaseAlone() {
        val slots = occupyBothSlots("high")
        try {
            add("high", 2)
            add("low", 1)
            router.present(ChatTaskProgress.Approval.outputTask("a"))
            expect("low show a APPROVAL null true")
        } finally { slots.forEach { it.abandon() } }
        expect("low clear a")
        expect("high show a APPROVAL null true")
        assertTrue(failures.isEmpty())
    }

    @Test fun lateClearExitsBeforeReplacementCanShowTheSameTask() {
        val release = CountDownLatch(1)
        val old = add("high", 2, clear = { release.await() })
        add("low", 1)
        try {
            router.present(ChatTaskProgress.Model.outputTask("a"))
            expect("high show a MODEL null false")
            old.update(emptyList(), true)
            expect("low show a MODEL null false")
            add("high", 3)
        } finally { release.countDown() }
        expect("high clear a")
        expect("low clear a")
        expect("high show a MODEL null false")
    }

    @Test fun selectionReroutesOnRevokeAndDisableAndClearsWhenFinished() {
        add("unavailable", 80, false)
        val high = add("high", 70)
        val low = add("low", 10)
        router.present(ChatTaskProgress.Model.outputTask("a"))
        expect("high show a MODEL null false")
        router.present(ChatTaskProgress.Tool("lookup").outputTask("a"))
        expect("high show a TOOL lookup false")
        high.update(emptyList(), false)
        expect("high clear a")
        expect("low show a TOOL lookup false")
        router.present(ChatTaskProgress.Approval.outputTask("a"))
        expect("low show a APPROVAL null true")
        router.present(ChatTaskProgress.Recovery.outputTask("b"))
        expect("low clear a")
        expect("low show b RECOVERY null true")
        low.update(emptyList(), true)
        expect("low clear b")
        add("later", 30)
        expect("later show b RECOVERY null true")
        router.present(null)
        expect("later clear b")
        assertTrue(failures.isEmpty())
    }

    @Test fun failedShowIsAttributedAndFallbackReceivesSameTask() {
        add("broken", 2) { error("failed after drawing") }
        add("fallback", 1)
        router.present(ChatTaskProgress.Approval.outputTask("a"))
        expect("broken clear a")
        expect("fallback show a APPROVAL null true")
        assertEquals("broken", failures.poll())
    }

    @Test fun stuckShowRetainsSlotUntilActualExitAndLateDrawingIsCleared() {
        val release = CountDownLatch(1)
        add("blocked", 2) { release.await() }
        add("fallback", 1)
        try {
            router.present(ChatTaskProgress.Model.outputTask("a"))
            expect("blocked clear a")
            expect("fallback show a MODEL null false")
            assertEquals("blocked", failures.poll())
            assertTrue(budget.occupiedSlots() >= 1)
        } finally { release.countDown() }
        expect("blocked show a MODEL null false")
        expect("blocked clear a")
        router.present(null)
        expect("fallback clear a")
    }
}
