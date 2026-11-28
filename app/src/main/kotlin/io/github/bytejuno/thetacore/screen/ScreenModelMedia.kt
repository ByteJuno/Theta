package io.github.bytejuno.thetacore.screen

import io.github.bytejuno.thetacore.agent.ledger.InvocationLedger
import io.github.bytejuno.thetacore.agent.protocol.*
import java.io.ByteArrayOutputStream

/** Session-scoped reader for user attachments and ledger-owned screenshot blobs. */
internal class ScreenModelMedia(
    private val ledger: InvocationLedger?,
    private val sessionId: String,
    private val userImages: MediaContentReader,
) {
    val reader = MediaContentReader { blob ->
        userImages.read(blob) ?: runCatching {
            require(blob.sizeBytes in 1..io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits.BYTES.toLong() &&
                blob.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif"))
            val output = ByteArrayOutputStream()
            while (output.size().toLong() < blob.sizeBytes) {
                val (ref, chunk) = requireNotNull(ledger).readSessionBlob(sessionId, blob.id, output.size().toLong())
                require(ref.sizeBytes == blob.sizeBytes && ref.mimeType == blob.mimeType && chunk.isNotEmpty())
                output.write(chunk)
            }
            output.toByteArray().also { require(it.size.toLong() == blob.sizeBytes) }
        }.getOrNull()
    }

}
