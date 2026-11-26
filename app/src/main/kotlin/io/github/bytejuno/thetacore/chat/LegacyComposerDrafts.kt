package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.DataInputStream
import java.io.File
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import java.util.UUID

/** Import private drafts from earlier builds before the store becomes available to the UI. */
internal fun migrateComposerFiles(root: File, store: AndroidAgentStore) {
    val directory = root.resolve("composer-drafts-v1")
    if (!directory.isDirectory) return
    var before: String? = null
    do {
        val ids = store.sessionIds(before)
        for (id in ids) {
            val source = directory.resolve(UUID.nameUUIDFromBytes(id.toByteArray()).toString())
            if (!source.isFile) continue
            store.composerDrafts.save(id, readLegacyDraft(source).snapshot(), emptyList())
            check(source.delete())
        }
        before = ids.lastOrNull()
    } while (before != null)
    // The UUID-named files left after visiting every live session belonged to removed sessions.
    directory.listFiles()?.filter { it.isFile && runCatching { UUID.fromString(it.name).toString() }.getOrNull() == it.name }
        ?.forEach { check(it.delete()) }
    if (directory.list()?.isEmpty() == true) directory.delete()
}

private fun readLegacyDraft(source: File): ChatComposerDraft =
        DataInputStream(source.inputStream().buffered()).use { input ->
            val version = input.readUTF()
            val length = input.readInt().also { require(it in 0..32_000) }
            val text = String(CharArray(length) { input.readChar() })
            ChatComposerDraft(text, version).apply {
                val count = input.readInt().also { require(it in 0..MAX_USER_IMAGES_PER_MESSAGE) }
                images = List(count) {
                    val localId = input.readUTF()
                    val mime = input.readUTF()
                    val size = input.readInt().also { require(it in 1..ImageLimits.BYTES) }
                    PendingUserImage(localId, mime, ByteArray(size).also(input::readFully))
                }
            }
        }
