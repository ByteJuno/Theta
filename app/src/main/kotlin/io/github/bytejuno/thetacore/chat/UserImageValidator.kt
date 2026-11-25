package io.github.bytejuno.thetacore.chat

import android.graphics.Bitmap
import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

internal const val MAX_USER_IMAGES_PER_MESSAGE = 4
internal const val IMAGE_ONLY_GOAL_FALLBACK = "[image]"
internal val ALLOWED_USER_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

internal class PendingUserImage(
    val localId: String = UUID.randomUUID().toString(),
    val mimeType: String,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()
        get() = field.copyOf()
    val sizeBytes: Int = bytes.size
    override fun toString() = "PendingUserImage(localId=$localId, mimeType=$mimeType, sizeBytes=$sizeBytes)"
}

internal class ValidatedUserImage(
    val mimeType: String,
    val width: Int,
    val height: Int,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()
        get() = field.copyOf()
}

internal fun readBoundedImageStream(input: InputStream, maxBytes: Int = ImageLimits.BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > maxBytes) {
            throw IllegalArgumentException("Image exceeds maximum allowed byte size")
        }
        out.write(buffer, 0, read)
    }
    val bytes = out.toByteArray()
    require(bytes.isNotEmpty()) { "Empty image stream" }
    return bytes
}

/**
 * Pure-JVM format, size, and header dimension validation aligned with [ImageLimits].
 * Rejects unsupported formats (GIF/SVG/etc.), malformed headers, files > 8 MiB, and images
 * exceeding 4096px per edge or 4,194,304 total pixels before any full bitmap allocation.
 */
internal fun inspectUserImage(bytes: ByteArray): ValidatedUserImage {
    require(bytes.size in 1..ImageLimits.BYTES) { "Image size out of bounds" }
    val (mime, width, height) = when {
        isPng(bytes) -> parsePngHeader(bytes)
        isJpeg(bytes) -> parseJpegHeader(bytes)
        isWebp(bytes) -> parseWebpHeader(bytes)
        else -> throw IllegalArgumentException("Unsupported image format; only JPEG, PNG, and WEBP are allowed")
    }
    require(mime in ALLOWED_USER_IMAGE_MIMES) { "Unsupported image MIME type" }
    require(ImageLimits.validDimensions(width, height)) { "Image dimensions exceed safety limits" }
    return ValidatedUserImage(mime, width, height, bytes)
}

/**
 * Full Android validation: runs header/size guardrails AND decodes with software ImageDecoder
 * rejecting partial/corrupt pixel streams before storing into SQLite.
 */
internal fun validateAndDecodeAndroidUserImage(bytes: ByteArray): ValidatedUserImage {
    val inspected = inspectUserImage(bytes)
    val bitmap = decodeImage(bytes)
    try {
        require(ImageLimits.validDimensions(bitmap.width, bitmap.height)) { "Decoded image dimensions exceed limits" }
        return ValidatedUserImage(inspected.mimeType, bitmap.width, bitmap.height, bytes)
    } finally {
        bitmap.recycle()
    }
}

internal fun encodeCameraBitmapToPng(bitmap: Bitmap): ValidatedUserImage {
    require(ImageLimits.validDimensions(bitmap.width, bitmap.height)) { "Camera image exceeds safety limits" }
    val out = ByteArrayOutputStream()
    val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    require(ok) { "Could not encode camera bitmap" }
    val bytes = out.toByteArray()
    return inspectUserImage(bytes)
}

private fun isPng(b: ByteArray): Boolean =
    b.size >= 24 &&
        b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() &&
        b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

private fun parsePngHeader(b: ByteArray): Triple<String, Int, Int> {
    require(b[12] == 'I'.code.toByte() && b[13] == 'H'.code.toByte() && b[14] == 'D'.code.toByte() && b[15] == 'R'.code.toByte()) {
        "Malformed PNG header"
    }
    val width = be32(b, 16)
    val height = be32(b, 20)
    return Triple("image/png", width, height)
}

private fun isJpeg(b: ByteArray): Boolean =
    b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

private fun parseJpegHeader(b: ByteArray): Triple<String, Int, Int> {
    var i = 2
    while (i + 3 < b.size) {
        if (b[i] != 0xFF.toByte()) throw IllegalArgumentException("Malformed JPEG marker")
        while (i < b.size && b[i] == 0xFF.toByte()) i++
        if (i >= b.size) break
        val marker = b[i].toInt() and 0xFF
        i++
        if (marker == 0xD9 || marker == 0xDA) break // EOI or SOS before SOF
        if (marker == 0x01 || marker in 0xD0..0xD7) continue // standalone markers
        require(i + 1 < b.size) { "Truncated JPEG segment length" }
        val length = be16(b, i)
        require(length >= 2 && i + length <= b.size) { "Invalid JPEG segment length" }
        val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isSof) {
            require(length >= 7 && i + 6 < b.size) { "Truncated JPEG SOF header" }
            val height = be16(b, i + 3)
            val width = be16(b, i + 5)
            return Triple("image/jpeg", width, height)
        }
        i += length
    }
    throw IllegalArgumentException("JPEG missing SOF dimensions")
}

private fun isWebp(b: ByteArray): Boolean =
    b.size >= 16 &&
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

private fun parseWebpHeader(b: ByteArray): Triple<String, Int, Int> {
    val chunk = String(b, 12, 4, Charsets.US_ASCII)
    return when (chunk) {
        "VP8X" -> {
            require(b.size >= 30) { "Truncated WebP VP8X header" }
            val width = 1 + (le24(b, 24))
            val height = 1 + (le24(b, 27))
            Triple("image/webp", width, height)
        }
        "VP8L" -> {
            require(b.size >= 25 && b[20] == 0x2F.toByte()) { "Malformed WebP VP8L header" }
            val bits = le32(b, 21)
            val width = (bits and 0x3FFF) + 1
            val height = ((bits ushr 14) and 0x3FFF) + 1
            Triple("image/webp", width, height)
        }
        "VP8 " -> {
            require(b.size >= 30 && b[23] == 0x9D.toByte() && b[24] == 0x01.toByte() && b[25] == 0x2A.toByte()) {
                "Malformed WebP VP8 header"
            }
            val width = le16(b, 26) and 0x3FFF
            val height = le16(b, 28) and 0x3FFF
            Triple("image/webp", width, height)
        }
        else -> throw IllegalArgumentException("Unsupported WebP chunk")
    }
}

private fun be16(b: ByteArray, offset: Int): Int =
    ((b[offset].toInt() and 0xFF) shl 8) or (b[offset + 1].toInt() and 0xFF)

private fun be32(b: ByteArray, offset: Int): Int =
    ((b[offset].toInt() and 0xFF) shl 24) or
        ((b[offset + 1].toInt() and 0xFF) shl 16) or
        ((b[offset + 2].toInt() and 0xFF) shl 8) or
        (b[offset + 3].toInt() and 0xFF)

private fun le16(b: ByteArray, offset: Int): Int =
    (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

private fun le24(b: ByteArray, offset: Int): Int =
    (b[offset].toInt() and 0xFF) or
        ((b[offset + 1].toInt() and 0xFF) shl 8) or
        ((b[offset + 2].toInt() and 0xFF) shl 16)

private fun le32(b: ByteArray, offset: Int): Int =
    (b[offset].toInt() and 0xFF) or
        ((b[offset + 1].toInt() and 0xFF) shl 8) or
        ((b[offset + 2].toInt() and 0xFF) shl 16) or
        ((b[offset + 3].toInt() and 0xFF) shl 24)
