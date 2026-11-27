package io.github.bytejuno.thetacore.plugin

import android.os.Build
import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.Extension
import io.github.bytejuno.thetacore.sdk.OutputSurface
import io.github.bytejuno.thetacore.sdk.TaskProgress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private data class Cleanup(val surface: Surface, val taskId: String)
    private val outstanding = ConcurrentHashMap.newKeySet<Cleanup>()
    private val pendingClears = ConcurrentHashMap.newKeySet<Cleanup>()
    private val draining = ConcurrentHashMap<PluginExecutionBudget.Lease, Binding>()
    private val closingBindings = mutableSetOf<Binding>()
    private val waitingForCapacity = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Callback<out T> {
        data class Done<T>(val value: T) : Callback<T>
        data object Busy : Callback<Nothing>
        data object Unavailable : Callback<Nothing>
        data class Failed(val cause: Throwable? = null) : Callback<Nothing>
    }

    private fun retryOnCapacity(pluginId: String) {
        if (!waitingForCapacity.add(pluginId)) return
        owner.launch {
            val retry = budget.awaitCapacity(pluginId)
            waitingForCapacity.remove(pluginId)
            if (retry) changes.trySend(Unit)
        }
    }

    init {
        owner.launch {
            for (change in changes) reconcile()
        }
    }

    fun present(task: TaskProgress?) {
        desired = task
        changes.trySend(Unit)
    }

    fun binding(pluginId: String, report: (Throwable) -> Unit) = Binding(pluginId, report)

    internal inner class Binding(val pluginId: String, val report: (Throwable) -> Unit) {
        private val entries = mutableMapOf<Long, Surface>()
        private var active = false
        private var closed = false
        private var registeredSurface = false
        internal val callbacks = Mutex()
        internal val drained = CompletableDeferred<Unit>()
        internal val expiry = CompletableDeferred<Unit>()
        internal val cleanupExpired get() = expiry.isCompleted
        @Volatile internal var cleanupFailed = false
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
                registeredSurface = true
                val item = entries.getOrPut(entry.registrationId) { Surface(this, output) }
                if (active) candidates.add(item)
            }
            if (closed) {
                if (!registeredSurface) drained.complete(Unit)
                else if (!drained.isCompleted && closingBindings.add(this)) owner.launch { drain(this@Binding) }
            }
            changes.trySend(Unit)
            Unit
        }

        fun expireCleanup() {
            expiry.complete(Unit)
            changes.trySend(Unit)
        }

        fun fail(reason: String) {
            if (failed) return
            failed = true
            report(IllegalStateException("Output surface: " + reason))
        }
    }

    private suspend fun drain(binding: Binding) {
        // Cross the same mutex as selection to include a show already admitted at revocation.
        val tasks = binding.callbacks.withLock { outstanding.filter { it.surface.binding === binding } }
        for (task in tasks) {
            while (true) {
                val released = budget.nextSlotRelease()
                if (task.surface.clear(task.taskId) !== Callback.Busy) break
                select<Unit> {
                    released.onAwait { }
                    binding.expiry.onAwait { }
                }
            }
        }
        draining.filterValues { it === binding }.keys.forEach { it.actualExited.await() }
        synchronized(gate) {
            binding.drained.complete(Unit)
            closingBindings.remove(binding)
        }
        changes.trySend(Unit)
    }

    private inner class Surface(val binding: Binding, val output: OutputSurface) {
        fun live() = !binding.failed && synchronized(gate) { this in candidates }

        suspend fun <T : Any> read(action: () -> T): Callback<T> = binding.callbacks.withLock {
            if (live()) call(action = action) else Callback.Unavailable
        }

        suspend fun show(task: TaskProgress): Callback<Unit> = binding.callbacks.withLock {
            if (!live()) return@withLock Callback.Unavailable
            val result = call(late = { output.clear(task.taskId) }) { output.show(task) }
            if (result is Callback.Done || result is Callback.Failed) outstanding.add(Cleanup(this, task.taskId))
            result
        }

        private suspend fun <T : Any> call(
            cleanup: Boolean = false,
            late: () -> Unit = {},
            action: () -> T,
        ): Callback<T> {
            val result = CompletableDeferred<Callback<T>>()
            var outcome: Callback<T> = Callback.Unavailable
            val lease = budget.submit(binding.pluginId, owner,
                onCancel = { result.complete(Callback.Failed()) }, onExit = { result.complete(outcome) }) {
                val admitted = if (cleanup) !binding.cleanupExpired else live()
                if (admitted) {
                    outcome = try { Callback.Done(action()) } catch (t: Throwable) {
                        Callback.Failed(t)
                    }
                    if (result.isCompleted) runCatching(late).onFailure { binding.cleanupFailed = true }
                }
            } ?: run {
                if (budget.isClosed()) return Callback.Unavailable
                retryOnCapacity(binding.pluginId)
                return Callback.Busy
            }
            val value = withTimeoutOrNull(timeoutMillis) { result.await() }
            if (value == null) {
                result.complete(Callback.Failed())
                lease.cancel()
            }
            if (value == null || value is Callback.Failed) {
                // A timed-out worker can still draw or clear, even across re-registration.
                draining[lease] = binding
                lease.actualExited.invokeOnCompletion { changes.trySend(Unit) }
                val failure = value as? Callback.Failed
                if (cleanup || live()) binding.fail(failure?.cause?.let(::pluginFailureIdentity) ?: "callback timed out or cancelled")
                return failure ?: Callback.Failed()
            }
            return value
        }

        suspend fun clear(id: String): Callback<Unit> = binding.callbacks.withLock {
            val pending = Cleanup(this, id)
            if (pending !in outstanding) return@withLock Callback.Done(Unit)
            val outcome = if (binding.cleanupExpired) Callback.Unavailable else call(cleanup = true) { output.clear(id) }
            if (outcome === Callback.Busy) {
                pendingClears.add(pending)
            } else {
                if (outcome !is Callback.Done) binding.cleanupFailed = true
                pendingClears.remove(pending)
                outstanding.remove(pending)
            }
            outcome
        }
    }

    private suspend fun clearDisplayed() {
        val previous = displayedTask
        if (previous != null) displayed?.clear(previous.taskId)
        displayed = null
        displayedTask = null
    }

    private fun awaitingCleanup(item: Surface): Boolean {
        val pluginId = item.binding.pluginId
        return synchronized(gate) { closingBindings.any { it.pluginId == pluginId } } ||
            pendingClears.any { it.surface.binding.pluginId == pluginId } || draining.keys.any { it.pluginId == pluginId }
    }

    private suspend fun reconcile() {
        draining.keys.removeAll { it.actualExited.isCompleted }
        pendingClears.toList().forEach { it.surface.clear(it.taskId) }
        val task = desired
        if (displayedTask?.taskId != task?.taskId || displayed?.live() == false) clearDisplayed()
        if (task == null) return
        val snapshot = synchronized(gate) { candidates.toList() }
        val ranked = snapshot.mapNotNull { item ->
            if (awaitingCleanup(item)) return@mapNotNull null
            val priority = item.read { item.output.priority }
            if (priority is Callback.Done) item to priority.value else null
        }.sortedByDescending { it.second }
        for ((item, _) in ranked) {
            if (desired !== task) return
            val available = item.read { item.output.isAvailable(device) }
            if (available !is Callback.Done || !available.value) continue
            if (item !== displayed) clearDisplayed()
            if (awaitingCleanup(item)) continue
            if (item === displayed && task == displayedTask) return
            if (desired !== task || !item.live()) return
            when (item.show(task)) {
                is Callback.Done -> {
                    displayed = item
                    displayedTask = task
                    return
                }
                is Callback.Failed -> item.clear(task.taskId)
                else -> continue
            }
            displayed = null
            displayedTask = null
        }
        clearDisplayed()
    }
}

internal suspend fun PluginOutputSurfaces.Binding?.disableAfterCleanup(disable: suspend () -> Unit) {
    this?.drained?.await()
    disable()
    check(this?.cleanupFailed != true) { "Output surface cleanup failed" }
}

internal suspend fun PluginWorker<List<Throwable>>.awaitPluginClose(
    timeoutMillis: Long,
    binding: PluginOutputSurfaces.Binding?,
): Result<List<Throwable>>? = await(timeoutMillis).also { result ->
    if (result == null || result.isFailure) binding?.expireCleanup()
}
