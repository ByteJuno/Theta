package io.github.bytejuno.thetacore.agent.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpClientTest {
    private fun reply(request: JsonObject, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
    }
    private fun response(value: JsonObject) = MockResponse.Builder().addHeader("Content-Type", "application/json")
        .body(value.toString()).build()
    private fun error(request: JsonObject, code: Int, message: String = "synthetic credential must never escape") = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", request.getValue("id"))
        putJsonObject("error") { put("code", code); put("message", message) }
    }
    private fun tools(start: Int, count: Int) = buildJsonArray {
        repeat(count) { i -> add(buildJsonObject {
            put("name", "tool.${start + i}"); put("description", "Tool ${start + i}")
            putJsonObject("inputSchema") { put("type", "object") }
            putJsonObject("annotations") { put("readOnlyHint", true); put("idempotentHint", true) }
        }) }
    }

    @Test fun legacyNegotiationPaginationHeadersAndNo128ToolTruncation() = runBlocking {
        MockWebServer().use { server ->
            val pages = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    return when (body["method"]!!.jsonPrimitive.content) {
                        "server/discover" -> response(error(body, -32601))
                        "initialize" -> response(reply(body, buildJsonObject { put("protocolVersion", "2025-11-25") }))
                            .newBuilder().addHeader("Mcp-Session-Id", "test-session").build()
                        "notifications/initialized" -> MockResponse.Builder().code(202).build()
                        "tools/list" -> {
                            val page = pages.getAndIncrement()
                            if (page == 1) assertEquals("next", body["params"]!!.jsonObject["cursor"]!!.jsonPrimitive.content)
                            response(reply(body, buildJsonObject {
                                put("tools", tools(page * 128, if (page == 0) 128 else 7))
                                put("ttlMs", 3210)
                                if (page == 0) put("nextCursor", "next")
                            }))
                        }
                        else -> error("unexpected method")
                    }
                }
            }
            server.start()
            val client = McpClient(McpEndpoint(server.url("/mcp").toString(), "synthetic-token"))
            client.initialize()
            assertEquals("2025-11-25", client.version)
            val directory = client.listTools()
            assertEquals(135, directory.tools.size); assertEquals(3210L, directory.ttlMs)
            assertEquals(directory.tools.map { it.name }.sorted(), directory.tools.map { it.name })
            val requests = List(5) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            requests.forEach { assertEquals("Bearer synthetic-token", it.headers["Authorization"])
                assertEquals("application/json, text/event-stream", it.headers["Accept"]) }
            assertEquals("test-session", requests.last().headers["Mcp-Session-Id"])
            assertEquals("2025-11-25", requests.last().headers["MCP-Protocol-Version"])
        }
    }

    @Test fun modernDiscoveryAndSseProgressNotificationAreConsumedBeforeResult() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    if (body["method"]!!.jsonPrimitive.content == "server/discover")
                        return response(reply(body, buildJsonObject { putJsonArray("supportedVersions") { add("2026-07-28") } }))
                    assertEquals("2026-07-28", body["params"]!!.jsonObject["_meta"]!!.jsonObject[
                        "io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
                    val notification = """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"call","progress":1,"total":2,"message":"synthetic-token"}}"""
                    val changed = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
                    val result = reply(body, buildJsonObject { put("content", JsonArray(emptyList())); putJsonObject("structuredContent") { put("ok", true) } })
                    return MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                        .body(": heartbeat\r\n\r\ndata: $notification\n\ndata: $changed\n\ndata: $result\n\n").build()
                }
            }
            server.start()
            var changed = 0
            val progress = mutableListOf<Pair<String, Float?>>()
            val client = McpClient(McpEndpoint(server.url("/mcp").toString())) { changed++ }
            client.initialize()
            val result = client.call("tool", buildJsonObject {}, "call") { t, f -> progress += t to f }
            assertEquals(JsonPrimitive(true), result["structuredContent"]!!.jsonObject["ok"])
            assertEquals(listOf(MCP_PROGRESS_RUNNING to .5f), progress)
            assertEquals(1, changed); assertEquals(2, server.requestCount)
        }
    }

    @Test fun redirectsRetryAfterAndInterruptedSseNeverReplayOrLeakCredentials() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val client = McpClient(McpEndpoint(server.url("/mcp").toString(), "synthetic-token", "X-API-Key"))
            for (code in listOf(301, 302, 307, 308, 503)) {
                server.enqueue(MockResponse.Builder().code(code).addHeader("Location", other.url("/leak"))
                    .addHeader("Retry-After", "0").body("synthetic-token").build())
                try { client.call("x", buildJsonObject {}, "call") { _, _ -> }; fail() }
                catch (e: McpException) { assertNull(e.cause); assertFalse(e.toString().contains("synthetic-token")) }
            }
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("data: {\"jsonrpc\":\"2.0\"").build())
            try { client.call("write", buildJsonObject {}, "call") { _, _ -> }; fail() } catch (_: McpException) { }
            assertEquals(6, server.requestCount); assertEquals(0, other.requestCount)
            assertEquals("synthetic-token", server.takeRequest()!!.headers["X-API-Key"])
        } }
    }

    @Test fun errorsDistinguishUnknownToolFromOtherRpcFailureWithoutRemoteText() = runBlocking {
        MockWebServer().use { server ->
            val count = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    return response(if (count.getAndIncrement() == 0) error(body, -32602, "Unknown tool x") else error(body, -32603))
                }
            }
            server.start()
            val client = McpClient(McpEndpoint(server.url("/").toString()))
            try { client.call("x", buildJsonObject {}, "a") { _, _ -> }; fail() } catch (_: McpUnknownToolException) { }
            try { client.call("x", buildJsonObject {}, "b") { _, _ -> }; fail() }
            catch (e: McpException) { assertEquals(-32603, e.code); assertFalse(e.toString().contains("credential")) }
        }
    }

    @Test fun cancellationClosesCallAndSendsBestEffortNotificationWithOriginalRequestId() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("{}").build())
            server.enqueue(MockResponse.Builder().code(202).build())
            val client = McpClient(McpEndpoint(server.url("/").toString()))
            val job = launch { client.call("write", buildJsonObject {}, "internal-call") { _, _ -> } }
            val request = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            withTimeout(2500) { job.cancelAndJoin() }
            val cancelled = withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS)!! }
            val original = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            val notice = Json.parseToJsonElement(cancelled.body!!.utf8()).jsonObject
            assertEquals("notifications/cancelled", notice["method"]!!.jsonPrimitive.content)
            assertEquals(original["id"], notice["params"]!!.jsonObject["requestId"])
            assertFalse(notice.containsKey("id")); assertEquals(2, server.requestCount)
        }
    }

    @Test fun malformedCursorAndHeaderMappedToolsFailClosed() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    return response(reply(body, buildJsonObject {
                        put("tools", Json.parseToJsonElement("""[{"name":"unsafe","inputSchema":{"type":"object","properties":{"secret":{"type":"number","x-mcp-header":"Authorization"}}}}]"""))
                        put("nextCursor", "same")
                    }))
                }
            }
            server.start()
            try { McpClient(McpEndpoint(server.url("/").toString())).listTools(); fail() } catch (_: McpException) { }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun endpointRulesMatchChatAndSecretsCannotEnterUrlOrHeaders() {
        for (url in listOf("http://127.0.0.1:8000/mcp", "http://192.168.1.2/mcp", "http://100.64.1.2/mcp", "https://example.com/mcp"))
            validateMcpUrl(url)
        for (url in listOf("http://example.com/mcp", "https://user:token@example.com/mcp", "https://example.com/mcp?token=a", "https://example.com/#a")) {
            try { validateMcpUrl(url); fail(url) } catch (_: IllegalArgumentException) { }
        }
        try { McpEndpoint("https://example.com", "bad\nsecret"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun modernSubscriptionFilterAndHeaderParametersUseActualWireContract() = runBlocking {
        MockWebServer().use { server ->
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{"nested":{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}}}""").jsonObject
            val definitions = mcpHeaderParameters(schema)!!
            val parameters = mcpParameterHeaders(definitions, Json.parseToJsonElement("""{"nested":{"region":" 中文\n"}}""").jsonObject)
            var changes = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    val method = body["method"]!!.jsonPrimitive.content
                    assertEquals(method, request.headers["Mcp-Method"])
                    return when (method) {
                        "server/discover" -> response(reply(body, buildJsonObject { putJsonArray("supportedVersions") { add("2026-07-28") } }))
                        "subscriptions/listen" -> {
                            assertEquals(JsonPrimitive(true), body["params"]!!.jsonObject["notifications"]!!.jsonObject["toolsListChanged"])
                            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                                .body("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n").build()
                        }
                        else -> {
                            assertEquals(mcpHeaderValue(" 中文\n"), request.headers["Mcp-Param-Region"])
                            assertEquals(mcpHeaderValue("工具"), request.headers["Mcp-Name"])
                            assertNull(request.headers["Authorization"])
                            response(reply(body, buildJsonObject { put("content", JsonArray(emptyList())) }))
                        }
                    }
                }
            }
            server.start()
            val client = McpClient(McpEndpoint(server.url("/").toString())) { changes++ }
            client.initialize(); client.listen()
            client.call("工具", buildJsonObject {}, "c", parameters) { _, _ -> }
            assertEquals(1, changes)
        }
    }

    @Test fun invalidHeaderAnnotationsAreExcludedWhileValidValuesAreEncodedSafely() {
        for (schema in listOf(
            """{"type":"object","properties":{"x":{"type":"number","x-mcp-header":"X"}}}""",
            """{"type":"object","properties":{"x":{"type":"string","x-mcp-header":"X"},"y":{"type":"string","x-mcp-header":"x"}}}""",
            """{"type":"object","allOf":[{"properties":{"x":{"type":"string","x-mcp-header":"X"}}}]}""",
            """{"type":"object","properties":{"x":{"type":"string","x-mcp-header":"bad\r\n"}}}""")) {
            assertNull(mcpHeaderParameters(Json.parseToJsonElement(schema).jsonObject))
        }
        val parameters = listOf(McpHeaderParameter(listOf("n"), "N", "integer"))
        assertEquals(mapOf("Mcp-Param-N" to "42"), mcpParameterHeaders(parameters, buildJsonObject { put("n", 42) }))
        assertTrue(mcpParameterHeaders(parameters, buildJsonObject {}).isEmpty())
        assertThrows(McpException::class.java) { mcpParameterHeaders(parameters, buildJsonObject { put("n", Long.MAX_VALUE) }) }
    }

    @Test fun legacyHttpErrorsWithNullIdRetainStatusAndNegotiateWithoutLeakingBody() = runBlocking {
        for (status in listOf(400, 404)) MockWebServer().use { server ->
            val methods = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    val method = body.getValue("method").jsonPrimitive.content
                    methods += method
                    return when (method) {
                        "server/discover" -> MockResponse.Builder().code(status).addHeader("Content-Type", "application/json")
                            .body("""{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Missing session secret"}}""").build()
                        "initialize" -> response(reply(body, buildJsonObject { put("protocolVersion", "2025-11-25") }))
                        else -> MockResponse.Builder().code(202).build()
                    }
                }
            }
            server.start()
            val client = McpClient(McpEndpoint(server.url("/").toString()))
            client.initialize()
            assertEquals("2025-11-25", client.version)
            assertEquals(listOf("server/discover", "initialize", "notifications/initialized"), methods)
        }
    }

    private fun inputResult(state: String = "opaque-state") = buildJsonObject {
        put("resultType", "input_required"); put("requestState", state)
        putJsonObject("inputRequests") { putJsonObject("destination") {
            put("method", "elicitation/create")
            putJsonObject("params") {
                put("mode", "form"); put("message", "Destination")
                put("requestedSchema", Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""))
            }
        } }
    }

    @Test fun inputContinuationUsesNewIdSameCallAndOpaqueStateWithAcceptOrDecline() = runBlocking {
        for (accept in listOf(true, false)) MockWebServer().use { server ->
            val bodies = mutableListOf<JsonObject>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    bodies += body
                    return response(reply(body, if (bodies.size == 1) inputResult()
                        else buildJsonObject { put("resultType", "complete"); put("content", JsonArray(emptyList())) }))
                }
            }
            server.start()
            var prompts = 0
            McpClient(McpEndpoint(server.url("/").toString())).call("write", buildJsonObject { put("arg", 1) }, "internal",
                input = { prompt, _ -> prompts++; assertEquals("Destination", prompt)
                    if (accept) buildJsonObject { put("name", "chosen") } else null }) { _, _ -> }
            assertEquals(1, prompts); assertEquals(2, bodies.size)
            assertNotEquals(bodies[0]["id"], bodies[1]["id"])
            val params = bodies[1].getValue("params").jsonObject
            assertEquals(JsonPrimitive("opaque-state"), params["requestState"])
            assertEquals(bodies[0]["params"]!!.jsonObject["arguments"], params["arguments"])
            assertEquals(JsonPrimitive("internal"), params["_meta"]!!.jsonObject["progressToken"])
            val answer = params["inputResponses"]!!.jsonObject["destination"]!!.jsonObject
            assertEquals(JsonPrimitive(if (accept) "accept" else "decline"), answer["action"])
            if (!accept) assertFalse("content" in answer)
        }
    }

    @Test fun cancellingInputWaitNeverSendsContinuationAndCancelsOriginalRequest() = runBlocking {
        MockWebServer().use { server ->
            val entered = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    return if (body["method"] == JsonPrimitive("tools/call")) response(reply(body, inputResult()))
                    else MockResponse.Builder().code(202).build()
                }
            }
            server.start()
            val job = launch {
                McpClient(McpEndpoint(server.url("/").toString())).call("write", buildJsonObject {}, "call",
                    input = { _, _ -> entered.complete(Unit); awaitCancellation() }) { _, _ -> }
            }
            withTimeout(5000) { entered.await(); job.cancelAndJoin() }
            val original = Json.parseToJsonElement(server.takeRequest()!!.body!!.utf8()).jsonObject
            val cancellation = Json.parseToJsonElement(server.takeRequest()!!.body!!.utf8()).jsonObject
            assertEquals(JsonPrimitive("notifications/cancelled"), cancellation["method"])
            assertEquals(original["id"], cancellation["params"]!!.jsonObject["requestId"])
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun invalidInputAndFailedContinuationNeverReplay() = runBlocking {
        for (invalid in listOf(true, false)) MockWebServer().use { server ->
            val calls = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                    return if (calls.incrementAndGet() == 1) response(reply(body, inputResult()))
                    else MockResponse.Builder().code(503).body("secret").build()
                }
            }
            server.start()
            try {
                McpClient(McpEndpoint(server.url("/").toString())).call("write", buildJsonObject {}, "call",
                    input = { _, _ -> if (invalid) buildJsonObject { put("name", 123) } else buildJsonObject { put("name", "ok") } }) { _, _ -> }
                fail()
            } catch (_: McpException) { }
            assertEquals(if (invalid) 1 else 2, calls.get())
        }
    }
    @Test fun headerAnnotationsIgnoreLiteralDataButRejectUnreachableSchemaNodes() {
        val validator = io.github.bytejuno.thetacore.agent.execution.Schema202012Validator()
        val literal = """{"x-mcp-header":"literal-value","properties":{"x":{"x-mcp-header":"also-data"}}}"""
        for (keyword in listOf("const", "enum")) {
            val value = if (keyword == "enum") "[$literal]" else literal
            val schema = Json.parseToJsonElement("""{"type":"object","properties":{
                "payload":{"type":"object","$keyword":$value},
                "scope":{"type":"string","x-mcp-header":"Scope"}}}""").jsonObject
            assertEquals(listOf(McpHeaderParameter(listOf("scope"), "Scope", "string")), mcpHeaderParameters(schema))
            assertTrue(validator.supported(schema.toString()))
            assertTrue(validator.valid(schema.toString(), """{"payload":$literal,"scope":"selected"}"""))
            assertFalse(validator.valid(schema.toString(), """{"payload":{}}"""))
        }
        val annotation = """{"type":"string","x-mcp-header":"InvalidLocation"}"""
        for (keyword in listOf("additionalProperties", "items", "contains", "not", "if", "then", "else",
            "unevaluatedProperties", "unevaluatedItems", "propertyNames", "contentSchema")) {
            assertNull(keyword, mcpHeaderParameters(Json.parseToJsonElement("""{"$keyword":$annotation}""").jsonObject))
        }
        for (keyword in listOf("allOf", "anyOf", "oneOf", "prefixItems")) {
            assertNull(keyword, mcpHeaderParameters(Json.parseToJsonElement("""{"$keyword":[$annotation]}""").jsonObject))
        }
        for (keyword in listOf("dependentSchemas", "patternProperties", "\$defs", "definitions")) {
            assertNull(keyword, mcpHeaderParameters(Json.parseToJsonElement("""{"$keyword":{"x":$annotation}}""").jsonObject))
        }
    }

}
