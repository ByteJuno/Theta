package io.github.bytejuno.thetacore.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

internal const val MAX_USER_IMAGES_PER_MESSAGE = ImageLimits.PER_MESSAGE
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
 * Validates and fully decodes a user-supplied image using Android's [ImageDecoder] header
 * callback (MIME type + dimensions against [ImageLimits]) and software pixel decoding
 * (rejecting partial/corrupt streams) before storing into SQLite.
 */
internal fun validateAndDecodeAndroidUserImage(bytes: ByteArray): ValidatedUserImage {
    require(bytes.size in 1..ImageLimits.BYTES) { "Image size out of bounds" }
    var detectedMime: String? = null
    var headerWidth = 0
    var headerHeight = 0
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val mime = info.mimeType.lowercase(Locale.ROOT)
        require(mime in ALLOWED_USER_IMAGE_MIMES) { "Unsupported image MIME type" }
        require(ImageLimits.validDimensions(info.size.width, info.size.height)) {
            "Image dimensions exceed safety limits"
        }
        detectedMime = mime
        headerWidth = info.size.width
        headerHeight = info.size.height
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setOnPartialImageListener { false }
    }
    try {
        val width = headerWidth.takeIf { it > 0 } ?: bitmap.width
        val height = headerHeight.takeIf { it > 0 } ?: bitmap.height
        require(ImageLimits.validDimensions(bitmap.width, bitmap.height) && ImageLimits.validDimensions(width, height)) {
            "Decoded image dimensions exceed limits"
        }
        val mime = requireNotNull(detectedMime) { "Unsupported image MIME type" }
        return ValidatedUserImage(mime, width, height, bytes)
    } finally {
        bitmap.recycle()
    }
}

internal fun encodeCameraBitmapToPng(bitmap: Bitmap): ValidatedUserImage {
    require(ImageLimits.validDimensions(bitmap.width, bitmap.height)) { "Camera image exceeds safety limits" }
    val out = object : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            require(count <= ImageLimits.BYTES - len) { "Encoded camera image exceeds safety limit" }
            super.write(b, off, len)
        }
        override fun write(b: Int) {
            require(count < ImageLimits.BYTES) { "Encoded camera image exceeds safety limit" }
            super.write(b)
        }
    }
    val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    require(ok) { "Could not encode camera bitmap" }
    val bytes = out.toByteArray()
    require(bytes.size in 1..ImageLimits.BYTES) { "Encoded camera image out of bounds" }
    return ValidatedUserImage("image/png", bitmap.width, bitmap.height, bytes)
}
