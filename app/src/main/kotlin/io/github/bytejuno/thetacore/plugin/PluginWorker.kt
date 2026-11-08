package io.github.bytejuno.thetacore.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No waiting task queue, caller-runs fallback or replacement threads for stuck callbacks. */
internal class PluginWorkerPool(capacity: Int, label: String) : AutoCloseable {
    init { require(capacity > 0) }
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        0, capacity, 30, TimeUnit.SECONDS, SynchronousQueue(),
        java.util.concurrent.ThreadFactory { action ->
            Thread(action, "theta-$label-${sequence.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(action: () -> Unit) { executor.execute(action) }

    /** Stop admission, not execution. Existing tasks retain their threads until actual return. */
    override fun close() { executor.shutdown() }

    internal fun awaitTermination(timeoutMillis: Long): Boolean =
        executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
}

/** Independent budgets: cleanup remains admissible when all enable workers are stuck. */
internal object PluginWorkerPools {
    val enable = PluginWorkerPool(4, "enable")
    val cleanup = PluginWorkerPool(2, "cleanup")
}

/** Never a structured child of the caller: timeout bounds waiting, not arbitrary dex execution. */
internal class PluginWorker<T>(name: String, pool: PluginWorkerPool, action: suspend () -> T) {
    constructor(name: String, action: suspend () -> T) : this(name, PluginWorkerPools.enable, action)
    private val job = Job()
    private val result = CompletableDeferred<Result<T>>()

    init {
        try {
            pool.execute {
                val thread = Thread.currentThread()
                val previousName = thread.name
                try {
                    thread.name = name
                    val outcome = runCatching { runBlocking(job) { action() } }
                    result.complete(outcome)
                } finally {
                    thread.name = previousName
                }
            }
        } catch (error: Throwable) {
            result.complete(Result.failure(error))
        }
    }

    suspend fun await(timeoutMillis: Long): Result<T>? = withTimeoutOrNull(timeoutMillis) { result.await() }
    suspend fun await(timeoutMillis: Long, faults: PluginFaultBoundary): Result<T>? =
        withTimeoutOrNull(timeoutMillis) {
            kotlinx.coroutines.selects.select {
                faults.failure.onAwait { Result.failure<T>(it) }
                result.onAwait { it }
            }
        }

    suspend fun join(): Result<T> = result.await()

    // Cancellation handlers belong to plugin code too: call only on the bounded cleanup worker.
    fun cancel() { job.cancel() }
}

/** One deadline covers cancellation, onDisable and ALL reverse-order cleanup actions. */
internal fun pluginCleanupWorker(
    name: String,
    cancel: () -> Unit,
    onDisable: suspend () -> Unit,
    actions: List<() -> Unit>,
): PluginWorker<List<Throwable>> = pluginCleanupWorker(name, cancel, onDisable, actions, PluginWorkerPools.cleanup)

/** Injectable pool for latch-based tests; production always uses the shared cleanup pool. */
internal fun pluginCleanupWorker(
    name: String,
    cancel: () -> Unit,
    onDisable: suspend () -> Unit,
    actions: List<() -> Unit>,
    pool: PluginWorkerPool,
): PluginWorker<List<Throwable>> = PluginWorker(name, pool) {
    val errors = mutableListOf<Throwable>()
    runCatching(cancel).exceptionOrNull()?.let(errors::add)
    runCatching { onDisable() }.exceptionOrNull()?.let(errors::add)
    actions.forEach { action -> runCatching(action).exceptionOrNull()?.let(errors::add) }
    errors
}
