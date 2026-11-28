package io.github.bytejuno.thetacore.screen

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScreenToolIntegrationTest {
    private suspend fun fixture(tools: List<Tool>, block: suspend (HostToolExecutionService) -> Unit) {
        val directory = Files.createTempDirectory("theta-screen-tools")
        val budget = PluginExecutionBudget()
        try {
            SqliteInvocationStore.open(directory.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { store ->
                val service = HostToolBridgeRoot(budget).initialize(
                    screenTools = tools, policy = ApprovalPolicy(mode = { ApprovalMode.FULL_AUTO })) { store }
                block(service)
            }
        } finally { budget.close(); directory.toFile().deleteRecursively() }
    }
    private fun input(definition: FrozenDefinition, name: String, args: String, session: String = "session") = InvocationInput(
        BindingValue.copyOf(callBinding("test", name, null, 0, "wire")), "chain-$name", session, "run", name,
        0, args, definition, false, definition.initialRisk)

    @Test fun missingScreenshotRetainsStorageReasonAcrossReopenWithoutChangingUnknownEvidence() {
        val directory = Files.createTempDirectory("screen-media-recovery")
        val file = directory.resolve(AgentDatabase.FILE_NAME)
        val driver = androidx.sqlite.driver.bundled.BundledSQLiteDriver()
        val gate = DefinitionGate()
        val d = FrozenDefinition("host/capture", "capture", "capture", "capture", "{\"type\":\"object\"}",
            Risk.READ, Source.FIRST_PARTY, "host", "1", 1, false, "screen", false, null)
        val i = input(d, "capture", "{}")
        gate.install(d); gate.freezeRun("run", listOf(d))
        val bytes = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        var original: InvocationRecord? = null
        var ref: io.github.bytejuno.thetacore.agent.ledger.BlobRef? = null
        var request: ChatRequest? = null
        try {
            SqliteInvocationStore.open(file, driver).use { store ->
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("initial")
                ledger.recordInvocation(i)
                val ready = ledger.validateAndPrepare(i.binding.callId) as Mutation.Applied
                val ticket = (ledger.claimExecution(ready.record.callId, ready.record.revision) as Mutation.Applied).ticket!!
                val output = ToolOutputNormalizer.normalize(listOf(OutputPart.Media(
                    io.github.bytejuno.thetacore.agent.ledger.MediaKind.IMAGE, "image/gif", bytes)), null, false,
                    Effect.UNKNOWN, null, ArgumentsValidator { _, _ -> true }, null)
                ledger.recordToolOutput(ticket.callId, ticket.attemptId, Status.SUCCEEDED, output, Effect.UNKNOWN)
                ledger.recordActualExit(ticket.callId, ticket.attemptId, "complete")
                original = ledger.get(ticket.callId)
                ref = output.stored.media.single()
                request = chatRequest("vision") { messages = listOf(message(Role.USER, textPart("Look")),
                    message(Role.ASSISTANT, toolCallPart(i.binding, d.modelName, "{}")),
                    ledger.resultsForStep("session", "run", "capture", listOf(ticket.callId))!!) }
                assertArrayEquals(bytes, ScreenModelMedia(ledger, "session", MediaContentReader { null }).reader
                    .read(request!!.messages.last().mediaParts().single().blob))
            }
            driver.open(file.toString()).use { db ->
                db.prepare("DELETE FROM content_chunks WHERE hash=?").use { statement ->
                    statement.bindText(1, ref!!.contentHash); statement.step()
                }
            }
            repeat(2) { pass ->
                SqliteInvocationStore.open(file, driver).use { store ->
                    val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                    ledger.recoverColdStart("reopen-$pass")
                    val reader = ScreenModelMedia(ledger, "session", MediaContentReader { null }).reader
                    val codec = io.github.bytejuno.thetacore.agent.provider.anthropic.AnthropicCodec(
                        io.github.bytejuno.thetacore.agent.provider.anthropic.AnthropicConfig("test", "https://example.invalid/v1"), reader)
                    val prepared = codec.prepareToolMedia(request!!)
                    val result = prepared.messages.last().parts.single() as ContentPart.ToolResult
                    assertTrue(result.text.contains("Result content is unavailable: missing or corrupt."))
                    assertTrue(result.text.contains(ref!!.id))
                    assertEquals(i.binding.callId, result.callId)
                    assertTrue(prepared.messages.last().mediaParts().isEmpty())
                    assertEquals(1, request!!.messages.last().mediaParts().size)
                    assertTrue(codec.encode(request!!).contains("missing or corrupt"))
                    assertEquals(original, ledger.get(i.binding.callId))
                    assertEquals(Effect.UNKNOWN, ledger.get(i.binding.callId)!!.result!!.effect)
                    assertEquals("screen", ledger.get(i.binding.callId)!!.isolation!!.resource)
                }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun cancelledAndTimedOutWritesStayUnknownAndRetainScreenUntilActualExit() = runBlocking {
        for (timeout in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val exit = CompletableDeferred<Unit>()
            val backend = object : ScreenControlBackend {
                override val ready = true
                override suspend fun execute(action: ScreenAction): ScreenReply {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { cancelled.complete(Unit); exit.await() }
                    }
                }
            }
            val control = ScreenController(backend, backend, { "no screen" })
            val screen = ScreenTool("screen_tap", control)
            // Exercise real execution budget timeout without spending the production 30-second budget.
            val tool = object : Tool by screen { override val spec = screen.spec.copy(timeoutMs = if (timeout) 300L else 30_000L) }
            fixture(listOf(tool)) { service ->
                val d = service.catalog.freeze("run").values.single { it.toolKey == "host/screen_tap" }
                val first = input(d, "first", """{"x":12,"y":23}""")
                try {
                    service.prepare(first); service.dispatch(first.binding.callId)
                    withTimeout(5000) { entered.await() }
                    if (!timeout) service.cancel(first.binding.callId)
                    withTimeout(5000) { cancelled.await() }
                    withTimeout(5000) { while (service.ledger.get(first.binding.callId)!!.result == null) delay(5) }
                    val record = service.ledger.get(first.binding.callId)!!
                    assertEquals(if (timeout) Status.TIMED_OUT else Status.CANCELLED, record.state)
                    assertEquals(Effect.UNKNOWN, record.result!!.effect)
                    assertEquals(InstanceState.UNCONFIRMED, record.instance!!.state)
                    val second = input(d, "second", """{"x":12,"y":23}""", "other-session")
                    service.prepare(second)
                    assertTrue(service.dispatch(second.binding.callId) is Mutation.Blocked)
                } finally {
                    exit.complete(Unit)
                    withTimeout(5000) { service.awaitObservedExit(first.binding.callId) }
                }
                assertNotEquals(InstanceState.UNCONFIRMED, service.ledger.get(first.binding.callId)!!.instance!!.state)
                assertEquals(Effect.UNKNOWN, service.ledger.get(first.binding.callId)!!.result!!.effect)
            }
        }
    }

    @Test fun screenshotMediaFlowsThroughHostBlobStoreAndCannotCrossSessions() = runBlocking {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 11, 22, 33, 0xff.toByte(), 0xd9.toByte())
        val backend = object : ScreenControlBackend {
            override val ready = true
            override suspend fun execute(action: ScreenAction) = ScreenReply(ToolResult(effect = ToolEffect.NONE)
                .withContent(listOf(ToolContent.Media(ToolMediaKind.IMAGE, "image/jpeg", bytes))))
        }
        fixture(listOf(ScreenTool("screen_capture", ScreenController(backend, backend, { "missing" })))) { service ->
            val d = service.catalog.freeze("run").values.single { it.toolKey == "host/screen_capture" }
            val i = input(d, "capture", "{}")
            service.prepare(i); service.dispatch(i.binding.callId)
            withTimeout(5000) { service.awaitObservedExit(i.binding.callId) }
            val record = service.ledger.get(i.binding.callId)!!
            assertEquals(Status.SUCCEEDED, record.state)
            val ref = record.result!!.output!!.media.single()
            assertArrayEquals(bytes, service.ledger.readSessionBlob("session", ref.id, 0).second)
            assertThrows(BlobReadException::class.java) { service.ledger.readSessionBlob("another-session", ref.id, 0) }
            val response = service.ledger.resultsForStep("session", "run", "capture", listOf(i.binding.callId))!!
            val request = chatRequest("vision") { messages = listOf(
                message(Role.USER, textPart("Look at the screen")),
                message(Role.ASSISTANT, toolCallPart(i.binding, d.modelName, "{}")), response) }
            val projection = ScreenModelMedia(service.ledger, "session", MediaContentReader { null })
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), request.messages.map { it.role })
            val image = (response.parts.single() as ContentPart.ToolResult).content.filterIsInstance<ContentPart.Media>().single()
            assertArrayEquals(bytes, projection.reader.read(image.blob))
            val codec = io.github.bytejuno.thetacore.agent.provider.anthropic.AnthropicCodec(
                io.github.bytejuno.thetacore.agent.provider.anthropic.AnthropicConfig("test", "https://example.invalid/v1"), projection.reader)
            val encoded = codec.encode(request)
            val last = Json.parseToJsonElement(encoded).jsonObject["messages"]!!.jsonArray.last().jsonObject
            val toolResult = last["content"]!!.jsonArray.single().jsonObject
            assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), toolResult["content"]!!.jsonArray.last()
                .jsonObject["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
            val textOnly = toolMediaRequest(request) { false }
            assertEquals(request.messages.size, textOnly.messages.size)
            assertTrue((textOnly.messages.last().parts.single() as ContentPart.ToolResult).text.contains("Tool media unavailable"))
            assertTrue(codec.estimateRequestBytes(request) > codec.estimateRequestBytes(textOnly))
            val foreign = ScreenModelMedia(service.ledger, "another-session", MediaContentReader { null })
            assertNull(foreign.reader.read(image.blob))
        }
    }
}
