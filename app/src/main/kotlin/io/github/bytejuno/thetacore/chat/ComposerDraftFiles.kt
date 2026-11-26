package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Session keys never become path components. Each replacement publishes a complete draft. */
internal class ComposerDraftFiles(private val directory: File) {
    companion object {
        fun inPrivateStorage(root: File) = ComposerDraftFiles(root.resolve("composer-drafts-v1"))
    }

    private fun file(id: String?) = directory.resolve(
        id?.let { UUID.nameUUIDFromBytes(it.toByteArray()).toString() } ?: "unbound",
    )

    fun read(id: String?): ChatComposerDraft? {
        val source = file(id)
        if (!source.exists()) return null
        return DataInputStream(source.inputStream().buffered()).use { input ->
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
    }

    fun write(id: String?, draft: ChatComposerDraft) {
        val target = file(id).toPath()
        if (draft.input.isEmpty() && draft.images.isEmpty()) {
            Files.deleteIfExists(target)
            return
        }
        Files.createDirectories(directory.toPath())
        val pending = Files.createTempFile(directory.toPath(), "saving-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(pending).buffered()).use { output ->
                output.writeUTF(draft.textVersion)
                output.writeInt(draft.input.length)
                output.writeChars(draft.input)
                output.writeInt(draft.images.size)
                for (image in draft.images) {
                    output.writeUTF(image.localId)
                    output.writeUTF(image.mimeType)
                    output.writeInt(image.sizeBytes)
                    output.write(image.bytes)
                }
            }
            Files.move(pending, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
