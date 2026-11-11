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
    val strings = uiStrings()
    val state by entry.state.collectAsState()
    Text(strings.get(R.string.entry_metadata, index + 1, state.displayName ?: strings.get(R.string.worker_metadata_waiting)))
    Text(strings.label(state.status))
    state.message?.let { message ->
        LaunchedEffect(message) { logUiDiagnostic(message) }
        Text(strings.get(R.string.entry_diagnostic_notice))
    }
}

@Composable
internal fun HostUpdateObservation(update: PluginUpdate) {
    val strings = uiStrings()
    Text(strings.get(R.string.update_metadata, update.from, update.to, strings.label(update.phase)))
    Text(if (update.dataRestorable) strings.get(R.string.rollback_includes_data)
        else strings.get(R.string.rollback_excludes_data))
    val start = update.startedAt
    Text(if (start == null) strings.get(R.string.observation_pending)
        else strings.get(R.string.observation_started, strings.dateTime(start)) +
            strings.get(R.string.observation_not_reset))
    Text(strings.get(R.string.rollback_manager_decision))
}

/** Host diagnostics only; no chat backend, approval ledger or fabricated agent session. */
@Composable
internal fun HostToolDialog(manager: PluginManager, selection: HostToolSelection, onClose: () -> Unit) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    val lifetime = remember(selection) { HostInvocationLifetime() }
    // This effect is installed by the first composition, before any button can admit work.
    // Disposal reads the current holder rather than the previous composition's null call.
    DisposableEffect(lifetime) { onDispose { lifetime.dispose() } }
    var argsText by remember(selection) { mutableStateOf("{}") }
    var error by remember(selection) { mutableStateOf<UiText?>(null) }
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
        title = { Text(strings.get(R.string.tool_diagnostics_title, selection.index + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(selection.pluginId)
                Text(strings.get(R.string.tool_diagnostics_notice))
                if (!registered) Text(strings.get(R.string.tool_unregistered))
                OutlinedTextField(
                    value = argsText,
                    onValueChange = {
                        if (it.length <= MAX_TOOL_JSON_CHARS) { argsText = it; error = null }
                        else error = uiText(R.string.arguments_too_long, MAX_TOOL_JSON_CHARS)
                    },
                    enabled = call == null && !submitting,
                    label = { Text(strings.get(R.string.arguments_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                Text(strings.get(R.string.arguments_limits, MAX_TOOL_JSON_CHARS, MAX_TOOL_JSON_DEPTH))
                error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
                if (submitting) Text(strings.get(R.string.tool_admitting))
                if (call == null) Text(strings.get(R.string.tool_metadata_notice))
                else {
                    HostInvocationMetadata(call)
                    Text(strings.get(R.string.invocation_identifiers, call.request.runId, call.request.sessionId, call.request.callId))
                    Text(strings.get(R.string.invocation_terminal, terminal?.status?.let(strings::label) ?: strings.get(R.string.running)))
                    terminal?.message?.let { message ->
                        LaunchedEffect(message) { logUiDiagnostic(message) }
                        Text(strings.get(R.string.invocation_diagnostic_notice))
                    }
                    terminal?.result?.let { result ->
                        Text(strings.get(R.string.invocation_effect, strings.label(result.isError), result.effect?.let(strings::label) ?: strings.get(R.string.unspecified)))
                        Text(result.text.take(4096))
                        if (result.text.length > 4096) Text(strings.get(R.string.result_truncated))
                    }
                    HostStructuredResult(terminal)
                    Text(if (actuallyExited) strings.get(R.string.execution_exited)
                        else strings.get(R.string.execution_not_exited))
                    if (!actuallyExited) Text(strings.get(R.string.close_invocation_notice))
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
                    catch (failure: Exception) { error = if (failure is IllegalArgumentException) uiText(R.string.invalid_arguments) else uiFailure(failure) }
                    finally { if (!published) admitted?.cancel(); submitting = false }
                }
            }) { Text(strings.get(R.string.invoke_once)) }
            else if (!actuallyExited) TextButton(enabled = terminal == null, onClick = { call.cancel() }) {
                Text(if (terminal == null) strings.get(R.string.cancel_invocation) else strings.get(R.string.awaiting_exit))
            }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = ::dismiss) { Text(strings.get(R.string.close)) } },
    )
}

@Composable
private fun HostInvocationMetadata(call: PluginInvocation) {
    val strings = uiStrings()
    val spec by call.spec.collectAsState()
    val progress by call.progress.collectAsState()
    val definition = spec
    var schemas by remember(call) { mutableStateOf<String?>(null) }
    LaunchedEffect(definition, strings) {
        schemas = withContext(Dispatchers.Default) {
            definition?.let {
                strings.get(R.string.input_schema, it.inputSchema.toString().take(4096)) +
                    strings.get(R.string.output_schema, it.outputSchema?.toString()?.take(4096) ?: strings.get(R.string.unspecified))
            }
        }
    }
    if (definition == null) Text(strings.get(R.string.spec_waiting))
    else {
        Text(strings.get(R.string.tool_name, definition.name.take(256)))
        Text(definition.description.take(2048))
        Text(strings.get(R.string.tool_declarations, strings.label(definition.risk), strings.label(definition.idempotent), strings.label(definition.parallelSafe)))
        Text(strings.get(R.string.tool_timeout_resource, definition.timeoutMs?.toString() ?: strings.get(R.string.host_default), definition.exclusiveResource?.take(256) ?: strings.get(R.string.none)))
        Text(strings.get(R.string.declarations_notice))
    }
    schemas?.let { Text(it) }
    progress?.let { value ->
        Text(strings.get(R.string.tool_progress, value.text))
        value.fraction?.let { Text(strings.get(R.string.progress_percent, (it * 100).toInt())) }
    }
}

@Composable
private fun HostStructuredResult(terminal: PluginInvocationResult?) {
    val strings = uiStrings()
    var rendered by remember(terminal) { mutableStateOf<String?>(null) }
    LaunchedEffect(terminal, strings) {
        rendered = withContext(Dispatchers.Default) {
            terminal?.result?.structured?.toString()?.let {
                it.take(4096) + if (it.length > 4096) strings.get(R.string.structured_truncated) else ""
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
