package io.github.bytejuno.thetacore.plugin

import android.os.Build
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.Extension
import io.github.bytejuno.thetacore.sdk.OutputSurface
import io.github.bytejuno.thetacore.sdk.TaskProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One process-owned projection of the current foreground or attention notification. */
internal class PluginOutputSurfaces(
    private val budget: PluginExecutionBudget,
    private val owner: CoroutineScope,
    private val device: DeviceInfo,
    private val timeoutMillis: Long = 1_000,
) {
    companion object {
        fun android(budget: PluginExecutionBudget) = PluginOutputSurfaces(
            budget, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            DeviceInfo(Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL))
    }

    private val gate = Any()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val candidates = mutableSetOf<Surface>()
    @Volatile private var desired: TaskProgress? = null
    // Only the consumer coroutine changes the displayed projection.
    private var displayed: Surface? = null
    private var displayedTask: TaskProgress? = null

    init { owner.launch { for (change in changes) reconcile() } }

    fun present(task: TaskProgress?) {
        desired = task
        changes.trySend(Unit)
    }

    fun binding(pluginId: String, report: (Throwable) -> Unit) = Binding(pluginId, report)

    internal inner class Binding(val pluginId: String, val report: (Throwable) -> Unit) {
        private val entries = mutableMapOf<Long, Surface>()
        private var active = false
        private var closed = false
        @Volatile internal var failed = false
            private set

        fun activate() = synchronized(gate) {
            if (!closed) {
                active = true
                candidates.addAll(entries.values)
                changes.trySend(Unit)
            }
            Unit
        }

        fun update(snapshot: List<RegisteredExtension<Extension>>, closing: Boolean) = synchronized(gate) {
            closed = closing
            val keep = snapshot.mapTo(mutableSetOf()) { it.registrationId }
            val removed = entries.keys - keep
            removed.forEach { id -> candidates.remove(entries.remove(id)) }
            if (!closed) for (entry in snapshot) {
                val output = entry.value as? OutputSurface ?: continue
                val item = entries.getOrPut(entry.registrationId) { Surface(this, output) }
                if (active) candidates.add(item)
            }
            changes.trySend(Unit)
            Unit
        }

        fun fail(reason: String) {
            if (failed) return
            failed = true
            report(IllegalStateException("Output surface: " + reason))
        }
    }

    private inner class Surface(val binding: Binding, val output: OutputSurface) {
        fun live() = !binding.failed && synchronized(gate) { this in candidates }

        suspend fun <T : Any> call(
            cleanup: Boolean = false,
            late: () -> Unit = {},
            action: () -> T,
        ): T? {
            val result = CompletableDeferred<Result<T>?>()
            var outcome: Result<T>? = null
            val lease = budget.submit(binding.pluginId, owner,
                onCancel = { result.complete(null) }, onExit = { result.complete(outcome) }) {
                if (cleanup || live()) {
                    outcome = runCatching(action)
                    if (result.isCompleted) runCatching(late)
                }
            } ?: return null
            val value = withTimeoutOrNull(timeoutMillis) { result.await() }
            if (value == null) {
                result.complete(null)
                lease.cancel()
                if (cleanup || live()) binding.fail("callback timed out or cancelled")
            } else value.exceptionOrNull()?.let { binding.fail(pluginFailureIdentity(it)) }
            return value?.getOrNull()
        }

        suspend fun clear(id: String) { call(cleanup = true) { output.clear(id) } }
    }

    private suspend fun clearDisplayed() {
        val previous = displayedTask
        if (previous != null) displayed?.clear(previous.taskId)
        displayed = null
        displayedTask = null
    }

    private suspend fun reconcile() {
        val task = desired
        if (displayedTask?.taskId != task?.taskId || displayed?.live() == false) clearDisplayed()
        if (task == null) return
        val snapshot = synchronized(gate) { candidates.toList() }
        val ranked = snapshot.mapNotNull { item ->
            item.call { item.output.priority }?.let { priority -> item to priority }
        }.sortedByDescending { it.second }
        for ((item, _) in ranked) {
            if (desired !== task) return
            if (item.call { item.output.isAvailable(device) } != true) continue
            if (item !== displayed) clearDisplayed()
            if (item === displayed && task == displayedTask) return
            if (desired !== task || !item.live()) return
            if (item.call(late = { item.output.clear(task.taskId) }) { item.output.show(task) } != null) {
                displayed = item
                displayedTask = task
                return
            }
            // show may throw after drawing or time out while still inside the plugin.
            item.clear(task.taskId)
            displayed = null
            displayedTask = null
        }
        clearDisplayed()
    }
}
