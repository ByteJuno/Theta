package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.*
import io.github.bytejuno.thetacore.ThetaApplication
import io.github.bytejuno.thetacore.plugin.DataPersona
import kotlinx.coroutines.*

internal data class ChatPersonaState(val personas: List<DataPersona> = emptyList(),
    val selection: PersonaSelection = PersonaSelection(), val busy: Boolean = true, val error: Boolean = false) {
    val label: String? get() = personas.firstOrNull { it.id == selection.id }?.name
}
internal class ChatPersonaSelection(private val host: ThetaApplication, private val scope: CoroutineScope) {
    var state by mutableStateOf(ChatPersonaState()); private set
    fun load() = update(null, false)
    fun select(id: String?) = update(id, true)
    private fun update(id: String?, save: Boolean) {
        if (state.busy && save) return
        state = state.copy(busy = true, error = false)
        scope.launch {
            try {
                val data = host.plugins.activePromptData()
                withContext(Dispatchers.IO) {
                    if (save) { check(id == null || data.personas.any { it.id == id }); host.personaSettings.select(id) }
                    host.personaSettings.resolve(data)
                }
                state = ChatPersonaState(data.personas.toList(), withContext(Dispatchers.IO) { host.personaSettings.current() }, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state = state.copy(busy = false, error = true) }
        }
    }
}
@Composable
internal fun rememberChatPersonaSelection(host: ThetaApplication): ChatPersonaSelection {
    val scope = rememberCoroutineScope()
    val selection = remember(host, scope) { ChatPersonaSelection(host, scope) }
    LaunchedEffect(selection) { selection.load() }
    return selection
}
