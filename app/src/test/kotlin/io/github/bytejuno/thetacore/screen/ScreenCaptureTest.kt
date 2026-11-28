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
    @Test fun rootCaptureDecodesValidPngAndDownsamplesLargePng() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeFalse("Native bitmap encoding requires Linux", System.getProperty("os.name").startsWith("Windows"))
        val context = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        for ((width, height) in listOf(48 to 64, 3600 to 4800)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val png = try {
                bitmap.eraseColor(0xff137c9a.toInt())
                java.io.ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            } finally { bitmap.recycle() }
            val backend = RootScreenBackend(context, io.github.bytejuno.thetacore.root.RootBridge(
                io.github.bytejuno.thetacore.root.RootBackend { command ->
                    assertTrue(command.contains("screencap -p"))
                    java.io.File(context.noBackupFilesDir, "screen-calls").listFiles()!!.single().resolve("data").writeBytes(png)
                    object : io.github.bytejuno.thetacore.root.RootProcess {
                        override val exitCode = 0
                        override val rootConfirmed = true
                        override val commandStarted = true
                        override val actualExit = true
                        override val outcome = io.github.bytejuno.thetacore.root.RootOutcome.COMPLETED
                        override val outputTruncated = false
                        override fun requestStop() = Unit
                        override fun output() = "" to ""
                        override fun close() = Unit
                    }
                }), authorized = { true })
            val reply = backend.execute(ScreenAction("screen_capture", buildJsonObject {}))
            assertFalse(reply.unavailableBeforeStart); assertFalse(reply.result.isError)
            val media = reply.result.content.single() as ToolContent.Media
            assertTrue(media.bytes.size <= SCREEN_IMAGE_BYTES)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(media.bytes, 0, media.bytes.size))
            try {
                val expected = screenImageSize(width, height)
                assertEquals(expected.first, decoded.width); assertEquals(expected.second, decoded.height)
                assertEquals(width, reply.result.structured!!.jsonObject["originalWidth"]!!.jsonPrimitive.int)
                assertEquals(height, reply.result.structured!!.jsonObject["originalHeight"]!!.jsonPrimitive.int)
            } finally { decoded.recycle() }
            assertTrue(java.io.File(context.noBackupFilesDir, "screen-calls").listFiles()!!.isEmpty())
        }
    }

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
