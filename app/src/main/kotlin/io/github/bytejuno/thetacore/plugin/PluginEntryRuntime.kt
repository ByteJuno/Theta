package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.DeviceInfo
import io.github.bytejuno.thetacore.sdk.EntryAdapter
import io.github.bytejuno.thetacore.sdk.EntryHost
import io.github.bytejuno.thetacore.sdk.EntryRequest
import io.github.bytejuno.thetacore.sdk.EntrySession
import io.github.bytejuno.thetacore.sdk.EntryUnavailableException
import java.util.concurrent.ScheduledFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class EntryRuntimeStatus { NEW, INITIALIZING, READY, UNAVAILABLE, STOPPED }
internal data class EntryRuntimeState(
    val status: EntryRuntimeStatus,
    val displayName: String? = null,
    val message: String? = null,
)

/** One attach attempt per registered object; failure affects this entry, not the whole plugin. */
internal class PluginEntryRuntime(
    private val budget: PluginExecutionBudget,
    private val pluginId: String,
    private val owner: CoroutineScope,
    private val adapter: EntryAdapter,
    private val device: DeviceInfo,
    private val openSession: suspend (EntryRequest) -> EntrySession = { throw EntryUnavailableException() },
    private val initializationTimeoutMillis: Long = 30_000,
) : AutoCloseable {
    init { require(initializationTimeoutMillis > 0) }
    private val mutableState = MutableStateFlow(EntryRuntimeState(EntryRuntimeStatus.NEW))
    val state: StateFlow<EntryRuntimeState> = mutableState.asStateFlow()
    private val exited = CompletableDeferred<Unit>()
    val actualExited: Deferred<Unit> = exited
    private var lease: PluginExecutionBudget.Lease? = null
    private var timer: ScheduledFuture<*>? = null
    private var startedNanos = 0L

    fun start(): Unit = synchronized(budget.gate) {
        if (state.value.status != EntryRuntimeStatus.NEW) return
        mutableState.value = EntryRuntimeState(EntryRuntimeStatus.INITIALIZING)
        startedNanos = System.nanoTime()
        lease = budget.submit(pluginId, owner,
            onCancel = { stopState() },
            onExit = { exited.complete(Unit); Unit },
        ) {
            try {
                // All plugin getters and availability execute inside the admitted worker.
                val name = adapter.displayName.take(256)
                val available = adapter.isAvailable(device)
                synchronized(budget.gate) {
                    if (state.value.status == EntryRuntimeStatus.INITIALIZING) {
                        mutableState.value = state.value.copy(displayName = name)
                    }
                }
                if (!available) {
                    unavailable("Entry is not available on this device")
                } else if (state.value.status == EntryRuntimeStatus.INITIALIZING) {
                    coroutineScope { adapter.attach(object : EntryHost {
                        override fun ready() = synchronized(budget.gate) {
                            if (state.value.status == EntryRuntimeStatus.INITIALIZING && initializationExpired()) {
                                unavailable("Entry initialization timed out")
                                lease?.cancel()
                            }
                            if (state.value.status == EntryRuntimeStatus.INITIALIZING) {
                                mutableState.value = state.value.copy(status = EntryRuntimeStatus.READY)
                                timer?.cancel(false)
                                timer = null
                            }
                        }
                        override suspend fun open(request: EntryRequest): EntrySession {
                            if (state.value.status != EntryRuntimeStatus.READY) throw EntryUnavailableException()
                            return openSession(request)
                        }
                    }) }
                    unavailable("Entry listener returned unexpectedly")
                }
            } catch (error: Throwable) {
                // A plugin's own CancellationException is still an unexpected stop unless the
                // host already recorded owner stop/timeout. Never read arbitrary error.message.
                unavailable(if (error is CancellationException) "Entry listener cancelled unexpectedly"
                    else "Entry failed: ${pluginFailureIdentity(error).take(256)}")
            }
        }
        if (lease == null) {
            unavailable("Entry execution capacity unavailable")
            exited.complete(Unit)
        } else if (state.value.status == EntryRuntimeStatus.INITIALIZING) {
            timer = budget.schedule(initializationTimeoutMillis) {
                synchronized(budget.gate) {
                    if (state.value.status == EntryRuntimeStatus.INITIALIZING) {
                        unavailable("Entry initialization timed out")
                        lease?.cancel()
                    }
                }
            }
        }
    }

    private fun initializationExpired(): Boolean =
        System.nanoTime() - startedNanos >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(initializationTimeoutMillis)

    private fun unavailable(message: String) = synchronized(budget.gate) {
        if (state.value.status == EntryRuntimeStatus.INITIALIZING || state.value.status == EntryRuntimeStatus.READY) {
            mutableState.value = state.value.copy(status = EntryRuntimeStatus.UNAVAILABLE, message = message)
            timer?.cancel(false)
            timer = null
        }
    }

    private fun stopState() {
        if (state.value.status != EntryRuntimeStatus.UNAVAILABLE && state.value.status != EntryRuntimeStatus.STOPPED) {
            mutableState.value = state.value.copy(status = EntryRuntimeStatus.STOPPED, message = null)
        }
        timer?.cancel(false)
        timer = null
    }

    /** Revocation/owner stop is nonblocking and cannot run plugin cancellation handlers inline. */
    override fun close() = synchronized(budget.gate) {
        stopState()
        lease?.cancel()
        if (lease == null) exited.complete(Unit)
    }
}
