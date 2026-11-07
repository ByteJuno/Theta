package io.github.bytejuno.thetacore.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.thread

/** Never a structured child of the caller: timeout bounds waiting, not arbitrary dex execution. */
internal class PluginWorker<T>(name: String, action: suspend () -> T) {
    private val job = Job()
    private val result = CompletableDeferred<Result<T>>()

    init {
        try {
            thread(name = name, isDaemon = true) {
                val outcome = runCatching { runBlocking(job) { action() } }
                result.complete(outcome)
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
): PluginWorker<List<Throwable>> = PluginWorker(name) {
    val errors = mutableListOf<Throwable>()
    runCatching(cancel).exceptionOrNull()?.let(errors::add)
    runCatching { onDisable() }.exceptionOrNull()?.let(errors::add)
    actions.forEach { action -> runCatching(action).exceptionOrNull()?.let(errors::add) }
    errors
}
