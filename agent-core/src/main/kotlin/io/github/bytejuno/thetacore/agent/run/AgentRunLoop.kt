package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord
import io.github.bytejuno.thetacore.agent.ledger.NonExecutionReason
import io.github.bytejuno.thetacore.agent.ledger.canonical
import io.github.bytejuno.thetacore.agent.ledger.atLedgerPrecision
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RunLimits(val maxSteps: Int = 50, val maxRetries: Int = 12, val maxPaused: Int = 3,
                     val repeatedCalls: Int = 3, val maxRetryWaitMs: Long = 120_000) {
    init { require(maxSteps > 0 && maxRetries >= 0 && maxPaused >= 0 && repeatedCalls > 0 && maxRetryWaitMs >= 0) }
}
enum class RunState { COMPLETED, PAUSED, FAILED }
@Serializable
data class RunResult(val state: RunState, val reason: String, val steps: Int, val retries: Int,
                     val serverExecutionUnknown: Boolean = false)

/** Optional real token-budget seam; null means unknown. Includes system, tools, history AND output
 * reservation. Shrink recovery is accepted only with a strictly smaller, known positive budget.
 * This version never rewrites original history or summarizes inside an active tool loop. */
fun interface RequestBudget {
    fun tokens(request: ChatRequest): Long?
    fun limit(model: String): Long? = null
}

/** One-shot, single-owner run. User input belongs in the initial RunHistory; enqueue new input only
 * after this run stops. A fresh loop is not a recovery API and must not reset task-chain limits.
 * Explicit persisted context continuations restore task counters before any model submission.
 */
