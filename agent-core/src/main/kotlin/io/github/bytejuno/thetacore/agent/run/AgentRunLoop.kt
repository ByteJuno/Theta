package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.canonical
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
 * Hosts continuing a checkpoint must carry counters forward (issue19 persistence integration).
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
    private fun result(state: RunState, reason: String, unknown: Boolean = false) = RunResult(state, reason, steps, retries, unknown)

    suspend fun run(): RunResult {
        check(started.compareAndSet(false, true)) { "Run is one-shot; do not replay a partially executed step" }
        tools.freeze(identity)
        var view = history.snapshot()
        validateHistory(view.messages)
        val causes = mutableMapOf<String, Int>()
        // Transient failures share one budget per selected endpoint/model, regardless of error code.
        // Credentials are deliberately excluded: rotating a key must not reset this budget.
        val transientCauses = mutableMapOf<Pair<String, String>, Int>()
        while (steps < limits.maxSteps) {
            currentCoroutineContext().ensureActive()
            if (tools.continuationBlocked(identity))
                return result(RunState.PAUSED, "Tool execution or resource recovery unresolved")
            check(history.revision() == view.revision) { "History changed during active run" }
            val notices = tools.uncertaintyNotices(identity, view.messages)
            if (notices.isNotEmpty()) {
                history.append(view.revision, notices)
                view = HistorySnapshot(history.revision(), freezeMessages(view.messages + notices))
            }
            val request = copyRequest(config, view.messages, requestId = UUID.randomUUID().toString())
            val estimate = budget.tokens(request)
            val capacity = budget.limit(request.model)
            if (estimate != null && capacity != null && estimate > capacity)
                return result(RunState.PAUSED, "Request budget exceeded; checkpoint required")
            if (!compatible(target, request)) return result(RunState.PAUSED, "Replay incompatible; checkpoint required")
            val mode = target.provider.executionMode(request) // freeze BEFORE submission, including extraBody
            val reducer = ModelEventReducer(target.id, request.requestId)
            journal.submitted(request.requestId, target.id, mode)
            var cancelled = false
            try {
                // Wrap creation as well as collection: synchronous chat() failures obey 2.8 too.
                flow { emitAll(target.provider.chat(request, target.credentials)) }
                    .validated(target.id, request.requestId, timeouts).collect { reducer.accept(it) }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                journal.finished(ModelAttempt(request.requestId, target.id, mode, reducer.assistantMessage(),
                    reducer.terminal, reducer.usage, cancelled))
            }
            val terminal = reducer.terminal!!
            if (terminal is ModelEvent.Failed) {
                val transient = terminal.error.code in setOf(ModelErrorCode.OVERLOADED, ModelErrorCode.SERVER,
                    ModelErrorCode.NETWORK, ModelErrorCode.TIMEOUT)
                val endpoint = target.id to request.model
                // 2.8 removes "this parameter" once, so each rejected parameter has its own attempt.
                val key = if (terminal.error.code == ModelErrorCode.UNSUPPORTED_PARAMETER)
                    "${terminal.error.code}:${terminal.error.param}" else terminal.error.code.name
                val n = if (transient) transientCauses.getOrDefault(endpoint, 0) else causes.getOrDefault(key, 0)
                val decision = recoveryDecision(terminal.error, mode, n)
                if (decision.serverUnknown) return result(RunState.PAUSED, "Server execution state unknown", true)
                if (recover(decision, terminal.error, request)) {
                    if (transient) transientCauses[endpoint] = n + 1 else causes[key] = n + 1
                    continue
                }
                return result(if (checkpointRequired || terminal.error.code == ModelErrorCode.CONTEXT_TOO_LONG) RunState.PAUSED else RunState.FAILED,
                    if (checkpointRequired) "Replay incompatible; checkpoint required" else "Model error: ${terminal.error.code}")
            }
            val finish = terminal as ModelEvent.Finished
            val assistant = reducer.assistantMessage()
            val calls = reducer.calls()
            val disposition = reducer.historyDisposition()
            if (finish.reason == FinishReason.REFUSAL || finish.reason == FinishReason.CONTENT_FILTER) {
                val n = causes.getOrDefault("refusal", 0)
                if (mode != ModelExecutionMode.GENERATION_ONLY) return result(RunState.PAUSED, "Server execution state unknown", true)
                if (n == 0 && recover(RecoveryDecision(RecoveryAction.FALLBACK), null, request)) {
                    causes["refusal"] = 1; continue
                }
                return result(if (checkpointRequired) RunState.PAUSED else RunState.FAILED,
                    if (checkpointRequired) "Replay incompatible; checkpoint required" else "Model refused response")
            }
            if (finish.reason in setOf(FinishReason.STOP, FinishReason.OTHER, FinishReason.TOOL_CALLS) && contentless(assistant)) {
                if (mode != ModelExecutionMode.GENERATION_ONLY) return result(RunState.PAUSED, "Server execution state unknown", true)
                val n = causes.getOrDefault("empty", 0)
                if (n == 0 && recover(RecoveryDecision(RecoveryAction.RETRY), null, request)) {
                    causes["empty"] = 1; continue
                }
                return result(RunState.FAILED, "Empty model response")
            }
            val truncated = finish.reason in setOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)
            if (truncated && disposition == HistoryDisposition.DISCARD) {
                val n = causes.getOrDefault("length", 0)
                if (mode != ModelExecutionMode.GENERATION_ONLY) return result(RunState.PAUSED, "Server execution state unknown", true)
                if (finish.reason == FinishReason.LENGTH && n == 0 &&
                    recover(RecoveryDecision(RecoveryAction.INCREASE_OUTPUT), null, request)) {
                    causes["length"] = 1; continue
                }
                return result(RunState.PAUSED, "Truncated response; checkpoint or user decision required")
            }
            steps++
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
                }
            } catch (e: Exception) {
                executionFailure?.let { e.addSuppressed(it) }
                throw e
            }
            executionFailure?.let {
                // A collaborator's own cancelled Deferred or withTimeout is not cancellation of this run.
                if (it is CancellationException && !currentCoroutineContext().isActive) throw it
                return result(RunState.PAUSED, "Tool step interrupted: ${it.message}")
            }
            if (calls.isNotEmpty() && tools.unresolved(calls)) return result(RunState.PAUSED, "Tool execution or effect unresolved")
            if (duplicate) return result(RunState.PAUSED, "Repeated tool call limit")
            if (truncated) return result(RunState.PAUSED, "Truncated response; checkpoint or user decision required",
                mode != ModelExecutionMode.GENERATION_ONLY)
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
