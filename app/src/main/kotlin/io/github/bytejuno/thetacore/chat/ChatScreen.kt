package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.Composable
import io.github.bytejuno.thetacore.ThetaApplication

@Composable
internal fun ChatScreen(host: ThetaApplication, drafts: ChatComposerDrafts, resumed: Boolean = true, onPlugins: () -> Unit) =
    ChatRoute(host, drafts, resumed, onPlugins)
