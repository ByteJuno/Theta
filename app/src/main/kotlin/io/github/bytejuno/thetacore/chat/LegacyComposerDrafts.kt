package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.UTFDataFormatException
import java.nio.file.Files
import java.util.UUID

private class LegacyDraftFormatException(cause: Throwable? = null) : Exception(cause)

/** Import private drafts from earlier builds before the store becomes available to the UI. */
internal fun migrateComposerFiles(root: File, store: AndroidAgentStore) {
    val directory = root.resolve("composer-drafts-v1")
    if (!directory.isDirectory) return
    val liveNames = mutableSetOf<String>()
    var before: String? = null
    do {
        val ids = store.sessionIds(before)
        for (id in ids) {
            val fileName = UUID.nameUUIDFromBytes(id.toByteArray()).toString()
            liveNames += fileName
            val source = directory.resolve(fileName)
            if (Files.isSymbolicLink(source.toPath()) || !source.isFile) continue
            // Isolate format corruption to this single file and retain the damaged original;
            // database read/write errors stay outside this catch and fail closed.
            val draft = try {
                readLegacyDraft(source)
            } catch (_: LegacyDraftFormatException) {
                continue
            }
            store.composerDrafts.save(id, draft.snapshot(), emptyList())
            check(source.delete())
        }
        before = ids.lastOrNull()
    } while (before != null)
    // Only UUID-named files that do not belong to any live session are removed-session orphans.
    directory.listFiles()
        ?.filter {
            !Files.isSymbolicLink(it.toPath()) && it.isFile && it.name !in liveNames &&
                runCatching { UUID.fromString(it.name).toString() }.getOrNull() == it.name
        }
        ?.forEach { check(it.delete()) }
    if (directory.list()?.isEmpty() == true) directory.delete()
}

private fun readLegacyDraft(source: File): ChatComposerDraft {
    val fileBytes = source.length()
    return try {
        DataInputStream(source.inputStream().buffered()).use { input ->
            val version = input.readUTF()
            if (version.isEmpty()) throw LegacyDraftFormatException()
            val length = input.readInt()
            if (length !in 0..32_000 || length.toLong() * 2L > fileBytes) throw LegacyDraftFormatException()
            val text = String(CharArray(length) { input.readChar() })
            val count = input.readInt()
            if (count !in 0..MAX_USER_IMAGES_PER_MESSAGE) throw LegacyDraftFormatException()
            val seenIds = mutableSetOf<String>()
            val images = List(count) {
                val localId = input.readUTF()
                val mime = input.readUTF()
                if (localId.isEmpty() || mime.isEmpty() || !seenIds.add(localId)) {
                    throw LegacyDraftFormatException()
                }
                val size = input.readInt()
                if (size !in 1..ImageLimits.BYTES || size.toLong() > fileBytes) {
                    throw LegacyDraftFormatException()
                }
                PendingUserImage(localId, mime, ByteArray(size).also(input::readFully))
            }
            if (input.read() != -1) throw LegacyDraftFormatException()
            ChatComposerDraft(text, version).apply { this.images = images }
        }
    } catch (error: LegacyDraftFormatException) {
        throw error
    } catch (error: EOFException) {
        throw LegacyDraftFormatException(error)
    } catch (error: UTFDataFormatException) {
        throw LegacyDraftFormatException(error)
    } catch (error: IllegalArgumentException) {
        throw LegacyDraftFormatException(error)
    }
}
