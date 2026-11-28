package io.github.bytejuno.thetacore.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.agent.ledger.ApprovalMode
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultChatSurface(snapshot: ChatUiSnapshot, actions: ChatUiActions) {
    val state = snapshot.chat
    val strings = uiStrings()
    val tokens = LocalThetaThemeTokens.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = !state.active && !state.pending && !state.loading && !snapshot.models.saving && !snapshot.persona.busy
    fun closeDrawer(action: () -> Unit) { scope.launch { drawer.close(); action() } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 360.dp).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = tokens.pageInset)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_theta_mark), null, Modifier.size(36.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                    Text(strings.get(R.string.app_name), Modifier.padding(tokens.smallGap), style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(strings.get(R.string.chat_search_sessions)) })
                FilledTonalButton(onClick = { closeDrawer(actions.newSession) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.get(R.string.chat_new))
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = tokens.smallGap)) {
                val groups = groupedSessions(state.sessions, query)
                if (groups.isEmpty()) item { Text(strings.get(R.string.chat_no_sessions), Modifier.padding(tokens.pageInset)) }
                groups.forEach { (day, sessions) ->
                    item(key = day) { Text(strings.get(when (day) {
                        SessionDay.TODAY -> R.string.chat_today
                        SessionDay.YESTERDAY -> R.string.chat_yesterday
                        SessionDay.EARLIER -> R.string.chat_earlier
                    }), Modifier.padding(tokens.smallGap), style = MaterialTheme.typography.labelLarge) }
                    items(sessions, key = { it.sessionId }) { entry ->
                        NavigationDrawerItem(selected = entry.sessionId == state.sessionId,
                            onClick = { if (enabled) closeDrawer { actions.selectSession(entry.sessionId) } },
                            label = { Text(entry.text ?: strings.get(R.string.chat_session_number, state.sessions.indexOf(entry) + 1),
                                maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            badge = { IconButton(enabled = enabled, onClick = { deleting = entry.sessionId }) {
                                Icon(painterResource(R.drawable.ic_chat_delete), strings.get(R.string.chat_delete_session))
                            } })
                    }
                }
                if (state.moreSessionsAvailable) item { TextButton(enabled = enabled, onClick = actions.moreSessions) {
                    Text(strings.get(R.string.chat_more_sessions))
                } }
            }
            HorizontalDivider()
            NavigationDrawerItem(selected = false, onClick = { closeDrawer(actions.plugins) },
                label = { Text(strings.get(R.string.chat_plugins)) }, badge = { Badge { Text(snapshot.enabledPlugins.toString()) } })
            NavigationDrawerItem(selected = false, onClick = { closeDrawer(actions.settings) }, label = { Text(strings.get(R.string.chat_settings)) })
        }
    }) {
        ChatWindowLayout { compact ->
            TopAppBar(title = {
                TextButton(onClick = { if (snapshot.models.catalog.model(snapshot.models.catalog.bindings.activeChatModelRef) == null)
                    actions.providers() else modelSheet = true }, modifier = Modifier.fillMaxWidth(),
                    enabled = !snapshot.models.saving) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(snapshot.models.catalog.model(snapshot.models.catalog.bindings.activeChatModelRef)?.label
                            ?: strings.get(R.string.chat_profile_unconfigured), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        snapshot.persona.label?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }, navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) {
                Icon(painterResource(R.drawable.ic_chat_menu), strings.get(R.string.chat_open_drawer))
            } }, actions = { IconButton(enabled = enabled, onClick = actions.newSession) {
                Icon(painterResource(R.drawable.ic_chat_new), strings.get(R.string.chat_new))
            } }, windowInsets = WindowInsets(0, 0, 0, 0))
            if (snapshot.approval.mode == ApprovalMode.FULL_AUTO)
                ChatFullAutoBanner(compact, actions.approvalSettings, actions.balanced)
            ChatReadingFlow(snapshot, actions)
            ChatComposerSurface(compact, snapshot, actions)
        }
    }
    if (modelSheet) ChatModelSheet(snapshot, actions) { modelSheet = false }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null },
        title = { Text(strings.get(R.string.chat_delete_session)) },
        text = { Text(strings.get(R.string.chat_delete_session_confirm)) },
        confirmButton = { TextButton(enabled = enabled, onClick = { deleting = null; actions.deleteSession(id) }) {
            Text(strings.get(R.string.chat_delete_session))
        } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings.get(R.string.chat_close)) } }) }
}
