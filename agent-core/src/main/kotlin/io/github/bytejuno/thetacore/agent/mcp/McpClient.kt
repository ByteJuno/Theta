package io.github.bytejuno.thetacore.agent.mcp

import io.github.bytejuno.thetacore.agent.provider.openai.SseDecoder
import io.github.bytejuno.thetacore.agent.provider.openai.isPrivateCleartextHost
import io.github.bytejuno.thetacore.agent.transport.SharedHttpClient
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Messages never retain an HTTP body, endpoint, credentials, or remote exception text. */
class McpException(val code: Int? = null, val httpStatus: Int? = null) : IOException("MCP request failed")
class McpUnknownToolException : IOException("MCP tool is unavailable")
const val MCP_PROGRESS_RUNNING = "theta:mcp:running"

fun validateMcpUrl(url: String): String {
    val u = url.toHttpUrl()
    require(u.username.isEmpty() && u.password.isEmpty() && u.fragment == null && u.query == null)
    require(u.scheme == "https" || isPrivateCleartextHost(u.host))
    return u.toString()
}

class McpEndpoint(val url: String, val token: String = "", val header: String = "Authorization") {
    init {
        validateMcpUrl(url)
        require(header in setOf("Authorization", "X-API-Key"))
        require(token.length <= 8192 && token.all { it in ' '..'~' })
    }
    override fun toString() = "McpEndpoint(redacted)"
}

data class McpToolDefinition(val name: String, val description: String,
    val inputSchema: JsonObject, val outputSchema: JsonObject?, val headers: List<McpHeaderParameter> = emptyList())
data class McpDirectory(val tools: List<McpToolDefinition>, val ttlMs: Long)

