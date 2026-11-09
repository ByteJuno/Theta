package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.Tool
import io.github.bytejuno.thetacore.sdk.ToolEnv
import io.github.bytejuno.thetacore.sdk.ToolResult
import io.github.bytejuno.thetacore.sdk.ToolRisk
import io.github.bytejuno.thetacore.sdk.ToolSpec
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
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
    private var timer: ScheduledFuture<*>? = null
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
        startedNanos = System.nanoTime()
        lastProgressNanos = startedNanos
        val action: suspend CoroutineScope.() -> Unit = {
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
                if (proceed) {
                    val env = ToolEnv(request.runId, request.sessionId, request.callId, request.unattended,
                        onProgress = { text, fraction -> reportProgress(text, fraction) },
                        onApproval = { summary, risk ->
                            if (completion.isCompleted) false
                            else requestApproval(summary, risk) && !completion.isCompleted
                        },
                        onInput = { prompt, schema ->
                            if (completion.isCompleted) null
                            else requestInput(prompt, schema).takeUnless { completion.isCompleted }
                        })
                    bodyEntered = true
                    val result = coroutineScope {
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
                    }
                    synchronized(budget.gate) {
                        // Deadline checks also arbitrate a return racing a delayed timer thread.
                        if (!expireIfDue()) finish(PluginInvocationResult(
                            if (result.isError) PluginInvocationStatus.FAILED else PluginInvocationStatus.SUCCEEDED,
                            result = result,
                        ))
                    }
                }
            } catch (error: Throwable) {
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
        lastProgressNanos = System.nanoTime()
        mutableProgress.value = PluginToolProgress(text.take(1024), fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f))
        armDeadline()
    }

    private fun remainingNanos(): Long {
        val now = System.nanoTime()
        return minOf(TimeUnit.MILLISECONDS.toNanos(totalTimeoutMillis) - (now - startedNanos),
            TimeUnit.MILLISECONDS.toNanos(idleMillis) - (now - lastProgressNanos))
    }

    private fun expireIfDue(): Boolean {
        if (completion.isCompleted) return true
        if (remainingNanos() > 0) return false
        finish(PluginInvocationResult(PluginInvocationStatus.TIMED_OUT, message = "Tool execution timed out; actual exit is tracked separately"))
        lease?.cancel()
        return true
    }

    private fun armDeadline() {
        timer?.cancel(false)
        timer = null
        if (completion.isCompleted || expireIfDue()) return
        // Round up; a sub-millisecond remainder must not become a tight timer loop.
        val millis = (remainingNanos().coerceAtLeast(1) + 999_999) / 1_000_000
        timer = budget.schedule(millis) {
            synchronized(budget.gate) { if (!expireIfDue()) armDeadline() }
        }
    }

    private fun finish(value: PluginInvocationResult) {
        if (!completion.complete(value)) return
        timer?.cancel(false)
        timer = null
    }

    /** Unique logical terminal; cancellation delivery and actual exit are separate. */
    fun cancel() = synchronized(budget.gate) {
        finish(PluginInvocationResult(PluginInvocationStatus.CANCELLED, message = "Invocation cancelled"))
        lease?.cancel()
        if (lease == null && (reservation == null || reservation.actualExited.isCompleted)) exited.complete(Unit)
    }
    override fun close() = cancel()
}
