package io.github.bytejuno.thetacore.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal fun decodeImage(bytes: ByteArray): Bitmap {
    require(bytes.size in 1..ImageLimits.BYTES)
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(ImageLimits.validDimensions(info.size.width, info.size.height))
        require(info.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setOnPartialImageListener { false }
    }
}

internal fun normalizeImage(bytes: ByteArray): ByteArray {
    val bitmap = decodeImage(bytes)
    try {
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(count <= ImageLimits.BYTES - length)
                super.write(bytes, offset, length)
            }
            override fun write(value: Int) { require(count < ImageLimits.BYTES); super.write(value) }
        }
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return output.toByteArray().also { require(it.size in 1..ImageLimits.BYTES) }
    } finally { bitmap.recycle() }
}

internal class ImageAttachments(private val storage: io.github.bytejuno.thetacore.agent.ledger.PrivateImageStore) {
    fun claim(sessionId: String, callId: String) = storage.claim(sessionId, callId)
    fun save(sessionId: String, callId: String, bytes: ByteArray) = storage.save(sessionId, callId, bytes)
    fun read(sessionId: String, callId: String) = storage.read(sessionId, callId)
}
