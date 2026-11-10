package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.plugin.tools.HostClockTool
import io.github.bytejuno.thetacore.sdk.Tool
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Process-owned composition. A failed initialization cannot masquerade as a cold restart. */
internal class HostToolBridgeRoot(
    private val budget: PluginExecutionBudget,
    private val hostTool: Tool = HostClockTool(),
) {
    // Startup is one-shot, so reading trusted host metadata must not lose a race against plugin
    // entry listeners that hold shared slots for their lifetime. Tool bodies still use [budget].
    private val metadataBudget = PluginExecutionBudget(processSlots = 1, perPluginSlots = 1, cancellationThreads = 1)
    private val mutex = Mutex()
    private var attempted = false
    private var service: HostToolExecutionService? = null

    /** Open inside the one-shot gate: even a failed open must not retry as a fresh installation. */
    suspend fun initialize(screenBackend: io.github.bytejuno.thetacore.plugin.tools.ScreenBackend? = null,
        openStore: () -> InvocationStore): HostToolExecutionService = withContext(Dispatchers.IO) {
        mutex.withLock {
            service?.let { return@withLock it }
            check(!attempted) { "Bridge initialization already attempted; process restart required" }
            attempted = true
            val store = openStore()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val gate = DefinitionGate()
                val validator = Schema202012Validator()
                val ledger = InvocationLedger(store, gate, validator)
                val recovery = ledger.recoverColdStart(UUID.randomUUID().toString())
                check(recovery !is Mutation.StorageFailure && recovery !is Mutation.Conflict) {
                    "Host tool cold recovery failed: $recovery"
                }
                val catalog = FrozenToolCatalog(gate, metadataBudget, owner, validator)
                catalog.registerHost("host/clock", "1", hostTool)
                screenBackend?.let {
                    catalog.registerHost("host/screen_info", "1", io.github.bytejuno.thetacore.plugin.tools.HostScreenTool(it))
                }
                catalog.registerHost("host/blob_read", "1", io.github.bytejuno.thetacore.plugin.tools.HostBlobReadTool(ledger))
                HostToolExecutionService(budget, owner, catalog, ledger, validator).also { service = it }
            } catch (error: Throwable) {
                owner.cancel()
                try { store.close() } catch (close: Throwable) { error.addSuppressed(close) }
                // A metadata timeout is a startup failure. Rethrown as cancellation, every caller
                // awaiting the shared startup would be silently cancelled instead of seeing it.
                if (error is CancellationException && currentCoroutineContext().isActive)
                    throw IllegalStateException("Host tool startup failed", error)
                throw error
            }
        }
    }
}
