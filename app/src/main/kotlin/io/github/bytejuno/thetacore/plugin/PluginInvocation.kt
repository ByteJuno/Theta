package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolRisk
import io.github.bytejuno.thetacore.sdk.ToolSpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal data class ToolInvocationRequest(
    val runId: String,
    val sessionId: String,
    val callId: String = UUID.randomUUID().toString(),
    val unattended: Boolean = false,
)
internal enum class PluginInvocationStatus { SUCCEEDED, FAILED, REJECTED, TIMED_OUT, CANCELLED }
internal data class PluginInvocationResult(
    val status: PluginInvocationStatus,
    val result: ToolResult? = null,
    val message: String? = null,
)
internal data class PluginToolProgress(val text: String, val fraction: Float?)

internal interface InvocationClock {
    fun nowNanos(): Long
    /** Monotonic time including suspend on Android, used only during human approval. */
    fun elapsedRealtimeNanos(): Long
    fun armApproval(deadlineNanos: Long, action: () -> Unit): () -> Unit {
        val remaining = (deadlineNanos - elapsedRealtimeNanos()).coerceAtLeast(0)
        return arm((remaining + 999_999) / 1_000_000, action)
    }
    fun arm(delayMillis: Long, action: () -> Unit): () -> Unit
}

/**
 * First S1 direct invocation, NOT an agent scheduler or approval/resource ledger. The caller
 * validates registration/admission before start, and must not hold its operation mutex while
 * awaiting terminal or actualExited. Callback hooks must be trusted host implementations.
 */
