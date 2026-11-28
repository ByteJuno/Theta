package io.github.bytejuno.thetacore.web

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.*
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import io.github.bytejuno.thetacore.storage.SecretCipher
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.crypto.KeyGenerator

class WebToolsTest {
    /** The production DNS guard still validates public addresses. Only test sockets route to
     * loopback; real HTTPS verification, requests, redirects and bounded body reads execute. */
    private class LocalHttps : AutoCloseable {
        val server = MockWebServer()
        private val certificate = HeldCertificate.Builder().commonName("web.test")
            .addSubjectAlternativeName("web.test").addSubjectAlternativeName("other.test")
            .addSubjectAlternativeName("api.search.brave.com").addSubjectAlternativeName("api.tavily.com").build()
        private val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client: OkHttpClient
        init {
            server.useHttps(tls.sslSocketFactory()); server.start()
            client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun connect(endpoint: SocketAddress, timeout: Int) {
                            super.connect(InetSocketAddress("127.0.0.1", server.port), timeout)
                        }
                    }
                    override fun createSocket(h: String, p: Int): Socket = error("unused")
                    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
                    override fun createSocket(h: InetAddress, p: Int): Socket = error("unused")
                    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
                }).build()
        }
        fun http(dns: Dns = Dns { listOf(InetAddress.getByName("93.184.215.14")) }) = WebHttp(client, dns)
        fun enqueue(body: String, type: String = "text/html") {
            server.enqueue(MockResponse.Builder().body(body).addHeader("Content-Type", type).build())
        }
        override fun close() { server.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    private fun env() = ToolEnv("run", "session", "call", false, { _, _ -> })
    private fun cipher(): SecretCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmSecretCipher("test-web") { key }
    }
    private suspend fun failure(expected: WebError, action: suspend () -> Unit) {
        try { action(); fail("Expected $expected") } catch (e: WebFailure) { assertEquals(expected, e.reason) }
    }

    @Test fun addressGuardRejectsPrivateAndEncodedAddresses() = runBlocking {
        val denied = listOf("0.0.0.0", "127.0.0.1", "127.1", "2130706433", "10.1.2.3", "172.31.0.1",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.254", "224.0.0.1", "255.255.255.255",
            "192.0.0.9", "198.18.0.1", "198.51.100.1", "203.0.113.1", "::", "::1", "fe80::1", "fd7a:115c:a1e0::1",
            "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::", "2001:db8::1")
        denied.forEach { assertFalse(it, publicWebAddress(InetAddress.getByName(it))) }
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach { assertTrue(it, publicWebAddress(InetAddress.getByName(it))) }
        for (url in listOf("http://example.org", "file:///tmp/secret", "data:text/plain,secret", "ftp://example.org", "https://a:b@example.org")) {
            failure(WebError.ADDRESS) { webUrl(url) }
        }
        LocalHttps().use { local ->
            failure(WebError.ADDRESS) { local.http(Dns { listOf(InetAddress.getLoopbackAddress()) }).get("https://web.test") }
            failure(WebError.ADDRESS) { local.http(Dns { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getLoopbackAddress()) }).get("https://web.test") }
            assertEquals(0, local.server.requestCount)
        }
    }

    @Test fun htmlExtractionAndRedirectsUseRealHttpsWithoutCredentials() = runBlocking {
        LocalHttps().use { local ->
            local.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://other.test/page").build())
            local.enqueue("<title>News &amp; facts</title><nav>noise</nav><main><h1>Hello</h1><p>中文 &amp; 😀</p>" +
                "<script>secret()</script><div hidden>hidden</div><a href='/next'>Next</a> <a href='javascript:evil()'>Bad</a></main>")
            val page = extractWebPage(local.http().get("https://web.test/start"))
            assertEquals("News & facts", page["title"]!!.jsonPrimitive.content)
            assertEquals("Hello 中文 & 😀 Next Bad", page["text"]!!.jsonPrimitive.content)
            assertEquals("https://other.test/next", page["links"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content)
            repeat(2) {
                val request = local.server.takeRequest(2, TimeUnit.SECONDS)!!
                assertNull(request.headers["Authorization"]); assertNull(request.headers["Cookie"])
            }
        }
    }

    @Test fun redirectRechecksDnsAndNeverFollowsSearchCredentials() = runBlocking {
        LocalHttps().use { local ->
            val dns = Dns { host -> listOf(InetAddress.getByName(if (host == "web.test") "8.8.8.8" else "127.0.0.1")) }
            local.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://private.test").build())
            failure(WebError.ADDRESS) { local.http(dns).get("https://web.test") }
            assertEquals(1, local.server.requestCount)
            local.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://other.test").build())
            failure(WebError.RESPONSE) { local.http().request(searchRequest(
                WebSearchSettings(SearchBackend.BRAVE, WebSearchSettings.BRAVE_ENDPOINT, "fixture-key"), "query", 3)) }
            assertEquals(2, local.server.requestCount)
            local.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://web.test").build())
            failure(WebError.ADDRESS) { local.http().get("https://web.test") }
        }
    }

    @Test fun boundsRejectDeclaredChunkedAndCompressedOversizeAndDoNotRetry503() = runBlocking {
        LocalHttps().use { local ->
            local.enqueue("a".repeat(WEB_DOWNLOAD_LIMIT + 1))
            failure(WebError.DOWNLOAD) { local.http().get("https://web.test") }
            local.server.enqueue(MockResponse.Builder().chunkedBody("a".repeat(WEB_DOWNLOAD_LIMIT + 1), 8192).build())
            failure(WebError.DOWNLOAD) { local.http().get("https://web.test") }
            val compressed = okio.Buffer()
            okio.GzipSink(compressed).use { gzip ->
                val raw = okio.Buffer().writeUtf8("a".repeat(WEB_DOWNLOAD_LIMIT + 1)); gzip.write(raw, raw.size)
            }
            local.server.enqueue(MockResponse.Builder().body(compressed).addHeader("Content-Encoding", "gzip").build())
            failure(WebError.DOWNLOAD) { local.http().get("https://web.test") }
            local.server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
            failure(WebError.NETWORK) { local.http().get("https://web.test") }
            assertEquals(4, local.server.requestCount)
        }
    }

    @Test fun cancellationClosesAnInFlightHttpsCall() = runBlocking {
        LocalHttps().use { local ->
            local.server.enqueue(MockResponse.Builder().body("delayed").bodyDelay(30, TimeUnit.SECONDS).build())
            val job = launch(Dispatchers.IO) { local.http().get("https://web.test") }
            assertNotNull(withContext(Dispatchers.IO) { local.server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(3000) { job.cancelAndJoin() }
            withTimeout(3000) { while (local.client.dispatcher.runningCallsCount() > 0) delay(10) }
        }
    }

    @Test fun sharedPoolCannotBypassDnsGuardAndTlsHostnameRemainsVerified() = runBlocking {
        LocalHttps().use { local ->
            local.enqueue("cached private route")
            // Another host feature is allowed to use private DNS and populate the shared pool.
            val other = local.client.newBuilder().dns { listOf(InetAddress.getLoopbackAddress()) }
                .protocols(listOf(Protocol.HTTP_1_1)).build()
            withContext(Dispatchers.IO) {
                other.newCall(Request.Builder().url("https://web.test").build()).execute().use { it.body.string() }
            }
            // OkHttp also includes the DNS policy in its pool identity. It must create a
            // new validated public route, rather than reuse the existing private one.
            local.enqueue("validated public route")
            assertEquals("validated public route", local.http().get("https://web.test").bytes.toString(Charsets.UTF_8))
            val privateRequest = local.server.takeRequest(2, TimeUnit.SECONDS)!!
            val publicRequest = local.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNotEquals(privateRequest.connectionIndex, publicRequest.connectionIndex)
            assertEquals(2, local.server.requestCount)
            failure(WebError.NETWORK) { local.http().get("https://wrong-certificate.test") }
            assertEquals(2, local.server.requestCount)
        }
    }

    @Test fun rebindingOnNextHopIsDeniedAndSameHopUsesOnlyOneDnsLookup() = runBlocking {
        LocalHttps().use { local ->
            var lookups = 0
            val dns = Dns { listOf(InetAddress.getByName(if (++lookups == 1) "8.8.8.8" else "127.0.0.1")) }
            local.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/next").build())
            failure(WebError.ADDRESS) { local.http(dns).get("https://web.test") }
            assertEquals(2, lookups); assertEquals(1, local.server.requestCount)
        }
    }

    @Test fun backendRequestsResponsesAndMissingSettingsAreBounded() = runBlocking {
        val root = Files.createTempDirectory("web-settings-test").toFile()
        try {
            val store = WebSearchSettingsStore(root, cipher())
            LocalHttps().use { local ->
                val tool = WebSearchTool(local.http(), store) { it.name }
                assertTrue(tool.call(buildJsonObject { put("query", "test") }, env()).isError)
                for (backend in SearchBackend.entries) {
                    val endpoint = when (backend) {
                        SearchBackend.SEARXNG -> "https://web.test/search"
                        SearchBackend.BRAVE -> WebSearchSettings.BRAVE_ENDPOINT
                        SearchBackend.TAVILY -> WebSearchSettings.TAVILY_ENDPOINT
                    }
                    store.save(WebSearchSettings(backend, endpoint, if (backend == SearchBackend.SEARXNG) "" else "fixture-key"))
                    val entry = """{"title":"<b>Title</b>","url":"https://example.org/a","description":"Brave snippet","content":"Other snippet","publishedDate":"2026-01-01"}"""
                    val array = "[$entry,{\"url\":\"javascript:evil()\"}]"
                    local.enqueue(if (backend == SearchBackend.BRAVE) "{\"web\":{\"results\":$array}}" else "{\"results\":$array}", "application/json")
                    val result = tool.call(buildJsonObject { put("query", "中文 & query"); put("count", 2) }, env())
                    assertFalse(result.text, result.isError)
                    val row = result.structured!!.jsonObject["results"]!!.jsonArray.single().jsonObject
                    assertEquals("Title", row["title"]!!.jsonPrimitive.content)
                    assertEquals("2026-01-01", row["publishedAt"]!!.jsonPrimitive.content)
                    val request = local.server.takeRequest(2, TimeUnit.SECONDS)!!
                    when (backend) {
                        SearchBackend.SEARXNG -> { assertEquals("中文 & query", request.url!!.queryParameter("q")); assertEquals("json", request.url!!.queryParameter("format")); assertNull(request.headers["Authorization"]) }
                        SearchBackend.BRAVE -> { assertEquals("fixture-key", request.headers["X-Subscription-Token"]); assertEquals("2", request.url!!.queryParameter("count")) }
                        SearchBackend.TAVILY -> { assertEquals("Bearer fixture-key", request.headers["Authorization"]); assertEquals("POST", request.method)
                            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                            assertEquals("中文 & query", body["query"]!!.jsonPrimitive.content); assertEquals(2, body["max_results"]!!.jsonPrimitive.int) }
                    }
                    assertFalse(result.toString().contains("fixture-key"))
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun settingsEncryptPreserveCorruptionAndRequireExplicitReplacement() {
        val root = Files.createTempDirectory("web-encryption-test").toFile()
        try {
            val cipher = cipher(); val store = WebSearchSettingsStore(root, cipher)
            val config = WebSearchSettings(SearchBackend.BRAVE, WebSearchSettings.BRAVE_ENDPOINT, "super-secret-fixture")
            assertNull(store.read().value); store.save(config)
            val file = root.resolve("web-search.bin"); val first = file.readBytes()
            assertFalse(first.toString(Charsets.UTF_8).contains(config.key))
            assertEquals(config.key, WebSearchSettingsStore(root, cipher).read().value!!.key)
            store.save(config); assertFalse(first.contentEquals(file.readBytes()))
            val validBytes = file.readBytes()
            val failing = WebSearchSettingsStore(root, object : SecretCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray = error("write failure")
                override fun decrypt(ciphertext: ByteArray) = cipher.decrypt(ciphertext)
            })
            assertThrows(WebFailure::class.java) { failing.save(config) }
            assertArrayEquals(validBytes, file.readBytes())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
            file.writeBytes(byteArrayOf(1, 2, 3)); assertTrue(store.read().unreadable)
            assertThrows(WebFailure::class.java) { store.save(config) }; assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
            store.save(config, replaceUnreadable = true); assertEquals(config.key, store.read().value!!.key)
            val wrong = WebSearchSettingsStore(root, cipher()); assertTrue(wrong.read().unreadable)
            assertFalse(config.toString().contains(config.key))
            store.save(null); assertNull(store.read().value); assertFalse(file.exists())
            assertThrows(IllegalArgumentException::class.java) {
                WebSearchSettings(SearchBackend.BRAVE, "https://evil.test", "key")
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun actualWebToolStoresLargeBodyInSessionBlobAndHonorsDeny() = runBlocking {
        val root = Files.createTempDirectory("web-ledger-test")
        val budget = PluginExecutionBudget()
        try {
            LocalHttps().use { local ->
                val text = "网页正文😀 ".repeat(6000).trim()
                local.enqueue("<main>$text</main>")
                SqliteInvocationStore.open(root.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { store ->
                    var denied = false
                    val tool = WebFetchTool(local.http()) { it.name }
                    val service = HostToolBridgeRoot(budget).initialize(webTools = listOf(tool), policy = ApprovalPolicy(denied = { denied })) { store }
                    val d = service.catalog.freeze("run").values.single { it.toolKey == "host/web_fetch" }
                    fun input(step: String) = InvocationInput(BindingValue.copyOf(callBinding("test", step, null, 0, "wire")),
                        "chain", "session", "run", step, 0, """{"url":"https://web.test/page"}""", d, true, Risk.READ)
                    val i = input("read"); service.prepare(i); service.dispatch(i.binding.callId)
                    withTimeout(10_000) { service.awaitObservedExit(i.binding.callId) }
                    val r = service.ledger.get(i.binding.callId)!!
                    assertEquals(Status.SUCCEEDED, r.state); assertEquals(Effect.NONE, r.result!!.effect)
                    assertTrue(r.result!!.text.toByteArray().size <= 32 * 1024); assertTrue(r.result!!.text.contains("Truncated"))
                    val ref = r.result!!.output!!.raw
                    val bytes = java.io.ByteArrayOutputStream(); var offset = 0L
                    while (offset < ref.sizeBytes) { val chunk = service.ledger.readSessionBlob("session", ref.id, offset).second; bytes.write(chunk); offset += chunk.size }
                    val raw = Json.decodeFromString(RawToolOutput.serializer(), bytes.toString("UTF-8"))
                    assertEquals(text, raw.structured!!.jsonObject["text"]!!.jsonPrimitive.content)
                    assertThrows(BlobReadException::class.java) { service.ledger.readSessionBlob("other", ref.id, 0) }
                    denied = true
                    assertEquals(Status.REJECTED, (service.prepare(input("denied")) as Mutation.Applied).record.state)
                    assertEquals(1, local.server.requestCount)
                }
            }
        } finally { budget.close(); root.toFile().deleteRecursively() }
    }
}
