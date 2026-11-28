package io.github.bytejuno.thetacore.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream

internal const val SCREEN_IMAGE_BYTES = 768 * 1024
internal const val SCREEN_IMAGE_EDGE = 1600

internal fun screenImageSize(width: Int, height: Int): Pair<Int, Int> {
    require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 64_000_000)
    val ratio = minOf(1.0, SCREEN_IMAGE_EDGE.toDouble() / maxOf(width, height))
    return maxOf(1, (width * ratio).toInt()) to maxOf(1, (height * ratio).toInt())
}

internal fun decodeScreenCapture(bytes: ByteArray): ToolResult {
    require(bytes.size <= 16 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384 &&
        bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000)
    val options = BitmapFactory.Options().apply {
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > SCREEN_IMAGE_EDGE * 2) inSampleSize *= 2
    }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    return try { encodeScreenCapture(bitmap, bounds.outWidth, bounds.outHeight) } finally { bitmap.recycle() }
}

internal fun encodeScreenCapture(bitmap: Bitmap, originalWidth: Int = bitmap.width, originalHeight: Int = bitmap.height): ToolResult {
    var current = bitmap
    try {
        val (width, height) = screenImageSize(bitmap.width, bitmap.height)
        if (width != bitmap.width || height != bitmap.height) current = Bitmap.createScaledBitmap(bitmap, width, height, true)
        var quality = 85
        while (true) {
            val output = ByteArrayOutputStream()
            check(current.compress(Bitmap.CompressFormat.JPEG, quality, output))
            if (output.size() <= SCREEN_IMAGE_BYTES) return ToolResult(structured = buildJsonObject {
                put("width", current.width); put("height", current.height)
                put("originalWidth", originalWidth); put("originalHeight", originalHeight)
                put("coordinateSpace", "original display pixels")
            }, effect = ToolEffect.NONE).withContent(listOf(ToolContent.Media(ToolMediaKind.IMAGE, "image/jpeg", output.toByteArray())))
            if (quality > 45) quality -= 20 else {
                check(current.width > 1 || current.height > 1)
                val next = Bitmap.createScaledBitmap(current, maxOf(1, current.width / 2), maxOf(1, current.height / 2), true)
                if (current !== bitmap) current.recycle()
                current = next; quality = 75
            }
        }
    } finally { if (current !== bitmap) current.recycle() }
}