internal class PluginInvocation(
    private val budget: PluginExecutionBudget,
    private val pluginId: String,
    private val owner: CoroutineScope,
    private val tool: Tool,
    private val args: JsonObject,
    val request: ToolInvocationRequest,
    private val requestApproval: suspend (String, ToolRisk) -> Boolean = { _, _ -> false },
    private val requestInput: suspend (String, JsonObject) -> JsonObject? = { _, _ -> null },
    private val defaultTimeoutMillis: Long = 60_000,
    private val totalTimeoutMillis: Long = 600_000,
    private val frozenSpec: ToolSpec? = null,
    private val reservation: PluginExecutionBudget.Lease? = null,
    /** Only the ledger-backed host hook can evaluate non-interactive grants. */
    private val unattendedApprovalPolicy: Boolean = false,
    private val clock: InvocationClock = budget,
    /** Last host admission check on the worker, before entering plugin code; not revocation. */
    private val beforeExecution: suspend () -> Boolean = { true },
) : AutoCloseable {
    init { require(defaultTimeoutMillis > 0 && totalTimeoutMillis in 1..600_000) }
    private val completion = CompletableDeferred<PluginInvocationResult>()
    val terminal: Deferred<PluginInvocationResult> = completion
    private val exited = CompletableDeferred<Unit>()
    val actualExited: Deferred<Unit> = exited
    private val mutableSpec = MutableStateFlow<ToolSpec?>(null)
    val spec: StateFlow<ToolSpec?> = mutableSpec.asStateFlow()
    private val mutableProgress = MutableStateFlow<PluginToolProgress?>(null)
    val progress: StateFlow<PluginToolProgress?> = mutableProgress.asStateFlow()
    private var lease: PluginExecutionBudget.Lease? = reservation
    private var disarm: (() -> Unit)? = null
    private var terminalStatus: PluginInvocationStatus? = null
    private data class ApprovalWait(val elapsedStart: Long, val executionStart: Long)
    private var approvalWait: ApprovalWait? = null
    private var approvalRemainingNanos = TimeUnit.MINUTES.toNanos(10)
    private var started = false
    private var startedNanos = 0L
    private var lastProgressNanos = 0L
    private var idleMillis = defaultTimeoutMillis.coerceAtMost(600_000)

    private val returned = CompletableDeferred<PluginInvocationResult>()
    val returnReported: Deferred<PluginInvocationResult> = returned
    @Volatile var returnEvidence: PluginInvocationResult? = null
        private set
    private fun capture(value: PluginInvocationResult) {
        returnEvidence = value
        returned.complete(value)
    }
    @Volatile var bodyEntered: Boolean = false
        private set

    fun start(): PluginInvocation = synchronized(budget.gate) {
        if (started) return this
        if (completion.isCompleted) {
            reservation?.let {
                it.actualExited.invokeOnCompletion { exited.complete(Unit) }
                it.abandon()
            }
            return this
        }
        started = true
        startedNanos = clock.nowNanos()
        lastProgressNanos = startedNanos
        val action: suspend CoroutineScope.() -> Unit = action@ {
            try {
                // JsonObject can wrap a plugin-provided Map. Detach it on the bounded worker
                // before publishing metadata/results for host/Main readers.
                val rawDefinition = frozenSpec ?: tool.spec
                val definition = rawDefinition.copy(
                    inputSchema = Json.parseToJsonElement(rawDefinition.inputSchema.toString()).jsonObject,
                    outputSchema = rawDefinition.outputSchema?.let { Json.parseToJsonElement(it.toString()).jsonObject },
                )
                val proceed = synchronized(budget.gate) {
                    if (expireIfDue()) false else {
                        mutableSpec.value = definition
                        idleMillis = (definition.timeoutMs ?: defaultTimeoutMillis).coerceIn(1, 600_000)
                        armDeadline()
                        !completion.isCompleted
                    }
                }
                if (!proceed) return@action
                val admitted = beforeExecution()
                if (!admitted) {
                    synchronized(budget.gate) {
                        if (!expireIfDue()) finish(PluginInvocationResult(PluginInvocationStatus.REJECTED, message = "Execution admission closed"))
                    }
                    return@action
                }
                run {
                    val env = ToolEnv(request.runId, request.sessionId, request.callId, request.unattended,
                        onProgress = { text, fraction -> reportProgress(text, fraction) },
                        onApproval = { summary, risk ->
                            if (completion.isCompleted || (request.unattended && !unattendedApprovalPolicy)) false
                            else requestApproval(summary, risk) && synchronized(budget.gate) { !expireIfDue() }
                        },
                        onInput = { prompt, schema ->
                            if (completion.isCompleted) null
                            else requestInput(prompt, schema).takeUnless { completion.isCompleted }
                        })
                    val result = coroutineScope {
                        val executionContext = coroutineContext
                        val enter = synchronized(budget.gate) {
                            // Linearize body entry with timeout/cancel, AFTER the suspending
                            // host admission callback. No database/definition lock is held here.
                            if (expireIfDue()) false
                            else if (!executionContext.isActive || !owner.isActive || lease?.cancelRequested == true) {
                                finish(PluginInvocationResult(PluginInvocationStatus.CANCELLED, message = "Invocation cancelled"))
                                false
                            } else {
                                bodyEntered = true
                                true
                            }
                        }
                        if (!enter) return@coroutineScope null
                        val rawResult = tool.call(args, env)
                        rawResult.copy(structured = rawResult.structured?.let {
                            Json.parseToJsonElement(it.toString())
                        }).withContent(rawResult.content.map { part -> when (part) {
                            is io.github.bytejuno.thetacore.sdk.ToolContent.Text -> part.copy()
                            is io.github.bytejuno.thetacore.sdk.ToolContent.Media -> {
                                require(part.mimeType.length in 1..128 && part.mimeType.none { it.isISOControl() })
                                part.copy(bytes = part.bytes.copyOf())
                            }
                        } }).also {
                            // Capture before coroutineScope checks cancellation on return.
                            capture(PluginInvocationResult(
                                if (it.isError) PluginInvocationStatus.FAILED else PluginInvocationStatus.SUCCEEDED, it))
                        }
                    } ?: return@action
                    synchronized(budget.gate) {
                        // Deadline checks also arbitrate a return racing a delayed timer thread.
                        if (!expireIfDue()) finish(PluginInvocationResult(
                            if (result.isError) PluginInvocationStatus.FAILED else PluginInvocationStatus.SUCCEEDED,
                            result = result,
                        ))
                    }
                }
            } catch (error: Throwable) {
                if (tool is io.github.bytejuno.thetacore.mcp.McpTool &&
                    error is io.github.bytejuno.thetacore.agent.mcp.McpUnknownToolException) {
                    val rejected = PluginInvocationResult(PluginInvocationStatus.REJECTED,
                        result = io.github.bytejuno.thetacore.sdk.ToolResult("MCP tool is unavailable", isError = true,
                            effect = io.github.bytejuno.thetacore.sdk.ToolEffect.NONE))
                    if (returnEvidence == null) capture(rejected)
                    synchronized(budget.gate) { if (!expireIfDue()) finish(rejected) }
                    return@action
                }
                if (returnEvidence == null) capture(PluginInvocationResult(PluginInvocationStatus.FAILED,
                    message = "Tool failed: ${pluginFailureIdentity(error).take(256)}"))
                synchronized(budget.gate) {
                    if (!expireIfDue()) finish(PluginInvocationResult(PluginInvocationStatus.FAILED,
                        message = "Tool failed: ${pluginFailureIdentity(error).take(256)}"))
                }
            }
        }
        lease = if (reservation != null) {
            reservation.actualExited.invokeOnCompletion { exited.complete(Unit) }
            if (budget.commit(reservation, action)) reservation else null
        } else budget.submit(pluginId, owner,
            onCancel = { finish(PluginInvocationResult(PluginInvocationStatus.CANCELLED, message = "Invocation cancelled")) },
            onExit = { exited.complete(Unit); Unit }, action = action)
        if (lease == null) {
            finish(PluginInvocationResult(PluginInvocationStatus.REJECTED, message = "Tool execution capacity unavailable"))
            if (reservation == null || reservation.actualExited.isCompleted) exited.complete(Unit)
        } else if (!completion.isCompleted) armDeadline()
        this
    }

    private fun reportProgress(text: String, fraction: Float?) = synchronized(budget.gate) {
        if (completion.isCompleted || expireIfDue()) return
        if (approvalWait == null) lastProgressNanos = clock.nowNanos()
        mutableProgress.value = PluginToolProgress(text.take(1024), fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f))
        armDeadline()
    }

    fun setHumanApprovalWaiting(waiting: Boolean): Boolean = synchronized(budget.gate) {
        val wait = approvalWait
        val now = if (wait == null) clock.nowNanos() else clock.elapsedRealtimeNanos()
        if (expireIfDue(now)) return false
        if (waiting == (wait != null)) return true
        if (wait == null) {
            approvalWait = ApprovalWait(clock.elapsedRealtimeNanos(), now)
        } else {
            approvalRemainingNanos -= now - wait.elapsedStart
            val paused = clock.nowNanos() - wait.executionStart
            startedNanos += paused
            lastProgressNanos += paused
            approvalWait = null
        }
        armDeadline()
        !completion.isCompleted
    }

    private fun deadlineClockNanos() = if (approvalWait == null) clock.nowNanos() else clock.elapsedRealtimeNanos()

    private fun remainingNanos(now: Long): Long {
        approvalWait?.let { return approvalRemainingNanos - (now - it.elapsedStart) }
        return minOf(TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis) - (now - startedNanos),
            TimeUnit.MILLISECONDS.toNanos(idleMillis) - (now - lastProgressNanos))
    }

    private fun expireIfDue(now: Long = deadlineClockNanos()): Boolean {
        if (completion.isCompleted) return true
        if (remainingNanos(now) > 0) return false
        finish(PluginInvocationResult(PluginInvocationStatus.TIMED_OUT, message = "Tool execution timed out; actual exit is tracked separately"))
        lease?.cancel()
        return true
    }

    private fun armDeadline() {
        disarm?.invoke()
        disarm = null
        if (completion.isCompleted || expireIfDue()) return
        val checkDeadline = { synchronized(budget.gate) { if (!expireIfDue()) armDeadline() } }
        val wait = approvalWait
        disarm = if (wait != null) {
            clock.armApproval(wait.elapsedStart + approvalRemainingNanos, checkDeadline)
        } else {
            val millis = (remainingNanos(clock.nowNanos()).coerceAtLeast(1) + 999_999) / 1_000_000
            clock.arm(millis, checkDeadline)
        }
    }

    private fun finish(value: PluginInvocationResult) {
        if (terminalStatus != null) return
        terminalStatus = value.status
        approvalWait = null
        disarm?.invoke()
        disarm = null
        completion.complete(value)
    }

    /** Sample the live deadline before the ledger commits a run-wide or individual cancellation. */
    fun timedOutAtCancellation(): Boolean = synchronized(budget.gate) {
        if (started) expireIfDue()
        terminalStatus == PluginInvocationStatus.TIMED_OUT
    }

    /** Unique logical terminal; cancellation delivery and actual exit are separate. */
    fun cancel(): PluginInvocationStatus = synchronized(budget.gate) {
        if (!started || !expireIfDue()) finish(PluginInvocationResult(PluginInvocationStatus.CANCELLED, message = "Invocation cancelled"))
        lease?.cancel()
        if (lease == null && (reservation == null || reservation.actualExited.isCompleted)) exited.complete(Unit)
        requireNotNull(terminalStatus)
    }
    suspend fun cancelAndAwaitDelivery() {
        cancel()
        lease?.cancellationDelivered?.await()
    }
    override fun close() { cancel() }
}
