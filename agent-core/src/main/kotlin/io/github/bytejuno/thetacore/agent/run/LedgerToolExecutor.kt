package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Trusted host boundary, never a plugin-facing API. submit is non-suspending and called under the
 * SAME DefinitionGate as the ledger claim. It must schedule the frozen ticket, not execute plugin
 * code inline. All outcomes, actual exits, timeouts and risk upgrades go through InvocationLedger.
 * awaitTerminal returns on LOGICAL completion; cancellation does not assert actual worker exit.
 * requestCancel must be non-blocking, must not release leases or invent an exit acknowledgement.
 * A thrown submit may have submitted work: the claimed invocation remains real and uncertain.
 */
interface ToolExecutionBridge {
    fun submit(ticket: ExecutionTicket)
    suspend fun awaitTerminal(callId: String)
    fun requestCancel(callId: String)
}
fun interface RunApprovals { suspend fun decide(request: ApprovalRequest): Decision }

data class RunIdentity(val taskChainId: String, val sessionId: String, val runId: String, val unattended: Boolean = false)
class ToolStepBlocked(message: String) : IllegalStateException(message)

/** Owns ordering, not permissions. Every path (including unknown tools and truncation) uses the
 * existing ledger. Host owns cold recovery, installed definitions, and process-wide execution slots.
 */
class LedgerToolExecutor(
    private val ledger: InvocationLedger,
    private val gate: DefinitionGate,
    definitions: List<FrozenDefinition>,
    private val bridge: ToolExecutionBridge,
    private val approvals: RunApprovals = RunApprovals { Decision.DENY },
) {
    val definitions: List<FrozenDefinition> = frozen(definitions.toList())
    private val byName = this.definitions.associateBy { it.modelName }
    init { require(byName.size == definitions.size) }

    fun freeze(identity: RunIdentity) = gate.freezeRun(identity.runId, definitions)

    fun register(identity: RunIdentity, step: String, calls: List<CollectedCall>, cancelled: Boolean) {
        calls.forEachIndexed { order, call ->
            val d = byName[call.name]
            record(ledger.recordInvocation(InvocationInput(BindingValue.copyOf(call.binding), identity.taskChainId,
                identity.sessionId, identity.runId, step, order, call.argsJson, d, identity.unattended,
                maxOf(d?.declaredRisk ?: Risk.READ, if (d?.source == Source.MCP) Risk.WRITE else Risk.READ),
                cancelled, call.name)))
        }
    }

    suspend fun execute(calls: List<CollectedCall>) {
        val active = mutableListOf<String>()
        suspend fun await(ids: List<String>) {
            for (id in ids) {
                bridge.awaitTerminal(id)
                if (ledger.get(id)?.state?.terminal != true) throw ToolStepBlocked("Bridge returned before terminal")
            }
        }
        for (call in calls) {
            currentCoroutineContext().ensureActive()
            var r = record(ledger.validateAndPrepare(call.binding.callId))
            var prompts = 0
            suspend fun resolveCurrent() {
                while (r.state == Status.WAITING_APPROVAL) {
                    currentCoroutineContext().ensureActive()
                    // Evidence may change while a human decides. Re-ask the renewed request,
                    // never consume a stale receipt, and stop if the boundary keeps moving.
                    if (++prompts > 8) throw ToolStepBlocked("Approval evidence kept changing")
                    val pending = requireNotNull(r.pending)
                    val decision = approvals.decide(pending)
                    currentCoroutineContext().ensureActive()
                    r = record(ledger.resolveApproval(r.callId, pending, decision))
                }
            }
            resolveCurrent()
            if (r.state.terminal) continue
            if (r.state != Status.READY) throw ToolStepBlocked("Approval not resolved")
            val resource = r.input.definition?.exclusiveResource
            if (resource != null) {
                val sharing = active.filter { ledger.get(it)?.input?.definition?.exclusiveResource == resource }
                await(sharing)
                active.removeAll(sharing.toSet())
            }
            if (r.input.definition?.parallelSafe != true) {
                await(active)
                active.clear()
            }
            // A serial invocation is awaited immediately. The ledger still enforces actual-exit
            // barriers, shared resources, definition versions, unknown effects and cold recovery.
            val context = currentCoroutineContext()
            var claimed: InvocationRecord
            while (true) {
                claimed = gate.withGate {
                    // Approval/barrier callbacks may cancel and still return normally. Check inside
                    // the scheduling gate, before a durable claim can make this an executed call.
                    context.ensureActive()
                    val mutation = ledger.claimExecution(r.callId, r.revision)
                    val next = record(mutation)
                    (mutation as? Mutation.Applied)?.ticket?.let { bridge.submit(it) }
                    next
                }
                if (claimed.state != Status.WAITING_APPROVAL) break
                r = claimed
                resolveCurrent()
                if (r.state.terminal) { claimed = r; break }
                if (r.state != Status.READY) throw ToolStepBlocked("Approval not resolved")
            }
            if (claimed.state == Status.RUNNING) {
                if (claimed.input.definition?.parallelSafe == true) active += claimed.callId
                else await(listOf(claimed.callId))
            } else if (!claimed.state.terminal) throw ToolStepBlocked("Submission not authorized")
        }
        await(active)
    }

    /** Non-suspending reconciliation, also used from a cancelled coroutine. Already committed
     * outcomes are retained; claimed writes become UNKNOWN, never synthetic NONE. */
    fun cancelRemaining(calls: List<CollectedCall>) {
        var failure: Exception? = null
        for (call in calls) {
            val id = call.binding.callId
            try {
                val before = ledger.get(id) ?: continue
                record(ledger.cancel(id))
                if (before.instance?.state == InstanceState.UNCONFIRMED) bridge.requestCancel(id)
            } catch (e: Exception) { if (failure == null) failure = e }
        }
        failure?.let { throw it }
    }

    fun results(identity: RunIdentity, step: String, calls: List<CollectedCall>): Message =
        ledger.resultsForStep(identity.sessionId, identity.runId, step, calls.map { it.binding.callId })
            ?: throw ToolStepBlocked("Incomplete ledger step; history must remain uncommitted")

    fun continuationBlocked(identity: RunIdentity): Boolean = ledger.continuationBlocked(identity.taskChainId)

    fun uncertaintyNotices(identity: RunIdentity, history: List<Message>): List<Message> =
        ledger.uncertaintyNotices(identity.taskChainId, history)

    fun unresolved(calls: List<CollectedCall>): Boolean = calls.any {
        val r = ledger.get(it.binding.callId) ?: return@any true
        ledger.continuationBlocked(r.input.taskChainId)
    }

    private fun record(mutation: Mutation): InvocationRecord = when (mutation) {
        is Mutation.Applied -> mutation.record
        is Mutation.AlreadyApplied -> mutation.record
        is Mutation.Blocked -> throw ToolStepBlocked(mutation.reason)
        is Mutation.Conflict -> throw ToolStepBlocked(mutation.reason)
        is Mutation.StorageFailure -> throw ToolStepBlocked("Ledger storage failure: ${mutation.reason}")
    }
}
