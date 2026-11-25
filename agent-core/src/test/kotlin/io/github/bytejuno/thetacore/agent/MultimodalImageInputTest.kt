package io.github.bytejuno.thetacore.agent

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.sun.net.httpserver.HttpServer
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.protocol.MediaKind as ProtocolMediaKind
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.agent.transport.*
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MultimodalImageInputTest {
    private val samplePng: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x02,
        0x08, 0x02, 0x00, 0x00, 0x00, 0xFD.toByte(), 0xD4.toByte(), 0x9A.toByte(), 0x73,
    )

    @Test
    fun sqliteRunHistoryRoundTripsMultimodalUserMessagePreservesLegacyJsonAndDeletesImagesWithSession() {
        val dir = Files.createTempDirectory("theta-multimodal-history")
        val dbFile = dir.resolve(AgentDatabase.FILE_NAME)
        val sessionId = "session-mm-1"
        val imageId = UUID.randomUUID().toString()
        val sampleB64 = Base64.getEncoder().encodeToString(samplePng)
        try {
            // Verify legacy text-only StoredPart omits mediaKind/mediaBlob (preserving existing SHA-256 chains).
            val textOnly = HistoryCodec.encodeMessage(message(Role.USER, textPart("hello")))
            val textWriter = StringWriter()
            HistoryCodec.writeValue(textOnly, textWriter)
            assertEquals(HistoryCodec.json.encodeToString(textOnly), textWriter.toString())
            assertFalse(textWriter.toString().contains("mediaKind"))
            assertFalse(textWriter.toString().contains("mediaBlob"))

            // Verify multimodal StoredPart round-trips and writeValue matches kotlinx JSON byte-for-byte.
            val userMsg = message(
                Role.USER,
                textPart("What is in this screenshot?"),
                mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageId, "image/png", samplePng.size.toLong())),
            )
            val encoded = HistoryCodec.encodeMessage(userMsg)
            val mediaWriter = StringWriter()
            HistoryCodec.writeValue(encoded, mediaWriter)
            assertEquals(HistoryCodec.json.encodeToString(encoded), mediaWriter.toString())
            assertFalse("History JSON must NEVER embed base64 image data", mediaWriter.toString().contains(sampleB64))
            val decoded = HistoryCodec.decodeMessage(encoded)
            assertEquals(userMsg, decoded)

            // Persist in SQLite + PrivateImageStore, close, reopen, verify paging & image retrieval.
            AgentDatabase.open(dbFile, BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                images.putUserImage(sessionId, imageId, samplePng)
                SqliteRunHistory.create(db, sessionId).use { history ->
                    history.append(0L, listOf(userMsg))
                }
            }

            AgentDatabase.open(dbFile, BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                assertArrayEquals(samplePng, images.readUserImage(sessionId, imageId))
                SqliteRunHistory.open(db, sessionId).use { history ->
                    val page = history.page(null, 20)
                    assertEquals(1, page.messages.size)
                    val restored = page.messages.single()
                    assertEquals(Role.USER, restored.role)
                    assertEquals(2, restored.parts.size)
                    val media = restored.parts[1] as ContentPart.Media
                    assertEquals(ProtocolMediaKind.IMAGE, media.kind)
                    assertEquals(imageId, media.blob.id)
                    assertEquals("image/png", media.blob.mimeType)
                    assertEquals(samplePng.size.toLong(), media.blob.sizeBytes)
                }
                // Delete session -> user image MUST be deleted atomically too.
                val store = SqliteInvocationStore.open(db)
                store.bindCoordinator().invoke(sessionId)
                assertNull(images.readUserImage(sessionId, imageId))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun openAiChatCodecEncodesImageUrlBlocksAccountsBudgetAndNeverLeaksBase64InDiagnostics(): Unit = runBlocking {
        val imageId = UUID.randomUUID().toString()
        val expectedB64 = Base64.getEncoder().encodeToString(samplePng)
        val config = OpenAiChatConfig(
            "chat",
            "https://api.example.com/v1",
            compat = OpenAiChatConfig.Compat(explicitCacheControl = true, supportsImageInput = true),
        )
        val codec = OpenAiChatCodec(config, MediaContentReader { blob ->
            if (blob.id == imageId) samplePng else null
        })

        val media = mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageId, "image/png", samplePng.size.toLong()))
        val req = chatRequest("gpt-vision") {
            messages = listOf(message(Role.USER, textPart("Describe this"), media, cacheBreakpoint = true))
        }
        val body = codec.encode(req)
        val parsed = Json.parseToJsonElement(body).jsonObject
        val content = parsed.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("Describe this", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject.getValue("type").jsonPrimitive.content)
        val url = content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        assertEquals("data:image/png;base64,$expectedB64", url)
        assertEquals("ephemeral", content[1].jsonObject.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)

        // Conservative budget estimate charges image attachment without calling mediaReader.
        val noReadCodec = OpenAiChatCodec(config, MediaContentReader { error("Budget must not read image bytes") })
        val textOnlyReq = chatRequest("gpt-vision") { messages = listOf(message(Role.USER, textPart("Describe this"))) }
        val estimatedWithImage = noReadCodec.estimateRequestBytes(req)
        val estimatedTextOnly = noReadCodec.estimateRequestBytes(textOnlyReq)
        assertTrue(estimatedWithImage >= estimatedTextOnly + OpenAiChatCodec.IMAGE_BUDGET_BASE_BYTES)

        // Provider error diagnostics NEVER leak base64 even if server echoes it in error body.
        val leakingTransport = ModelTransport { _, _, _ ->
            flow {
                emit(TransportEvent.Headers(400, "application/json", null))
                emit(TransportEvent.Bytes("""{"error":{"message":"Rejected $expectedB64","param":"$expectedB64"}}""".toByteArray()))
            }
        }
        val provider = OpenAiChatProvider(config, leakingTransport, MediaContentReader { samplePng })
        val events = provider.chat(req, ApiKeyCredentials("sk-test")).toList()
        val failed = events.filterIsInstance<ModelEvent.Failed>().single()
        assertFalse(failed.error.message.contains(expectedB64))
        assertNull(failed.error.param)
    }

    @Test
    fun supportsImageInputFalseBlocksLocallyBeforeAnyNetworkCall(): Unit = runBlocking {
        val transportCalls = AtomicInteger()
        val transport = ModelTransport { _, _, _ ->
            transportCalls.incrementAndGet()
            flow { emit(TransportEvent.Headers(200, "text/event-stream", null)) }
        }
        val config = OpenAiChatConfig(
            "chat",
            "https://api.example.com/v1",
            compat = OpenAiChatConfig.Compat(supportsImageInput = false),
        )
        val provider = OpenAiChatProvider(config, transport, MediaContentReader { samplePng })
        val imageId = UUID.randomUUID().toString()
        val history = MemoryRunHistory(
            listOf(
                message(
                    Role.USER,
                    textPart("Look at this"),
                    mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageId, "image/png", samplePng.size.toLong())),
                ),
            ),
        )
        val request = chatRequest("text-only-model")
        val loop = AgentRunLoop(
            RunIdentity("chain-1", "session-1", "run-1"),
            ModelTarget("chat", provider, ApiKeyCredentials("sk-test")),
            request,
            history,
            emptyToolExecutor(),
            MemoryRunJournal(),
        )
        val result = loop.run()
        assertEquals(RunState.FAILED, result.state)
        assertEquals("Image input disabled for active model", result.reason)
        assertEquals("Network transport must NEVER be called when supportsImageInput=false", 0, transportCalls.get())
    }

    @Test
    fun loopbackHttpServerReceivesMultimodalImageUrlAndCompletesAgentRunLoop(): Unit = runBlocking {
        val capturedBody = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            capturedBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val sse = "data: {\"id\":\"cmpl-mm\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"I see a 2x2 PNG image.\"}}]}\n\n" +
                "data: {\"id\":\"cmpl-mm\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
            val out = sse.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, out.size.toLong())
            exchange.responseBody.use { it.write(out) }
        }
        server.start()
        val dir = Files.createTempDirectory("theta-mm-loopback")
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
            val sessionId = "session-loopback"
            val imageId = UUID.randomUUID().toString()
            val expectedDataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(samplePng)}"
            AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                images.putUserImage(sessionId, imageId, samplePng)
                SqliteRunHistory.create(db, sessionId).use { history ->
                    history.append(
                        0L,
                        listOf(
                            message(
                                Role.USER,
                                textPart("What is this?"),
                                mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageId, "image/png", samplePng.size.toLong())),
                            ),
                        ),
                    )
                    val snapshot = OpenAiRunSnapshot(
                        OpenAiProviderSettings("chat", baseUrl, "vision-model"),
                        NoAuthCredentials,
                    )
                    val target = snapshot.target(
                        OkHttpModelTransport(),
                        MediaContentReader { blob -> images.readUserImage(sessionId, blob.id) },
                    )
                    val loop = AgentRunLoop(
                        RunIdentity("chain-lb", sessionId, "run-lb"),
                        target,
                        snapshot.request(),
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                    )
                    val result = loop.run()
                    assertEquals(RunState.COMPLETED, result.state)
                    val rawReq = requireNotNull(capturedBody.get())
                    val reqJson = Json.parseToJsonElement(rawReq).jsonObject
                    val userContent = reqJson.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
                    assertEquals("What is this?", userContent[0].jsonObject.getValue("text").jsonPrimitive.content)
                    assertEquals(
                        expectedDataUrl,
                        userContent[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content,
                    )
                    val msgs = history.snapshot().messages
                    assertEquals(2, msgs.size)
                    assertEquals("I see a 2x2 PNG image.", (msgs[1].parts.single() as ContentPart.Text).text)
                }
            }
        } finally {
            server.stop(0)
            dir.toFile().deleteRecursively()
        }
    }

    private fun emptyToolExecutor(): LedgerToolExecutor {
        val gate = DefinitionGate()
        val store = object : InvocationStore {
            override fun bindCoordinator(): ((String) -> Unit)? = null
            override fun records(): List<InvocationRecord> = emptyList()
            override fun save(record: InvocationRecord, expectedRevision: Long?) = Unit
            override fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) = Unit
            override fun readBlob(ref: io.github.bytejuno.thetacore.agent.ledger.BlobRef, offset: Long, limit: Int): ByteArray = ByteArray(0)
            override fun close() = Unit
        }
        val ledger = InvocationLedger(store, gate, Schema202012Validator())
        ledger.recoverColdStart("test-epoch")
        val bridge = object : ToolExecutionBridge {
            override fun submit(ticket: ExecutionTicket) = error("Unexpected tool call")
            override suspend fun awaitTerminal(callId: String) = error("Unexpected tool call")
            override fun requestCancel(callId: String) = Unit
        }
        return LedgerToolExecutor(ledger, gate, emptyList(), bridge)
    }
}
