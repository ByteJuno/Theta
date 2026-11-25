package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sun.net.httpserver.HttpServer
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.OkHttpModelTransport
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import io.github.bytejuno.thetacore.plugin.HostToolBridgeRoot
import io.github.bytejuno.thetacore.plugin.PluginExecutionBudget
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class UserImageChatTest {
    private fun pngHeader(width: Int, height: Int): ByteArray = ByteBuffer.allocate(33).apply {
        put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        putInt(13)
        put("IHDR".toByteArray(Charsets.US_ASCII))
        putInt(width)
        putInt(height)
        put(8)
        put(2)
        put(0)
        put(0)
        put(0)
        putInt(0)
    }.array()

    private fun jpegHeader(width: Int, height: Int): ByteArray = ByteBuffer.allocate(20).apply {
        put(0xFF.toByte()); put(0xD8.toByte()) // SOI
        put(0xFF.toByte()); put(0xC0.toByte()) // SOF0
        putShort(11)
        put(8)
        putShort(height.toShort())
        putShort(width.toShort())
        put(3)
        put(byteArrayOf(1, 0x11, 0))
    }.array()

    private fun webpVp8xHeader(width: Int, height: Int): ByteArray {
        val b = ByteArray(30)
        "RIFF".toByteArray().copyInto(b, 0)
        "WEBP".toByteArray().copyInto(b, 8)
        "VP8X".toByteArray().copyInto(b, 12)
        val w = width - 1
        val h = height - 1
        b[24] = (w and 0xFF).toByte(); b[25] = ((w ushr 8) and 0xFF).toByte(); b[26] = ((w ushr 16) and 0xFF).toByte()
        b[27] = (h and 0xFF).toByte(); b[28] = ((h ushr 8) and 0xFF).toByte(); b[29] = ((h ushr 16) and 0xFF).toByte()
        return b
    }

    @Test
    fun inspectUserImageAcceptsValidPngJpegWebpAndRejectsCorruptOversizedAndIllegalMime() {
        val png = inspectUserImage(pngHeader(800, 600))
        assertEquals("image/png", png.mimeType)
        assertEquals(800, png.width)
        assertEquals(600, png.height)

        val jpg = inspectUserImage(jpegHeader(1024, 768))
        assertEquals("image/jpeg", jpg.mimeType)
        assertEquals(1024, jpg.width)
        assertEquals(768, jpg.height)

        val webp = inspectUserImage(webpVp8xHeader(640, 480))
        assertEquals("image/webp", webp.mimeType)
        assertEquals(640, webp.width)
        assertEquals(480, webp.height)

        // Illegal MIME (GIF / SVG / plain text)
        assertThrows(IllegalArgumentException::class.java) { inspectUserImage("GIF89a\u0001\u0000\u0001\u0000".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { inspectUserImage("<svg></svg>".toByteArray()) }
        // Corrupt/truncated header
        assertThrows(IllegalArgumentException::class.java) { inspectUserImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
        // Oversized dimensions (> 4096px edge and > 4 MP total pixels)
        assertThrows(IllegalArgumentException::class.java) { inspectUserImage(pngHeader(4097, 100)) }
        assertThrows(IllegalArgumentException::class.java) { inspectUserImage(pngHeader(3000, 2000)) }
        // Oversized byte stream (> 8 MiB)
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedImageStream(ByteArrayInputStream(ByteArray(ImageLimits.BYTES + 1)))
        }
    }

    @Test
    fun providerSettingsPersistsSupportsImageInputAndDiagnosticMapsLocalRejection() {
        val dir = Files.createTempDirectory("theta-img-pref").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = OpenAiSettingsStore(dir, AesGcmSecretCipher("img-pref-aad") { key })
            val settings = OpenAiProviderSettings(
                CHAT_PROVIDER_ID,
                "https://api.example.com/v1",
                "text-only",
                compat = OpenAiChatConfig.Compat(supportsImageInput = false),
            )
            store.saveProfile(null, "TextOnly", settings, "sk-test")
            assertFalse(store.readSettings().compat.supportsImageInput)
            assertFalse(ProviderSettingsForm.loaded(store.readSettings()).supportsImageInput)

            assertEquals(io.github.bytejuno.thetacore.uiText(R.string.chat_image_input_disabled), diagnosticReason("Image input disabled for active model"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun chatSessionOwnerSendsImagesOverLoopbackBlocksWhenDisabledAndDeletesImagesWithSession(): Unit = runBlocking {
        val requestCount = AtomicInteger()
        val lastBody = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requestCount.incrementAndGet()
            lastBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val sse = "data: {\"id\":\"cmpl-app\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Nice screenshot!\"}}]}\n\n" +
                "data: {\"id\":\"cmpl-app\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
            val bytes = sse.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()

        val root = Files.createTempDirectory("theta-chat-user-img").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val budget = PluginExecutionBudget()
        var storeHandle: AndroidAgentStore? = null
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
            val dbDir = File(root, "db").apply { mkdirs() }
            val agentStore = AndroidAgentStore.open(dbDir, BundledSQLiteDriver())
            storeHandle = agentStore
            val service = HostToolBridgeRoot(budget).initialize { agentStore }

            var allowImages = false
            val transport = OkHttpModelTransport()
            val owner = ChatSessionOwner(
                scope = scope,
                service = { service },
                configuration = {
                    OpenAiRunSnapshot(
                        OpenAiProviderSettings(
                            CHAT_PROVIDER_ID,
                            baseUrl,
                            "vision-model",
                            compat = OpenAiChatConfig.Compat(supportsImageInput = allowImages),
                        ),
                        NoAuthCredentials,
                    )
                },
                targetForRun = sharedTargetFactory(transport),
                targetWithMediaForRun = sharedTargetWithMediaFactory(transport),
                userImageValidator = ::inspectUserImage,
            )
            owner.open().join()
            val sessionId = requireNotNull(owner.state.value.sessionId)
            val validPng = pngHeader(120, 80)

            // 1. supportsImageInput=false -> rejected locally before network call, history untouched!
            owner.send("Check this", listOf(PendingUserImage(mimeType = "image/png", bytes = validPng))).join()
            assertEquals(R.string.chat_image_input_disabled, owner.state.value.error)
            assertEquals(0, requestCount.get())
            assertTrue(owner.state.value.messages.isEmpty())
            assertEquals(0L, owner.state.value.acceptedMessages)

            // 2. Enable supportsImageInput -> invalid image rejected locally!
            allowImages = true
            owner.send("Bad img", listOf(PendingUserImage(mimeType = "image/png", bytes = "not-an-image".toByteArray()))).join()
            assertEquals(R.string.chat_image_invalid, owner.state.value.error)
            assertEquals(0, requestCount.get())

            // 3. Valid text + image sends full OpenAI image_url payload over loopback HTTP!
            owner.send("Explain this", listOf(PendingUserImage(mimeType = "image/png", bytes = validPng))).join()
            withTimeout(10_000) {
                while (owner.state.value.active) delay(20)
            }
            assertEquals(R.string.chat_complete, owner.state.value.notice)
            assertEquals(1, requestCount.get())
            val reqJson = Json.parseToJsonElement(requireNotNull(lastBody.get())).jsonObject
            val content = reqJson.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
            assertEquals("Explain this", content[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals(
                "data:image/png;base64,${Base64.getEncoder().encodeToString(validPng)}",
                content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
            )

            // Stored user image is readable for current session and removed on session deletion!
            val userMsg = owner.state.value.messages.first()
            val mediaPart = userMsg.parts.filterIsInstance<ContentPart.Media>().single()
            assertArrayEquals(validPng, owner.userImage(sessionId, mediaPart.blob.id))

            owner.deleteCurrentSession().join()
            assertNull(agentStore.privateImages.readUserImage(sessionId, mediaPart.blob.id))
        } finally {
            scope.cancel()
            budget.close()
            storeHandle?.close()
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
