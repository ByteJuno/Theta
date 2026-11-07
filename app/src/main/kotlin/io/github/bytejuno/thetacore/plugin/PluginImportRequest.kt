package io.github.bytejuno.thetacore.plugin

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Wait for the SAME manager mutation gate with the caller Job; only the acquired durable
 * transition adopts host lifetime. Cancelled waiters never queue cleanup behind the gate.
 */
internal suspend fun <T> serializedPluginImport(
    gate: Mutex, hostContext: CoroutineContext, action: suspend () -> T,
): T {
    gate.lock()
    try { return withContext(hostContext) { action() } }
    finally { gate.unlock() }
}

/** Main-only UI request identity. Cancellation retires UI ownership immediately, independent
 * of provider return or old coroutine finally. Each request owns a distinct manager session.
 */
internal class PluginImportRequests(private val close: (PluginImportSession) -> Unit) {
    internal class Request {
        val session = PluginImportSession()
        var job: Job? = null
    }
    var current: Request? = null
        private set
    fun begin(): Request {
        cancel()
        return Request().also { current = it }
    }
    fun owns(request: Request): Boolean = current === request
    fun cancel() {
        val old = current ?: return
        current = null
        close(old.session)
        old.job?.cancel()
    }
    fun finishReading(request: Request): Boolean = owns(request)
    // Confirmation/install retains this session until replacement/disposal. Install takes its
    // stage under the gate before suspend; closing afterward cannot delete the installed file.
}
