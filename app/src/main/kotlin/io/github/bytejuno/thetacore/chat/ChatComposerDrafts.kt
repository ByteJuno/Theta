package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
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

internal class ChatComposerDrafts {
    private val sessions = mutableMapOf<String?, ChatComposerDraft>()
    operator fun get(id: String?) = sessions.getOrPut(id) { ChatComposerDraft() }

    companion object {
        // Image bytes stay in memory. Only editable text and its receipt identity enter saved state.
        val Saver = listSaver<ChatComposerDrafts, String>(
            save = { book -> book.sessions.flatMap { (id, draft) ->
                if (id == null || draft.input.isEmpty()) emptyList() else listOf(id, draft.input, draft.textVersion)
            } },
            restore = { saved -> ChatComposerDrafts().apply {
                saved.chunked(3).forEach { (id, text, version) -> sessions[id] = ChatComposerDraft(text, version) }
            } },
        )
    }
}
