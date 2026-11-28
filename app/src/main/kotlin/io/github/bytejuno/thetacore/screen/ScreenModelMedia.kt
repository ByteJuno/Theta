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
    val reader = object : MediaContentReader {
        override fun read(blob: BlobRef): ByteArray? = runCatching { readAvailable(blob) }.getOrNull()

        override fun unavailableReason(blob: BlobRef): String? = try {
            val bytes = readAvailable(blob)
            if (bytes == null) "unavailable: missing or quarantined" else null
        } catch (e: io.github.bytejuno.thetacore.agent.ledger.BlobReadException) {
            "unavailable: ${e.message}"
        } catch (_: IllegalArgumentException) {
            "unavailable: invalid media metadata or size"
        }
    }

    private fun readAvailable(blob: BlobRef): ByteArray? = userImages.read(blob) ?: run {
        require(blob.sizeBytes in 1..io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits.BYTES.toLong() &&
            blob.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif"))
        val output = ByteArrayOutputStream()
        while (output.size().toLong() < blob.sizeBytes) {
            val (ref, chunk) = (ledger ?: return null).readSessionBlob(sessionId, blob.id, output.size().toLong())
            require(ref.sizeBytes == blob.sizeBytes && ref.mimeType == blob.mimeType && chunk.isNotEmpty())
            output.write(chunk)
        }
        output.toByteArray().also { require(it.size.toLong() == blob.sizeBytes) }
    }
}
