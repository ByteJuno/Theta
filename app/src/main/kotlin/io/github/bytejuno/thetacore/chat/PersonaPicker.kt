package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.plugin.PluginPromptData
import kotlinx.coroutines.*

@Composable
internal fun PersonaPicker(host: ThetaApplication, enabled: Boolean) {
    val strings = uiStrings()
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(PluginPromptData()) }
    var selection by remember { mutableStateOf(PersonaSelection()) }
    fun load() {
        busy = true; error = false
        scope.launch {
            try {
                data = host.plugins.activePromptData()
                withContext(Dispatchers.IO) { host.personaSettings.resolve(data) }
                selection = withContext(Dispatchers.IO) { host.personaSettings.current() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    fun choose(id: String?) {
        busy = true; error = false
        scope.launch {
            try {
                // Revalidate immediately before saving; stale UI cannot select a disabled persona.
                val active = host.plugins.activePromptData()
                check(id == null || active.personas.any { it.id == id })
                withContext(Dispatchers.IO) { host.personaSettings.select(id) }
                selection = withContext(Dispatchers.IO) { host.personaSettings.current() }
                data = active; open = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(host) { load() }
    TextButton(enabled = enabled && !busy, onClick = { open = true; load() }) {
        Text(strings.get(R.string.chat_persona))
    }
    if (busy && !open) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!busy && !error) Text(data.personas.firstOrNull { it.id == selection.id }?.name
        ?: strings.get(R.string.chat_persona_none), style = MaterialTheme.typography.bodyMedium)
    Text(strings.get(R.string.chat_persona_next_run), style = MaterialTheme.typography.bodySmall)
    if (selection.unavailable) Text(strings.get(R.string.chat_persona_unavailable))
    if (selection.recovered) Text(strings.get(R.string.chat_persona_recovered))
    if (error && !open) Text(strings.get(R.string.chat_persona_failed), color = MaterialTheme.colorScheme.error)
    if (open) AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text(strings.get(R.string.chat_persona)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) {
                    Text(strings.get(R.string.chat_persona_failed), color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !busy, onClick = { load() }) { Text(strings.get(R.string.chat_provider_reload)) }
                }
                if (selection.unavailable) Text(strings.get(R.string.chat_persona_unavailable))
                if (selection.recovered) Text(strings.get(R.string.chat_persona_recovered))
                if (!busy && !error && data.personas.isEmpty()) Text(strings.get(R.string.chat_persona_empty))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    item {
                        TextButton(enabled = !busy, onClick = { choose(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(strings.get(if (selection.id == null) R.string.chat_persona_none_selected else R.string.chat_persona_none))
                        }
                    }
                    items(data.personas, key = { it.id }) { persona ->
                        TextButton(enabled = !busy, onClick = { choose(persona.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(if (selection.id == persona.id) strings.get(R.string.chat_persona_selected, persona.name) else persona.name)
                                Text(persona.id, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text(strings.get(R.string.chat_persona_close)) } },
    )
}
