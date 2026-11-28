package io.github.bytejuno.thetacore.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.chat.rememberChatPersonaSelection
import io.github.bytejuno.thetacore.plugin.PluginPromptData
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PersonaSettingsScreen(host: ThetaApplication, enabled: Boolean, back: () -> Unit) {
    val strings = uiStrings()
    val persona = rememberChatPersonaSelection(host)
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(PluginPromptData()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    fun reload() {
        loading = true; failed = false; persona.load()
        scope.launch {
            try { data = host.plugins.activePromptData() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    LaunchedEffect(host) { reload() }
    val state = persona.state
    SettingsPage(strings.get(R.string.settings_personas_skills), back, canGoBack = !state.busy) {
        Text(strings.get(R.string.chat_persona_next_run))
        if (loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!enabled) Text(strings.get(R.string.settings_persona_busy))
        if (state.error || failed || data.errors.isNotEmpty()) Text(strings.get(R.string.chat_persona_failed), color = MaterialTheme.colorScheme.error)
        TextButton(enabled = !loading && !state.busy, onClick = ::reload) { Text(strings.get(R.string.chat_provider_reload)) }
        if (state.selection.unavailable) Text(strings.get(R.string.chat_persona_unavailable))
        if (state.selection.recovered) Text(strings.get(R.string.chat_persona_recovered))
        if (!loading && !state.busy && state.personas.isEmpty()) Text(strings.get(R.string.chat_persona_empty))
        OutlinedButton(onClick = { persona.select(null) }, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth()) {
            RadioButton(state.selection.id == null, null)
            Text(strings.get(R.string.chat_persona_none), Modifier.weight(1f))
        }
        state.personas.forEach { item ->
            OutlinedButton(onClick = { persona.select(item.id) }, enabled = enabled && !state.busy, modifier = Modifier.fillMaxWidth()) {
                RadioButton(state.selection.id == item.id, null)
                Column(Modifier.weight(1f)) { Text(item.name); Text(item.id, style = MaterialTheme.typography.bodySmall) }
            }
        }
        HorizontalDivider()
        Text(strings.get(R.string.settings_skills), style = MaterialTheme.typography.titleMedium)
        Text(strings.get(R.string.settings_skills_detail))
        if (!loading && data.skills.isEmpty()) Text(strings.get(R.string.settings_skills_empty))
        data.skills.forEach { skill ->
            Text(skill.name, style = MaterialTheme.typography.titleSmall)
            Text(skill.description)
        }
    }
}
