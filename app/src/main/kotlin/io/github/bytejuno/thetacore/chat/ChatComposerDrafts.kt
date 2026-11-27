package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import io.github.bytejuno.thetacore.agent.ledger.ComposerConsumption
import io.github.bytejuno.thetacore.agent.ledger.ComposerImage
import io.github.bytejuno.thetacore.agent.ledger.ComposerSnapshot
import io.github.bytejuno.thetacore.agent.ledger.SessionComposerStore
import java.util.UUID

internal data class DraftIdentitySnapshot(val sessionId: String, val textVersion: String, val imageIds: List<String>)

internal class ChatComposerDraft(
    text: String = "",
    version: String = UUID.randomUUID().toString(),
    private val onAcknowledge: ((ChatDraftReceipt) -> Unit)? = null,
) {
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
        val handler = onAcknowledge
        if (handler != null) handler(receipt)
        else {
            if (receipt.clearsText(textVersion)) input = ""
            images = receipt.remaining(images)
        }
    }
}

internal fun ChatComposerDraft.snapshot() = ComposerSnapshot(textVersion, input,
    images.map { ComposerImage(it.localId, it.mimeType, it.bytes) })

internal class ChatComposerDrafts(private val store: () -> SessionComposerStore?) {
    private val sessions = mutableMapOf<String?, ChatComposerDraft>()
    private data class SavedParts(val version: String, val emptyText: Boolean, val imageIds: List<String>)
    private val saved = mutableMapOf<String?, SavedParts>()

    private fun createDraft(id: String?, text: String, version: String) = ChatComposerDraft(
        text,
        version,
        id?.let { sessionId -> { receipt -> reconcile(sessionId, receipt) } },
    )

    operator fun get(id: String?) = sessions.getOrPut(id) {
        val row = id?.let { requireNotNull(store()).read(it) }
        createDraft(id, row?.text.orEmpty(), row?.version ?: UUID.randomUUID().toString()).apply {
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

    fun identities(ids: Collection<String>): List<DraftIdentitySnapshot> = ids.mapNotNull { id ->
        val draft = sessions[id] ?: return@mapNotNull null
        DraftIdentitySnapshot(id, draft.textVersion, draft.images.map { it.localId })
    }

    fun readConsumed(snapshots: List<DraftIdentitySnapshot>): Map<String, ComposerConsumption> {
        val activeStore = store() ?: return emptyMap()
        return snapshots.associate { it.sessionId to activeStore.consumed(it.sessionId, it.textVersion, it.imageIds) }
    }

    fun applyConsumed(consumed: Map<String, ComposerConsumption>, receipt: ChatDraftReceipt? = null) {
        val ids = buildSet {
            addAll(consumed.keys)
            if (receipt != null && receipt.sessionId in sessions) add(receipt.sessionId)
        }
        for (id in ids) {
            val draft = sessions[id] ?: continue
            val dbConsumed = consumed[id]
            val matchingReceipt = receipt?.takeIf { it.sessionId == id }
            val textConsumedInDb = dbConsumed != null && draft.textVersion in dbConsumed.versions
            val textClearedByReceipt = matchingReceipt != null && matchingReceipt.clearsText(draft.textVersion)
            if (textConsumedInDb || textClearedByReceipt) {
                draft.input = ""
                if (textConsumedInDb) {
                    val fresh = UUID.randomUUID().toString()
                    draft.textVersion = fresh
                    val baseline = saved[id]
                    saved[id] = baseline?.copy(version = fresh, emptyText = true) ?: SavedParts(fresh, true, emptyList())
                }
            }
            val dbImages = dbConsumed?.imageIds.orEmpty()
            if (dbImages.isNotEmpty() || matchingReceipt != null) {
                draft.images = draft.images.filterNot { img ->
                    img.localId in dbImages || (matchingReceipt != null && matchingReceipt.clearsImage(img.localId))
                }
                if (dbImages.isNotEmpty()) {
                    saved[id]?.let { baseline ->
                        saved[id] = baseline.copy(imageIds = baseline.imageIds.filterNot { it in dbImages })
                    }
                }
            }
        }
    }

    fun reconcile(id: String?, receipt: ChatDraftReceipt? = null) {
        val targets = buildSet {
            if (id != null && id in sessions) add(id)
            if (receipt != null && receipt.sessionId in sessions) add(receipt.sessionId)
        }
        if (targets.isEmpty()) return
        val snapshots = identities(targets)
        applyConsumed(readConsumed(snapshots), receipt)
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
                    if (it.size == 3) sessions[it[0]] = createDraft(it[0], it[1], it[2])
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
