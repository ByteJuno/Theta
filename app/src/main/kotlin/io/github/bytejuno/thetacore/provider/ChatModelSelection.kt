package io.github.bytejuno.thetacore.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ModelSelectionState(val catalog: ProviderCatalog = ProviderCatalog(), val saving: Boolean = false, val error: Int? = null)

/** Set saving synchronously on the UI thread, before dispatching persistence to IO. */
internal class ChatModelSelection(private val scope: CoroutineScope, private val save: suspend (ModelRef) -> ProviderCatalog) {
    private val mutable = MutableStateFlow(ModelSelectionState())
    val state = mutable.asStateFlow()
    fun loaded(catalog: ProviderCatalog) { if (!mutable.value.saving) mutable.value = mutable.value.copy(catalog = catalog) }
    fun select(ref: ModelRef): Job? {
        if (mutable.value.saving) return null
        mutable.value = mutable.value.copy(saving = true, error = null)
        return scope.launch {
            try { mutable.value = ModelSelectionState(save(ref)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = R.string.providers_switch_failed) }
            finally { mutable.value = mutable.value.copy(saving = false) }
        }
    }
}

@Composable
internal fun rememberChatModelSelection(store: OpenAiSettingsStore): ChatModelSelection {
    val scope = rememberCoroutineScope()
    val selection = remember(store, scope) { ChatModelSelection(scope) { withContext(Dispatchers.IO) { store.selectChatModel(it) } } }
    val revision by store.revision.collectAsState()
    LaunchedEffect(store, revision) {
        try { selection.loaded(withContext(Dispatchers.IO) { store.readProviderCatalog() }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The settings screen provides the explicit recovery flow. */ }
    }
    return selection
}

@Composable
internal fun ChatModelPicker(selection: ChatModelSelection, enabled: Boolean, close: () -> Unit, manage: () -> Unit) {
    val state by selection.state.collectAsState()
    val strings = uiStrings()
    AlertDialog(onDismissRequest = { if (!state.saving) close() }, title = { Text(strings.get(R.string.chat_profile_switch_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(strings.get(R.string.providers_switching)) }
            state.error?.let { Text(strings.get(it), color = MaterialTheme.colorScheme.error) }
            ProviderBindingChoices(state.catalog, enabled && !state.saving, false) { selection.select(requireNotNull(it)) }
        } },
        confirmButton = { TextButton(enabled = !state.saving, onClick = manage) { Text(strings.get(R.string.chat_profile_manage)) } },
        dismissButton = { TextButton(enabled = !state.saving, onClick = close) { Text(strings.get(R.string.chat_close)) } })
}