/** A single connection/session. Requests are never replayed, including after a 404 or disconnect. */
class McpClient(private val endpoint: McpEndpoint, private val sessionInvalid: () -> Unit = {},
    private val changed: () -> Unit = {}) {
    @Volatile private var session: String? = null
    @Volatile var version: String = "2026-07-28"
        private set
    private var modern = false

    suspend fun initialize() {
        val discovered = try { rpc("server/discover", buildJsonObject {}) }
        catch (e: McpException) {
            if (e.code in setOf(-32601, -32022) || e.httpStatus in setOf(400, 404)) null else throw e
        }
        if (discovered != null) {
            val supported = discovered["supportedVersions"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val selected = VERSIONS.firstOrNull { it in supported }
                ?: throw McpException()
            if (selected !in VERSIONS) throw McpException()
            version = selected
            modern = selected == "2026-07-28"
            if (modern) return
        }
        version = "2025-11-25"
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", "2025-11-25")
            put("capabilities", capabilities())
            put("clientInfo", buildJsonObject { put("name", "Theta"); put("version", "1") })
        })
        val selected = result["protocolVersion"]?.jsonPrimitive?.content ?: throw McpException()
        if (selected !in VERSIONS) throw McpException()
        version = selected
        notification("notifications/initialized", buildJsonObject {})
    }

    suspend fun listTools(): McpDirectory {
        val all = linkedMapOf<String, McpToolDefinition>()
        val cursors = hashSetOf<String>()
        var cursor: String? = null
        var ttl = 60_000L
        do {
            currentCoroutineContext().ensureActive()
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            result["ttlMs"]?.jsonPrimitive?.longOrNull?.let {
                if (it < 0) throw McpException()
                ttl = minOf(ttl, it.coerceAtMost(86_400_000))
            }
            for (element in result.getValue("tools").jsonArray) {
                val tool = element.jsonObject
                val schema = tool.getValue("inputSchema").jsonObject
                val headers = mcpHeaderParameters(schema) ?: continue
                val name = tool.getValue("name").jsonPrimitive.content
                if (!validMcpName(name) || name in all) throw McpException()
                all[name] = McpToolDefinition(name, tool["description"]?.jsonPrimitive?.content.orEmpty(),
                    schema, tool["outputSchema"]?.jsonObject, headers)
            }
            cursor = result["nextCursor"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            if (cursor != null && (cursor.isEmpty() || !cursors.add(cursor))) throw McpException()
        } while (cursor != null)
        return McpDirectory(all.values.sortedBy { it.name }, ttl)
    }

    suspend fun call(name: String, arguments: JsonObject, callId: String, headers: Map<String, String> = emptyMap(),
        input: suspend (String, JsonObject) -> JsonObject? = { _, _ -> null },
        progress: (String, Float?) -> Unit): JsonObject {
        var id = UUID.randomUUID().toString()
        try {
            var continuation: JsonObject? = null
            repeat(8) {
                currentCoroutineContext().ensureActive()
                val result = rpc("tools/call", buildJsonObject {
                    put("name", name); put("arguments", arguments)
                    put("_meta", buildJsonObject { put("progressToken", callId) })
                    continuation?.forEach { (key, value) -> put(key, value) }
                }, id, callId, progress, headers)
                when (result["resultType"]?.jsonPrimitive?.content ?: "complete") {
                    "complete" -> return result
                    "input_required" -> continuation = inputContinuation(result, input)
                    else -> throw McpException()
                }
                // Only an explicit input response continues a call; never retry a failed request.
                id = UUID.randomUUID().toString()
            }
            throw McpException()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                withTimeoutOrNull(1_000) {
                    try { notification("notifications/cancelled", buildJsonObject { put("requestId", id) }) }
                    catch (_: Exception) { }
                }
            }
            throw cancelled
        }
    }

    private suspend fun inputContinuation(result: JsonObject,
        input: suspend (String, JsonObject) -> JsonObject?): JsonObject {
        val requests = result["inputRequests"] as? JsonObject ?: throw McpException()
        if (requests.isEmpty() || requests.size > 16) throw McpException()
        val validator = Schema202012Validator()
        // Validate the entire batch before showing prompts. Sampling/URL auth are not advertised.
        val forms = requests.map { (key, element) ->
            val request = element as? JsonObject ?: throw McpException()
            val params = request["params"] as? JsonObject ?: throw McpException()
            val schema = params["requestedSchema"] as? JsonObject ?: throw McpException()
            val prompt = params["message"]?.jsonPrimitive?.content ?: throw McpException()
            if (key.isEmpty() || key.length > 256 || prompt.length > 4096 ||
                request["method"] != JsonPrimitive("elicitation/create") ||
                (params["mode"]?.jsonPrimitive?.content ?: "form") != "form" ||
                !validator.supported(schema.toString())) throw McpException()
            Triple(key, prompt, schema)
        }
        val state = result["requestState"]
        if (state != null && (state !is JsonPrimitive || !state.isString)) throw McpException()
        val responses = buildJsonObject {
            for ((key, prompt, schema) in forms) {
                currentCoroutineContext().ensureActive()
                val answer = input(prompt, schema)
                currentCoroutineContext().ensureActive()
                if (answer != null && !validator.valid(schema.toString(), answer.toString())) throw McpException()
                putJsonObject(key) {
                    put("action", if (answer == null) "decline" else "accept")
                    if (answer != null) put("content", answer)
                }
            }
        }
        return buildJsonObject {
            put("inputResponses", responses)
            state?.let { put("requestState", it) }
        }
    }

    /** Optional server event channel. No reconnect/replay loop; TTL still gates calls after loss. */
    suspend fun listen() {
        if (modern) {
            val body = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", UUID.randomUUID().toString()); put("method", "subscriptions/listen")
                put("params", requestParams(buildJsonObject { putJsonObject("notifications") { put("toolsListChanged", true) } }))
            }
            exchange(body.toString()).collect { consumeNotification(it.value, null) { _, _ -> } }
        } else {
            exchange(null).collect { event -> consumeNotification(event.value, null) { _, _ -> } }
        }
    }

    private suspend fun rpc(method: String, params: JsonObject, id: String = UUID.randomUUID().toString(),
        progressToken: String? = null, progress: (String, Float?) -> Unit = { _, _ -> }, headers: Map<String, String> = emptyMap()): JsonObject {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", requestParams(params)) }
        val envelope = exchange(body.toString(), headers).onEach { consumeNotification(it.value, progressToken, progress) }
            .firstOrNull { it.value["id"] == JsonPrimitive(id) ||
                (it.status !in 200..299 && "error" in it.value && it.value["id"] in listOf(null, JsonNull)) }
            ?: throw McpException()
        val response = envelope.value
        val error = response["error"]?.jsonObject
        if (error != null) {
            val code = error["code"]?.jsonPrimitive?.intOrNull
            val text = error["message"]?.jsonPrimitive?.content.orEmpty().lowercase()
            if (method == "tools/call" && code == -32602 &&
                ("unknown tool" in text || "tool not found" in text || "no such tool" in text))
                throw McpUnknownToolException()
            throw McpException(code, envelope.status)
        }
        return response["result"]?.jsonObject ?: throw McpException()
    }

    private fun requestParams(params: JsonObject): JsonObject = if (version != "2026-07-28") params else buildJsonObject {
        params.forEach { (k, v) -> put(k, v) }
        putJsonObject("_meta") {
            params["_meta"]?.jsonObject?.forEach { (k, v) -> put(k, v) }
            put("io.modelcontextprotocol/protocolVersion", version)
            putJsonObject("io.modelcontextprotocol/clientInfo") { put("name", "Theta"); put("version", "1") }
            put("io.modelcontextprotocol/clientCapabilities", capabilities())
        }
    }

    private fun consumeNotification(value: JsonObject, token: String?, progress: (String, Float?) -> Unit) {
        when (value["method"]?.jsonPrimitive?.content) {
            "notifications/tools/list_changed", "notifications/toolsListChanged" -> changed()
            "notifications/progress" -> {
                val p = value["params"]?.jsonObject ?: return
                if (token == null || p["progressToken"] != JsonPrimitive(token)) return
                val n = p["progress"]?.jsonPrimitive?.doubleOrNull
                val total = p["total"]?.jsonPrimitive?.doubleOrNull
                // Remote messages can echo secrets; only fixed text and numeric progress escape.
                progress(MCP_PROGRESS_RUNNING, if (n != null && total != null && total > 0)
                    (n / total).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) else null)
            }
        }
    }

    private suspend fun notification(method: String, params: JsonObject) {
        exchange(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) }.toString()).collect()
    }

    private fun capabilities() = buildJsonObject { putJsonObject("elicitation") { putJsonObject("form") {} } }
    private data class Envelope(val value: JsonObject, val status: Int)
    private fun exchange(body: String?, parameters: Map<String, String> = emptyMap()): Flow<Envelope> = callbackFlow {
        val request = Request.Builder().url(endpoint.url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", version)
            .apply {
                session?.let { header("Mcp-Session-Id", it) }
                if (endpoint.token.isNotEmpty()) header(endpoint.header,
                    if (endpoint.header == "Authorization") "Bearer ${endpoint.token}" else endpoint.token)
                if (body != null) {
                    val message = Json.parseToJsonElement(body).jsonObject
                    header("Mcp-Method", message.getValue("method").jsonPrimitive.content)
                    message["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.let {
                        header("Mcp-Name", mcpHeaderValue(it))
                    }
                    parameters.forEach { (name, value) ->
                        require(name.startsWith("Mcp-Param-"))
                        header(name, value)
                    }
                    val delegate = body.toRequestBody("application/json".toMediaType())
                    post(object : RequestBody() {
                        override fun contentType() = delegate.contentType()
                        override fun contentLength() = delegate.contentLength()
                        override fun isOneShot() = true
                        override fun writeTo(sink: okio.BufferedSink) = delegate.writeTo(sink)
                    })
                }
            }.build()
        val call = SharedHttpClient.instance.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled()) close(McpException()) }
            override fun onResponse(call: Call, response: Response) {
                val job = launch(Dispatchers.IO) {
                    try {
                        response.use {
                            if (it.code == 404 && session != null) sessionInvalid()
                            if (body == null && it.code in setOf(404, 405)) return@use
                            val type = it.header("Content-Type").orEmpty().substringBefore(';').trim()
                            // New MCP returns JSON-RPC errors with HTTP 400/404 too. Never follow
                            // redirects; only inspect a bounded JSON error envelope on these statuses.
                            if (!it.isSuccessful && !(it.code in setOf(400, 404) && type == "application/json"))
                                throw McpException(httpStatus = it.code)
                            it.header("Mcp-Session-Id")?.takeIf { _ -> it.isSuccessful }?.let { sid ->
                                if (sid.length !in 1..1024 || sid.any { ch -> ch !in '!'..'~' }) throw McpException()
                                session = sid
                            }
                            if (it.code == 202 || it.code == 204) return@use
                            val source = it.body.source()
                            val buffer = ByteArray(8192)
                            val sse = SseDecoder(MAX_RESPONSE)
                            val json = ByteArrayOutputStream()
                            while (true) {
                                val n = source.read(buffer)
                                if (n == -1) break
                                when (type) {
                                    "text/event-stream" -> sse.feed(buffer.copyOf(n)) { _, data, _ ->
                                        if (data != null) send(Envelope(Json.parseToJsonElement(data).jsonObject, it.code))
                                    }
                                    "application/json" -> {
                                        if (json.size() + n > MAX_RESPONSE) throw McpException()
                                        json.write(buffer, 0, n)
                                    }
                                    else -> throw McpException()
                                }
                            }
                            if (type == "text/event-stream") sse.endOfInput()
                            else {
                                val value = Json.parseToJsonElement(json.toString("UTF-8")).jsonObject
                                if (!it.isSuccessful && "error" !in value) throw McpException(httpStatus = it.code)
                                send(Envelope(value, it.code))
                            }
                        }
                        close()
                    } catch (error: Exception) { if (!call.isCanceled()) close(error as? McpException ?: McpException()) }
                }
                job.invokeOnCompletion { response.close() }
            }
        })
        awaitClose { call.cancel() }
    }

    companion object {
        private val VERSIONS = setOf("2026-07-28", "2025-11-25")
        private const val MAX_RESPONSE = 8 * 1024 * 1024
    }
}

fun validMcpName(name: String): Boolean = name.length in 1..128 && name.none {
    it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\'
} && name !in setOf(".", "..")
