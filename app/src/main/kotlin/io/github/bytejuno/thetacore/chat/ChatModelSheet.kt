package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.provider.ModelRef

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatModelSheet(snapshot: ChatUiSnapshot, actions: ChatUiActions, close: () -> Unit) {
    val strings = uiStrings()
    val models = snapshot.models
    val persona = snapshot.persona
    val busy = models.saving || persona.busy
    val enabled = !busy && !snapshot.chat.active && !snapshot.chat.pending && !snapshot.chat.loading
    // Material has already hidden the sheet when dismissing; always remove it so it can reopen.
    // Selection is owned by the route and keeps saving after the sheet leaves composition.
    ModalBottomSheet(onDismissRequest = close) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(strings.get(R.string.chat_profile_switch_title), style = MaterialTheme.typography.titleLarge) }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(strings.get(R.string.providers_switching)) }
            models.error?.let { error -> item { Text(strings.get(error), color = MaterialTheme.colorScheme.error) } }
            item { Text(strings.get(R.string.chat_persona), style = MaterialTheme.typography.titleSmall) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(persona.selection.id == null, { actions.selectPersona(null) }, enabled = enabled,
                    label = { Text(strings.get(R.string.chat_persona_none)) }) }
                items(persona.personas, key = { it.id }) { p ->
                    FilterChip(persona.selection.id == p.id, { actions.selectPersona(p.id) }, enabled = enabled, label = { Text(p.name) })
                }
            } }
            if (persona.error) item {
                Text(strings.get(R.string.chat_persona_failed), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = actions.reloadPersona) { Text(strings.get(R.string.chat_provider_reload)) }
            }
            if (persona.selection.unavailable) item { Text(strings.get(R.string.chat_persona_unavailable)) }
            if (persona.selection.recovered) item { Text(strings.get(R.string.chat_persona_recovered)) }
            item { Text(strings.get(R.string.chat_persona_next_run), style = MaterialTheme.typography.bodySmall) }
            models.catalog.providers.forEach { provider ->
                val candidates = provider.models.filter { it.chat }
                if (candidates.isNotEmpty()) item { Text(provider.connection.displayName, style = MaterialTheme.typography.titleSmall) }
                items(candidates, key = { provider.connection.providerId + ':' + it.modelId }) { model ->
                    val ref = ModelRef(provider.connection.providerId, model.modelId)
                    FilterChip(models.catalog.bindings.activeChatModelRef == ref, { actions.selectModel(ref) }, enabled = enabled,
                        label = { Text(model.label) }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                Text(strings.get(when (snapshot.approval.mode) {
                    io.github.bytejuno.thetacore.agent.ledger.ApprovalMode.BALANCED -> R.string.chat_approval_mode_balanced
                    io.github.bytejuno.thetacore.agent.ledger.ApprovalMode.FULL_AUTO -> R.string.chat_approval_mode_full_auto
                    else -> R.string.chat_approval_mode_strict
                }))
                TextButton(onClick = { close(); actions.approvalSettings() }, enabled = !busy) { Text(strings.get(R.string.chat_approval_settings)) }
                TextButton(onClick = { close(); actions.providers() }, enabled = !busy) { Text(strings.get(R.string.chat_profile_manage)) }
            }
        }
    }
}
