package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.canonical
import io.github.bytejuno.thetacore.agent.ledger.atLedgerPrecision
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RunLimits(val maxSteps: Int = 50, val maxRetries: Int = 12, val maxPaused: Int = 3,
                     val repeatedCalls: Int = 3, val maxRetryWaitMs: Long = 120_000) {
    init { require(maxSteps > 0 && maxRetries >= 0 && maxPaused >= 0 && repeatedCalls > 0 && maxRetryWaitMs >= 0) }
}
enum class RunState { COMPLETED, PAUSED, FAILED }
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
    private var activeInputCount = 0
    private var admissionPending = false
    private var accountingActive = false
    private var confirmedGenerationResponse = false

    private fun persistAccounting() {
        if (accountingActive) (history as? FileRunHistory)?.saveAccounting(
            RunAccounting(identity.sessionId, identity.taskChainId, identity.runId, steps, retries))
    }

    private fun irreducible(source: HistorySnapshot): Boolean {
        val capacity = budget.limit(config.model) ?: return false
        val base = copyRequest(config, emptyList())
        if ((budget.tokens(base) ?: 0) > capacity) return true
        return source.messages.take(activeInputCount).drop(contextPolicy?.priorMessageCount ?: 0)
            .filter { it.role == Role.USER }.any { (budget.tokens(copyRequest(config, listOf(it))) ?: 0) > capacity }
    }

    private fun requestMessages(source: HistorySnapshot): List<Message> {
        val view = contextView
        if (view != null) require(view.matches(source, view.settingsKey)) { "Active context source changed" }
        // Accepted recovery may change output reserve/model; never silently restore omitted raw pairs.
        return cachedView(view?.messages(source.messages) ?: source.messages)
    }

    private fun budgetKey(): String = listOf(budget.limit(config.model), contextPolicy?.summaryModel?.let {
            settingsKey(it.target.id, it.request, it.target.provider.contextConfigurationKey()) + ":" + it.budget.limit(it.request.model)
        }).joinToString("|")

    private fun restoreContext(source: HistorySnapshot) {
        val disk = history as? FileRunHistory
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
        val localBlocked = tools.localExecutionBlocked(tools.checkpointRecords(source.messages)) ||
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
        val policy = contextPolicy ?: return
        require(policy.priorMessageCount <= source.messages.size)
        validateHistory(source.messages.take(policy.priorMessageCount))
        validateHistory(source.messages.drop(policy.priorMessageCount))
        require(policy.resumeCheckpointId == null && policy.readmitBudgetCheckpointId == null || disk != null) { "Continuation requires persistent history" }
        if (disk == null) return
        if (policy.readmitBudgetCheckpointId != null) {
            require(saved != null && saved.id == policy.readmitBudgetCheckpointId && saved.kind == "PAUSED" &&
                saved.pauseClass == "BUDGET" && !saved.serverUnresolved)
            require(saved.taskChainId == identity.taskChainId && saved.goal == policy.goal)
            require(saved.catalog == tools.definitions.sortedBy { it.modelName })
            require(saved.matches(source, saved.settingsKey)) { "Budget readmission requires unchanged durable source" }
            require(saved.budgetKey != budgetKey()) { "Choose a changed window or summary configuration" }
            val records = tools.checkpointRecords(source.messages)
            require(records.map { it.atLedgerPrecision() } == saved.ledger.map { it.atLedgerPrecision() } && !tools.checkpointBlocked(records))
            disk.saveCheckpoint(saved.copy(pauseClass = "ADMITTED", reason = "Budget readmission reserved"))
            admissionPending = true
        }
        if (policy.resumeCheckpointId != null) {
            require(saved != null && saved.id == policy.resumeCheckpointId && saved.kind == "CONTINUATION")
            require(saved.identity() == identity && saved.goal == policy.goal && !saved.serverUnresolved)
            require(saved.catalog == tools.definitions.sortedBy { it.modelName }) { "Checkpoint catalog changed" }
            require(saved.matches(source, settingsKey(target.id, config, target.provider.contextConfigurationKey()))) { "Stale checkpoint or changed run settings" }
            val records = tools.checkpointRecords(source.messages)
            require(records.map { it.atLedgerPrecision() } == saved.ledger.map { it.atLedgerPrecision() } && !tools.checkpointBlocked(records))
            // Counter restoration above uses this checkpoint only for legacy sessions.
            // The continuation's initial summary is a stable view; future appends keep it valid.
            contextView = saved.copy(kind = "VIEW", nextRunId = null)
            // Consume admission before submission. A crash must not allow the same continuation
            // token to replay work whose response may never have reached history.
            disk.saveCheckpoint(saved.copy(kind = "PAUSED", nextRunId = null, summary = null, pauseClass = "ADMITTED",
                reason = "Continuation admitted; reconcile journal before another attempt"))
            admissionPending = true
        } else {
            val view = disk.loadView() ?: saved?.takeIf { it.kind == "VIEW" }
            if (view != null && view.sourceCount <= policy.priorMessageCount &&
                view.catalog == tools.definitions.sortedBy { it.modelName } &&
                view.matches(source, settingsKey(target.id, config, target.provider.contextConfigurationKey()))) {
                val records = tools.checkpointRecords(source.messages)
                // Ordinary prior-history compaction (2.11) is not checkpoint continuation: after
                // actual exit and a recorded decision, the ledger keeps enforcing UNKNOWN approvals.
                if (tools.compactionBlocked(records)) return
                val durableRecords = records.map { it.atLedgerPrecision() }.associateBy { it.callId }
                // A derived view whose ledger snapshot no longer matches (e.g. late exit evidence)
                // is stale, not a session lock: fall back to raw history, which may re-compact.
                if (view.ledger.any { old -> durableRecords[old.callId] != old.atLedgerPrecision() }) return
                contextView = view
            }
        }
    }

    private suspend fun summarize(source: List<Message>, reference: String): String? {
        val model = contextPolicy?.summaryModel ?: return null
        return ContextSummarizer(model, journal, timeouts) {
            if (steps >= limits.maxSteps || retries >= limits.maxRetries) false
            else {
                steps++; retries++
                persistAccounting()
                val snapshot = history.snapshot()
                (history as? FileRunHistory)?.saveCheckpoint(checkpoint(snapshot, snapshot.messages.size,
                    null, "PAUSED", "Summary attempt reserved; interrupted work requires review", false)
                    .copy(pauseClass = "SUMMARY_RESERVED"))
                true
            }
        }.compact(source, reference).also { if (it == null) summaryFailed = true }
    }

    private fun checkpoint(source: HistorySnapshot, count: Int, summary: String?, kind: String,
                           reason: String, blocked: Boolean): ContextCheckpoint {
        val policy = requireNotNull(contextPolicy)
        return ContextCheckpoint(id = UUID.randomUUID().toString(), sessionId = identity.sessionId,
            taskChainId = identity.taskChainId, runId = identity.runId,
            nextRunId = if (kind == "CONTINUATION") UUID.randomUUID().toString() else null,
            unattended = identity.unattended, sourceRevision = source.revision, sourceCount = count,
            sourceDigest = sourceDigest(source.messages.take(count)), settingsKey = settingsKey(target.id, config, target.provider.contextConfigurationKey()),
            goal = policy.goal, steps = steps, retries = retries, kind = kind, summary = summary,
            reason = reason, serverUnresolved = blocked, ledger = tools.checkpointRecords(source.messages),
            catalog = tools.definitions.sortedBy { it.modelName }, budgetKey = budgetKey())
    }

    /** Old-history compaction only; the active run's prefix is never partly stripped. */
    private suspend fun compactPrior(source: HistorySnapshot): Boolean {
        val policy = contextPolicy ?: return false
        val disk = history as? FileRunHistory ?: return false
        if (contextView != null || policy.priorMessageCount == 0 || serverLoopOpen || serverUnknown) return false
        val records = tools.checkpointRecords(source.messages)
        // Only live/undecided local execution blocks ordinary compaction; accepted UNKNOWN effects
        // stay in the ledger and still gate later writes. Checkpoint continuation remains stricter.
        if (tools.compactionBlocked(records)) return false
        val summary = summarize(source.messages.take(policy.priorMessageCount),
            "${identity.sessionId}@${source.revision}") ?: return false
        val saved = checkpoint(source, policy.priorMessageCount, summary, "VIEW", "Prior history summary", false)
        val before = budget.tokens(copyRequest(config, source.messages)) ?: return false
        val after = budget.tokens(copyRequest(config, saved.messages(source.messages))) ?: return false
        if (after >= before) return false
        disk.saveCheckpoint(saved)
        contextView = saved
        return true
    }

    /** Checkpoint creation never starts a new run. The host explicitly selects the returned ID.
     * A failed summary, unknown side effect, or open server loop yields a durable PAUSED record. */
    private suspend fun pauseContext(source: HistorySnapshot, reason: String): RunResult {
        val disk = history as? FileRunHistory
        if (contextPolicy == null || disk == null) return result(RunState.PAUSED, reason, serverUnknown)
        val remoteBlocked = serverUnknown || serverLoopOpen
        val localBlocked = tools.checkpointBlocked(tools.checkpointRecords(source.messages))
        val blocked = remoteBlocked || localBlocked
        // Commit a resumable *record*, not a continuation, before any billable summary call.
        disk.saveCheckpoint(checkpoint(source, source.messages.size, null, "PAUSED", reason, remoteBlocked)
            .copy(pauseClass = if (localBlocked && !remoteBlocked) "LOCAL_WAIT" else "REVIEW"))
        val summary = if (blocked || summaryFailed || irreducible(source)) null
            else summarize(source.messages, "${identity.sessionId}@${source.revision}")
        val budgetPause = !blocked && (reason.contains("budget", ignoreCase = true) || reason.contains("CONTEXT_TOO_LONG") ||
            reason.contains("Truncated response"))
        val saved = checkpoint(source, source.messages.size, summary,
            if (summary == null) "PAUSED" else "CONTINUATION", reason, remoteBlocked)
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
        val disk = history as? FileRunHistory ?: return
        val prior = disk.loadBarrier()
        disk.saveBarrier(prior?.copy(steps = maxOf(prior.steps, steps), retries = maxOf(prior.retries, retries))
            ?: ExecutionBarrier(identity.sessionId, identity.taskChainId, identity.runId, reason, steps, retries,
                UUID.randomUUID().toString()))
    }

    private fun refreshCheckpoint(saved: ContextCheckpoint, source: HistorySnapshot) = saved.copy(
        id = UUID.randomUUID().toString(), sessionId = identity.sessionId, taskChainId = identity.taskChainId,
        runId = identity.runId, unattended = identity.unattended, nextRunId = null,
        sourceRevision = source.revision, sourceCount = source.messages.size,
        sourceDigest = sourceDigest(source.messages), steps = steps, retries = retries,
        settingsKey = settingsKey(target.id, config, target.provider.contextConfigurationKey()),
        ledger = tools.checkpointRecords(source.messages), catalog = tools.definitions.sortedBy { it.modelName },
        kind = "PAUSED", summary = null, serverUnresolved = false)

    private fun result(state: RunState, reason: String, unknown: Boolean = false): RunResult {
        val unresolved = unknown || serverUnknown || serverLoopOpen
        if (unresolved) persistServerBarrier(reason)
        // Admission state is not accounting. Retire only a resolved admission/local wait;
        // summary reservations, budget pauses and continuations must survive every exit.
        val disk = history as? FileRunHistory
        if (!unresolved && persistedPause == null && disk != null) {
            val saved = disk.loadCheckpoint()
            val source = history.snapshot()
            val localBlocked = tools.localExecutionBlocked(tools.checkpointRecords(source.messages))
            val settled = saved == null || saved.kind in setOf("VIEW", "COMPLETE") || disk.checkpointReconciled(saved) ||
                saved.pauseClass == "ADMITTED" && (!admissionPending || confirmedGenerationResponse) ||
                saved.pauseClass == "LOCAL_WAIT" && !localBlocked
            if (settled && !localBlocked) {
                val completed = if (contextPolicy != null) checkpoint(source,
                    source.messages.size, null, "COMPLETE", reason, false)
                else saved?.let { refreshCheckpoint(it, source).copy(kind = "COMPLETE", pauseClass = "REVIEW", reason = reason) }
                if (completed != null) disk.saveCheckpoint(completed)
            }
        }
        return RunResult(state, reason, steps, retries, unresolved)
    }

    suspend fun run(): RunResult {
        check(started.compareAndSet(false, true)) { "Run is one-shot; do not replay a partially executed step" }
        val disk = history as? FileRunHistory
        disk?.beginRun()
        var failure: Throwable? = null
        try {
            return runStarted()
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

    private suspend fun runStarted(): RunResult {
        tools.freeze(identity)
        var view = history.snapshot()
        validateHistory(view.messages)
        activeInputCount = view.messages.size
        restoreContext(view)
        persistedPause?.let { return result(RunState.PAUSED, it, serverUnknown) }
        accountingActive = true
        val causes = mutableMapOf<String, Int>()
        // Transient failures share one budget per selected endpoint/model, regardless of error code.
        // Credentials are deliberately excluded: rotating a key must not reset this budget.
        val transientCauses = mutableMapOf<Pair<String, String>, Int>()
        while (steps < limits.maxSteps) {
            currentCoroutineContext().ensureActive()
            if (tools.continuationBlocked(identity))
                return result(RunState.PAUSED, "Tool execution or resource recovery unresolved")
            check(history.revision() == view.revision) { "History changed during active run" }
            val messages = requestMessages(view).map { m ->
                if (cacheDisabled) message(m.role, *m.parts.toTypedArray()) else m
            }
            val request = copyRequest(config, messages, requestId = UUID.randomUUID().toString())
            val estimate = budget.tokens(request)
            val capacity = budget.limit(request.model)
            if (estimate != null && capacity != null && estimate > capacity) {
                if (!irreducible(view) && compactPrior(view)) continue
                return pauseContext(view, "Request budget exceeded; checkpoint required")
            }
            if (!compatible(target, request)) return pauseContext(view, "Replay incompatible; checkpoint required")
            val mode = target.provider.executionMode(request) // freeze BEFORE submission, including extraBody
            val reducer = ModelEventReducer(target.id, request.requestId)
            if (admissionPending) (history as? FileRunHistory)?.let { disk ->
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
                    .validated(target.id, request.requestId, timeouts).collect { reducer.accept(it) }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                journal.finished(ModelAttempt(request.requestId, target.id, mode, reducer.assistantMessage(),
                    reducer.terminal, reducer.usage, cancelled))
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
            if (finish.reason in setOf(FinishReason.STOP, FinishReason.OTHER, FinishReason.TOOL_CALLS) && contentless(assistant)) {
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
            persistAccounting()
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
                // Persist the complete assistant before registration, so a process loss during
                // registration/execution can reconcile the entire ordered step from ledger truth.
                history.prepareStep(view.revision, identity, step, assistant)
                try {
                    tools.register(identity, step, calls, cancelled = !execute)
                    if (execute) tools.execute(calls)
                    currentCoroutineContext().ensureActive()
                } catch (e: Exception) {
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
                    history.append(view.revision, batch)
                    view = HistorySnapshot(history.revision(), freezeMessages(view.messages + batch))
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
                (history as? FileRunHistory)?.completeServerExecution(identity)
            val localBlocked = calls.isNotEmpty() && tools.localExecutionBlocked(tools.checkpointRecords(view.messages))
            if (localBlocked && !serverUnknown && !serverLoopOpen && contextPolicy != null) {
                (history as? FileRunHistory)?.saveCheckpoint(checkpoint(view, view.messages.size,
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

    /** Replay on an empty part is opaque adapter state, so only replay-free empty text is contentless. */
    private fun contentless(message: Message) = message.parts.all { it is ContentPart.Text && it.text.isEmpty() && it.replay == null }

    private fun compatible(target: ModelTarget, request: ChatRequest): Boolean = request.messages.all { m ->
        m.parts.all { p -> p.replay?.let { target.provider.canReplay(it, request.model) } != false }
    }

    private suspend fun recover(decision: RecoveryDecision, error: ModelError?, request: ChatRequest): Boolean {
        val action = decision.action ?: return false
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
