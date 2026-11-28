package io.github.bytejuno.thetacore.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.*

@Composable
internal fun WebSearchSettingsEntry() {
    var open by remember { mutableStateOf(false) }
    val strings = uiStrings()
    TextButton(onClick = { open = true }) { Text(strings.get(R.string.web_title)) }
    if (open) WebSearchSettingsScreen { open = false }
}

@Composable
private fun WebSearchSettingsScreen(close: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { WebRuntime.settings(context) }
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf(SearchBackend.SEARXNG) }
    var endpoint by remember { mutableStateOf("") }
    // Never persist an API key in saved instance state or display an existing key in cleartext.
    var key by remember { mutableStateOf("") }
    var original by remember { mutableStateOf<WebSearchSettings?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        val loaded = withContext(Dispatchers.IO) { store.read() }
        original = loaded.value; unreadable = loaded.unreadable
        loaded.value?.let { backend = it.backend; endpoint = if (it.backend == SearchBackend.SEARXNG) it.endpoint else "" }
        loading = false
    }
    fun save(clear: Boolean = false, replacement: Boolean = false) {
        busy = true; notice = null
        scope.launch {
            try {
                val next = if (clear) null else WebSearchSettings(backend, when (backend) {
                    SearchBackend.SEARXNG -> endpoint.trim()
                    SearchBackend.BRAVE -> WebSearchSettings.BRAVE_ENDPOINT
                    SearchBackend.TAVILY -> WebSearchSettings.TAVILY_ENDPOINT
                }, if (backend == SearchBackend.SEARXNG) "" else key.ifBlank {
                    original?.takeIf { it.backend == backend }?.key.orEmpty()
                })
                withContext(Dispatchers.IO) { store.save(next, replacement) }
                original = next; unreadable = false; key = ""
                if (clear) endpoint = ""
                notice = if (clear) R.string.web_cleared else R.string.web_saved
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = R.string.web_save_error }
            finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(enabled = busy) { }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !busy, onClick = close) { Text(strings.get(R.string.web_back)) }
                Text(strings.get(R.string.web_title), style = MaterialTheme.typography.headlineSmall)
                Text(strings.get(R.string.web_notice))
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (unreadable) Text(strings.get(R.string.web_unreadable), color = MaterialTheme.colorScheme.error)
                SearchBackend.entries.forEach { option ->
                    TextButton(enabled = !loading && !busy, onClick = { backend = option; key = "" }) {
                        RadioButton(selected = backend == option, onClick = null)
                        Text(strings.get(when (option) {
                            SearchBackend.SEARXNG -> R.string.web_searxng
                            SearchBackend.BRAVE -> R.string.web_brave
                            SearchBackend.TAVILY -> R.string.web_tavily
                        }))
                    }
                }
                if (backend == SearchBackend.SEARXNG) OutlinedTextField(value = endpoint,
                    onValueChange = { if (it.length <= 8192) endpoint = it }, enabled = !busy && !loading,
                    modifier = Modifier.fillMaxWidth(), label = { Text(strings.get(R.string.web_endpoint)) },
                    supportingText = { Text(strings.get(R.string.web_endpoint_hint)) }, singleLine = false)
                else OutlinedTextField(value = key, onValueChange = { if (it.length <= 4096) key = it },
                    enabled = !busy && !loading, modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.get(R.string.web_key)) }, visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text(strings.get(if (original?.backend == backend && !unreadable)
                        R.string.web_keep_key else R.string.web_key_hint)) }, singleLine = true)
                notice?.let { Text(strings.get(it), color = if (it == R.string.web_save_error)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                Button(enabled = !busy && !loading, onClick = { if (unreadable) confirmReplace = true else save() }) {
                    Text(strings.get(R.string.web_save))
                }
                OutlinedButton(enabled = !busy && !loading && (original != null || unreadable),
                    onClick = { confirmClear = true }) { Text(strings.get(R.string.web_clear)) }
            }
        }
        if (confirmReplace || confirmClear) AlertDialog(onDismissRequest = { confirmReplace = false; confirmClear = false },
            title = { Text(strings.get(if (confirmClear) R.string.web_clear else R.string.web_replace)) },
            text = { Text(strings.get(R.string.web_replace_notice)) },
            confirmButton = { TextButton(onClick = {
                val clear = confirmClear; confirmClear = false; confirmReplace = false; save(clear, replacement = true)
            }) { Text(strings.get(R.string.web_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false; confirmClear = false }) {
                Text(strings.get(R.string.web_cancel))
            } })
    }
}
