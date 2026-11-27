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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private fun add(id: String, rank: Int, available: Boolean = true, show: () -> Unit = {}):
        PluginOutputSurfaces.Binding {
        val surface = object : OutputSurface {
            override val id = id
            override val displayName = id
            override val priority = rank
            override fun isAvailable(device: DeviceInfo) = available && device.sdkInt == 33
            override fun show(task: TaskProgress) {
                show()
                events.add("$id show ${task.taskId} ${task.phase} ${task.toolName} ${task.waitingUser}")
            }
            override fun clear(taskId: String) { events.add("$id clear $taskId") }
        }
        return router.binding(id) { failures.add(id) }.apply {
            update(listOf(RegisteredExtension(id, surface, 1)), false)
            activate()
        }
    }

    private fun expect(event: String) = assertEquals(event, events.poll(4, TimeUnit.SECONDS))

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
