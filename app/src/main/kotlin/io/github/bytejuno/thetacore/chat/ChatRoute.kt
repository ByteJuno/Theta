package io.github.bytejuno.thetacore.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatRoute(host: ThetaApplication, drafts: ChatComposerDrafts, resumed: Boolean = true, onPlugins: () -> Unit) {
    val context = LocalContext.current
    val owner = remember(host) { host.chat }
    val launchTask = rememberChatTaskLauncher(host)
    val state by owner.state.collectAsState()
    val composer = drafts[state.sessionId]
    var pickerSession by rememberSaveable { mutableStateOf<String?>(null) }
    var stream by remember { mutableStateOf("") }
    LaunchedEffect(state.active) {
        if (state.active) while (true) withFrameNanos { stream = owner.previewText() }
        else stream = ""
    }
    val uiScope = rememberCoroutineScope()
    val modelSelection = io.github.bytejuno.thetacore.provider.rememberChatModelSelection(host.providerSettings)
    val modelState by modelSelection.state.collectAsState()
    val approvalSettings by host.approvalSettings.state.collectAsState()
    LaunchedEffect(host.approvalSettings) {
        withContext(Dispatchers.IO) { runCatching { host.approvalSettings.ensureLoaded() } }
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_USER_IMAGES_PER_MESSAGE),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val id = pickerSession
        uiScope.launch {
            val target = drafts.pickerTarget(id) { owner.open().join() } ?: return@launch
            target.error = null
            val remaining = MAX_USER_IMAGES_PER_MESSAGE - target.images.size
            if (remaining <= 0) {
                target.error = R.string.chat_image_limit
                return@launch
            }
            val selected = uris.take(remaining)
            if (uris.size > remaining) target.error = R.string.chat_image_limit
            val loaded = withContext(Dispatchers.IO) {
                val added = mutableListOf<PendingUserImage>()
                var invalid = false
                for (uri in selected) {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { readBoundedImageStream(it) }
                            ?: throw IllegalArgumentException()
                        val valid = validateAndDecodeAndroidUserImage(bytes)
                        added += PendingUserImage(mimeType = valid.mimeType, bytes = valid.bytes)
                    } catch (_: Exception) {
                        invalid = true
                    }
                }
                added to invalid
            }
            if (loaded.first.isNotEmpty() && drafts.isLiveDraft(id, target)) {
                target.images = (target.images + loaded.first).take(MAX_USER_IMAGES_PER_MESSAGE)
            }
            if (loaded.second && drafts.isLiveDraft(id, target)) target.error = R.string.chat_image_invalid
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        val id = pickerSession
        uiScope.launch {
            val target = drafts.pickerTarget(id) { owner.open().join() } ?: return@launch
            target.error = null
            if (target.images.size >= MAX_USER_IMAGES_PER_MESSAGE) {
                target.error = R.string.chat_image_limit
                return@launch
            }
            val captured = withContext(Dispatchers.IO) {
                runCatching { encodeCameraBitmapToPng(bitmap) }.getOrNull()
            }
            if (captured != null && drafts.isLiveDraft(id, target)) {
                target.images = (target.images + PendingUserImage(mimeType = captured.mimeType, bytes = captured.bytes))
                    .take(MAX_USER_IMAGES_PER_MESSAGE)
            } else if (captured == null && drafts.isLiveDraft(id, target)) {
                target.error = R.string.chat_image_invalid
            }
        }
    }
    LaunchedEffect(owner) { owner.open() }
    LaunchedEffect(drafts, state.sessionId, state.acceptedMessages, state.acceptedDraft, state.draftSync, resumed) {
        reconcileDraftsAsync(drafts, state.sessionId, state.acceptedDraft)
    }
    ChatRouteSurface(host, drafts, state, composer, stream, modelSelection, modelState, approvalSettings,
        launchTask, onPlugins,
        gallery = { composer.error = null; pickerSession = state.sessionId
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        camera = { composer.error = null; pickerSession = state.sessionId; cameraLauncher.launch(null) })
}
