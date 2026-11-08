package io.github.bytejuno.thetacore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.plugin.PluginEntryRuntime
import io.github.bytejuno.thetacore.plugin.PluginInvocation
import io.github.bytejuno.thetacore.plugin.PluginInvocationResult
import io.github.bytejuno.thetacore.plugin.PluginManager
import io.github.bytejuno.thetacore.plugin.PluginUpdate
import io.github.bytejuno.thetacore.sdk.Tool
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Identity-only UI selection: never invokes Tool.id, spec, equals or hashCode. */
internal class HostToolSelection(val pluginId: String, val index: Int, val tool: Tool)

@Composable
internal fun HostEntryState(entry: PluginEntryRuntime, index: Int) {
    val state by entry.state.collectAsState()
    Text("Entry ${index + 1}: ${state.displayName ?: "Waiting for worker metadata"}")
    Text(state.status.name)
    state.message?.let { Text(it) }
}

@Composable
internal fun HostUpdateObservation(update: PluginUpdate) {
    Text("Update ${update.from} → ${update.to} · ${update.phase.name}")
    Text(if (update.dataRestorable) "Rollback includes plugin private data, preferences and secrets."
        else "Code-only rollback: NO data, preferences or secrets rollback.")
    val start = update.startedAt
    Text(if (start == null) "24-hour observation starts only after this version first starts successfully."
        else "24-hour observation began at ${DateFormat.getDateTimeInstance().format(Date(start))}. " +
            "Restarting or reopening this screen does not reset it.")
    Text("Rollback availability is decided by the manager, not a UI countdown.")
}

/** Host diagnostics only; no chat backend, approval ledger or fabricated agent session. */
@Composable
internal fun HostToolDialog(manager: PluginManager, selection: HostToolSelection, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifetime = remember(selection) { HostInvocationLifetime() }
    // This effect is installed by the first composition, before any button can admit work.
    // Disposal reads the current holder rather than the previous composition's null call.
    DisposableEffect(lifetime) { onDispose { lifetime.dispose() } }
    var argsText by remember(selection) { mutableStateOf("{}") }
    var error by remember(selection) { mutableStateOf<String?>(null) }
    var submitting by remember(selection) { mutableStateOf(false) }
    var invocation by remember(selection) { mutableStateOf<PluginInvocation?>(null) }
    var terminal by remember(selection) { mutableStateOf<PluginInvocationResult?>(null) }
    var actuallyExited by remember(selection) { mutableStateOf(false) }
    val call = invocation
    // Deferred terminal and exit signals are observed independently. Awaiting either must
    // never run inside PluginManager's serialized operation/mutation gate.
    LaunchedEffect(call) { if (call != null) terminal = call.terminal.await() }
    LaunchedEffect(call) { if (call != null) { call.actualExited.await(); actuallyExited = true } }
    val registered = manager.plugins.any { plugin ->
        plugin.manifest.id == selection.pluginId && !plugin.busy && !plugin.pendingDelete &&
            plugin.tools.any { it === selection.tool }
    }
    fun dismiss() {
        if (submitting) return
        call?.cancel()
        onClose()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Tool ${selection.index + 1} diagnostics") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(selection.pluginId)
                Text("Direct full-trust plugin call, not an agent run. Approval requests are denied; input requests return unavailable. Cancellation does not undo effects.")
                if (!registered) Text("Tool registration is no longer available for a new call.")
                OutlinedTextField(
                    value = argsText,
                    onValueChange = {
                        if (it.length <= MAX_TOOL_JSON_CHARS) { argsText = it; error = null }
                        else error = "Arguments exceed $MAX_TOOL_JSON_CHARS characters; edit or shorten them."
                    },
                    enabled = call == null && !submitting,
                    label = { Text("Arguments (JSON object)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                Text("Limit: $MAX_TOOL_JSON_CHARS characters, $MAX_TOOL_JSON_DEPTH nesting levels. S1 does not validate against the tool schema.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (submitting) Text("Validating arguments / requesting admission…")
                if (call == null) Text("Tool name and spec are read only by the admitted worker after invocation.")
                else {
                    HostInvocationMetadata(call)
                    Text("Run: ${call.request.runId}\nSession: ${call.request.sessionId}\nCall: ${call.request.callId}")
                    Text("Logical terminal: ${terminal?.status?.name ?: "RUNNING"}")
                    terminal?.message?.let { Text(it.take(4096)) }
                    terminal?.result?.let { result ->
                        Text("Error: ${result.isError} · Effect: ${result.effect?.name ?: "unspecified"}")
                        Text(result.text.take(4096))
                        if (result.text.length > 4096) Text("Result text display truncated to 4096 characters.")
                    }
                    HostStructuredResult(terminal)
                    Text(if (actuallyExited) "Actual execution exited; worker capacity has been released."
                        else "Actual execution has NOT exited. A terminal result, timeout or cancellation is not proof of exit; capacity may remain occupied.")
                    if (!actuallyExited) Text("Closing requests cancellation but does not release an occupied worker slot. The manager continues tracking actual exit. Uncooperative full-trust code may require a process restart.")
                }
            }
        },
        confirmButton = {
            if (call == null) TextButton(enabled = registered && !submitting && error == null, onClick = {
                submitting = true
                error = null
                val submittedText = argsText
                scope.launch {
                    var admitted: PluginInvocation? = null
                    var published = false
                    try {
                        val args = withContext(Dispatchers.Default) { parseHostToolArguments(submittedText) }
                        // Admission may survive UI cancellation. Always obtain its handle so
                        // finally can signal cancel instead of losing a live invocation.
                        admitted = withContext(NonCancellable) { manager.invokeTool(selection.pluginId, selection.tool, args) }
                        ensureActive()
                        val accepted = checkNotNull(admitted)
                        lifetime.adopt { accepted.cancel() }
                        invocation = accepted
                        published = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message?.take(1024) ?: "Tool invocation failed" }
                    finally { if (!published) admitted?.cancel(); submitting = false }
                }
            }) { Text("Invoke once") }
            else if (!actuallyExited) TextButton(enabled = terminal == null, onClick = { call.cancel() }) {
                Text(if (terminal == null) "Cancel invocation" else "Waiting for actual exit")
            }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = ::dismiss) { Text("Close") } },
    )
}

