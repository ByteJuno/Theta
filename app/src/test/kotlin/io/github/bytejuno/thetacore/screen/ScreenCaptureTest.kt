package io.github.bytejuno.thetacore.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenCaptureTest {
    @Test fun captureScalesAndReturnsRealEncodedBoundedImageWithOriginalCoordinates() {
        org.junit.Assume.assumeFalse("Robolectric native graphics is unavailable on Windows; Linux reviewer runs this test",
            System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val bitmap = Bitmap.createBitmap(2400, 3600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff137c9a.toInt())
            val result = encodeScreenCapture(bitmap)
            val media = result.content.single() as ToolContent.Media
            assertEquals(ToolEffect.NONE, result.effect); assertEquals("image/jpeg", media.mimeType)
            assertTrue(media.bytes.size <= SCREEN_IMAGE_BYTES)
            val decoded = BitmapFactory.decodeByteArray(media.bytes, 0, media.bytes.size)
            try {
                assertEquals(1600, decoded.height); assertTrue(decoded.width <= 1600)
                assertEquals(3600, result.structured!!.jsonObject["originalHeight"]!!.jsonPrimitive.int)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle() }
        assertThrows(IllegalArgumentException::class.java) { decodeScreenCapture(byteArrayOf(1, 2, 3)) }
    }
}
