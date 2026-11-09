package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.plugin.tools.HostClockTool
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Process-owned composition. A failed initialization cannot masquerade as a cold restart. */
internal class HostToolBridgeRoot(private val budget: PluginExecutionBudget) {
    private val mutex = Mutex()
    private var attempted = false
    private var service: HostToolExecutionService? = null

    /** Open inside the one-shot gate: even a failed open must not retry as a fresh installation. */
    suspend fun initialize(openStore: () -> InvocationStore): HostToolExecutionService = withContext(Dispatchers.IO) {
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
                val catalog = FrozenToolCatalog(gate, budget, owner, validator)
                catalog.registerHost("host/clock", "1", HostClockTool())
                HostToolExecutionService(budget, owner, catalog, ledger, validator).also { service = it }
            } catch (error: Throwable) {
                owner.cancel()
                try { store.close() } catch (close: Throwable) { error.addSuppressed(close) }
                throw error
            }
        }
    }
}
