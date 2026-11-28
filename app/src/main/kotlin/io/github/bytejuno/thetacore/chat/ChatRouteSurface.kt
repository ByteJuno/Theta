package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.ApprovalMode
import io.github.bytejuno.thetacore.provider.*
import io.github.bytejuno.thetacore.settings.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.*

@Composable
internal fun ChatRouteSurface(host: ThetaApplication, drafts: ChatComposerDrafts, state: ChatState,
    composer: ChatComposerDraft, stream: String, models: ChatModelSelection, modelState: ModelSelectionState,
    approval: ChatApprovalSettings, launchTask: (() -> Unit) -> Unit, onPlugins: () -> Unit,
    gallery: () -> Unit, camera: () -> Unit) {
    val owner = host.chat
    val scope = rememberCoroutineScope()
    val persona = rememberChatPersonaSelection(host)
    val settings = rememberSettingsNavigator()
    val pages = rememberSaveableStateHolder()
    var operating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    fun locked(): Boolean = owner.state.value.let { it.active || it.pending || it.loading } ||
        operating || models.state.value.saving || persona.state.busy
    fun backSettings() { settings.back(); if (settings.navigation.current == null) { owner.latest(); persona.load() } }
    val snapshot = ChatUiSnapshot(state.copy(loading = state.loading || operating), modelState, approval,
        persona.state, stream, ChatDraftSnapshot(composer.input, composer.images.toList(), composer.error),
        host.plugins.plugins.count { it.enabled } + host.plugins.dataInventory.count { it.state.enabled })
    fun operation(block: suspend () -> Unit) {
        if (locked()) return
        operating = true
        scope.launch { try { block() } finally { operating = false } }
    }
    val actions = ChatUiActions(
        newSession = { operation { owner.newSession().join() } },
        selectSession = { id -> operation { owner.select(id).join() } },
        deleteSession = { id -> operation { owner.select(id).join(); owner.deleteCurrentSession(id).join() } },
        moreSessions = { owner.moreSessions() }, older = { owner.older() }, latest = { owner.latest() },
        olderReplies = { owner.olderReplies() }, continueSavedStep = { launchTask { owner.continueSavedStep() } },
        edit = composer::edit, removeImage = { id -> composer.images = composer.images.filterNot { it.localId == id }; composer.error = null },
        gallery = { if (!locked()) gallery() }, camera = { if (!locked()) camera() },
        send = { if (!locked() && state.sessionId != null) {
            val text = composer.input; val images = composer.images; val receipt = drafts.receipt(state.sessionId)
            composer.error = null; launchTask { owner.send(text, images, receipt) }
        } }, stop = { owner.stop() }, plugins = onPlugins,
        settings = { settings.open(SettingsDestination.HOME) }, providers = { settings.open(SettingsDestination.PROVIDERS) },
        approvalSettings = { settings.open(SettingsDestination.SAFETY) },
        balanced = { scope.launch { try { withContext(Dispatchers.IO) { host.approvalSettings.setMode(ApprovalMode.BALANCED) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.chat_operation_failed } } },
        selectModel = { if (!locked()) models.select(it) }, selectPersona = { if (!locked()) persona.select(it) },
        reloadPersona = persona::load,
        decide = { call, request, choice -> launchTask { owner.decide(call, request, choice) } },
        resolve = { call, request, choice -> owner.recover(call, request, choice) },
        recover = { token, action, window, confirmed -> launchTask { owner.recover(token, action, window, confirmed) } },
        input = { call, request, answer -> owner.respondInput(call, request, answer) },
        userImage = owner::userImage, generatedImage = owner::image)
    val destination = settings.navigation.current
    pages.SaveableStateProvider(destination ?: SettingsDestination.CHAT) {
        if (destination == null) DefaultChatSurface(snapshot, actions)
        else SettingsScreen(host, destination, settings::open, ::backSettings, !locked())
    }
    val strings = uiStrings()
    error?.let { AlertDialog(onDismissRequest = { error = null }, text = { Text(strings.get(it)) },
        confirmButton = { TextButton(onClick = { error = null }) { Text(strings.get(R.string.chat_close)) } }) }
}
