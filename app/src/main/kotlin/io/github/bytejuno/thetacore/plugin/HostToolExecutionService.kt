package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*

/** One host-owned coordinator. Never cold-recovers on service recreation. The composition root
 * must keep this instance for the process lifetime. No model transport, UI or plugin discovery.
 * Persistence runs on IO, outside the budget callbacks/lock; lock order is definition -> budget.
 */
internal class HostToolExecutionService(
    private val budget: PluginExecutionBudget,
    private val owner: CoroutineScope,
    val catalog: FrozenToolCatalog,
    val ledger: InvocationLedger,
    private val validator: Schema202012Validator,
    /** How long a cancelled or timed-out worker may take to exit before the owner needs a restart. */
    private val exitGraceMillis: Long = 2_000,
) {
    private val gate get() = catalog.gate
    private val control = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = mutableMapOf<String, PluginInvocation>()
    private val observations = mutableMapOf<String, Deferred<Unit>>()
    private val logicalResults = mutableMapOf<String, CompletableDeferred<Unit>>()
    @Volatile private var storageFailed = false
    private fun observe(mutation: Mutation): Mutation {
        if (mutation is Mutation.StorageFailure) storageFailed = true
        return mutation
    }

    /** ID-only approval API. The binding is always read from the current durable record. */
    suspend fun decide(callId: String, requestId: String, decision: Decision): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            if (storageFailed) return@withGate Mutation.Blocked("Storage failed; submissions closed")
            val request = ledger.get(callId)?.pending
            if (request == null || request.id != requestId) Mutation.Conflict("Approval is not current")
            else observe(ledger.resolveApproval(callId, request, decision))
        }
    }
    suspend fun prepare(input: InvocationInput): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            if (storageFailed) return@withGate Mutation.Blocked("Storage failed; submissions closed")
            val registered = observe(ledger.recordInvocation(input))
            if (registered !is Mutation.Applied && registered !is Mutation.AlreadyApplied) registered
            else observe(ledger.validateAndPrepare(input.binding.callId))
        }
    }

    /** Reserving capacity runs no tool code. Claim must be acknowledged before commit. */
    suspend fun dispatch(callId: String): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            if (storageFailed) return@withGate Mutation.Blocked("Storage failed; submissions closed")
            val existing = ledger.get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
            if (existing.state != Status.READY) return@withGate Mutation.Conflict("Not READY")
            // Persist stale READY calls as REJECTED even when the catalog entry was removed.
            val checked = observe(ledger.validateAndPrepare(callId))
            val record = when (checked) {
                is Mutation.Applied -> checked.record
                is Mutation.AlreadyApplied -> checked.record
                else -> return@withGate checked
            }
            if (record.state != Status.READY) return@withGate checked
            val entry = catalog.current(requireNotNull(record.input.definition)) ?: return@withGate Mutation.Blocked("Definition unavailable")
            var invocation: PluginInvocation? = null
            val reservation = budget.reserve(entry.definition.owner, owner,
                onCancel = { invocation?.cancel() }, onExit = {})
                ?: return@withGate Mutation.Blocked("Execution capacity unavailable")
            val claimed = try { observe(ledger.claimExecution(callId, record.revision)) }
                catch (error: Throwable) { reservation.abandon(); storageFailed = true; throw error }
            val ticket = (claimed as? Mutation.Applied)?.ticket
            if (ticket == null) { reservation.abandon(); return@withGate claimed }
            invocation = PluginInvocation(budget, entry.definition.owner, owner, entry.tool,
                Json.parseToJsonElement(ticket.argsJson).jsonObject,
                ToolInvocationRequest(record.input.runId, record.input.sessionId, callId, record.input.unattended),
                requestApproval = { _, risk ->
                    // SDK's unattended path returns false before this hook. Explicitly narrower than
                    // ledger ALLOW_ALWAYS: no unattended internal upgrades in this SDK slice.
                    withContext(Dispatchers.IO) {
                        gate.withGate {
                            if (storageFailed) false else {
                                val m = observe(ledger.requestRiskUpgrade(callId, ticket.attemptId, Risk.valueOf(risk.name)))
                                val r = when (m) { is Mutation.Applied -> m.record; is Mutation.AlreadyApplied -> m.record; else -> null }
                                r != null && r.pending == null && r.effectiveRisk >= Risk.valueOf(risk.name)
                            }
                        }
                    }
                }, frozenSpec = entry.spec, reservation = reservation)
            val call = invocation
            active[callId] = call
            logicalResults[callId] = CompletableDeferred()
            // Observer lifetime is independent of run cancellation; no plugin callbacks on IO.
            observations[callId] = control.async { monitor(ticket, call) }
            call.start()
            claimed
        }
    }

    private fun outcome(ticket: ExecutionTicket, value: PluginInvocationResult, entered: Boolean): Mutation {
        val result = value.result
        val structured = result?.structured
        val outputValid = structured == null || ticket.definition.outputSchema?.let { validator.valid(it, structured.toString()) } != false
        val original = result?.text ?: value.message.orEmpty()
        // PROTOCOL 3.8: valid JSON goes first under a label; invalid JSON is dropped and only an
        // otherwise empty result becomes a model-visible error.
        val text = when {
            structured == null -> original
            !outputValid -> original.ifEmpty { "Tool returned invalid structured output" }
            original.isEmpty() -> "[structured JSON]\n$structured"
            else -> "[structured JSON]\n$structured\n$original"
        }
        // Explicit SDK effect wins, including inconsistent READ/UNKNOWN. Absent effect is inferred
        // by the ledger from effective risk, never silently forced to NONE after tool entry.
        val effect = if (!entered) Effect.NONE else result?.effect?.let { Effect.valueOf(it.name) }
        val succeeded = value.status == PluginInvocationStatus.SUCCEEDED && (outputValid || original.isNotEmpty())
        return observe(ledger.recordOutcome(ticket.callId, ticket.attemptId,
            if (succeeded) Status.SUCCEEDED else Status.FAILED, text, effect))
    }
    /** Never acknowledge exit after an earlier outcome/evidence write was lost. Keeping the
     * durable instance UNCONFIRMED forces cold recovery to preserve the uncertainty. */
    private fun acknowledged(mutation: Mutation) {
        check(mutation is Mutation.Applied || mutation is Mutation.AlreadyApplied) {
            "Invocation transition not acknowledged: $mutation"
        }
    }
    private suspend fun monitor(ticket: ExecutionTicket, call: PluginInvocation) {
        try {
            val logical = call.terminal.await()
            gate.withGate {
                acknowledged(when (logical.status) {
                    PluginInvocationStatus.CANCELLED -> observe(ledger.cancel(ticket.callId))
                    PluginInvocationStatus.TIMED_OUT -> observe(ledger.timeout(ticket.callId))
                    else -> outcome(ticket, logical, call.bodyEntered)
                })
            }
            gate.withGate { logicalResults[ticket.callId] }?.complete(Unit)
            coroutineScope {
                // A cooperative worker needs a moment to unwind after cancellation; only an exit
                // that stays unconfirmed past the grace period stops the owner until restart.
                val restartWatch = if (logical.status in setOf(PluginInvocationStatus.CANCELLED, PluginInvocationStatus.TIMED_OUT)) launch {
                    delay(exitGraceMillis)
                    gate.withGate {
                        if (!call.actualExited.isCompleted)
                            acknowledged(observe(ledger.markRestartRequired(ticket.callId, ticket.attemptId)))
                    }
                } else null
                val returned = select<PluginInvocationResult?> {
                    call.returnReported.onAwait { it }
                    call.actualExited.onAwait { call.returnEvidence }
                }
                if (returned != null) gate.withGate { acknowledged(outcome(ticket, returned, call.bodyEntered)) }
                call.actualExited.await()
                restartWatch?.cancel()
                gate.withGate {
                    call.returnEvidence?.let { acknowledged(outcome(ticket, it, call.bodyEntered)) }
                    acknowledged(observe(ledger.recordActualExit(ticket.callId, ticket.attemptId, "Shared execution budget confirmed worker and cancellation handlers exited")))
                    active.remove(ticket.callId)
                    // A failed monitor keeps its entries so waiters still see the failure.
                    observations.remove(ticket.callId)
                    logicalResults.remove(ticket.callId)
                }
            }
        } catch (error: Throwable) {
            storageFailed = true
            gate.withGate { logicalResults[ticket.callId] }?.completeExceptionally(error)
            throw error
        }
    }
    suspend fun cancel(callId: String): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            val mutation = observe(ledger.cancel(callId))
            active[callId]?.cancel()
            mutation
        }
    }
    suspend fun awaitObservedExit(callId: String) { gate.withGate { observations[callId] }?.await() }

    /** Receives the fully consumed protocol reducer, not an optimistic Finished event.
     * emptyArguments is eligibility, not approval: preserve raw arguments and let the ledger
     * normalize empty/null to {} only after frozen-schema validation; never gate on complete alone.
     * Unknown names are registered as terminal rejections without a fabricated definition;
     * they retain their original binding/order and do not prevent other calls from executing.
     */
    suspend fun executeStep(reducer: ModelEventReducer, taskChain: String, session: String, run: String,
        step: String, unattended: Boolean = false): Message? = withContext(Dispatchers.IO) {
        reducer.endOfInput()
        val disposition = reducer.historyDisposition()
        if (disposition == HistoryDisposition.DISCARD) return@withContext null
        val calls = if (disposition == HistoryDisposition.NEEDS_CANCELLED_NONE_RESULTS) reducer.calls() else reducer.toolCandidates()
        if (calls.isEmpty()) return@withContext null
        // Unknown-tool rejections are permanent, so they need an actual catalog to be judged against.
        val frozen = catalog.frozen(run) ?: error("Run catalog not frozen: $run; step not submitted")
        val inputs = calls.mapIndexed { index, call ->
            val definition = frozen[call.name]
            InvocationInput(
                io.github.bytejuno.thetacore.agent.ledger.BindingValue.copyOf(call.binding), taskChain, session, run, step, index,
                call.argsJson, definition, unattended, definition?.declaredRisk ?: Risk.READ,
                disposition == HistoryDisposition.NEEDS_CANCELLED_NONE_RESULTS, call.name)
        }
        // Entire step is registered before any dispatch, so partial storage failure cannot execute it.
        val registered = gate.withGate {
            if (storageFailed) false else inputs.all {
                val m = observe(ledger.recordInvocation(it))
                m is Mutation.Applied || m is Mutation.AlreadyApplied
            }
        }
        if (!registered) return@withContext null
        for (input in inputs) {
            val id = input.binding.callId
            val prepared = gate.withGate { observe(ledger.validateAndPrepare(id)) }
            val record = when (prepared) { is Mutation.Applied -> prepared.record; is Mutation.AlreadyApplied -> prepared.record; else -> return@withContext null }
            if (record.state == Status.READY) {
                val dispatched = dispatch(id)
                if (dispatched is Mutation.Applied && dispatched.record.state.terminal) continue
                if (dispatched !is Mutation.Applied || dispatched.ticket == null) return@withContext null
                gate.withGate { logicalResults[id] }?.await()
                if (storageFailed) return@withContext null
                // Never wait indefinitely for a timed-out non-cooperative body just to return
                // its logical result. Subsequent claims still enforce the actual-exit barrier.
                if (ledger.get(id)?.state in setOf(Status.SUCCEEDED, Status.FAILED)) awaitObservedExit(id)
            } else if (!record.state.terminal) return@withContext null
        }
        ledger.resultsForStep(session, run, step, inputs.map { it.binding.callId })
    }
}
