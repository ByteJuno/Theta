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

    @Test
    fun contextTooLongAndPriorCompactionWithImagesConsumeActualImageContentOrPauseSafely(): Unit = runBlocking {
        val dir = Files.createTempDirectory("theta-mm-compaction")
        val expectedDataUrl = "data:image/png;base64,${Base64.getEncoder().encodeToString(samplePng)}"
        try {
            // Part A: CONTEXT_TOO_LONG -> summary model ACTUALLY receives image_url -> CONTINUATION -> resume completes,
            // while durable SQLite history retains ONLY BlobRef (never base64).
            val sessionA = "session-mm-ctl"
            val imageIdA = UUID.randomUUID().toString()
            val summaryCapturedBodies = mutableListOf<String>()
            val summaryTransport = ModelTransport { _, body, _ ->
                summaryCapturedBodies += body
                flow {
                    emit(TransportEvent.Headers(200, "text/event-stream", null))
                    val sse = "data: {\"id\":\"sum-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Image shows a 2x2 PNG chart.\"}}]}\n\n" +
                        "data: {\"id\":\"sum-1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                }
            }
            var mainCallsA = 0
            val mainTransportA = ModelTransport { _, _, _ ->
                mainCallsA++
                flow {
                    if (mainCallsA == 1) {
                        emit(TransportEvent.Headers(400, "application/json", null))
                        emit(TransportEvent.Bytes("""{"error":{"code":"context_length_exceeded","message":"context too long"}}""".toByteArray()))
                    } else {
                        emit(TransportEvent.Headers(200, "text/event-stream", null))
                        val sse = "data: {\"id\":\"main-2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Resumed from image summary.\"}}]}\n\n" +
                            "data: {\"id\":\"main-2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                            "data: [DONE]\n\n"
                        emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                    }
                }
            }
            AgentDatabase.open(dir.resolve("a.db"), BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                images.putUserImage(sessionA, imageIdA, samplePng)
                val reader = MediaContentReader { blob -> images.readUserImage(sessionA, blob.id) }
                val visionCfg = OpenAiChatConfig("chat", "https://api.example.com/v1", compat = OpenAiChatConfig.Compat(supportsImageInput = true))
                val codec = OpenAiChatCodec(visionCfg, reader)
                val mainProvider = OpenAiChatProvider(visionCfg, mainTransportA, reader)
                val sumProvider = OpenAiChatProvider(visionCfg, summaryTransport, reader)
                val sumBudget = ContextBudget({ r -> 256L + codec.estimateRequestBytes(r) }, { 200_000L })
                val sumModel = SummaryModel(
                    ModelTarget("chat", sumProvider, ApiKeyCredentials("sk-test")),
                    chatRequest("vision-summary") {
                        toolChoice = ToolChoice.None
                        maxOutputTokens = 1024
                    },
                    sumBudget,
                )
                SqliteRunHistory.create(db, sessionA).use { history ->
                    history.append(
                        0L,
                        listOf(
                            message(
                                Role.USER,
                                textPart("Analyze this chart"),
                                mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageIdA, "image/png", samplePng.size.toLong())),
                            ),
                        ),
                    )
                    val first = AgentRunLoop(
                        RunIdentity("chain-a", sessionA, "run-a-1"),
                        ModelTarget("chat", mainProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-main") { maxOutputTokens = 1024 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        contextPolicy = ContextPolicy(goal = "Analyze chart", summaryModel = sumModel),
                    ).run()
                    assertEquals(RunState.PAUSED, first.state)
                    assertEquals("Summary model must have been called once", 1, summaryCapturedBodies.size)
                    val sumJson = Json.parseToJsonElement(summaryCapturedBodies.single()).jsonObject
                    val sumContent = sumJson.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray
                    assertTrue(
                        "Summary request must include real image_url content, not just storage reference",
                        sumContent.any {
                            it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" &&
                                it.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content == expectedDataUrl
                        },
                    )
                    val cp = requireNotNull(history.checkpointInfo())
                    assertTrue("Continuation must be offered after image-consuming summary", cp.canContinue)
                    assertEquals("CONTINUATION", cp.kind)

                    val resumed = AgentRunLoop(
                        requireNotNull(cp.nextIdentity),
                        ModelTarget("chat", mainProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-main") { maxOutputTokens = 1024 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        contextPolicy = ContextPolicy(goal = "Analyze chart", summaryModel = sumModel, resumeCheckpointId = cp.id),
                    ).run()
                    assertEquals(RunState.COMPLETED, resumed.state)
                    val persisted = history.snapshot().messages
                    assertEquals(2, persisted.size)
                    val encodedOrig = HistoryCodec.json.encodeToString(HistoryCodec.encodeMessage(persisted[0]))
                    assertFalse("Durable history must keep only BlobRef, never base64", encodedOrig.contains(Base64.getEncoder().encodeToString(samplePng)))
                }
            }

            // Part B: If SummaryModel cannot process images (supportsImageInput=false), fail closed to PAUSED
            // WITHOUT calling summary network and WITHOUT offering a reference-only CONTINUATION.
            val sessionB = "session-mm-nosum"
            val imageIdB = UUID.randomUUID().toString()
            val textOnlySummaryCalls = AtomicInteger()
            val textOnlySummaryTransport = ModelTransport { _, _, _ ->
                textOnlySummaryCalls.incrementAndGet()
                flow { emit(TransportEvent.Headers(200, "text/event-stream", null)) }
            }
            val ctlTransportB = ModelTransport { _, _, _ ->
                flow {
                    emit(TransportEvent.Headers(400, "application/json", null))
                    emit(TransportEvent.Bytes("""{"error":{"code":"context_length_exceeded","message":"context too long"}}""".toByteArray()))
                }
            }
            AgentDatabase.open(dir.resolve("b.db"), BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                images.putUserImage(sessionB, imageIdB, samplePng)
                val reader = MediaContentReader { blob -> images.readUserImage(sessionB, blob.id) }
                val visionCfg = OpenAiChatConfig("chat", "https://api.example.com/v1", compat = OpenAiChatConfig.Compat(supportsImageInput = true))
                val noImgCfg = OpenAiChatConfig("chat", "https://api.example.com/v1", compat = OpenAiChatConfig.Compat(supportsImageInput = false))
                val mainProvider = OpenAiChatProvider(visionCfg, ctlTransportB, reader)
                val noImgSummaryProvider = OpenAiChatProvider(noImgCfg, textOnlySummaryTransport, reader)
                val sumBudget = ContextBudget({ 500L }, { 200_000L })
                val sumModel = SummaryModel(
                    ModelTarget("chat", noImgSummaryProvider, ApiKeyCredentials("sk-test")),
                    chatRequest("text-only-summary") {
                        toolChoice = ToolChoice.None
                        maxOutputTokens = 1024
                    },
                    sumBudget,
                )
                SqliteRunHistory.create(db, sessionB).use { history ->
                    history.append(
                        0L,
                        listOf(
                            message(
                                Role.USER,
                                textPart("Analyze image"),
                                mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageIdB, "image/png", samplePng.size.toLong())),
                            ),
                        ),
                    )
                    val res = AgentRunLoop(
                        RunIdentity("chain-b", sessionB, "run-b-1"),
                        ModelTarget("chat", mainProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-main") { maxOutputTokens = 1024 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        contextPolicy = ContextPolicy(goal = "Analyze image", summaryModel = sumModel),
                    ).run()
                    assertEquals(RunState.PAUSED, res.state)
                    assertEquals("Summary model without image support must never be called", 0, textOnlySummaryCalls.get())
                    val cp = requireNotNull(history.checkpointInfo())
                    assertFalse("Must not offer reference-only CONTINUATION", cp.canContinue)
                    assertEquals("PAUSED", cp.kind)
                }
            }

            // Part C: Old-history compaction (compactPrior) with image in prior messages consumes real image content
            // and compacts into a VIEW so the new turn succeeds within budget.
            val sessionC = "session-mm-prior"
            val imageIdC = UUID.randomUUID().toString()
            val priorSumBodies = mutableListOf<String>()
            val priorSumTransport = ModelTransport { _, body, _ ->
                priorSumBodies += body
                flow {
                    emit(TransportEvent.Headers(200, "text/event-stream", null))
                    val sse = "data: {\"id\":\"sum-p\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Earlier user shared a 2x2 PNG.\"}}]}\n\n" +
                        "data: {\"id\":\"sum-p\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                }
            }
            val priorMainBodies = mutableListOf<String>()
            val priorMainTransport = ModelTransport { _, body, _ ->
                priorMainBodies += body
                flow {
                    emit(TransportEvent.Headers(200, "text/event-stream", null))
                    val sse = "data: {\"id\":\"main-p\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Follow-up answered.\"}}]}\n\n" +
                        "data: {\"id\":\"main-p\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                }
            }
            AgentDatabase.open(dir.resolve("c.db"), BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                images.putUserImage(sessionC, imageIdC, samplePng)
                val reader = MediaContentReader { blob -> images.readUserImage(sessionC, blob.id) }
                val visionCfg = OpenAiChatConfig("chat", "https://api.example.com/v1", compat = OpenAiChatConfig.Compat(supportsImageInput = true))
                val codec = OpenAiChatCodec(visionCfg, reader)
                val mainProvider = OpenAiChatProvider(visionCfg, priorMainTransport, reader)
                val sumProvider = OpenAiChatProvider(visionCfg, priorSumTransport, reader)
                // Main window fits text-only compacted view (~1500 bytes + 256 reserve) but NOT raw prior image (+4096 bytes).
                val mainBudget = ContextBudget({ r -> codec.estimateRequestBytes(r) }, { 3_500L })
                val sumBudget = ContextBudget({ r -> codec.estimateRequestBytes(r) }, { 50_000L })
                val sumModel = SummaryModel(
                    ModelTarget("chat", sumProvider, ApiKeyCredentials("sk-test")),
                    chatRequest("vision-summary") {
                        toolChoice = ToolChoice.None
                        maxOutputTokens = 256
                    },
                    sumBudget,
                )
                SqliteRunHistory.create(db, sessionC).use { history ->
                    history.append(
                        0L,
                        listOf(
                            message(
                                Role.USER,
                                textPart("Old image turn"),
                                mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageIdC, "image/png", samplePng.size.toLong())),
                            ),
                            message(Role.ASSISTANT, textPart("I saw it.")),
                            message(Role.USER, textPart("Now give me a concise follow-up")),
                        ),
                    )
                    val res = AgentRunLoop(
                        RunIdentity("chain-c", sessionC, "run-c-1"),
                        ModelTarget("chat", mainProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-main") { maxOutputTokens = 256 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        budget = mainBudget,
                        contextPolicy = ContextPolicy(goal = "Follow-up", priorMessageCount = 2, summaryModel = sumModel),
                    ).run()
                    assertEquals(RunState.COMPLETED, res.state)
                    assertEquals(1, priorSumBodies.size)
                    assertTrue(
                        "Prior compaction summary request must carry real image_url",
                        priorSumBodies.single().contains(expectedDataUrl),
                    )
                    assertEquals(1, priorMainBodies.size)
                    assertTrue(priorMainBodies.single().contains("Earlier user shared a 2x2 PNG."))
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun disabledImageInputRejectsBeforeSummaryAndBudgetReadmissionWithoutAdmittedOrReconciliation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("theta-mm-no-admit")
        try {
            val sessionId = "session-mm-budget"
            val imageId = UUID.randomUUID().toString()
            val mainCalls = AtomicInteger()
            val summaryCalls = AtomicInteger()
            val mainTransport = ModelTransport { _, _, _ ->
                mainCalls.incrementAndGet()
                flow {
                    emit(TransportEvent.Headers(200, "text/event-stream", null))
                    val sse = "data: {\"id\":\"m-ok\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Budget recovered with image.\"}}]}\n\n" +
                        "data: {\"id\":\"m-ok\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                    emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                }
            }
            val summaryTransport = ModelTransport { _, _, _ ->
                summaryCalls.incrementAndGet()
                flow { emit(TransportEvent.Headers(200, "text/event-stream", null)) }
            }
            AgentDatabase.open(dir.resolve("budget.db"), BundledSQLiteDriver()).use { db ->
                val images = PrivateImageStore(db)
                images.putUserImage(sessionId, imageId, samplePng)
                val reader = MediaContentReader { blob -> images.readUserImage(sessionId, blob.id) }
                val visionCfg = OpenAiChatConfig("chat", "https://api.example.com/v1", compat = OpenAiChatConfig.Compat(supportsImageInput = true))
                val disabledCfg = OpenAiChatConfig("chat", "https://api.example.com/v1", compat = OpenAiChatConfig.Compat(supportsImageInput = false))
                val visionProvider = OpenAiChatProvider(visionCfg, mainTransport, reader)
                val disabledProvider = OpenAiChatProvider(disabledCfg, mainTransport, reader)
                val summaryProvider = OpenAiChatProvider(visionCfg, summaryTransport, reader)
                val codec = OpenAiChatCodec(visionCfg, reader)

                SqliteRunHistory.create(db, sessionId).use { history ->
                    history.append(
                        0L,
                        listOf(
                            message(
                                Role.USER,
                                textPart("Inspect screenshot"),
                                mediaPart(ProtocolMediaKind.IMAGE, blobRef(imageId, "image/png", samplePng.size.toLong())),
                            ),
                        ),
                    )
                    // 1. Ordinary run with supportsImageInput=false, exceeded budget, and summaryModel configured:
                    //    must reject locally BEFORE calling summaryModel or main model, leaving NO ADMITTED/SUMMARY_RESERVED.
                    val tightBudget = ContextBudget({ r -> codec.estimateRequestBytes(r) }, { 1_000L })
                    val sumModel = SummaryModel(
                        ModelTarget("chat", summaryProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-summary") {
                            toolChoice = ToolChoice.None
                            maxOutputTokens = 256
                        },
                        ContextBudget({ r -> codec.estimateRequestBytes(r) }, { 50_000L }),
                    )
                    val rejectedOrdinary = AgentRunLoop(
                        RunIdentity("chain-1", sessionId, "run-reject-ordinary"),
                        ModelTarget("chat", disabledProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("text-only") { maxOutputTokens = 256 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        budget = tightBudget,
                        contextPolicy = ContextPolicy(goal = "Inspect screenshot", summaryModel = sumModel),
                    ).run()
                    assertEquals(RunState.FAILED, rejectedOrdinary.state)
                    assertEquals("Image input disabled for active model", rejectedOrdinary.reason)
                    assertEquals(0, mainCalls.get())
                    assertEquals(0, summaryCalls.get())
                    assertNull("Definite local rejection must not persist ADMITTED/SUMMARY_RESERVED checkpoint", history.checkpointInfo())
                    assertNull(history.retryReconciliation())

                    // 2. Now pause genuinely on budget (supportsImageInput=true, tight window, no summaryModel).
                    val pausedBudget = AgentRunLoop(
                        RunIdentity("chain-1", sessionId, "run-pause-budget"),
                        ModelTarget("chat", visionProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-model") { maxOutputTokens = 256 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        budget = tightBudget,
                        contextPolicy = ContextPolicy(goal = "Inspect screenshot"),
                    ).run()
                    assertEquals(RunState.PAUSED, pausedBudget.state)
                    val budgetCp = requireNotNull(history.checkpointInfo())
                    assertEquals("PAUSED", budgetCp.kind)
                    assertEquals("BUDGET", budgetCp.pauseClass)

                    // 3. Attempt budget readmission with a wider window BUT supportsImageInput=false:
                    //    must fail locally BEFORE consuming readmission (NO ADMITTED, NO reconciliation, 0 network calls).
                    val wideBudget = ContextBudget({ r -> codec.estimateRequestBytes(r) }, { 50_000L })
                    val rejectedReadmit = AgentRunLoop(
                        RunIdentity("chain-1", sessionId, "run-readmit-disabled"),
                        ModelTarget("chat", disabledProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-model") { maxOutputTokens = 256 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        budget = wideBudget,
                        contextPolicy = ContextPolicy(
                            goal = "Inspect screenshot",
                            summaryModel = sumModel,
                            readmitBudgetCheckpointId = budgetCp.id,
                        ),
                    ).run()
                    assertEquals(RunState.FAILED, rejectedReadmit.state)
                    assertEquals("Image input disabled for active model", rejectedReadmit.reason)
                    assertEquals(0, mainCalls.get())
                    assertEquals(0, summaryCalls.get())
                    val afterRejectCp = requireNotNull(history.checkpointInfo())
                    assertEquals("BUDGET checkpoint must remain intact, not ADMITTED", "BUDGET", afterRejectCp.pauseClass)
                    assertEquals(budgetCp.id, afterRejectCp.id)
                    assertNull("No reconciliation must be required after definite local rejection", history.retryReconciliation())

                    // 4. Subsequent retry with supportsImageInput=true and the SAME budget checkpoint ID succeeds cleanly!
                    val recovered = AgentRunLoop(
                        RunIdentity("chain-1", sessionId, "run-readmit-enabled"),
                        ModelTarget("chat", visionProvider, ApiKeyCredentials("sk-test")),
                        chatRequest("vision-model") { maxOutputTokens = 256 },
                        history,
                        emptyToolExecutor(),
                        StoredRunJournal(history),
                        budget = wideBudget,
                        contextPolicy = ContextPolicy(
                            goal = "Inspect screenshot",
                            readmitBudgetCheckpointId = budgetCp.id,
                        ),
                    ).run()
                    assertEquals(RunState.COMPLETED, recovered.state)
                    assertEquals(1, mainCalls.get())
                    assertEquals(0, summaryCalls.get())
                    assertNull(history.retryReconciliation())
                }
            }
        } finally {
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
