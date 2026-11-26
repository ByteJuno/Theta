package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.bytejuno.thetacore.agent.ledger.ComposerImage
import io.github.bytejuno.thetacore.agent.ledger.ComposerSnapshot
import io.github.bytejuno.thetacore.agent.ledger.SessionComposerStore
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

internal fun ChatComposerDraft.snapshot() = ComposerSnapshot(textVersion, input,
    images.map { ComposerImage(it.localId, it.mimeType, it.bytes) })

internal class ChatComposerDrafts(private val store: () -> SessionComposerStore?) {
    private val sessions = mutableMapOf<String?, ChatComposerDraft>()
    private data class SavedParts(val version: String, val emptyText: Boolean, val imageIds: List<String>)
    private val saved = mutableMapOf<String?, SavedParts>()
    operator fun get(id: String?) = sessions.getOrPut(id) {
        val row = id?.let { requireNotNull(store()).read(it) }
        ChatComposerDraft(row?.text.orEmpty(), row?.version ?: UUID.randomUUID().toString()).apply {
            images = row?.images.orEmpty().map { PendingUserImage(it.id, it.mime, it.bytes) }
            saved[id] = identity()
        }
    }

    // Empty text after acknowledgment can have the same receipt version as the submitted text.
    private fun ChatComposerDraft.identity() =
        SavedParts(textVersion, input.isEmpty(), images.map { it.localId })

    fun receipt(id: String): ChatDraftReceipt {
        val draft = this[id]
        val baseline = saved[id]
        return ChatDraftReceipt(id, draft.textVersion, draft.images.map { it.localId }.toSet(),
            baseline?.version, baseline?.imageIds.orEmpty())
    }

    fun checkpoint(): Int {
        for ((id, draft) in sessions) {
            val identity = draft.identity()
            val baseline = saved[id]
            if (id != null && identity != baseline) {
                val textChanged = baseline?.version != identity.version || baseline.emptyText != identity.emptyText
                requireNotNull(store()).save(id, draft.snapshot(), baseline?.imageIds.orEmpty(), textChanged)
                saved[id] = identity
            }
        }
        return 1
    }

    companion object {
        fun saver(store: () -> SessionComposerStore?) = Saver<ChatComposerDrafts, Any>(
            save = { it.checkpoint() },
            restore = { value -> ChatComposerDrafts(store).apply {
                // Earlier versions saved flattened (session, text, version) triples.
                if (value is List<*>) value.filterIsInstance<String>().chunked(3).forEach {
                    if (it.size == 3) sessions[it[0]] = ChatComposerDraft(it[1], it[2])
                }
            } },
        )
    }
}

@Composable
internal fun rememberChatComposerDrafts(owner: ChatSessionOwner): ChatComposerDrafts {
    return rememberSaveable(saver = ChatComposerDrafts.saver { owner.composerStore }) {
        ChatComposerDrafts { owner.composerStore }
    }
}