@Composable
private fun HostInvocationMetadata(call: PluginInvocation) {
    val spec by call.spec.collectAsState()
    val progress by call.progress.collectAsState()
    val definition = spec
    var schemas by remember(call) { mutableStateOf<String?>(null) }
    LaunchedEffect(definition) {
        schemas = withContext(Dispatchers.Default) {
            definition?.let {
                "Input schema (up to 4096 characters):\n${it.inputSchema.toString().take(4096)}\n" +
                    "Output schema (up to 4096 characters):\n${it.outputSchema?.toString()?.take(4096) ?: "unspecified"}"
            }
        }
    }
    if (definition == null) Text("Spec: waiting for bounded worker")
    else {
        Text("Tool: ${definition.name.take(256)}")
        Text(definition.description.take(2048))
        Text("Declared risk: ${definition.risk.name} · Idempotent: ${definition.idempotent} · Parallel-safe: ${definition.parallelSafe}")
        Text("Declared timeout: ${definition.timeoutMs?.toString() ?: "host default"} ms\nExclusive resource: ${definition.exclusiveResource?.take(256) ?: "none"}")
        Text("Declarations are metadata, not a permission sandbox or approval.")
    }
    schemas?.let { Text(it) }
    progress?.let { value ->
        Text("Progress: ${value.text}")
        value.fraction?.let { Text("${(it * 100).toInt()}%") }
    }
}

@Composable
private fun HostStructuredResult(terminal: PluginInvocationResult?) {
    var rendered by remember(terminal) { mutableStateOf<String?>(null) }
    LaunchedEffect(terminal) {
        rendered = withContext(Dispatchers.Default) {
            terminal?.result?.structured?.toString()?.let {
                it.take(4096) + if (it.length > 4096) "\nStructured result display truncated." else ""
            }
        }
    }
    rendered?.let { Text(it) }
}

private const val MAX_TOOL_JSON_CHARS = 16_384
private const val MAX_TOOL_JSON_DEPTH = 32

/** Bound both size and nesting before the recursive JSON parser runs off Main. */
internal fun parseHostToolArguments(text: String): JsonObject {
    require(text.length <= MAX_TOOL_JSON_CHARS) { "Arguments exceed $MAX_TOOL_JSON_CHARS characters" }
    var depth = 0
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false
            else when (char) { '\\' -> escaped = true; '"' -> quoted = false }
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= MAX_TOOL_JSON_DEPTH) { "JSON nesting exceeds $MAX_TOOL_JSON_DEPTH levels" } }
            '}', ']' -> { depth--; require(depth >= 0) { "Unbalanced JSON" } }
        }
    }
    require(!quoted && depth == 0) { "Incomplete JSON" }
    return Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("Arguments must be a JSON object")
}
