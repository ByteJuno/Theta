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
import io.github.bytejuno.thetacore.plugin.PluginDataCatalog
import io.github.bytejuno.thetacore.plugin.PluginPromptData
import io.github.bytejuno.thetacore.plugin.installPromptBundle
import io.github.bytejuno.thetacore.plugin.promptData
import io.github.bytejuno.thetacore.plugin.readSkill
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
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
    private val samplePng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x02,
        0x08, 0x02, 0x00, 0x00, 0x00, 0xFD.toByte(), 0xD4.toByte(), 0x9A.toByte(), 0x73,
    )

    @Test
    fun readBoundedImageStreamEnforcesByteLimitAndNonEmptyStream() {
        val ok = readBoundedImageStream(ByteArrayInputStream(samplePng))
        assertArrayEquals(samplePng, ok)
        assertThrows(IllegalArgumentException::class.java) {
            readBoundedImageStream(ByteArrayInputStream(ByteArray(0)))
        }
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

            assertEquals(
                io.github.bytejuno.thetacore.uiText(R.string.chat_image_input_disabled),
                diagnosticReason("Image input disabled for active model"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun chatSessionOwnerSendsImagesWithDataPromptsAndFallsBackWhenDisabled(): Unit = runBlocking {
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
            val catalog = PluginDataCatalog(root, 1)
            val selected = installPromptBundle(catalog, mapOf(
                "skills/read/SKILL.md" to "---\ndescription: Read a document.\n---\nFull skill body stays on demand.",
                "personas/guide.json" to """{"id":"guide","name":"Guide","systemPrompt":"Explain in small steps."}"""))
            val preference = PersonaSettings(root.resolve("settings"))
            preference.select("test.prompts/guide")
            val service = HostToolBridgeRoot(budget).initialize(
                skillReader = { id, path -> catalog.readSkill(selected, id, path) },
            ) { agentStore }

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
                            systemPrompt = "User system prompt.",
                            compat = OpenAiChatConfig.Compat(supportsImageInput = allowImages),
                        ),
                        NoAuthCredentials,
                    )
                },
                targetForRun = sharedTargetFactory(transport),
                targetWithMediaForRun = sharedTargetWithMediaFactory(transport),
                promptSnapshot = {
                    val data = catalog.restore { fail(it) }.filter { it.active(false) }.map(catalog::promptData)
                    preference.resolve(PluginPromptData(data.flatMap { it.skills }, data.flatMap { it.personas }))
                },
                // Pure-JVM test injects a deterministic validator seam instead of Android ImageDecoder.
                userImageValidator = { bytes ->
                    require(bytes.contentEquals(samplePng)) { "Invalid test image" }
                    ValidatedUserImage("image/png", 2, 2, bytes)
                },
            )
            owner.open().join()
            val sessionId = requireNotNull(owner.state.value.sessionId)

            // 1. supportsImageInput=false -> rejected locally before network call, history untouched!
            owner.send("Check this", listOf(PendingUserImage(mimeType = "image/png", bytes = samplePng))).join()
            assertEquals(R.string.chat_image_input_disabled, owner.state.value.error)
            assertEquals(0, requestCount.get())
            assertTrue(owner.state.value.messages.isEmpty())
            assertEquals(0L, owner.state.value.acceptedMessages)

            // 2. Enable supportsImageInput -> invalid image rejected by validator before network or storage!
            allowImages = true
            owner.send("Bad img", listOf(PendingUserImage(mimeType = "image/png", bytes = "not-an-image".toByteArray()))).join()
            assertEquals(R.string.chat_image_invalid, owner.state.value.error)
            assertEquals(0, requestCount.get())
            assertTrue(owner.state.value.messages.isEmpty())

            // 3. Valid text + image sends full OpenAI image_url payload over loopback HTTP!
            owner.send("Explain this", listOf(PendingUserImage(mimeType = "image/png", bytes = samplePng))).join()
            withTimeout(10_000) {
                while (owner.state.value.active) delay(20)
            }
            assertEquals(R.string.chat_complete, owner.state.value.notice)
            assertEquals(1, requestCount.get())
            val reqJson = Json.parseToJsonElement(requireNotNull(lastBody.get())).jsonObject
            val messages = reqJson.getValue("messages").jsonArray.map { it.jsonObject }
            val system = messages.single { it.getValue("role").jsonPrimitive.content == "system" }
                .getValue("content").jsonPrimitive.content
            assertTrue(system.startsWith("Available skills"))
            assertTrue(system.contains("test.prompts/read"))
            assertTrue(system.contains("Explain in small steps."))
            assertTrue(system.endsWith("User system prompt."))
            assertFalse(system.contains("Full skill body"))
            assertFalse(owner.state.value.personaUnavailable)
            val content = messages.single { it.getValue("role").jsonPrimitive.content == "user" }
                .getValue("content").jsonArray
            assertEquals("Explain this", content[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals(
                "data:image/png;base64,${Base64.getEncoder().encodeToString(samplePng)}",
                content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
            )

            // Stored user image is readable for current session and removed on session deletion!
            val userMsg = owner.state.value.messages.first()
            val mediaPart = userMsg.parts.filterIsInstance<ContentPart.Media>().single()
            assertArrayEquals(samplePng, owner.userImage(sessionId, mediaPart.blob.id))

            // Disabling the data bundle falls back on the next image run without losing media support.
            catalog.setEnabled("test.prompts", false, selected)
            owner.send("Explain again", listOf(PendingUserImage(mimeType = "image/png", bytes = samplePng))).join()
            withTimeout(10_000) { while (owner.state.value.active) delay(20) }
            assertEquals(R.string.chat_complete, owner.state.value.notice)
            assertEquals(2, requestCount.get())
            assertTrue(owner.state.value.personaUnavailable)
            assertNull(preference.current().id)
            val fallbackMessages = Json.parseToJsonElement(requireNotNull(lastBody.get())).jsonObject
                .getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals("User system prompt.", fallbackMessages.single {
                it.getValue("role").jsonPrimitive.content == "system"
            }.getValue("content").jsonPrimitive.content)
            val fallbackContent = fallbackMessages.last {
                it.getValue("role").jsonPrimitive.content == "user"
            }.getValue("content").jsonArray
            assertEquals("Explain again", fallbackContent[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals(content[1], fallbackContent[1])

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
