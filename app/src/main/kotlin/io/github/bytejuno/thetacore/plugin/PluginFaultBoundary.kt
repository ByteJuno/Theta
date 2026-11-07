package io.github.bytejuno.thetacore.plugin

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Never use Throwable.toString/message/stackTrace/cause: those may execute plugin code. */
internal fun pluginFailureIdentity(error: Throwable): String = error.javaClass.name

internal fun reportPluginFailure(report: (String) -> Unit, prefix: String, error: Throwable) {
    try { report("$prefix: ${pluginFailureIdentity(error)}") } catch (_: Throwable) { }
}

/** Shared production enqueue path. The exception handler never waits for operations. */
internal fun postPluginOperation(
    host: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    operations: Mutex,
    report: (Throwable) -> Unit,
    action: suspend () -> Unit,
) {
    fun failed(error: Throwable) { try { report(error) } catch (_: Throwable) { } }
    try {
        host.launch(dispatcher) {
            try { operations.withLock { action() } } catch (error: Throwable) { failed(error) }
        }
    } catch (error: Throwable) { failed(error) }
}

/** Reporting is deliberately after durable intent and protected by the close finally. */
internal suspend fun <T> finishPluginFault(
    persist: suspend () -> T,
    report: (T) -> Unit,
    close: suspend () -> Unit,
): T {
    try {
        val result = persist()
        try { report(result) } catch (_: Throwable) { }
        return result
    } finally { close() }
}

/** No eligibility IO on the accepted branch; failed refresh cannot replace request error. */
internal suspend fun requestPluginRollback(
    request: suspend () -> Unit,
    refreshRejected: suspend () -> Unit,
    accepted: () -> Unit,
    close: suspend () -> Unit,
) {
    try { request() } catch (error: Throwable) {
        try { refreshRejected() } catch (_: Throwable) { }
        throw error
    }
    try { accepted() } finally { close() }
}

/** Called by serialized host operations; keep the quarantine on any deletion failure. */
internal suspend fun deletePluginRecovery(
    id: String,
    blocked: MutableSet<String>,
    delete: suspend () -> Unit,
) {
    delete()
    blocked.remove(id)
}

internal data class ManagedPluginFault(val error: Throwable, val duringInitialization: Boolean)

/** Only short host-memory transitions under this lock: no IO, waits or plugin callbacks.
 * beginStart arbitrates successful onEnable against initialization faults. Once it wins,
 * faults are runtime faults (even during durable commit), but publication still requires
 * successful IO AND a second fault check. No lock is held across IO.
 */
internal class PluginFaultBoundary(
    private val closeAdmission: () -> Unit,
    private val postFault: () -> Unit,
    private val reportInternalFailure: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private var started = false
    private var closed = false
    private var fault: ManagedPluginFault? = null
    private var handled = false
    val failure = CompletableDeferred<Throwable>()
    val handler = CoroutineExceptionHandler { _, error -> report(error) }

    fun report(error: Throwable) {
        if (error is CancellationException) return
        // Exception handlers must NEVER propagate failures into Android's uncaught handler.
        try {
            synchronized(lock) {
                if (closed || fault != null) return
                fault = ManagedPluginFault(error, duringInitialization = !started)
            }
            try { closeAdmission() } catch (internal: Throwable) { reportSafely(internal) }
            failure.complete(error)
            try { postFault() } catch (internal: Throwable) { reportSafely(internal) }
        } catch (internal: Throwable) {
            reportSafely(internal)
        }
    }

    fun beginStart(): Boolean = synchronized(lock) {
        if (closed || fault != null) return false
        check(!started)
        started = true
        true
    }

    /** Called on Main AFTER durable commit; host-memory publication only. */
    private fun publishStarted(publish: () -> Unit): Boolean = synchronized(lock) {
        check(started)
        if (closed || fault != null) return false
        publish()
        true
    }

    suspend fun completeStart(persist: suspend () -> Unit, publish: () -> Unit): Boolean {
        if (!beginStart()) return false
        persist()
        return publishStarted(publish)
    }

    fun close() { synchronized(lock) { closed = true } }
    fun hasFault(): Boolean = synchronized(lock) { fault != null }

    /** Serialized host handling, including callbacks queued before a card was replaced. */
    fun takeFault(current: PluginFaultBoundary?): ManagedPluginFault? = synchronized(lock) {
        if (current !== this || handled) return null
        val result = fault ?: return null
        handled = true
        result
    }

    private fun reportSafely(error: Throwable) {
        try { reportInternalFailure(error) } catch (_: Throwable) { /* terminal reporter */ }
    }
}

/** Registry writers never touch Compose state. Queued Main callbacks read the latest
 * snapshot, not a captured old list, so delayed publication cannot resurrect entries.
 */
internal class PluginPublication<T>(private val postRefresh: () -> Unit) {
    private val latest = AtomicReference<List<T>>(emptyList())
    fun update(values: List<T>) {
        latest.set(values)
        postRefresh()
    }
    fun current(): List<T> = latest.get()
}

internal data class PluginFaultPersistence(
    val state: PluginState,
    val rollback: Boolean,
    val errors: List<Throwable>,
)

/** IO only. Failure to persist must not skip revocation/cancellation in the caller.
 * Keep the initialization marker until recovery: failed rollback writes can be retried
 * at cold start. Explicit user state changes clear resumeAfterRollback.
 */
internal fun persistPluginFault(
    states: PluginStateStore,
    updates: PluginUpdates,
    id: String,
    version: Long,
    duringInitialization: Boolean,
): PluginFaultPersistence {
    val errors = mutableListOf<Throwable>()
    var disabled = PluginState(enabled = false, initializing = duringInitialization)
    try {
        val previous = states.read(id)
        disabled = previous.copy(
            enabled = false,
            initializing = duringInitialization,
            resumeAfterRollback = duringInitialization && previous.enabled && !previous.pendingDelete,
        )
        states.write(id, disabled)
    } catch (error: Throwable) { errors += error }
    val rollback = if (duringInitialization) {
        try { updates.failed(id, version) } catch (error: Throwable) { errors += error; false }
    } else false
    return PluginFaultPersistence(disabled, rollback, errors)
}

/** Recovery of one corrupt ID must not abort restore or retry the same broken record
 * while computing its UI affordances. The caller retains the card for uninstall.
 */
internal suspend fun <T> isolatePluginRecovery(
    id: String,
    blocked: MutableSet<String>,
    report: (String) -> Unit,
    action: suspend () -> T,
): T? {
    if (id in blocked) return null
    return try { action() } catch (error: Exception) {
        if (error is CancellationException) throw error
        blocked += id
        report("$id: state recovery failed: ${pluginFailureIdentity(error)}")
        null
    }
}
