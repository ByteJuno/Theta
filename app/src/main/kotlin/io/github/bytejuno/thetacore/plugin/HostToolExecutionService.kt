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
    /** Test scheduling seam outside the gate; never supplies authorization or executes plugin code. */
    private val afterRuntimeApprovalStart: suspend () -> Unit = {},
) {
    private val gate get() = catalog.gate
    private val control = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = mutableMapOf<String, PluginInvocation>()
    private val observations = mutableMapOf<String, Deferred<Unit>>()
    private val logicalResults = mutableMapOf<String, CompletableDeferred<Unit>>()
    // Signals carry no authorization. On wake the coroutine re-reads the durable record under gate.
    private class ApprovalWaiter(var requestId: String, var signal: CompletableDeferred<Unit> = CompletableDeferred())
    private val approvalWaiters = mutableMapOf<String, ApprovalWaiter>()
    private fun wakeApproval(callId: String) { approvalWaiters[callId]?.signal?.complete(Unit) }
    @Volatile private var storageFailed = false
    private val storageFailure = CompletableDeferred<Unit>()
    private fun closeSubmissions() {
        storageFailed = true
        storageFailure.complete(Unit)
    }
    private fun observe(mutation: Mutation): Mutation {
        if (mutation is Mutation.StorageFailure) closeSubmissions()
        return mutation
    }

    /** ID-only approval API. The binding is always read from the current durable record. */
    suspend fun decide(callId: String, requestId: String, decision: Decision): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            if (storageFailed) return@withGate Mutation.Blocked("Storage failed; submissions closed")
            val record = ledger.get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
            val request = record.pending
            if (request == null || request.id != requestId) Mutation.Conflict("Approval is not current")
            else if (request.internal && (active[callId] == null || active[callId]!!.terminal.isCompleted ||
                    active[callId]!!.actualExited.isCompleted)) Mutation.Conflict("Runtime approval instance is no longer live")
            else if (catalog.frozen(record.input.runId) == null) Mutation.Blocked("Run catalog not frozen")
            else observe(ledger.decideApproval(callId, requestId, decision)).also { mutation ->
                // Only the owner of the replaced ID follows an evidence-driven renewal.
                if (mutation is Mutation.Applied) approvalWaiters[callId]?.takeIf { it.requestId == requestId }?.let { waiter ->
                    mutation.record.pending?.takeIf { it.internal }?.let { waiter.requestId = it.id }
                }
                wakeApproval(callId)
            }
        }
    }
    suspend fun decideRecovery(callId: String, requestId: String, resolution: RecoveryResolution): Mutation =
        withContext(Dispatchers.IO) {
            gate.withGate {
                if (storageFailed) Mutation.Blocked("Storage failed; submissions closed")
                else observe(ledger.decideRecovery(callId, requestId, resolution))
            }
        }

    private suspend fun requestRuntimeApproval(ticket: ExecutionTicket, summary: String, risk: Risk): Boolean =
        withContext(Dispatchers.IO) {
            var owned: ApprovalWaiter? = null
            try {
                // Decide the no-wait result in this gate too: it must never inspect/adopt a
                // different request that starts after this gate is released.
                val immediate = gate.withGate {
                    if (storageFailed || approvalWaiters.containsKey(ticket.callId) ||
                        active[ticket.callId]?.terminal?.isCompleted != false) return@withGate false
                    val m = observe(ledger.requestRiskUpgrade(ticket.callId, ticket.attemptId, risk, summary))
                    val r = (m as? Mutation.Applied)?.record ?: return@withGate false
                    val pending = r.pending ?: return@withGate true
                    owned = ApprovalWaiter(pending.id).also { approvalWaiters[ticket.callId] = it }
                    null
                }
                afterRuntimeApprovalStart()
                if (immediate != null) return@withContext immediate
                val waiter = requireNotNull(owned)
                while (true) {
                    select<Unit> {
                        waiter.signal.onAwait { }
                        storageFailure.onAwait { }
                    }
                    val answer = gate.withGate {
                        val r = ledger.get(ticket.callId)
                        if (approvalWaiters[ticket.callId] !== waiter || storageFailed || r == null ||
                            active[ticket.callId]?.terminal?.isCompleted != false || r.state != Status.RUNNING ||
                            r.instance?.attemptId != ticket.attemptId || r.instance?.state != InstanceState.UNCONFIRMED)
                            return@withGate false
                        if (r.pending != null) {
                            if (r.pending?.id != waiter.requestId) return@withGate false
                        } else {
                            val checked = observe(ledger.checkRiskApproval(ticket.callId, ticket.attemptId, waiter.requestId))
                            val current = when (checked) {
                                is Mutation.Applied -> checked.record
                                is Mutation.AlreadyApplied -> checked.record
                                else -> return@withGate false
                            }
                            val renewed = current.pending ?: return@withGate true
                            waiter.requestId = renewed.id
                        }
                        waiter.signal = CompletableDeferred()
                        null
                    }
                    answer?.let { return@withContext it }
                }
                @Suppress("UNREACHABLE_CODE") false
            } finally {
                gate.withGate {
                    owned?.let { waiter ->
                        if (approvalWaiters[ticket.callId] === waiter) {
                            approvalWaiters.remove(ticket.callId)
                            observe(ledger.withdrawRiskUpgrade(ticket.callId, ticket.attemptId, waiter.requestId))
                        }
                    }
                }
            }
        }

    suspend fun prepare(input: InvocationInput): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            if (storageFailed) return@withGate Mutation.Blocked("Storage failed; submissions closed")
            if (catalog.frozen(input.runId) == null) return@withGate Mutation.Blocked("Run catalog not frozen")
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
            // Missing process-local run metadata is not evidence of a stale definition. Do not
            // reconstruct aliases from durable records; a trusted caller must explicitly freeze.
            if (catalog.frozen(existing.input.runId) == null) return@withGate Mutation.Blocked("Run catalog not frozen")
            // With a known run directory, persist genuinely stale READY calls as REJECTED.
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
                catch (error: Throwable) { reservation.abandon(); closeSubmissions(); throw error }
            val ticket = (claimed as? Mutation.Applied)?.ticket
            if (ticket == null) { reservation.abandon(); return@withGate claimed }
            invocation = PluginInvocation(budget, entry.definition.owner, owner, entry.tool,
                Json.parseToJsonElement(ticket.argsJson).jsonObject,
                ToolInvocationRequest(record.input.runId, record.input.sessionId, callId, record.input.unattended),
                requestApproval = { summary, risk -> requestRuntimeApproval(ticket, summary, Risk.valueOf(risk.name)) },
                frozenSpec = entry.spec, reservation = reservation, unattendedApprovalPolicy = true)
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
    private fun acknowledged(mutation: Mutation): InvocationRecord = when (mutation) {
        is Mutation.Applied -> mutation.record
        is Mutation.AlreadyApplied -> mutation.record
        else -> error("Invocation transition not acknowledged: $mutation")
    }
    private suspend fun monitor(ticket: ExecutionTicket, call: PluginInvocation) {
        try {
            val logical = call.terminal.await()
            val durable = gate.withGate {
                acknowledged(when (logical.status) {
                    PluginInvocationStatus.CANCELLED -> observe(ledger.cancel(ticket.callId))
                    PluginInvocationStatus.TIMED_OUT -> observe(ledger.timeout(ticket.callId))
                    else -> outcome(ticket, logical, call.bodyEntered)
                }).also { wakeApproval(ticket.callId) }
            }
            gate.withGate { logicalResults[ticket.callId] }?.complete(Unit)
            coroutineScope {
                // A cooperative worker needs a moment to unwind after cancellation; only an exit
                // that stays unconfirmed past the grace period stops the owner. DESIGN 13.11 makes
                // only a tool-call timeout release on actual exit; a cancelled call needs a restart.
                // The ledger owns one restriction per attempt, not a blanket plugin reset.
                // Cancellation may have won durably before this observer records SDK success.
                val restartWatch = if (durable.state in setOf(Status.CANCELLED, Status.TIMED_OUT)) launch {
                    delay(exitGraceMillis)
                    gate.withGate {
                        if (!call.actualExited.isCompleted)
                            acknowledged(observe(ledger.markRestartRequired(ticket.callId, ticket.attemptId,
                                untilActualExit = durable.state == Status.TIMED_OUT)))
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
            closeSubmissions()
            gate.withGate { logicalResults[ticket.callId] }?.completeExceptionally(error)
            throw error
        }
    }
    suspend fun cancel(callId: String): Mutation = withContext(Dispatchers.IO) {
        gate.withGate {
            val mutation = observe(ledger.cancel(callId))
            wakeApproval(callId)
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
        val pending = mutableListOf<String>()
        suspend fun awaitHealthy(completion: Deferred<Unit>?): Boolean {
            if (storageFailed) return false
            if (completion == null) return true
            return select {
                storageFailure.onAwait { false }
                completion.onAwait { !storageFailed }
            }
        }
        suspend fun observePending(id: String): Boolean {
            if (!awaitHealthy(gate.withGate { logicalResults[id] })) return false
            // Logical timeout/cancellation is not actual exit. Never wait indefinitely for it;
            // subsequent claims still enforce leases, isolation and actual-exit barriers.
            return ledger.get(id)?.state !in setOf(Status.SUCCEEDED, Status.FAILED) ||
                awaitHealthy(gate.withGate { observations[id] })
        }
        suspend fun drain(): Boolean {
            for (id in pending) if (!observePending(id)) return false
            pending.clear()
            return !storageFailed
        }
        suspend fun awaitAdmissionProgress(blocked: Mutation.Blocked, input: InvocationInput): Boolean {
            // Only current-batch leases can be relieved here. Permanent guards (claim budget,
            // restart, stale definition, recovery) must pause without waiting on unrelated work.
            val candidates = when (blocked.reason) {
                "Execution capacity unavailable" -> pending.toList()
                "Resource occupied or externally isolated" -> pending.filter {
                    ledger.get(it)?.input?.definition?.exclusiveResource == input.definition?.exclusiveResource
                }
                "Step barrier awaits actual exit/submission" -> pending.toList()
                else -> emptyList()
            }
            if (candidates.isEmpty()) return false
            return coroutineScope {
                val waits = candidates.associateWith { id -> async { observePending(id) } }
                try {
                    val completed = select<Pair<String, Boolean>> {
                        waits.forEach { (id, wait) -> wait.onAwait { id to it } }
                    }
                    pending.remove(completed.first)
                    completed.second && !storageFailed
                } finally { waits.values.forEach { it.cancel() } }
            }
        }
        for (input in inputs) {
            val id = input.binding.callId
            // Validation and approval remain ordered; only submitted tool bodies overlap.
            val prepared = gate.withGate { observe(ledger.validateAndPrepare(id)) }
            val record = when (prepared) { is Mutation.Applied -> prepared.record; is Mutation.AlreadyApplied -> prepared.record; else -> return@withContext null }
            if (record.state == Status.READY) {
                val parallel = input.definition?.parallelSafe == true
                if (!parallel && !drain()) return@withContext null
                var dispatched = dispatch(id)
                // Retry admission, never execution, after a relevant current-batch lease
                // progresses. A free slot need not wait for unrelated slower siblings.
                while (dispatched is Mutation.Blocked && pending.isNotEmpty()) {
                    if (!awaitAdmissionProgress(dispatched, input)) return@withContext null
                    dispatched = dispatch(id)
                }
                if (dispatched is Mutation.Applied && dispatched.record.state.terminal) continue
                // A call cancelled while queued for admission is finished; its siblings still run.
                if (dispatched is Mutation.Conflict && ledger.get(id)?.state?.terminal == true) continue
                if (dispatched !is Mutation.Applied || dispatched.ticket == null) return@withContext null
                pending += id
                if (!parallel && !drain()) return@withContext null
            } else if (record.state == Status.RUNNING ||
                (record.state in setOf(Status.SUCCEEDED, Status.FAILED) &&
                    record.instance?.state == InstanceState.UNCONFIRMED)) {
                // A resumed step observes its existing claim, including a normal result whose
                // completion handlers have not exited yet; it never submits the claim twice.
                pending += id
                if (input.definition?.parallelSafe != true && !drain()) return@withContext null
            } else if (!record.state.terminal) return@withContext null
        }
        if (!drain()) return@withContext null
        ledger.resultsForStep(session, run, step, inputs.map { it.binding.callId })
    }
}
