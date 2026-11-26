package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

internal class ChatComposerDraft(text: String = "", version: String = UUID.randomUUID().toString()) {
    var input by mutableStateOf(text)
    var textVersion by mutableStateOf(version)
    var images by mutableStateOf(emptyList<PendingUserImage>())
    var error by mutableStateOf<Int?>(null)

    fun edit(text: String) {
        if (text.length <= 32_000 && input != text) {
            input = text
            textVersion = UUID.randomUUID().toString()
        }
    }

    fun acknowledge(receipt: ChatDraftReceipt) {
        if (receipt.clearsText(textVersion)) input = ""
        images = receipt.remaining(images)
    }
}

internal class ChatComposerDrafts(private val files: ComposerDraftFiles) {
    private val sessions = mutableMapOf<String?, ChatComposerDraft>()
    private val saved = mutableMapOf<String?, Pair<String, List<String>>>()
    operator fun get(id: String?) = sessions.getOrPut(id) {
        (files.read(id) ?: ChatComposerDraft()).also { saved[id] = it.identity() }
    }

    // Empty text after acknowledgment can have the same receipt version as the submitted text.
    private fun ChatComposerDraft.identity() =
        (if (input.isEmpty()) "" else textVersion) to images.map { it.localId }

    fun checkpoint(): Int {
        for ((id, draft) in sessions) {
            val identity = draft.identity()
            if (identity != saved[id]) {
                files.write(id, draft)
                saved[id] = identity
            }
        }
        return 1
    }

    companion object {
        fun saver(files: ComposerDraftFiles) = Saver<ChatComposerDrafts, Any>(
            save = { it.checkpoint() },
            restore = { value -> ChatComposerDrafts(files).apply {
                // Earlier versions saved flattened (session, text, version) triples.
                if (value is List<*>) value.filterIsInstance<String>().chunked(3).forEach {
                    if (it.size == 3) sessions[it[0]] = ChatComposerDraft(it[1], it[2])
                }
            } },
        )
    }
}

@Composable
internal fun rememberChatComposerDrafts(): ChatComposerDrafts {
    val root = LocalContext.current.noBackupFilesDir
    val files = remember(root) { ComposerDraftFiles.inPrivateStorage(root) }
    return rememberSaveable(saver = ChatComposerDrafts.saver(files)) { ChatComposerDrafts(files) }
}
