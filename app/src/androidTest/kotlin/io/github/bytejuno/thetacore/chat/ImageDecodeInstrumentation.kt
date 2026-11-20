package io.github.bytejuno.thetacore.chat

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import java.io.ByteArrayOutputStream

class ImageDecodeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val original = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            original.eraseColor(Color.MAGENTA)
            val buffer = ByteArrayOutputStream()
            check(original.compress(Bitmap.CompressFormat.PNG, 100, buffer))
            original.recycle()
            val normalized = normalizeImage(buffer.toByteArray())
            val decoded = decodeImage(normalized)
            check(decoded.width == 32 && decoded.height == 16 && decoded.getPixel(0, 0) == Color.MAGENTA)
            decoded.recycle()
            var rejected = false
            try { normalizeImage(byteArrayOf(1, 2, 3)) } catch (_: Exception) { rejected = true }
            check(rejected)
            val oversized = Bitmap.createBitmap(4097, 1, Bitmap.Config.ARGB_8888)
            buffer.reset(); check(oversized.compress(Bitmap.CompressFormat.PNG, 100, buffer)); oversized.recycle()
            rejected = false
            try { normalizeImage(buffer.toByteArray()) } catch (_: Exception) { rejected = true }
            check(rejected)
            result.putString("stream", "PASS image decoder: PNG roundtrip, pixel identity, corrupt and oversized rejection. This is not Compose visual acceptance.\n")
            finish(-1, result)
        } catch (error: Exception) {
            result.putString("stream", "FAIL image decoder: " + error.javaClass.simpleName + "\n")
            finish(0, result)
        }
    }
}

