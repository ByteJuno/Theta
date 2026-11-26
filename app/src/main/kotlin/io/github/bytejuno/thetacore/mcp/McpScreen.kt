package io.github.bytejuno.thetacore.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.McpPreset
import kotlinx.coroutines.*
import java.net.URI

@Composable
internal fun McpScreen(host: ThetaApplication, close: () -> Unit) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    val connections by host.mcp.connections.collectAsState()
    val loadFailed by host.mcp.loadFailed.collectAsState()
    val replacementAllowed by host.mcp.replacementAllowed.collectAsState()
    var presets by remember { mutableStateOf<List<McpPreset>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<McpConnectionView?>(null) }
    var preset by remember { mutableStateOf<McpPreset?>(null) }
    var replacing by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        busy = true; error = false
        scope.launch {
            try { block() } catch (c: CancellationException) { throw c }
            catch (_: Exception) { error = true } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { host.hostTools(); host.mcp.reload(); presets = host.plugins.activeMcpPresets() }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { error = true }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(strings.get(R.string.mcp_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.get(R.string.mcp_notice))
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error || loadFailed) {
                    Text(strings.get(R.string.mcp_error), color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !busy, onClick = { action {
                        host.mcp.reload(); presets = host.plugins.activeMcpPresets()
                    } }) { Text(strings.get(R.string.mcp_retry)) }
                    if (replacementAllowed) TextButton(enabled = !busy, onClick = {
                        selected = null; preset = null; replacing = true; editing = true
                    }) { Text(strings.get(R.string.mcp_replace)) }
                }
                if (!loading && connections.isEmpty()) Text(strings.get(R.string.mcp_empty))
                connections.forEach { c ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(URI(c.url).host, style = MaterialTheme.typography.labelLarge)
                        Text(c.url, style = MaterialTheme.typography.bodySmall)
                        Text(strings.get(c.stateText(), c.tools))
                        FlowRow {
                            TextButton(enabled = !busy, onClick = { action { host.mcp.enable(c.id, !c.enabled) } }) {
                                Text(strings.get(if (c.enabled) R.string.mcp_disable else R.string.mcp_enable))
                            }
                            TextButton(enabled = !busy, onClick = { selected = c; preset = null; editing = true }) {
                                Text(strings.get(R.string.mcp_edit))
                            }
                            TextButton(enabled = !busy && c.enabled, onClick = { action { host.mcp.refresh(c.id) } }) {
                                Text(strings.get(R.string.mcp_refresh))
                            }
                            TextButton(enabled = !busy, onClick = { action { host.mcp.delete(c.id) } }) {
                                Text(strings.get(R.string.mcp_delete))
                            }
                        }
                    }
                    HorizontalDivider()
                }
                OutlinedButton(enabled = !busy && !loading && !loadFailed, onClick = {
                    selected = null; preset = null; editing = true
                }) { Text(strings.get(R.string.mcp_add)) }
                if (presets.isNotEmpty()) Text(strings.get(R.string.mcp_presets), style = MaterialTheme.typography.titleSmall)
                presets.forEach { p ->
                    TextButton(enabled = !busy && !loadFailed, onClick = { selected = null; preset = p; editing = true }) {
                        Text(strings.get(R.string.mcp_import, p.name, URI(p.url).host))
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = close) { Text(strings.get(R.string.mcp_close)) } })
    if (editing) McpEditor(host, selected, preset, replacing) { editing = false; replacing = false }
}

@Composable
private fun McpEditor(host: ThetaApplication, selected: McpConnectionView?, preset: McpPreset?, replacing: Boolean, close: () -> Unit) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(selected?.name ?: preset?.name.orEmpty()) }
    var url by remember { mutableStateOf(selected?.url ?: preset?.url.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var noAuth by remember { mutableStateOf(selected?.let { !it.hasToken } ?: (preset?.isNoAuth() == true || preset == null)) }
    var apiKey by remember { mutableStateOf(selected?.isApiKey() == true || preset?.isApiKey() == true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val destination = runCatching { URI(url).host }.getOrNull().orEmpty()
    val canReuse = selected?.hasToken == true && url == selected.url &&
        mcpHeader(apiKey) == selected.header
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(strings.get(R.string.mcp_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (replacing) {
                Text(strings.get(R.string.mcp_replace_notice), color = MaterialTheme.colorScheme.error)
                Row { Checkbox(confirmed, { confirmed = it }, enabled = !busy); Text(strings.get(R.string.mcp_replace_confirm)) }
            }
            if (preset?.requiresOAuth() == true) Text(strings.get(R.string.mcp_oauth_notice), color = MaterialTheme.colorScheme.error)
            Text(strings.get(R.string.mcp_destination, destination), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, enabled = !busy, label = { Text(strings.get(R.string.mcp_name)) })
            OutlinedTextField(url, { url = it }, enabled = !busy, label = { Text(strings.get(R.string.mcp_url)) })
            Row { Checkbox(noAuth, { noAuth = it; token = "" }, enabled = !busy); Text(strings.get(R.string.mcp_no_auth)) }
            if (!noAuth) {
                Row { Checkbox(apiKey, { apiKey = it }, enabled = !busy); Text(strings.get(R.string.mcp_api_key)) }
                OutlinedTextField(token, { token = it }, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(strings.get(R.string.mcp_token)) })
                if (canReuse) Text(strings.get(R.string.mcp_reuse))
                else Text(strings.get(R.string.mcp_new_token))
            }
            Text(strings.get(R.string.mcp_save_notice))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error) Text(strings.get(R.string.mcp_error), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && (!replacing || confirmed) && name.isNotBlank() && destination.isNotBlank() && (noAuth || token.isNotBlank() || canReuse),
            onClick = {
                busy = true; error = false
                scope.launch {
                    try {
                        if (replacing) host.mcp.replaceUnreadable(name, url, if (noAuth) "" else token, mcpHeader(apiKey))
                        else host.mcp.save(selected?.id, name, url, selected?.enabled ?: false,
                            if (noAuth) "" else token.takeIf { it.isNotEmpty() }, mcpHeader(apiKey))
                        close()
                    } catch (c: CancellationException) { throw c }
                    catch (_: Exception) { error = true } finally { busy = false }
                }
            }) { Text(strings.get(R.string.mcp_save)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = close) { Text(strings.get(R.string.mcp_close)) } })
}