class AgentRunLoop(
    private val identity: RunIdentity,
    target: ModelTarget,
    request: ChatRequest,
    private val history: RunHistory,
    private val tools: LedgerToolExecutor,
    private val journal: RunJournal = MemoryRunJournal(),
    private val recovery: RecoveryStrategy = RecoveryStrategy { _, _, _, _ -> null },
    private val budget: RequestBudget = RequestBudget { null },
    private val limits: RunLimits = RunLimits(),
    private val timeouts: EventTimeouts = EventTimeouts(),
    initialSteps: Int = 0,
    initialRetries: Int = 0,
    private val contextPolicy: ContextPolicy? = null,
    private val requestViewLimit: RequestViewLimit = RequestViewLimit(),
    private val onEvent: (String, ModelEvent) -> Unit = { _, _ -> },
    private val beforeModelSubmit: suspend () -> Unit = {},
    private val reasoningCapabilities: ReasoningCapabilityObserver = ReasoningCapabilityObserver { _, _ -> },
) {
    private var target = target.copy()
    private var config = copyRequest(request, emptyList())
    private val started = AtomicBoolean()
    private var steps = initialSteps
    private var retries = initialRetries
    private var paused = 0
    private var lastCall: String? = null
    private var repeated = 0
    private var checkpointRequired = false
    private var contextView: ContextCheckpoint? = null
    private var serverLoopOpen = false
    private var serverUnknown = false
    private var cacheDisabled = false
    private var summaryFailed = false
    private var persistedPause: String? = null
    private var localMediaFailure: String? = null
    private var activeInputCount = 0
    private var admissionPending = false
    private var accountingActive = false
    private var confirmedGenerationResponse = false
    private var rejectedContextReadmission = false
    private var reasoningRemoved = false
    private var reasoningOutputObserved = false
    private var summarySteps = 0

    private fun persistAccounting() {
        if (accountingActive) (history as? SqliteRunHistory)?.saveAccounting(
            RunAccounting(identity.sessionId, identity.taskChainId, identity.runId, steps, retries, identity.unattended))
    }

    private fun irreducible(source: HistorySnapshot): Boolean {
        val active = source.messages.subList(contextPolicy?.priorMessageCount ?: 0, activeInputCount)
        if (active.any { !ImagePayloadBudget.admitsMessages(listOf(it)) }) return true
        val capacity = budget.limit(config.model) ?: return false
        val base = copyRequest(config, emptyList())
        if ((budget.tokens(base) ?: 0) > capacity) return true
        return source.messages.subList(contextPolicy?.priorMessageCount ?: 0, activeInputCount)
            .asSequence().filter { it.role == Role.USER }.any { (budget.tokens(copyRequest(config, listOf(it))) ?: 0) > capacity }
    }

    private fun requestMessages(source: HistorySnapshot): List<Message> {
        val view = contextView
        if (view != null) require(view.matches(source, view.settingsKey)) { "Active context source changed" }
        // Accepted recovery may change output reserve/model; never silently restore omitted raw pairs.
        return view?.messages(source.messages) ?: source.messages
    }

    private fun budgetKey(): String = listOf(budget.limit(config.model), requestViewLimit, contextPolicy?.summaryModel?.let {
            settingsKey(it.target.id, it.request, it.target.provider.contextConfigurationKey()) + ":" + it.budget.limit(it.request.model) + ":" + it.memoryLimit
        }).joinToString("|")

    private fun restoreContext(source: HistorySnapshot) {
        val disk = history as? SqliteRunHistory
        val barrier = disk?.loadBarrier()
        val saved = disk?.loadCheckpoint()
        // Retained per-chain accounting is authoritative even after another chain or history edit.
        val accounting = disk?.loadAccounting(identity.taskChainId)
        accounting?.let {
            steps = maxOf(steps, it.steps); retries = maxOf(retries, it.retries)
        }
        if (accounting == null && saved?.taskChainId == identity.taskChainId) {
            steps = maxOf(steps, saved.steps); retries = maxOf(retries, saved.retries)
        }
        // Safety is session durable, not a derived context view. Even disabling context policy
        // or changing provider cannot admit a request over unresolved remote execution.
        val reconciled = saved?.let { disk.checkpointReconciled(it) } == true
        if (barrier != null || saved?.serverUnresolved == true && !reconciled) {
            serverUnknown = true
            if (barrier?.taskChainId == identity.taskChainId) {
                steps = maxOf(steps, barrier.steps); retries = maxOf(retries, barrier.retries)
            }
            persistedPause = "Server execution requires reconciliation"
            return
        }
        // A paired history is not proof of actual worker exit. Check live ledger truth even
        // without context policy/checkpoint, and before consuming any explicit admission token.
        val sourceRecords = tools.checkpointRecords(source.messages)
        val localBlocked = tools.localExecutionBlocked(sourceRecords) ||
            saved?.let { tools.localExecutionBlocked(tools.currentCheckpointRecords(it.ledger)) } == true
        if (localBlocked) {
            persistedPause = "Local tool execution or effect requires reconciliation"
            return
        }
        if (saved != null && saved.kind !in setOf("VIEW", "COMPLETE")) {
            val selected = contextPolicy?.let { it.resumeCheckpointId != null || it.readmitBudgetCheckpointId != null } == true
            // A local wait is re-evaluated from live ledger truth. It is not a remote barrier or
            // a consumed model admission; actual exit may resolve it without a new override API.
            val localWaitResolved = saved.pauseClass == "LOCAL_WAIT"
            // A definite budget pause or an unselected summary offer records no uncertain work.
            // Once newer input makes it unselectable (no readmission/continuation can match),
            // it must not lock the session; the ordinary run rechecks budget and compatibility.
            val superseded = !saved.serverUnresolved && saved.sourceRevision < source.revision &&
                (saved.kind == "CONTINUATION" || saved.kind == "PAUSED" && saved.pauseClass == "BUDGET")
            if (!selected && !localWaitResolved && !reconciled && !superseded) {
                persistedPause = "Persisted checkpoint requires explicit continuation or user review: ${saved.id}"
                return
            }
        }
        if (reconciled && saved?.pauseClass in setOf("ADMITTED", "SUMMARY_RESERVED", "REVIEW")) admissionPending = true
        val policy = contextPolicy
        if (policy == null) {
            if (hasUnsupportedMedia(target, config.model, source.messages)) {
                localMediaFailure = "Image input disabled for active model"
            }
            return
        }
        require(policy.priorMessageCount <= source.messages.size)
        validateHistorySequence(source.messages.subList(0, policy.priorMessageCount), history !is SqliteRunHistory)
        validateHistorySequence(source.messages.subList(policy.priorMessageCount, source.messages.size), history !is SqliteRunHistory)
        require(policy.resumeCheckpointId == null && policy.readmitBudgetCheckpointId == null || disk != null) { "Continuation requires persistent history" }
        if (disk == null) {
            if (hasUnsupportedMedia(target, config.model, source.messages)) {
                localMediaFailure = "Image input disabled for active model"
            }
            return
        }
        if (policy.readmitBudgetCheckpointId != null) {
            require(saved != null && saved.id == policy.readmitBudgetCheckpointId && saved.kind == "PAUSED" &&
                saved.pauseClass == "BUDGET" && !saved.serverUnresolved)
            require(saved.taskChainId == identity.taskChainId && saved.goal == policy.goal)
            require(saved.catalog == tools.definitions.sortedBy { it.modelName })
            require(matches(saved, source, saved.settingsKey)) { "Budget readmission requires unchanged durable source" }
            require(saved.budgetKey != budgetKey()) { "Choose a changed window or summary configuration" }
            val records = tools.currentCheckpointRecords(sourceRecords)
            require(records.map { it.atLedgerPrecision() } == saved.ledger.map { it.atLedgerPrecision() } && !tools.checkpointBlocked(records))
            val existingView = loadReusableView(disk, source, policy, sourceRecords)
            val effectiveMessages = existingView?.messages(source.messages) ?: source.messages
            val rejectedReadmit = saved.reason.contains("CONTEXT_TOO_LONG") ||
                saved.reason == "Truncated response; checkpoint required"
            if (isDefiniteMediaRejection(source, effectiveMessages, policy, existingView != null, rejectedReadmit)) {
                localMediaFailure = "Image input disabled for active model"
                return
            }
            disk.saveCheckpoint(saved.copy(pauseClass = "ADMITTED", reason = "Budget readmission reserved"))
            admissionPending = true
            // Both a rejected input and an output that exhausted context require a smaller
            // request view before another generation. Keep recognizing persisted checkpoints.
            rejectedContextReadmission = rejectedReadmit
        }
        if (policy.resumeCheckpointId != null) {
            require(saved != null && saved.id == policy.resumeCheckpointId && saved.kind == "CONTINUATION")
            require(saved.identity() == identity && saved.goal == policy.goal && !saved.serverUnresolved)
            require(saved.catalog == tools.definitions.sortedBy { it.modelName }) { "Checkpoint catalog changed" }
            val key = settingsKey(target.id, config, target.provider.contextConfigurationKey())
            if (saved.settingsKey != key) throw CheckpointSettingsChangedException()
            require(matches(saved, source, key)) { "Stale checkpoint or changed run settings" }
            val records = tools.currentCheckpointRecords(sourceRecords)
            require(records.map { it.atLedgerPrecision() } == saved.ledger.map { it.atLedgerPrecision() } && !tools.checkpointBlocked(records))
            val resumedView = saved.copy(kind = "VIEW", nextRunId = null)
            if (hasUnsupportedMedia(target, config.model, resumedView.messages(source.messages))) {
                localMediaFailure = "Image input disabled for active model"
                return
            }
            // Counter restoration above uses this checkpoint only for legacy sessions.
            // The continuation's initial summary is a stable view; future appends keep it valid.
            contextView = resumedView
            // Consume admission before submission. A crash must not allow the same continuation
            // token to replay work whose response may never have reached history.
            disk.saveCheckpoint(saved.copy(kind = "PAUSED", nextRunId = null, summary = null, pauseClass = "ADMITTED",
                reason = "Continuation admitted; reconcile journal before another attempt"))
            admissionPending = true
        } else {
            val view = loadReusableView(disk, source, policy, sourceRecords)
            if (view != null) {
                contextView = view
            }
            if (policy.readmitBudgetCheckpointId == null) {
                val effectiveMessages = view?.messages(source.messages) ?: source.messages
                if (isDefiniteMediaRejection(source, effectiveMessages, policy, view != null, rejectedReadmission = false)) {
                    localMediaFailure = "Image input disabled for active model"
                    return
                }
            }
        }
    }

    private fun loadReusableView(
        disk: SqliteRunHistory,
        source: HistorySnapshot,
        policy: ContextPolicy,
        sourceRecords: List<InvocationRecord>,
    ): ContextCheckpoint? {
        val saved = disk.loadCheckpoint()
        val view = disk.loadView() ?: saved?.takeIf { it.kind == "VIEW" } ?: return null
        if (view.sourceCount > policy.priorMessageCount ||
            view.catalog != tools.definitions.sortedBy { it.modelName } ||
            !matches(view, source, settingsKey(target.id, config, target.provider.contextConfigurationKey()))
        ) return null
        val records = tools.currentCheckpointRecords(sourceRecords)
        if (tools.compactionBlocked(records)) return null
        val durableRecords = records.map { it.atLedgerPrecision() }.associateBy { it.callId }
        if (view.ledger.any { old -> durableRecords[old.callId] != old.atLedgerPrecision() }) return null
        return view
    }

    private fun isDefiniteMediaRejection(
        source: HistorySnapshot,
        effectiveMessages: List<Message>,
        policy: ContextPolicy,
        hasReusableView: Boolean,
        rejectedReadmission: Boolean,
    ): Boolean {
        val activeMessages = source.messages.subList(policy.priorMessageCount, source.messages.size)
        if (hasUnsupportedMedia(target, config.model, activeMessages)) return true
        if (!hasUnsupportedMedia(target, config.model, effectiveMessages)) return false
        if (hasReusableView || policy.priorMessageCount == 0) return true
        val summaryModel = policy.summaryModel ?: return true
        val priorMessages = source.messages.subList(0, policy.priorMessageCount)
        if (hasUnsupportedMedia(summaryModel.target, summaryModel.request.model, priorMessages)) return true
        if (rejectedReadmission || !requestViewLimit.admits(effectiveMessages)) return false
        val probeReq = copyRequest(config, cachedView(effectiveMessages))
        val estimate = budget.tokens(probeReq)
        val capacity = budget.limit(probeReq.model)
        val exceedsBudget = estimate != null && capacity != null && estimate > capacity
        return !exceedsBudget
    }

    private fun sourceDigestOf(source: HistorySnapshot, count: Int): String =
        (history as? SqliteRunHistory)?.verifiedSourceDigest(source, count)
            ?: sourceDigest(source.messages.subList(0, count))

    private fun matches(checkpoint: ContextCheckpoint, source: HistorySnapshot, key: String): Boolean =
        checkpoint.version == 1 && checkpoint.settingsKey == key && checkpoint.sourceRevision <= source.revision &&
            checkpoint.sourceCount in 0..source.messages.size &&
            (checkpoint.kind == "VIEW" || checkpoint.sourceRevision == source.revision && checkpoint.sourceCount == source.messages.size) &&
            checkpoint.sourceDigest == sourceDigestOf(source, checkpoint.sourceCount)

    private suspend fun summarize(snapshot: HistorySnapshot, source: List<Message>,
        records: List<InvocationRecord>, reference: String): String? {
        val model = contextPolicy?.summaryModel ?: return null
        val disk = history as? SqliteRunHistory ?: return null
        return ContextSummarizer(model, journal, timeouts, disk::createSummarySpool) {
            if (steps >= limits.maxSteps || retries >= limits.maxRetries) false
            else {
                beforeModelSubmit()
                currentCoroutineContext().ensureActive()
                check(history.revision() == snapshot.revision) { "History changed during active run" }
                steps++; summarySteps++; retries++
                persistAccounting()
                disk.saveCheckpoint(checkpoint(snapshot, snapshot.messages.size,
                    null, "PAUSED", "Summary attempt reserved; interrupted work requires review", false,
                    tools.currentCheckpointRecords(records))
                    .copy(pauseClass = "SUMMARY_RESERVED"))
                true
            }
        }.compact(source, reference).also { if (it == null) summaryFailed = true }
    }

    private fun checkpoint(source: HistorySnapshot, count: Int, summary: String?, kind: String,
                           reason: String, blocked: Boolean,
                           records: List<InvocationRecord> = tools.checkpointRecords(source.messages)): ContextCheckpoint {
        val policy = requireNotNull(contextPolicy)
        return ContextCheckpoint(id = UUID.randomUUID().toString(), sessionId = identity.sessionId,
            taskChainId = identity.taskChainId, runId = identity.runId,
            nextRunId = if (kind == "CONTINUATION") UUID.randomUUID().toString() else null,
            unattended = identity.unattended, sourceRevision = source.revision, sourceCount = count,
            sourceDigest = sourceDigestOf(source, count), settingsKey = settingsKey(target.id, config, target.provider.contextConfigurationKey()),
            goal = policy.goal, steps = steps, retries = retries, kind = kind, summary = summary,
            reason = reason, serverUnresolved = blocked, ledger = records,
            catalog = tools.definitions.sortedBy { it.modelName }, budgetKey = budgetKey())
    }

    /** Old-history compaction only; the active run's prefix is never partly stripped. */
    private suspend fun compactPrior(source: HistorySnapshot): Boolean {
        val policy = contextPolicy ?: return false
        val disk = history as? SqliteRunHistory ?: return false
        if (contextView != null || policy.priorMessageCount == 0 || serverLoopOpen || serverUnknown) return false
        val records = tools.checkpointRecords(source.messages)
        // Only live/undecided local execution blocks ordinary compaction; accepted UNKNOWN effects
        // stay in the ledger and still gate later writes. Checkpoint continuation remains stricter.
        if (tools.compactionBlocked(records)) return false
        val summary = summarize(source, source.messages.subList(0, policy.priorMessageCount), records,
            "${identity.sessionId}@${source.revision}") ?: return false
        val saved = checkpoint(source, policy.priorMessageCount, summary, "VIEW", "Prior history summary", false,
            tools.currentCheckpointRecords(records))
        val summarized = saved.messages(source.messages)
        if (!requestViewLimit.admits(summarized)) return false
        val after = budget.tokens(copyRequest(config, summarized))
        if (requestViewLimit.admits(source.messages)) {
            val before = budget.tokens(copyRequest(config, source.messages)) ?: return false
            if (after == null || after >= before) return false
        }
        disk.saveCheckpoint(saved)
        contextView = saved
        return true
    }

    /** Checkpoint creation never starts a new run. The host explicitly selects the returned ID.
     * A failed summary, unknown side effect, or open server loop yields a durable PAUSED record. */
    private suspend fun pauseContext(source: HistorySnapshot, reason: String): RunResult {
        val disk = history as? SqliteRunHistory
        if (contextPolicy == null || disk == null) return result(RunState.PAUSED, reason, serverUnknown)
        val remoteBlocked = serverUnknown || serverLoopOpen
        val records = tools.checkpointRecords(source.messages)
        val localBlocked = tools.checkpointBlocked(records)
        val blocked = remoteBlocked || localBlocked
        // Commit a resumable *record*, not a continuation, before any billable summary call.
        disk.saveCheckpoint(checkpoint(source, source.messages.size, null, "PAUSED", reason, remoteBlocked, records)
            .copy(pauseClass = if (localBlocked && !remoteBlocked) "LOCAL_WAIT" else "REVIEW"))
        val summary = if (blocked || summaryFailed || irreducible(source)) null
            else summarize(source, source.messages, records, "${identity.sessionId}@${source.revision}")
        val budgetPause = !blocked && (reason.contains("budget", ignoreCase = true) || reason.contains("CONTEXT_TOO_LONG") ||
            reason.contains("Truncated response"))
        val saved = checkpoint(source, source.messages.size, summary,
            if (summary == null) "PAUSED" else "CONTINUATION", reason, remoteBlocked,
            tools.currentCheckpointRecords(records))
            .copy(pauseClass = if (localBlocked && !remoteBlocked) "LOCAL_WAIT" else if (budgetPause) "BUDGET" else "REVIEW")
        disk.saveCheckpoint(saved)
        return result(RunState.PAUSED, "$reason; checkpoint ${saved.id}" +
            if (summary == null) "; reduce input or choose a larger window" else "; summary continuation requires explicit selection", serverUnknown)
    }
    init {
        require(initialSteps >= 0 && initialRetries >= 0)
        // The executable directory and model-visible catalog cannot diverge.
        require(config.tools.map { it.name }.toSet() == tools.definitions.map { it.modelName }.toSet())
        require(config.tools.size == tools.definitions.size)
        config.tools.forEach { t ->
            val d = tools.definitions.single { it.modelName == t.name }
            require(t.description == d.description && t.inputSchema == Json.parseToJsonElement(d.inputSchema))
        }
    }
    private fun persistServerBarrier(reason: String) {
        val disk = history as? SqliteRunHistory ?: return
        val prior = disk.loadBarrier()
        disk.saveBarrier(prior?.copy(steps = maxOf(prior.steps, steps), retries = maxOf(prior.retries, retries))
            ?: ExecutionBarrier(identity.sessionId, identity.taskChainId, identity.runId, reason, steps, retries,
                UUID.randomUUID().toString()))
    }

    private fun refreshCheckpoint(saved: ContextCheckpoint, source: HistorySnapshot,
        records: List<InvocationRecord> = tools.checkpointRecords(source.messages)) = saved.copy(
        id = UUID.randomUUID().toString(), sessionId = identity.sessionId, taskChainId = identity.taskChainId,
        runId = identity.runId, unattended = identity.unattended, nextRunId = null,
        sourceRevision = source.revision, sourceCount = source.messages.size,
        sourceDigest = sourceDigestOf(source, source.messages.size), steps = steps, retries = retries,
        settingsKey = settingsKey(target.id, config, target.provider.contextConfigurationKey()),
        ledger = records, catalog = tools.definitions.sortedBy { it.modelName },
        kind = "PAUSED", summary = null, serverUnresolved = false)

    private fun result(state: RunState, reason: String, unknown: Boolean = false): RunResult {
        val unresolved = unknown || serverUnknown || serverLoopOpen
        if (unresolved) persistServerBarrier(reason)
        // Admission state is not accounting. Retire only a resolved admission/local wait;
        // summary reservations, budget pauses and continuations must survive every exit.
        val disk = history as? SqliteRunHistory
        if (!unresolved && persistedPause == null && disk != null) {
            val saved = disk.loadCheckpoint()
            val source = history.runSnapshot()
            val records = tools.checkpointRecords(source.messages)
            val localBlocked = tools.localExecutionBlocked(records)
            val settled = saved == null || saved.kind in setOf("VIEW", "COMPLETE") || disk.checkpointReconciled(saved) ||
                saved.pauseClass == "ADMITTED" && (!admissionPending || confirmedGenerationResponse) ||
                saved.pauseClass == "LOCAL_WAIT" && !localBlocked
            if (settled && !localBlocked) {
                val completed = if (contextPolicy != null) checkpoint(source,
                    source.messages.size, null, "COMPLETE", reason, false, records)
                else saved?.let { refreshCheckpoint(it, source, records).copy(kind = "COMPLETE", pauseClass = "REVIEW", reason = reason) }
                if (completed != null) disk.saveCheckpoint(completed)
            }
        }
        return RunResult(state, reason, steps, retries, unresolved)
    }

    suspend fun run(): RunResult = run(chatTask = false)
    suspend fun runChatTask(): RunResult {
        require(history is SqliteRunHistory)
        return run(chatTask = true)
    }

    private suspend fun run(chatTask: Boolean): RunResult {
        check(started.compareAndSet(false, true)) { "Run is one-shot; do not replay a partially executed step" }
        val disk = history as? SqliteRunHistory
        disk?.beginRun()
        var failure: Throwable? = null
        try {
            return runStarted(chatTask)
        } catch (e: HistoryViewLimitException) {
            // Do not materialize the rejected transaction merely to construct another checkpoint.
            return RunResult(RunState.PAUSED, e.message!!, steps, retries,
                serverUnknown || serverLoopOpen || disk?.loadBarrier() != null || disk?.loadCheckpoint()?.serverUnresolved == true)
        } catch (e: UncertainSummaryResponse) {
            return result(RunState.PAUSED, "Summary response uncertain; host reconciliation required")
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            // Non-suspending: real cancellation and collaborator/storage exceptions obey the
            // same accounting rule. Never mask primary failure if the history writer is poisoned.
            try { persistAccounting() } catch (e: Throwable) {
                if (failure == null) throw e else failure.addSuppressed(e)
            } finally { disk?.endRun() }
        }
    }

    private suspend fun runStarted(chatTask: Boolean): RunResult {
        tools.freeze(identity)
        var view = history.runSnapshot()
        if (history !is SqliteRunHistory) validateHistory(view.messages)
        activeInputCount = view.messages.size
        restoreContext(view)
        persistedPause?.let { return result(RunState.PAUSED, it, serverUnknown) }
        localMediaFailure?.let { return RunResult(RunState.FAILED, it, steps, retries, false) }
        accountingActive = true
        // Cold recovery must know attendance even if the process dies during the first request.
        persistAccounting()
        // A newer interrupted task must not inherit the previous turn's completed status.
        if (chatTask) (history as SqliteRunHistory).saveChatResult(RunResult(RunState.PAUSED,
            "Run admitted; awaiting durable completion", steps, retries))
        if (rejectedContextReadmission && !compactPrior(view))
            return pauseContext(view, "Model error: CONTEXT_TOO_LONG")
        val causes = mutableMapOf<String, Int>()
        // Transient failures share one budget per selected endpoint/model, regardless of error code.
        // Credentials are deliberately excluded: rotating a key must not reset this budget.
        val transientCauses = mutableMapOf<Pair<String, String>, Int>()
        while (steps < limits.maxSteps) {
            currentCoroutineContext().ensureActive()
            if (tools.continuationBlocked(identity))
                return result(RunState.PAUSED, "Tool execution or resource recovery unresolved")
            check(history.revision() == view.revision) { "History changed during active run" }
            val rawMessages = requestMessages(view)
            val outgoing = target.provider.prepareToolMedia(copyRequest(config, rawMessages))
            if (!requestViewLimit.admits(outgoing.messages)) {
                if (!irreducible(view) && compactPrior(view)) continue
                return pauseContext(view, "Request memory budget exceeded; checkpoint required")
            }
            val messages = cachedView(outgoing.messages).map { m ->
                if (cacheDisabled) message(m.role, *m.parts.toTypedArray()) else m
            }
            val request = copyRequest(config, messages, requestId = UUID.randomUUID().toString())
            // A wire estimator may reject incompatible history too (for example native
            // tool input that the ledger rejected). Preserve it via the checkpoint path
            // before asking the provider to serialize it for budget estimation.
            if (!compatible(target, request)) return pauseContext(view, "Replay incompatible; checkpoint required")
            val estimate = budget.tokens(request)
            val capacity = budget.limit(request.model)
            if (estimate != null && capacity != null && estimate > capacity) {
                if (!irreducible(view) && compactPrior(view)) continue
                return pauseContext(view, "Request budget exceeded; checkpoint required")
            }
            if (!mediaSupported(target, request)) return result(RunState.FAILED, "Image input disabled for active model")
            // PAUSED confirms an open server loop even if the request was initially
            // classified as generation-only. Its continuation must not be retried blindly.
            val mode = if (serverLoopOpen) ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
                else target.provider.executionMode(request) // freeze BEFORE submission, including extraBody
            val admittedToolNames = target.provider.toolNamesForExecution(request).toSet()
            val reducer = ModelEventReducer(target.id, request.requestId)
            beforeModelSubmit()
            currentCoroutineContext().ensureActive()
            if (admissionPending) (history as? SqliteRunHistory)?.let { disk ->
                val reserved = if (contextPolicy != null) checkpoint(view, view.messages.size,
                    null, "PAUSED", "Admitted request awaiting durable response", false)
                else refreshCheckpoint(requireNotNull(disk.loadCheckpoint()), view)
                disk.saveCheckpoint(reserved.copy(pauseClass = "ADMITTED",
                    reason = "Admitted request awaiting durable response"))
            }
            if (mode != ModelExecutionMode.GENERATION_ONLY) persistServerBarrier("Server request submitted; result not yet durable")
            journal.submitted(request.requestId, target.id, mode)
            var cancelled = false
            confirmedGenerationResponse = false
            val evidence = ResponseEvidence()
            try {
                // Wrap creation as well as collection: synchronous chat() failures obey 2.8 too.
                evidence.observe(flow { emitAll(target.provider.chat(request, target.credentials)) })
                    .validated(target.id, request.requestId, timeouts).collect {
                        reducer.accept(it)
                        if (it is ModelEvent.TextDelta || it is ModelEvent.ReasoningDelta || it is ModelEvent.ToolCallStart ||
                            it is ModelEvent.PartEnd && it.replay != null) reasoningOutputObserved = true
                        onEvent(request.requestId, it)
                    }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                journal.finished(ModelAttempt(request.requestId, target.id, mode, reducer.assistantMessage(),
                    reducer.terminal, reducer.usage, cancelled, reducer.historyDisposition()))
            }
            val terminal = reducer.terminal!!
            confirmedGenerationResponse = mode == ModelExecutionMode.GENERATION_ONLY && evidence.confirms(terminal)
            if (admissionPending && !confirmedGenerationResponse && terminal is ModelEvent.Failed &&
                mode == ModelExecutionMode.GENERATION_ONLY)
                return result(RunState.PAUSED, "Admitted response uncertain; host reconciliation required")
            if (terminal is ModelEvent.Failed) {
                val transient = terminal.error.code in setOf(ModelErrorCode.OVERLOADED, ModelErrorCode.SERVER,
                    ModelErrorCode.NETWORK, ModelErrorCode.TIMEOUT)
                val endpoint = target.id to request.model
                // 2.8 removes "this parameter" once, so each rejected parameter has its own attempt.
                val key = if (terminal.error.code == ModelErrorCode.UNSUPPORTED_PARAMETER)
                    "${terminal.error.code}:${terminal.error.param}" else terminal.error.code.name
                val n = if (transient) transientCauses.getOrDefault(endpoint, 0) else causes.getOrDefault(key, 0)
                val decision = recoveryDecision(terminal.error, mode, n)
                if (decision.serverUnknown) {
                    serverUnknown = true
                    return pauseContext(view, "Server execution state unknown")
                }
                // Summary reservations consume the task budget, but are not new assistant turns.
                // Inherited steps remain conservative evidence of a previously started run.
                if (canRemoveReasoning(request, terminal.error, mode, steps - summarySteps, reducer.assistantMessage(),
                        confirmedGenerationResponse, reasoningRemoved || reasoningOutputObserved) && retries < limits.maxRetries) {
                    reasoningCapabilities.unsupported(target, request)
                    config = copyRequest(object : ChatRequest by config { override val reasoningLevel: String? = null }, emptyList())
                    reasoningRemoved = true; retries++; causes[key] = n + 1
                    persistAccounting()
                    continue
                }
                if (terminal.error.code == ModelErrorCode.UNSUPPORTED_PARAMETER && terminal.error.param == "cache_control" &&
                    !cacheDisabled && request.messages.any { it.cacheBreakpoint } && retries < limits.maxRetries) {
                    cacheDisabled = true; retries++
                    persistAccounting()
                    continue
                }
                if (terminal.error.code == ModelErrorCode.CONTEXT_TOO_LONG && n == 0 && !irreducible(view) && compactPrior(view)) {
                    causes[key] = n + 1
                    continue
                }
                if (recover(decision, terminal.error, request)) {
                    if (transient) transientCauses[endpoint] = n + 1 else causes[key] = n + 1
                    continue
                }
                if (checkpointRequired || terminal.error.code == ModelErrorCode.CONTEXT_TOO_LONG)
                    return pauseContext(view, if (checkpointRequired) "Replay incompatible; checkpoint required" else "Model error: CONTEXT_TOO_LONG")
                return result(RunState.FAILED, "Model error: ${terminal.error.code}")
            }
            val finish = terminal as ModelEvent.Finished
            val assistant = reducer.assistantMessage()
            val calls = reducer.calls()
            val disposition = reducer.historyDisposition()
            if (finish.reason == FinishReason.REFUSAL || finish.reason == FinishReason.CONTENT_FILTER) {
                val n = causes.getOrDefault("refusal", 0)
                if (mode != ModelExecutionMode.GENERATION_ONLY) {
                    serverUnknown = true
                    return pauseContext(view, "Server execution state unknown")
                }
                if (n == 0 && recover(RecoveryDecision(RecoveryAction.FALLBACK), null, request)) {
                    causes["refusal"] = 1; continue
                }
                if (checkpointRequired) return pauseContext(view, "Replay incompatible; checkpoint required")
                return result(RunState.FAILED, "Model refused response")
            }
            if (finish.reason in setOf(FinishReason.STOP, FinishReason.OTHER, FinishReason.TOOL_CALLS) && disposition == HistoryDisposition.DISCARD) {
                if (mode != ModelExecutionMode.GENERATION_ONLY) {
                    serverUnknown = true
                    return pauseContext(view, "Server execution state unknown")
                }
                val n = causes.getOrDefault("empty", 0)
                if (n == 0 && recover(RecoveryDecision(RecoveryAction.RETRY), null, request)) {
                    causes["empty"] = 1; continue
                }
                return result(RunState.FAILED, "Empty model response")
            }
            val truncated = finish.reason in setOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)
            if (truncated && mode != ModelExecutionMode.GENERATION_ONLY) serverUnknown = true
            if (truncated && disposition == HistoryDisposition.DISCARD) {
                val n = causes.getOrDefault("length", 0)
                if (mode != ModelExecutionMode.GENERATION_ONLY) {
                    serverUnknown = true
                    return pauseContext(view, "Server execution state unknown")
                }
                if (finish.reason == FinishReason.LENGTH && n == 0 &&
                    recover(RecoveryDecision(RecoveryAction.INCREASE_OUTPUT), null, request)) {
                    causes["length"] = 1; continue
                }
                if (finish.reason == FinishReason.CONTEXT_EXCEEDED)
                    return pauseContext(view, "Truncated response; checkpoint required")
                return result(RunState.PAUSED, "Truncated response; checkpoint or user decision required")
            }
            steps++
            // Reserve the count before pending intent, ledger registration or history append.
            val chatDisk = (history as? SqliteRunHistory)?.takeIf { chatTask }
            val finalAnswer = chatDisk != null && calls.isEmpty() && !truncated && !serverUnknown &&
                finish.reason != FinishReason.PAUSED && disposition != HistoryDisposition.DISCARD
            if (!finalAnswer) persistAccounting()
            val duplicate = calls.any { call ->
                val args = runCatching { canonical(Json.parseToJsonElement(call.argsJson)) }.getOrDefault(call.argsJson)
                val key = call.name + "\u0000" + args
                repeated = if (key == lastCall) repeated + 1 else 1
                lastCall = key
                repeated >= limits.repeatedCalls
            }
            val execute = !truncated && !duplicate && reducer.toolCandidates().isNotEmpty()
            val step = request.requestId
            var executionFailure: Exception? = null
            if (calls.isNotEmpty()) {
                // Validate the whole batch before intent or registration, including with memory
                // history. Do not replace code units, partially register siblings or fabricate results.
                if (!HistoryCodec.toolInputsAreUtf8(HistoryCodec.encodeMessage(assistant))) {
                    serverUnknown = serverUnknown || mode != ModelExecutionMode.GENERATION_ONLY
                    return result(RunState.PAUSED,
                        "Tool step contains an unpaired Unicode surrogate in arguments, name or binding; no local tools registered")
                }
                // Persist the complete assistant before registration, so a process loss during
                // registration/execution can reconcile the entire ordered step from ledger truth.
                if (chatDisk != null) chatDisk.prepareStep(view.revision, identity, step, assistant,
                    tools.definitions.singleOrNull { it.toolKey == "host/blob_read" })
                else history.prepareStep(view.revision, identity, step, assistant)
                try {
                    val reason = when {
                        truncated -> null // Protocol 2.7 requires truncation results even at the repeat limit.
                        duplicate -> NonExecutionReason.REPEATED_CALL_LIMIT
                        finish.reason == FinishReason.PAUSED -> NonExecutionReason.MODEL_PAUSED
                        else -> null
                    }
                    tools.register(identity, step, calls, cancelled = !execute, reason = reason,
                        admittedToolNames = admittedToolNames)
                    if (execute) tools.execute(calls)
                    currentCoroutineContext().ensureActive()
                } catch (e: Exception) {
                    if (e is ToolStepPaused && chatDisk != null &&
                        mode == ModelExecutionMode.GENERATION_ONLY && currentCoroutineContext().isActive) {
                        // The full response/step is durable. Do not cancel queued calls or append
                        // synthetic results. The host resumes these bindings without regenerating
                        // the model turn, and still rechecks approval, isolation and actual exit.
                        return RunResult(RunState.PAUSED, "Tool step admission paused: ${e.message}", steps, retries)
                    }
                    executionFailure = e
                    // Never retry this generation, even if submit threw before returning a handle.
                    // Complete the full registered batch using ledger truth, not CANCELLED/NONE guesses.
                    try { tools.cancelRemaining(calls) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                }
            }
            // Non-suspending atomic append also runs in a cancelled context. If storage is uncertain,
            // propagate and halt; ledger/journal retain evidence for host reconciliation. No retry.
            try {
                if (disposition != HistoryDisposition.DISCARD) {
                    val batch = if (calls.isEmpty()) listOf(assistant)
                        else listOf(assistant, tools.results(identity, step, calls))
                    if (finalAnswer) requireNotNull(chatDisk).appendChatAnswer(view.revision, assistant,
                        RunAccounting(identity.sessionId, identity.taskChainId, identity.runId, steps, retries, identity.unattended),
                        tools.canRetireChatChain(identity))
                    else history.append(view.revision, batch)
                    if (finalAnswer) accountingActive = false // Final counters committed with the answer.
                    view = history.runSnapshot()
                    admissionPending = false
                }
            } catch (e: Exception) {
                executionFailure?.let { e.addSuppressed(it) }
                throw e
            }
            // Update before *any* terminal path (duplicate/tool error/limits included).
            serverLoopOpen = finish.reason == FinishReason.PAUSED
            if (serverLoopOpen) persistServerBarrier("Server loop paused; continuation incomplete")
            else if (!truncated && disposition != HistoryDisposition.DISCARD)
                (history as? SqliteRunHistory)?.completeServerExecution(identity)
            val localBlocked = calls.isNotEmpty() && tools.localExecutionBlocked(tools.checkpointRecords(view.messages))
            if (localBlocked && !serverUnknown && !serverLoopOpen && contextPolicy != null) {
                (history as? SqliteRunHistory)?.saveCheckpoint(checkpoint(view, view.messages.size,
                    null, "PAUSED", "Tool execution or effect unresolved", false).copy(pauseClass = "LOCAL_WAIT"))
            }
            executionFailure?.let {
                // A collaborator's own cancelled Deferred or withTimeout is not cancellation of this run.
                if (it is CancellationException && !currentCoroutineContext().isActive) throw it
                return result(RunState.PAUSED, "Tool step interrupted: ${it.message}")
            }
            if (localBlocked)
                return pauseContext(view, "Tool execution or effect unresolved")
            if (duplicate) return result(RunState.PAUSED, "Repeated tool call limit")
            if (truncated) {
                serverUnknown = mode != ModelExecutionMode.GENERATION_ONLY
                if (finish.reason == FinishReason.CONTEXT_EXCEEDED)
                    return pauseContext(view, "Truncated response; checkpoint required")
                return result(RunState.PAUSED, "Truncated response; checkpoint or user decision required", serverUnknown)
            }
            serverLoopOpen = finish.reason == FinishReason.PAUSED
            if (finish.reason == FinishReason.PAUSED) {
                if (disposition == HistoryDisposition.DISCARD)
                    return result(RunState.PAUSED, "Missing server continuation history",
                        mode != ModelExecutionMode.GENERATION_ONLY)
                if (++paused > limits.maxPaused) return result(RunState.PAUSED, "Server continuation limit")
            } else {
                if (calls.isEmpty()) return result(RunState.COMPLETED, finish.rawReason ?: finish.reason.name)
                paused = 0 // The server loop ended; maxPaused bounds one continuation chain, maxSteps the run.
            }
            causes.clear()
            transientCauses.clear()
        }
        return result(RunState.PAUSED, "Step limit")
    }

    private fun compatible(target: ModelTarget, request: ChatRequest): Boolean =
        target.provider.canReplayMessages(request.messages, request.model)

    private fun hasUnsupportedMedia(target: ModelTarget, model: String, messages: List<Message>): Boolean =
        messages.any { m ->
            m.parts.filterIsInstance<ContentPart.Media>().any { media ->
                !target.provider.supportsMediaInput(media.kind, model)
            }
        }

    private fun mediaSupported(target: ModelTarget, request: ChatRequest): Boolean =
        !hasUnsupportedMedia(target, request.model, request.messages)

    private suspend fun recover(decision: RecoveryDecision, error: ModelError?, request: ChatRequest): Boolean {
        val action = decision.action ?: return false
        if (action == RecoveryAction.REMOVE_PARAMETER && isReasoningParameter(error?.param)) return false
        if (retries >= limits.maxRetries || decision.delayMs > limits.maxRetryWaitMs) return false
        val proposal = try {
            when (action) {
                RecoveryAction.RETRY -> RecoveryProposal(target, request)
                RecoveryAction.ROTATE_CREDENTIALS -> recovery.recover(action, error, target, request) ?: RecoveryProposal(target, request)
                else -> recovery.recover(action, error, target, request) ?: return false
            }
        } catch (e: CancellationException) {
            // A strategy's local timeout is a failed recovery; only real cancellation ends the run.
            if (!currentCoroutineContext().isActive) throw e
            return false
        }
        val next = copyRequest(proposal.request)
        // Recovery may change a credential/model/output limit or remove an optional parameter,
        // never mutate the tool catalog, reasoning configuration, system, or live history.
        if (next.messages != request.messages || next.tools != request.tools || next.system != request.system ||
            next.reasoningLevel != request.reasoningLevel) return false
        val expected = copyRequest(object : ChatRequest by request {
            override val model = if (action == RecoveryAction.FALLBACK) next.model else request.model
            override val maxOutputTokens = if (action in setOf(RecoveryAction.INCREASE_OUTPUT, RecoveryAction.SHRINK_REQUEST))
                next.maxOutputTokens else request.maxOutputTokens
            override val temperature = if (action == RecoveryAction.REMOVE_PARAMETER && error?.param == "temperature") null else request.temperature
            override val topP = if (action == RecoveryAction.REMOVE_PARAMETER && error?.param == "top_p") null else request.topP
            override val parallelToolCalls = if (action == RecoveryAction.REMOVE_PARAMETER && error?.param == "parallel_tool_calls") null else request.parallelToolCalls
            override val responseSchema = if (action == RecoveryAction.REMOVE_PARAMETER && error?.param == "response_format") null else request.responseSchema
            override val stopSequences = if (action == RecoveryAction.REMOVE_PARAMETER && error?.param == "stop") emptyList() else request.stopSequences
            override val extraBody = if (action == RecoveryAction.REMOVE_PARAMETER && error?.param?.let { it in request.extraBody } == true)
                JsonObject(request.extraBody.filterKeys { it != error?.param }) else request.extraBody
        }, requestId = next.requestId)
        if (next != expected) return false
        if (action != RecoveryAction.FALLBACK && (proposal.target.id != target.id || proposal.target.provider !== target.provider)) return false
        if (action == RecoveryAction.SHRINK_REQUEST) {
            val before = budget.tokens(request) ?: return false
            val after = budget.tokens(next) ?: return false
            if (after <= 0 || after >= before) return false
        }
        if (action == RecoveryAction.INCREASE_OUTPUT &&
            (next.maxOutputTokens ?: 0) <= (request.maxOutputTokens ?: 0)) return false
        if (action == RecoveryAction.REMOVE_PARAMETER && !removed(error?.param, request, next)) return false
        if (action == RecoveryAction.FALLBACK && proposal.target == target && next.model == request.model) return false
        if (!compatible(proposal.target, next)) { checkpointRequired = true; return false }
        if (!mediaSupported(proposal.target, next)) return false
        retries++
        persistAccounting()
        delay(decision.delayMs)
        target = proposal.target.copy()
        config = copyRequest(next, emptyList())
        return true
    }

    private fun removed(param: String?, before: ChatRequest, after: ChatRequest): Boolean = when (param) {
        "temperature" -> before.temperature != null && after.temperature == null
        "top_p" -> before.topP != null && after.topP == null
        "parallel_tool_calls" -> before.parallelToolCalls != null && after.parallelToolCalls == null
        "response_format" -> before.responseSchema != null && after.responseSchema == null
        "stop" -> before.stopSequences.isNotEmpty() && after.stopSequences.isEmpty()
        else -> param != null && param in before.extraBody && param !in after.extraBody
    }
}
