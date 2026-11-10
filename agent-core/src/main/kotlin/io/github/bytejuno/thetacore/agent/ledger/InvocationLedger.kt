package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*
import java.util.UUID

/** S2 increment on the merged PR8 protocol baseline; not standalone S2 acceptance.
 * No external-side-effect exactly-once guarantee.
 * Integration seam: restore frozen run catalogs, inject real Schema 2020-12 validation, cold-recover,
 * then under gate.withGate claim and submit the frozen ticket to PluginExecutionBudget/Invocation.
 * A submit failure is NOT proof of no execution: retain UNCONFIRMED unless the bridge has trusted
 * actual-exit evidence. Cancellation hooks must report terminal and actual exit separately.
 * UI, result/media normalization, history append transactions and Android durability are not here.
 *
 * One coordinator per host, backed by one exclusive store writer. All state transitions share the
 * definition scheduling gate. No executors, network, UI, or automatic retries live in this core.
 * Startup loads one snapshot; acknowledged writes update a callId index under the same gate.
 * Policy/barrier checks scan in-memory history. Records (including terminal history) are retained
 * indefinitely: grants, task-chain budgets and unknown-effect retry checks depend on them.
 */
class InvocationLedger(
    private val store: InvocationStore, private val gate: DefinitionGate,
    private val validator: ArgumentsValidator, private val policy: ApprovalPolicy = ApprovalPolicy(),
    private val maxClaimsPerTaskChain: Int = Int.MAX_VALUE,
) {
    init { require(maxClaimsPerTaskChain > 0); store.bindCoordinator() }
    private val recordsById = store.records().associateByTo(linkedMapOf()) { it.callId }
    private var epoch: String? = null
    private var halted = false
    fun get(callId: String): InvocationRecord? = gate.withGate { recordsById[callId]?.detached() }
    private fun all() = recordsById.values.toList()
    private fun id() = UUID.randomUUID().toString()
    private fun persist(previous: InvocationRecord?, next: InvocationRecord, ticket: ExecutionTicket? = null): Mutation {
        if (halted) return Mutation.Blocked("Storage acknowledgement uncertain; reopen and cold-recover")
        return try {
            val snapshot = next.detached()
            store.save(snapshot, previous?.revision)
            recordsById[next.callId] = snapshot.detached()
            Mutation.Applied(next.detached(), ticket)
        } catch (e: StoreException) {
            if (e.uncertain) halted = true
            Mutation.StorageFailure(e.message ?: "storage failure", e.uncertain)
        }
    }
    private fun update(r: InvocationRecord, next: InvocationRecord, ticket: ExecutionTicket? = null) =
        persist(r, next.copy(revision = r.revision + 1), ticket)

    fun recordInvocation(input: InvocationInput): Mutation = gate.withGate {
        val old = get(input.binding.callId)
        if (old != null) return@withGate if (old.input == input) Mutation.AlreadyApplied(old) else Mutation.Conflict("Immutable binding differs")
        val step = all().filter { sameStep(it.input, input) }
        if (input.callOrder != step.size)
            return@withGate Mutation.Conflict("Register calls in contiguous assistant order")
        val record = InvocationRecord(2, 1, input, input.definition?.digest, Status.RECEIVED, input.initialRisk,
            null, null, emptyList(), null, null, emptyList(), null, false, false)
        // Unknown calls are terminal in their first durable write: they can never be claimed,
        // including after a crash before the rest of the step was registered.
        persist(null, when {
            input.cancelledBeforeExecution -> terminal(record, Status.CANCELLED, null, "Truncated model turn: not executed")
            input.definition == null -> terminal(record, Status.REJECTED, null, "Unknown tool: ${input.modelName}")
            else -> record
        })
    }

    private fun sameStep(a: InvocationInput, b: InvocationInput) =
        a.sessionId == b.sessionId && a.runId == b.runId && a.stepId == b.stepId
    private fun prior(r: InvocationRecord) = all().filter { sameStep(it.input, r.input) && it.input.callOrder < r.input.callOrder }
    private fun definitionCheck(r: InvocationRecord): Mutation? = when (gate.check(r.input)) {
        DefinitionState.CURRENT -> null
        DefinitionState.REFRESH_PENDING -> Mutation.Blocked("REFRESH_PENDING")
        DefinitionState.STALE -> if (r.instance == null) update(r, terminal(r, Status.REJECTED, null, "STALE definition"))
            else Mutation.Blocked("STALE definition on submitted instance")
    }
    private fun argumentKey(r: InvocationRecord): String? = try {
        canonical(Json.parseToJsonElement(r.executionArgs ?: r.input.argsJson))
    } catch (_: IllegalArgumentException) { null }
    private fun hasUnknown(r: InvocationRecord) = r.result?.effect == Effect.UNKNOWN ||
        r.evidence.any { it.outcome?.effect == Effect.UNKNOWN }
    /** Chain-local pause, independent of step, owner, parallelSafe and resource declarations. */
    private fun chainBarrier(r: InvocationRecord): Mutation.Blocked? {
        val others = all().filter { it.callId != r.callId && it.input.taskChainId == r.input.taskChainId }
        return when {
            others.any { it.recoveryPending } -> Mutation.Blocked("Task chain awaits explicit cold-recovery decision")
            others.any { it.instance?.state == InstanceState.UNCONFIRMED && hasUnknown(it) } ->
                Mutation.Blocked("Task chain awaits unknown instance actual exit")
            else -> null
        }
    }
    private fun unknown(r: InvocationRecord, risk: Risk = r.effectiveRisk): List<String> = all().filter {
        it.callId != r.callId && it.input.taskChainId == r.input.taskChainId &&
            (it.result?.effect == Effect.UNKNOWN || it.evidence.any { e -> e.outcome?.effect == Effect.UNKNOWN }) &&
            // Same-argument retries always ask (3.6). Until verified, every write asks (3.8).
            ((it.input.definition?.toolKey == r.input.definition?.toolKey && argumentKey(it) == argumentKey(r)) ||
                (risk > Risk.READ && it.isolation?.resolution != RecoveryResolution.VERIFIED_CLEAR))
    }.map { it.callId }.sorted()
    private fun request(r: InvocationRecord, risk: Risk, internal: Boolean, summary: String = ""): ApprovalRequest {
        val definition = requireNotNull(r.input.definition)
        return ApprovalRequest(id(), r.callId, requireNotNull(r.definitionDigest),
            definition.version, definition.generation, risk, unknown(r, risk), internal, summary.take(4096))
    }

    fun validateAndPrepare(callId: String): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.state == Status.READY) definitionCheck(r)?.let { return@withGate it }
        if (r.state != Status.RECEIVED) return@withGate Mutation.AlreadyApplied(r)
        if (prior(r).any { it.state == Status.RECEIVED || it.state == Status.WAITING_APPROVAL })
            return@withGate Mutation.Blocked("Earlier call approval pending")
        definitionCheck(r)?.let { return@withGate it }
        val parsed = try { if (r.input.argsJson.isEmpty()) JsonNull else Json.parseToJsonElement(r.input.argsJson) }
            catch (_: IllegalArgumentException) { return@withGate update(r, terminal(r, Status.REJECTED, null, "Invalid JSON")) }
        val args = if (parsed == JsonNull) "{}" else r.input.argsJson
        if ((parsed != JsonNull && parsed !is JsonObject) || !validator.valid(requireNotNull(r.input.definition).inputSchema, args))
            return@withGate update(r, terminal(r, Status.REJECTED, null, "Invalid arguments"))
        val prepared = r.copy(executionArgs = args)
        when (policy.evaluate(prepared, prepared.effectiveRisk, unknown(prepared), all())) {
            ApprovalPolicy.Verdict.DENY -> update(r, terminal(prepared, Status.REJECTED, null, "Policy denied"))
            ApprovalPolicy.Verdict.ALLOW -> update(r, prepared.copy(state = Status.READY))
            ApprovalPolicy.Verdict.ASK -> update(r, prepared.copy(state = Status.WAITING_APPROVAL,
                pending = request(prepared, prepared.effectiveRisk, false)))
        }
    }

    /** Host decisions supply identifiers, never caller-owned risk/digest/unknown evidence. */
    fun decideApproval(callId: String, requestId: String, decision: Decision): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        val pending = r.pending
        if (pending == null || pending.id != requestId) return@withGate Mutation.Conflict("Approval is not current")
        if (r.input.unattended) return@withGate Mutation.Blocked("Unattended approval unavailable")
        resolveApproval(callId, pending, decision)
    }

    /** The entire stored request must match; a UI-supplied digest/version is not trusted. */
    fun resolveApproval(callId: String, approval: ApprovalRequest, decision: Decision): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        r.receipts.find { it.request.id == approval.id }?.let {
            return@withGate if (it.request == approval && it.decision == decision) Mutation.AlreadyApplied(r)
                else Mutation.Conflict("Conflicting repeated approval")
        }
        if (r.state.terminal || r.pending != approval || approval.callId != callId)
            return@withGate Mutation.Conflict("Approval is not current or binding differs")
        if (approval.internal && r.instance?.state != InstanceState.UNCONFIRMED)
            return@withGate Mutation.Conflict("Internal approval instance already exited")
        if (!approval.internal && prior(r).any { it.state == Status.RECEIVED || it.state == Status.WAITING_APPROVAL })
            return@withGate Mutation.Blocked("Earlier call approval pending")
        if (decision == Decision.DENY) {
            // Current-request denial grants nothing and must remain possible during refresh or
            // changed policy/evidence. Internal denial leaves the already-running invocation real.
            val denied = r.copy(pending = null, receipts = r.receipts + ApprovalReceipt(approval, decision))
            return@withGate update(r, if (approval.internal) denied else terminal(denied, Status.REJECTED, null, "Approval denied"))
        }
        definitionCheck(r)?.let { return@withGate it }
        if (policy.isDenied(r.input)) return@withGate Mutation.Blocked("Deny rule takes priority")
        if (decision in setOf(Decision.ALLOW_SESSION, Decision.ALLOW_ALWAYS) && !policy.permitsLongTerm(approval.risk))
            return@withGate Mutation.Blocked("ROOT long-term grants disabled")
        if (unknown(r, approval.risk) != approval.unknownCalls)
            return@withGate update(r, r.copy(pending = request(r, approval.risk, approval.internal, approval.summary)))
        val next = r.copy(pending = null, receipts = r.receipts + ApprovalReceipt(approval, decision))
        update(r, next.copy(state = if (approval.internal) Status.RUNNING else Status.READY,
            effectiveRisk = maxOf(r.effectiveRisk, approval.risk)))
    }

    fun claimExecution(callId: String, expectedRevision: Long): Mutation = gate.withGate {
        if (halted || epoch == null) return@withGate Mutation.Blocked("Cold recovery required")
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.revision != expectedRevision || r.state != Status.READY || r.instance != null)
            return@withGate Mutation.Conflict("Not a current READY revision")
        chainBarrier(r)?.let { return@withGate it }
        definitionCheck(r)?.let { return@withGate it }
        if (policy.isDenied(r.input)) return@withGate update(r, terminal(r, Status.REJECTED, null, "Policy denied before claim"))
        val unknown = unknown(r)
        val accepted = r.receipts.any { it.decision != Decision.DENY && !it.request.internal &&
            (it.decision == Decision.ALLOW_ONCE || policy.permitsLongTerm(r.effectiveRisk)) &&
            it.request.risk >= r.effectiveRisk && it.request.unknownCalls == unknown }
        if (!accepted && policy.evaluate(r, r.effectiveRisk, unknown, all()) != ApprovalPolicy.Verdict.ALLOW) {
            if (r.input.unattended) return@withGate update(r, terminal(r, Status.REJECTED, null, "Unattended approval unavailable"))
            return@withGate update(r, r.copy(state = Status.WAITING_APPROVAL, pending = request(r, r.effectiveRisk, false)))
        }
        val records = all()
        if (records.count { it.input.taskChainId == r.input.taskChainId && it.instance != null } >= maxClaimsPerTaskChain)
            return@withGate Mutation.Blocked("Task-chain claim budget exhausted")
        val d = requireNotNull(r.input.definition)
        if (records.any { it.restartRequired && it.input.definition?.owner == d.owner })
            return@withGate Mutation.Blocked("Plugin restart required")
        if (d.exclusiveResource != null && records.any {
                it.input.definition?.exclusiveResource == d.exclusiveResource &&
                    (it.instance?.state == InstanceState.UNCONFIRMED || it.isolation?.let { q -> q.resolution != RecoveryResolution.VERIFIED_CLEAR } == true)
            }) return@withGate Mutation.Blocked("Resource occupied or externally isolated")
        if (prior(r).any {
                !it.state.terminal && it.instance == null ||
                    ((!d.parallelSafe || it.input.definition?.parallelSafe != true) && it.instance?.state == InstanceState.UNCONFIRMED)
            }) return@withGate Mutation.Blocked("Step barrier awaits actual exit/submission")
        val instance = ExecutionInstance(id(), epoch!!, InstanceState.UNCONFIRMED)
        val ticket = ExecutionTicket(r.callId, instance.attemptId, instance.processEpoch, d, r.executionArgs!!)
        update(r, r.copy(state = Status.RUNNING, instance = instance), ticket)
    }

    /** Every call is a new authorization, even below the historical risk watermark. Applied with
     * no pending request is the immediate ALLOW outcome, after current checks and durable risk
     * acknowledgement. ASK is identified by its pending ID; resolve it from that ID's receipt,
     * never from effectiveRisk. ALLOW_ONCE on an earlier internal step cannot authorize this one. */
    fun requestRiskUpgrade(callId: String, attemptId: String, risk: Risk, summary: String = ""): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.state != Status.RUNNING || r.instance?.attemptId != attemptId || r.instance.state != InstanceState.UNCONFIRMED)
            return@withGate Mutation.Conflict("Instance not running")
        if (r.pending != null) return@withGate Mutation.Blocked("Approval already pending")
        definitionCheck(r)?.let { return@withGate it }
        chainBarrier(r)?.let { return@withGate it }
        val requestedRisk = maxOf(r.effectiveRisk, risk)
        when (policy.evaluate(r, requestedRisk, unknown(r, requestedRisk), all())) {
            ApprovalPolicy.Verdict.DENY -> Mutation.Blocked("Internal upgrade denied")
            ApprovalPolicy.Verdict.ALLOW -> update(r, r.copy(effectiveRisk = maxOf(r.effectiveRisk, requestedRisk)))
            ApprovalPolicy.Verdict.ASK -> update(r, r.copy(pending = request(r, requestedRisk, true, summary)))
        }
    }

    /** Recheck the specific durable decision at the bridge's consumption boundary. A new UNKNOWN
     * set re-arms only this request, while denial, stale definitions and live occupancy fail closed. */
    fun checkRiskApproval(callId: String, attemptId: String, requestId: String): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.state != Status.RUNNING || r.instance?.attemptId != attemptId || r.instance.state != InstanceState.UNCONFIRMED)
            return@withGate Mutation.Conflict("Instance not running")
        if (r.pending != null) return@withGate Mutation.Conflict("Approval still pending")
        val receipt = r.receipts.singleOrNull { it.request.id == requestId && it.request.internal }
            ?: return@withGate Mutation.Conflict("No decision for this runtime request")
        if (receipt.decision == Decision.DENY) return@withGate Mutation.Blocked("Internal step denied")
        definitionCheck(r)?.let { return@withGate it }
        chainBarrier(r)?.let { return@withGate it }
        if (policy.isDenied(r.input)) return@withGate Mutation.Blocked("Deny rule takes priority")
        if (receipt.decision != Decision.ALLOW_ONCE && !policy.permitsLongTerm(receipt.request.risk))
            return@withGate Mutation.Blocked("ROOT long-term grants disabled")
        if (unknown(r, receipt.request.risk) != receipt.request.unknownCalls) {
            if (r.input.unattended) return@withGate Mutation.Blocked("Unattended approval unavailable")
            return@withGate update(r, r.copy(pending = request(r, receipt.request.risk, true, receipt.request.summary)))
        }
        Mutation.AlreadyApplied(r)
    }

    /** A cancelled SDK waiter no longer owns a proposed step. Withdrawal needs no live definition
     * or permission: it can only deny that attempt's current pending upgrade. */
    fun withdrawRiskUpgrade(callId: String, attemptId: String, requestId: String): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        val pending = r.pending
        if (r.state != Status.RUNNING || r.instance?.attemptId != attemptId ||
            pending?.id != requestId || !pending.internal) return@withGate Mutation.Conflict("Upgrade is not current")
        update(r, r.copy(pending = null, receipts = r.receipts + ApprovalReceipt(pending, Decision.DENY)))
    }

    private fun terminal(r: InvocationRecord, status: Status, explicit: Effect?, text: String): InvocationRecord {
        require(status.terminal)
        val effect = if (r.instance == null || status == Status.REJECTED) Effect.NONE
            else explicit ?: if (r.effectiveRisk == Risk.READ) Effect.NONE
            else if (status == Status.SUCCEEDED) Effect.APPLIED else Effect.UNKNOWN
        val result = LogicalResult(status, effect, text, r.effectiveRisk == Risk.READ && effect != Effect.NONE)
        return r.copy(state = status, pending = null, result = result,
            isolation = if (effect == Effect.UNKNOWN) Isolation("Unknown external effect", r.input.definition?.exclusiveResource, null) else r.isolation)
    }
    fun cancel(callId: String): Mutation = finishLocally(callId, Status.CANCELLED)
    fun timeout(callId: String): Mutation = finishLocally(callId, Status.TIMED_OUT)
    private fun finishLocally(callId: String, status: Status): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.state.terminal) Mutation.AlreadyApplied(r) else update(r, terminal(r, status, null, status.name))
    }
    /** Return evidence is NOT actual exit: children and cancellation handlers may still run. */
    fun recordOutcome(callId: String, attemptId: String, status: Status, text: String, effect: Effect? = null): Mutation = gate.withGate {
        require(status == Status.SUCCEEDED || status == Status.FAILED)
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.instance?.attemptId != attemptId) return@withGate Mutation.Conflict("Wrong attempt")
        val outcome = terminal(r, status, effect, text).result!!
        val evidence = ExitEvidence(attemptId, text, outcome)
        if (evidence in r.evidence) return@withGate Mutation.AlreadyApplied(r)
        val next = if (r.state.terminal) r else terminal(r, status, effect, text)
        update(r, next.copy(evidence = r.evidence + evidence,
            isolation = if (outcome.effect == Effect.UNKNOWN)
                Isolation("Return evidence reports unknown effect", r.input.definition?.exclusiveResource, null) else next.isolation))
    }

    /** Clear only this attempt's grace-expiry restriction; other attempts and restart causes remain. */
    private fun exited(r: InvocationRecord): InvocationRecord = r.copy(
        instance = requireNotNull(r.instance).copy(state = InstanceState.EXIT_CONFIRMED),
        restartRequired = r.restartRequired && !r.restartRequiredUntilExit,
        restartRequiredUntilExit = false)

    /** Completion is also a trusted actual-exit callback, not merely a cancellation acknowledgement. */
    fun complete(callId: String, attemptId: String, status: Status, text: String, effect: Effect? = null): Mutation = gate.withGate {
        require(status == Status.SUCCEEDED || status == Status.FAILED)
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.instance?.attemptId != attemptId) return@withGate Mutation.Conflict("Wrong attempt")
        val outcome = terminal(r, status, effect, text).result!!
        val evidence = ExitEvidence(attemptId, text, outcome)
        if (evidence in r.evidence) {
            return@withGate if (r.instance.state == InstanceState.EXIT_CONFIRMED) Mutation.AlreadyApplied(r)
                else update(r, exited(r))
        }
        val next = if (r.state.terminal) r else terminal(r, status, effect, text)
        update(r, exited(next).copy(
            // A clearance covers only evidence known at the time. New uncertainty invalidates it;
            // identical callback replays return above and do not undo a later clearance.
            isolation = if (outcome.effect == Effect.UNKNOWN)
                Isolation("Late evidence reports unknown effect", r.input.definition?.exclusiveResource, null) else next.isolation,
            evidence = r.evidence + evidence))
    }
    fun recordActualExit(callId: String, attemptId: String, evidence: String): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.instance?.attemptId != attemptId) return@withGate Mutation.Conflict("Wrong attempt")
        val item = ExitEvidence(attemptId, evidence, null)
        if (item in r.evidence) return@withGate Mutation.AlreadyApplied(r)
        update(r, exited(r).copy(evidence = r.evidence + item))
    }
    fun markRestartRequired(callId: String, attemptId: String, untilActualExit: Boolean = false): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.instance?.attemptId != attemptId || r.instance.state != InstanceState.UNCONFIRMED)
            return@withGate Mutation.Conflict("No unconfirmed instance")
        if (untilActualExit && r.state != Status.TIMED_OUT)
            return@withGate Mutation.Conflict("No timed-out instance")
        // A sticky cause wins regardless of arrival order; a later grace expiry cannot weaken it.
        val releaseOnExit = untilActualExit && (!r.restartRequired || r.restartRequiredUntilExit)
        if (r.restartRequired && r.restartRequiredUntilExit == releaseOnExit) Mutation.AlreadyApplied(r)
        else update(r, r.copy(restartRequired = true, restartRequiredUntilExit = releaseOnExit))
    }
    fun pendingApprovals(): List<ApprovalRequest> = gate.withGate { all().mapNotNull { it.detached().pending } }

    private fun recoveryRequest(r: InvocationRecord): RecoveryRequest? {
        if (!r.state.terminal || r.instance?.state != InstanceState.EXIT_CONFIRMED || r.isolation == null ||
            (!r.recoveryPending && r.isolation.resolution == RecoveryResolution.VERIFIED_CLEAR)) return null
        return RecoveryRequest("recovery:${r.callId}:${r.revision}", r.callId, r.revision)
    }
    fun pendingRecoveries(): List<RecoveryRequest> = gate.withGate { all().mapNotNull(::recoveryRequest) }

    /** CAS against the CURRENT durable evidence, not a caller-supplied record. Reopening does not
     * renew IDs; late evidence does. A decision acknowledges uncertainty, never rewrites effects. */
    fun decideRecovery(callId: String, requestId: String, resolution: RecoveryResolution): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (recoveryRequest(r)?.id != requestId) return@withGate Mutation.Conflict("Recovery decision is not current")
        if (!r.recoveryPending && r.isolation?.resolution == resolution)
            return@withGate Mutation.Conflict("Recovery decision already recorded")
        resolveRecovery(callId, resolution)
    }

    fun resolveRecovery(callId: String, resolution: RecoveryResolution): Mutation = gate.withGate {
        val r = get(callId) ?: return@withGate Mutation.Conflict("Unknown call")
        if (r.instance?.state == InstanceState.UNCONFIRMED) return@withGate Mutation.Blocked("Actual exit still unconfirmed")
        val isolation = r.isolation ?: return@withGate Mutation.AlreadyApplied(r)
        if (isolation.resolution == resolution && !r.recoveryPending) Mutation.AlreadyApplied(r)
        else update(r, r.copy(isolation = isolation.copy(resolution = resolution), recoveryPending = false))
    }

    /** Call only on a genuine new host process. Epoch must never be reused. All admission is closed
     * until every recovery write succeeds; partial recovery is safe to retry, never auto-executes.
     * Affected task chains remain blocked until resolveRecovery persists a host/user decision,
     * including READ/no-resource instances. Accepting uncertainty does not clear resource isolation. */
    fun recoverColdStart(newProcessEpoch: String): Mutation = gate.withGate {
        require(newProcessEpoch.isNotBlank())
        if (halted) return@withGate Mutation.Blocked("Reopen storage required")
        if (epoch != null && epoch != newProcessEpoch) return@withGate Mutation.Conflict("Live coordinator cannot change epoch")
        if (epoch == newProcessEpoch) return@withGate Mutation.Blocked("Already recovered")
        val records = all()
        if (records.any { it.instance?.processEpoch == newProcessEpoch }) return@withGate Mutation.Conflict("Process epoch reused")
        var last: Mutation? = null
        for (r in records) {
            if (r.state == Status.RUNNING || r.instance?.state == InstanceState.UNCONFIRMED || r.restartRequired) {
                var next = if (r.state == Status.RUNNING) terminal(r, Status.LOST, null, "Host process lost") else r
                val unresolved = r.instance?.state == InstanceState.UNCONFIRMED
                val needsDecision = r.state == Status.RUNNING || unresolved
                next = next.copy(instance = r.instance?.copy(state = InstanceState.EXIT_CONFIRMED), restartRequired = false,
                    restartRequiredUntilExit = false,
                    recoveryPending = r.recoveryPending || needsDecision,
                    isolation = if (needsDecision)
                        next.isolation ?: Isolation("Host loss requires recovery decision", r.input.definition?.exclusiveResource, null)
                    else next.isolation)
                last = update(r, next)
                if (last !is Mutation.Applied) return@withGate last
            }
        }
        epoch = newProcessEpoch
        last ?: Mutation.Blocked("Recovery complete; no records changed")
    }

    /** Pure projection, not an append operation. Caller must freeze/register the entire step first.
     * expectedCallIds is the authoritative ordered assistant-call list, preventing partial batches. */
    fun resultsForStep(sessionId: String, runId: String, stepId: String, expectedCallIds: List<String>): Message? = gate.withGate {
        val records = all().filter { it.input.sessionId == sessionId && it.input.runId == runId && it.input.stepId == stepId }
            .sortedBy { it.input.callOrder }
        if (records.isEmpty() || records.map { it.callId } != expectedCallIds || records.any { !it.state.terminal }) return@withGate null
        message(Role.TOOL, *records.map { r ->
            val result = r.result!!
            val text = buildJsonObject {
                put("status", result.status.name.lowercase()); put("effect", result.effect.name.lowercase())
                put("message", result.text)
            }.toString()
            toolResultPart(r.callId, text, r.state != Status.SUCCEEDED)
        }.toTypedArray())
    }
}
