package io.github.bytejuno.thetacore.mcp

import com.sun.net.httpserver.HttpServer
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.mcp.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.storage.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator

class McpIntegrationTest {
    private val schema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
    private class MemoryStore : InvocationStore {
        override fun bindCoordinator(): ((String) -> Unit)? = null
        val data = linkedMapOf<String, InvocationRecord>()
        val blobs = mutableMapOf<String, ByteArray>()
        override fun records() = data.values.toList()
        override fun save(record: InvocationRecord, expectedRevision: Long?) {
            record.validate(); check(data[record.callId]?.revision == expectedRevision); data[record.callId] = record
        }
        override fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) {
            blobs.forEach { this.blobs[it.ref.id] = it.bytes() }; save(record, expectedRevision)
        }
        override fun readBlob(ref: BlobRef, offset: Long, limit: Int) = blobs.getValue(ref.id).let {
            it.copyOfRange(offset.toInt(), minOf(it.size, offset.toInt() + limit))
        }
        override fun close() {}
    }
    private inner class Fixture : AutoCloseable {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val budget = PluginExecutionBudget(4, 4)
        val gate = DefinitionGate()
        val store = MemoryStore()
        val validator = Schema202012Validator()
        val ledger = InvocationLedger(store, gate, validator)
        val catalog = FrozenToolCatalog(gate, budget, owner, validator)
        val service = HostToolExecutionService(budget, owner, catalog, ledger, validator)
        init { ledger.recoverColdStart(UUID.randomUUID().toString()) }
        suspend fun register(client: McpClient, description: String = "Original", run: String = "run"): FrozenDefinition {
            catalog.replaceMcp("connection", "v1", listOf(McpTool(client, McpToolDefinition("remote", description, schema, null))))
            catalog.registerHost("host/blob_read", "1", io.github.bytejuno.thetacore.plugin.tools.HostBlobReadTool(ledger))
            return catalog.freeze(run).values.first { it.source == Source.MCP }
        }
        suspend fun prepare(definition: FrozenDefinition, index: Int = 0): InvocationRecord {
            val input = InvocationInput(BindingValue("test", "request", null, index, null, UUID.randomUUID().toString()),
                "chain", "session", "run", "step", index, "{}", definition, false, Risk.WRITE)
            return (service.prepare(input) as Mutation.Applied).record
        }
        suspend fun approve(r: InvocationRecord): InvocationRecord =
            (service.decide(r.callId, r.pending!!.id, Decision.ALLOW_ONCE) as Mutation.Applied).record
        override fun close() { owner.cancel(); budget.close() }
    }
    private class Server(val result: (JsonObject) -> JsonObject?) : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}/mcp"
        @Volatile var calls = 0
        init {
            server.createContext("/mcp") { exchange ->
                if (exchange.requestMethod == "GET") { exchange.sendResponseHeaders(405, -1); exchange.close() }
                else {
                    val request = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
                    calls++
                    val response = result(request)
                    if (response == null) {
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        val truncated = "data: {".toByteArray()
                        exchange.sendResponseHeaders(200, truncated.size.toLong()); exchange.responseBody.write(truncated)
                    } else {
                        val bytes = buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", request["id"] ?: JsonNull)
                            response.forEach { (k,v) -> put(k,v) }
                        }.toString().toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                    }
                    exchange.close()
                }
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }
    private suspend fun await(check: () -> Boolean) = withTimeout(5000) { while (!check()) delay(5) }

    @Test fun refreshAfterApprovalBlocksThenUnchangedRestoresAndChangedRejectsOldSnapshot() = runBlocking {
        val client = McpClient(McpEndpoint("http://127.0.0.1:9/mcp"))
        Fixture().use { f ->
            val d = f.register(client)
            assertEquals(Source.MCP, d.source); assertEquals(Risk.WRITE, d.declaredRisk); assertFalse(d.idempotent)
            val r = f.approve(f.prepare(d))
            f.catalog.beginMcpRefresh("connection")
            assertTrue(f.service.dispatch(r.callId) is Mutation.Blocked)
            assertNull(f.ledger.get(r.callId)!!.instance)
            // Failed refresh performs no install; a second dispatch stays blocked.
            assertTrue(f.service.dispatch(r.callId) is Mutation.Blocked)
            f.catalog.replaceMcp("connection", "v1", listOf(McpTool(client, McpToolDefinition("remote", "Original", schema, null))))
            assertTrue(f.catalog.current(d) != null)
            assertEquals(Status.READY, (f.ledger.validateAndPrepare(r.callId) as Mutation.AlreadyApplied).record.state)
            f.catalog.beginMcpRefresh("connection")
            f.catalog.replaceMcp("connection", "v1", listOf(McpTool(client, McpToolDefinition("remote", "Changed", schema, null))))
            f.service.dispatch(r.callId)
            val rejected = f.ledger.get(r.callId)!!
            assertEquals(Status.REJECTED, rejected.state); assertEquals(Effect.NONE, rejected.result!!.effect)
            assertNull(rejected.instance)
        }
    }

    @Test fun changedConnectionCannotReuseLongTermAuthorizationForIdenticalTool() = runBlocking {
        Fixture().use { f ->
            val client = McpClient(McpEndpoint("http://127.0.0.1:9/mcp"))
            val d = f.register(client)
            val r = f.prepare(d)
            f.service.decide(r.callId, r.pending!!.id, Decision.ALLOW_ALWAYS)
            f.service.cancel(r.callId)
            f.catalog.replaceMcp("connection", "new-destination-revision", listOf(McpTool(client, McpToolDefinition("remote", "Original", schema, null))))
            val next = f.catalog.freeze("new-run").values.first { it.source == Source.MCP }
            val input = r.input.copy(binding = r.input.binding.copy(callId = UUID.randomUUID().toString()),
                runId = "new-run", stepId = "new-step", definition = next)
            val pending = (f.service.prepare(input) as Mutation.Applied).record
            assertEquals(Status.WAITING_APPROVAL, pending.state)
        }
    }

    @Test fun managerRefreshFailureRemainsBlockedThenExplicitRetryRestores() = runBlocking {
        var failList = false
        Server { request -> buildJsonObject {
            when (request["method"]!!.jsonPrimitive.content) {
                "server/discover" -> putJsonObject("result") { putJsonArray("supportedVersions") { add("2026-07-28") } }
                "tools/list" -> if (failList) putJsonObject("error") { put("code", -32603); put("message", "failure") }
                    else putJsonObject("result") { put("ttlMs", 60_000); putJsonArray("tools") { add(buildJsonObject {
                        put("name", "remote"); put("description", "Original"); put("inputSchema", schema)
                    }) } }
                else -> putJsonObject("result") { }
            }
        } }.use { server ->
            val dir = Files.createTempDirectory("theta-mcp-refresh").toFile()
            try {
                val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val store = McpConnectionStore(dir, AesGcmSecretCipher("mcp") { key })
                store.save(McpConnection("connection", "Local", server.url, true))
                Fixture().use { f ->
                    val manager = McpManager(store, f.owner); manager.attach(f.catalog)
                    await { manager.connections.value.single().state == "ready" }
                    val d = f.catalog.freeze("run").values.single()
                    val r = f.approve(f.prepare(d))
                    failList = true; manager.refresh("connection")
                    await { manager.connections.value.single().state == "failed" }
                    assertTrue(f.service.dispatch(r.callId) is Mutation.Blocked)
                    failList = false; manager.refresh("connection")
                    await { manager.connections.value.single().state == "ready" }
                    assertNotNull(f.catalog.current(d))
                    manager.delete("connection")
                    f.service.dispatch(r.callId)
                    assertEquals(Status.REJECTED, f.ledger.get(r.callId)!!.state)
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun expiredTtlAndDisableAreCheckedAtAtomicSubmission() = runBlocking {
        Fixture().use { f ->
            val d = f.register(McpClient(McpEndpoint("http://127.0.0.1:9/mcp")))
            val r = f.approve(f.prepare(d))
            var expired = false
            f.gate.setFreshness("mcp/connection", f.catalog.mcpKeys("connection"), 0) { expired = true }
            assertTrue(f.service.dispatch(r.callId) is Mutation.Blocked); assertTrue(expired)
            assertNull(f.ledger.get(r.callId)!!.instance)
            f.catalog.revokeMcp("connection")
            f.service.dispatch(r.callId)
            assertEquals(Status.REJECTED, f.ledger.get(r.callId)!!.state)
        }
    }

    @Test fun fullStructuredResourcesAndMediaRoundTripThroughActualLedgerBlobs() = runBlocking {
        val longText = "z".repeat(50_000)
        Server { buildJsonObject { putJsonObject("result") {
            putJsonObject("structuredContent") { put("full", longText) }
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", "visible text") })
                for (type in listOf("image", "audio")) add(buildJsonObject {
                    put("type", type); put("mimeType", if (type == "image") "image/png" else "audio/wav"); put("data", "AQID")
                })
                add(buildJsonObject { put("type", "resource_link"); put("uri", "https://example.test/resource"); put("name", "reference") })
                add(buildJsonObject { put("type", "resource"); putJsonObject("resource") { put("uri", "file:///remote"); put("text", longText) } })
            }
        } } }.use { server -> Fixture().use { f ->
            val d = f.register(McpClient(McpEndpoint(server.url)))
            val r = f.approve(f.prepare(d))
            f.service.dispatch(r.callId)
            f.service.runBridge().awaitTerminal(r.callId)
            val done = f.ledger.get(r.callId)!!
            assertEquals(Status.SUCCEEDED, done.state); assertEquals(Effect.APPLIED, done.result!!.effect)
            assertTrue(done.result!!.text.length < 32768)
            val rawId = done.result!!.output!!.raw.id
            val raw = f.store.blobs.getValue(rawId).toString(Charsets.UTF_8)
            assertTrue(raw.contains(longText)); assertTrue(raw.contains("https://example.test/resource"))
            assertEquals(2, done.result!!.output!!.media.size)
            done.result!!.output!!.media.forEach { assertArrayEquals(byteArrayOf(1,2,3), f.store.blobs.getValue(it.id)) }
            assertTrue(f.ledger.readSessionBlob("session", rawId, 0).second.isNotEmpty())
            assertThrows(BlobReadException::class.java) { f.ledger.readSessionBlob("other", rawId, 0) }
            Unit
        } }
    }

    @Test fun remoteUnknownToolIsRejectedAndDisconnectOrRpcErrorStayUnknownWithoutReplay() = runBlocking {
        for (mode in listOf("missing", "rpc", "isError", "disconnect")) {
            Server {
                if (mode == "disconnect") null else buildJsonObject {
                    if (mode == "isError") putJsonObject("result") { put("isError", true); put("content", JsonArray(emptyList())) }
                    else putJsonObject("error") { put("code", if (mode == "missing") -32602 else -32603)
                        put("message", if (mode == "missing") "Unknown tool remote" else "secret must not escape") }
                }
            }.use { server -> Fixture().use { f ->
                val d = f.register(McpClient(McpEndpoint(server.url)))
                val r = f.approve(f.prepare(d)); f.service.dispatch(r.callId)
                f.service.runBridge().awaitTerminal(r.callId)
                val done = f.ledger.get(r.callId)!!
                assertEquals(if (mode == "missing") Status.REJECTED else Status.FAILED, done.state)
                assertEquals(if (mode == "missing") Effect.NONE else Effect.UNKNOWN, done.result!!.effect)
                assertEquals(InstanceState.EXIT_CONFIRMED, done.instance!!.state)
                assertFalse(done.result!!.text.contains("secret")); assertEquals(1, server.calls)
                done.validate()
            } }
        }
    }

    @Test fun encryptedStorePreservesRecordsOnFailureAndManagerRevokesImmediately() = runBlocking {
        val dir = Files.createTempDirectory("theta-mcp-store").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipher = AesGcmSecretCipher("mcp-test") { key }
            val store = McpConnectionStore(dir, cipher)
            store.save(McpConnection("connection", "Local", "http://127.0.0.1:9/mcp", false, "private-test-token"))
            assertFalse(dir.resolve("mcp-connections.bin").readBytes().toString(Charsets.ISO_8859_1).contains("private-test-token"))
            assertEquals("private-test-token", store.read().single().token)
            val before = dir.resolve("mcp-connections.bin").readBytes()
            val bad = McpConnectionStore(dir, object : SecretCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray = error("secret")
                override fun decrypt(ciphertext: ByteArray): ByteArray = error("secret")
            })
            assertThrows(McpSettingsException::class.java) { bad.save(McpConnection("new", "New", "http://localhost/", false)) }
            assertArrayEquals(before, dir.resolve("mcp-connections.bin").readBytes())
            Fixture().use { f ->
                val d = f.register(McpClient(McpEndpoint("http://127.0.0.1:9/mcp")))
                val manager = McpManager(store, f.owner)
                manager.attach(f.catalog)
                manager.enable("connection", false)
                assertTrue(f.catalog.mcpKeys("connection").isEmpty()); assertNull(f.catalog.current(d))
                manager.delete("connection")
                assertTrue(store.read().isEmpty()); assertTrue(manager.connections.value.isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun explicitReplacementRecoversInvalidKeyWhileReadsAndTransientErrorsPreserveRecord() = runBlocking {
        val dir = Files.createTempDirectory("theta-mcp-recovery").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var failure: Exception? = null
            var deletes = 0
            var key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val keys = object : SecretKeySource {
                override fun load(create: Boolean): javax.crypto.SecretKey { failure?.let { throw it }; return key }
                override fun delete() { deletes++; failure = null; key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
            }
            val classify: (Throwable) -> KeyFailureKind = { when (it) {
                is java.io.IOException -> KeyFailureKind.NON_RECOVERABLE
                is UnusableSecretKeyException -> KeyFailureKind.DEFINITIVE_INVALIDATION
                else -> KeyFailureKind.UNKNOWN
            } }
            val cipher = KeyStoreSecretCipher("mcp", keys, allowKeyRecovery = true, classifyNode = classify)
            val store = McpConnectionStore(dir, cipher) { isDefinitiveKeyInvalidation(it, classify) }
            store.save(McpConnection("old", "Original", "http://localhost/mcp", false, "old-token"))
            val original = dir.resolve("mcp-connections.bin").readBytes()
            val manager = McpManager(store, owner)
            failure = java.io.IOException("transient")
            manager.reload()
            assertTrue(manager.loadFailed.value); assertFalse(manager.replacementAllowed.value)
            assertThrows(McpSettingsException::class.java) { store.replaceUnreadable(McpConnection("new", "New", "http://localhost/mcp", false)) }
            assertArrayEquals(original, dir.resolve("mcp-connections.bin").readBytes()); assertEquals(0, deletes)
            failure = UnusableSecretKeyException("invalid")
            manager.reload()
            assertTrue(manager.replacementAllowed.value)
            try { manager.save("old", "Original", "http://localhost/mcp", false, "replacement", "Authorization"); fail() }
            catch (_: McpSettingsException) { }
            assertArrayEquals(original, dir.resolve("mcp-connections.bin").readBytes()); assertEquals(0, deletes)
            manager.replaceUnreadable("Recovered", "http://localhost/mcp", "new-token", "Authorization")
            assertEquals(1, deletes); assertFalse(manager.loadFailed.value); assertFalse(manager.replacementAllowed.value)
            val recovered = store.read().single()
            assertEquals("new-token", recovered.token); assertFalse(recovered.enabled)
            assertNotEquals("old", recovered.id)
            assertFalse(dir.resolve("mcp-connections.bin").readBytes().toString(Charsets.ISO_8859_1).contains("new-token"))
        } finally { owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun expiredSessionBlocksAdmissionAndExplicitRefreshRenegotiatesWithoutToolReplay() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var expired = false
        var initialization = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "GET") { exchange.sendResponseHeaders(405, -1); exchange.close() }
            else {
                val request = Json.parseToJsonElement(exchange.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                val session = exchange.requestHeaders.getFirst("Mcp-Session-Id").orEmpty()
                methods += "$method:$session"
                val invalid = expired && session == "session-1" && method in setOf("tools/list", "tools/call")
                val body = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", request["id"] ?: JsonNull)
                    when {
                        method == "server/discover" -> putJsonObject("error") { put("code", -32601) }
                        invalid -> putJsonObject("error") { put("code", -32001) }
                        else -> putJsonObject("result") {
                            when (method) {
                                "initialize" -> {
                                    initialization++; exchange.responseHeaders.add("Mcp-Session-Id", "session-$initialization")
                                    put("protocolVersion", "2025-11-25")
                                }
                                "tools/list" -> {
                                    put("ttlMs", 60_000); putJsonArray("tools") { add(buildJsonObject {
                                        put("name", "remote"); put("description", "Original"); put("inputSchema", schema)
                                    }) }
                                }
                                else -> put("content", JsonArray(emptyList()))
                            }
                        }
                    }
                }.toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (invalid) 404 else 200, body.size.toLong())
                exchange.responseBody.write(body); exchange.close()
            }
        }
        server.start()
        val dir = Files.createTempDirectory("theta-mcp-session").toFile()
        try {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = McpConnectionStore(dir, AesGcmSecretCipher("mcp") { key })
            store.save(McpConnection("connection", "Local", "http://127.0.0.1:${server.address.port}/mcp", true))
            Fixture().use { f ->
                val manager = McpManager(store, f.owner); manager.attach(f.catalog)
                await { manager.connections.value.single().state == "ready" }
                val definition = f.catalog.freeze("run").values.single()
                val failed = f.approve(f.prepare(definition))
                val pending = f.approve(f.prepare(definition, 1))
                expired = true
                f.service.dispatch(failed.callId)
                f.service.runBridge().awaitTerminal(failed.callId)
                assertEquals(Effect.UNKNOWN, f.ledger.get(failed.callId)!!.result!!.effect)
                await { manager.connections.value.single().state == "failed" }
                assertTrue(f.service.dispatch(pending.callId) is Mutation.Blocked)
                assertEquals(1, initialization)
                manager.refresh("connection")
                await { manager.connections.value.single().state == "ready" }
                assertEquals(2, initialization); assertTrue(methods.contains("tools/list:session-2"))
                assertEquals(1, methods.count { it.startsWith("tools/call:") })
                assertNotNull(f.catalog.current(definition))
                // Session restoration cannot acknowledge the earlier UNKNOWN side effect.
                assertEquals(Effect.UNKNOWN, f.ledger.get(failed.callId)!!.result!!.effect)
                assertEquals(1, methods.count { it.startsWith("tools/call:") })
            }
        } finally { server.stop(0); dir.deleteRecursively() }
    }

    private fun requiredInput() = buildJsonObject {
        put("resultType", "input_required"); put("requestState", "opaque")
        putJsonObject("inputRequests") { putJsonObject("form") {
            put("method", "elicitation/create"); putJsonObject("params") {
                put("message", "Confirm name")
                put("requestedSchema", Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""))
            }
        } }
    }

    @Test fun realHostInputAcceptDeclineAndCancellationAreScopedToLiveCall() = runBlocking {
        for (mode in listOf("accept", "decline", "cancel")) {
            val requests = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            Server { request ->
                requests += request
                buildJsonObject { put("result", if (request["params"]?.jsonObject?.containsKey("inputResponses") == true)
                    buildJsonObject { put("content", JsonArray(emptyList())) } else requiredInput()) }
            }.use { server -> Fixture().use { f ->
                val definition = f.register(McpClient(McpEndpoint(server.url)))
                val record = f.approve(f.prepare(definition)); f.service.dispatch(record.callId)
                await { f.service.activeInputs(listOf(record.callId)).isNotEmpty() }
                val input = f.service.activeInputs(listOf(record.callId)).getValue(record.callId)
                assertFalse(f.service.respondInput("other-session", record.callId, input.id, null))
                assertFalse(f.service.respondInput("session", record.callId, "stale", null))
                assertFalse(f.service.respondInput("session", record.callId, input.id, buildJsonObject { put("name", 42) }))
                if (mode == "cancel") f.service.cancel(record.callId)
                else assertTrue(f.service.respondInput("session", record.callId, input.id,
                    if (mode == "accept") buildJsonObject { put("name", "chosen") } else null))
                f.service.runBridge().awaitTerminal(record.callId)
                await { f.service.activeInputs(listOf(record.callId)).isEmpty() }
                assertFalse(f.service.respondInput("session", record.callId, input.id, null))
                val calls = requests.filter { it["method"] == JsonPrimitive("tools/call") }
                assertEquals(if (mode == "cancel") 1 else 2, calls.size)
                assertEquals(if (mode == "cancel") Status.CANCELLED else Status.SUCCEEDED, f.ledger.get(record.callId)!!.state)
                if (mode != "cancel") assertEquals(JsonPrimitive(if (mode == "accept") "accept" else "decline"),
                    calls[1]["params"]!!.jsonObject["inputResponses"]!!.jsonObject["form"]!!.jsonObject["action"])
            } }
        }
    }

    @Test fun unattendedInputDeclinesWithoutPromptingOrBlindReplay() = runBlocking {
        var calls = 0
        var prompts = 0
        Server { request ->
            calls++
            buildJsonObject { put("result", if (calls == 1) requiredInput() else {
                assertEquals(JsonPrimitive("decline"), request["params"]!!.jsonObject["inputResponses"]!!.jsonObject["form"]!!.jsonObject["action"])
                buildJsonObject { put("isError", true); put("content", JsonArray(emptyList())) }
            }) }
        }.use { server ->
            val env = ToolEnv("run", "session", "call", true, { _, _ -> }, onInput = { _, _ -> prompts++; buildJsonObject {} })
            val result = McpTool(McpClient(McpEndpoint(server.url)), McpToolDefinition("remote", "Remote", schema, null))
                .call(buildJsonObject {}, env)
            assertTrue(result.isError); assertEquals(0, prompts); assertEquals(2, calls)
        }
    }
}
