package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.provider.ModelSelectionState
import io.github.bytejuno.thetacore.provider.ModelRef
import kotlinx.serialization.json.JsonObject

/** App-only read projection. No owner, store or mutable draft is exposed to the surface. */
internal data class ChatUiSnapshot(
    val chat: ChatState,
    val models: ModelSelectionState,
    val approval: ChatApprovalSettings,
    val persona: ChatPersonaState,
    val stream: String,
    val draft: ChatDraftSnapshot,
    val enabledPlugins: Int,
)
internal data class ChatDraftSnapshot(val text: String, val images: List<PendingUserImage>, val error: Int?,
    val documents: List<ComposerDocument> = emptyList(), val importing: Boolean = false)
internal data class ChatUiActions(
    val newSession: () -> Unit,
    val selectSession: (String) -> Unit,
    val deleteSession: (String) -> Unit,
    val moreSessions: () -> Unit,
    val older: () -> Unit,
    val latest: () -> Unit,
    val olderReplies: () -> Unit,
    val continueSavedStep: () -> Unit,
    val edit: (String) -> Unit,
    val removeImage: (String) -> Unit,
    val gallery: () -> Unit,
    val camera: () -> Unit,
    val send: () -> Unit,
    val stop: () -> Unit,
    val plugins: () -> Unit,
    val settings: () -> Unit,
    val providers: () -> Unit,
    val approvalSettings: () -> Unit,
    val balanced: () -> Unit,
    val selectModel: (ModelRef) -> Unit,
    val selectPersona: (String?) -> Unit,
    val reloadPersona: () -> Unit,
    val decide: (String, String, Decision) -> Unit,
    val resolve: (String, String, RecoveryResolution) -> Unit,
    val recover: (ChatRecoveryToken, ChatRecoveryAction, Long?, Boolean) -> Unit,
    val input: (String, String, JsonObject?) -> Unit,
    val userImage: suspend (String, String) -> ByteArray?,
    val generatedImage: suspend (String, String) -> ByteArray?,
    val files: () -> Unit = {},
    val removeDocument: (String) -> Unit = {},
)
