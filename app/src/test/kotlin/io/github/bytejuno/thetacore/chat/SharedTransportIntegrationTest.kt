package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.agent.transport.OkHttpModelTransport
import io.github.bytejuno.thetacore.agent.transport.TransportEvent
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import io.github.bytejuno.thetacore.plugin.HostToolBridgeRoot
import io.github.bytejuno.thetacore.plugin.PluginExecutionBudget
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlin.concurrent.thread

/** Real storage encryption, real SQLite history/ledger, real AgentRunLoop and loopback HTTP/1.1 sockets. */
class SharedTransportIntegrationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun settingsStore(root: java.io.File): OpenAiSettingsStore {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return OpenAiSettingsStore(root, AesGcmSecretCipher("issue59-test") { key })
    }

    private fun sseText(text: String): String =
        "data: {\"id\":\"cmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    private fun sseToolCall(name: String, argsJson: String): String {
        val escapedArgs = argsJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return "data: {\"id\":\"cmpl-tool\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"$name\",\"arguments\":\"$escapedArgs\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"
    }

    private class KeepAliveSseServer(private val responses: (Int) -> String) : AutoCloseable {
        data class RecordedRequest(val authorization: String?, val body: String)

        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val baseUrl: String get() = "http://127.0.0.1:${server.localPort}/v1"
        val connections = AtomicInteger()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile private var closed = false
        private val threads = CopyOnWriteArrayList<Thread>()

        private val acceptThread = thread(isDaemon = true, name = "issue59-accept") {
            while (!closed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                connections.incrementAndGet()
                threads += thread(isDaemon = true, name = "issue59-conn") {
                    socket.use { conn ->
                        conn.soTimeout = 10_000
                        val input = BufferedInputStream(conn.getInputStream())
                        val output = conn.getOutputStream()
                        while (!closed) {
                            val headerBytes = readHeaders(input) ?: break
                            val headerText = headerBytes.toString(Charsets.UTF_8)
                            val lines = headerText.split("\r\n")
                            var length = 0
                            var auth: String? = null
                            for (line in lines.drop(1)) {
                                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                    length = line.substringAfter(':').trim().toInt()
                                } else if (line.startsWith("Authorization:", ignoreCase = true)) {
                                    auth = line.substringAfter(':').trim()
                                }
                            }
                            val bodyBytes = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val count = input.read(bodyBytes, read, length - read)
                                if (count < 0) break
                                read += count
                            }
                            val index = requests.size
                            requests += RecordedRequest(auth, bodyBytes.toString(Charsets.UTF_8))
                            val payload = responses(index).toByteArray(Charsets.UTF_8)
                            val response = (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/event-stream\r\n" +
                                    "Connection: keep-alive\r\n" +
                                    "Content-Length: ${payload.size}\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII)
                            output.write(response)
                            output.write(payload)
                            output.flush()
                        }
                    }
                }
            }
        }

        private fun readHeaders(input: BufferedInputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            var state = 0
            while (true) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else out.toByteArray()
                out.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> return out.toByteArray()
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
        }

        override fun close() {
            closed = true
            runCatching { server.close() }
            acceptThread.join(3_000)
            threads.forEach { it.join(3_000) }
        }
    }

    private suspend fun awaitState(owner: ChatSessionOwner, predicate: (ChatState) -> Boolean) {
        withTimeout(10_000) {
            while (!predicate(owner.state.value)) delay(10)
        }
    }

    @Test fun multipleChatRunsReuseInjectedProcessLevelTransportInstance() = runBlocking {
        val root = temp.newFolder()
        val credStore = settingsStore(root)
        credStore.replace(OpenAiProviderSettings("chat", "https://example.invalid/v1", "model-one"), "synthetic-key-1")

        val seenTransports = CopyOnWriteArrayList<ModelTransport>()
        val seenTokens = CopyOnWriteArrayList<String>()
        val seenModels = CopyOnWriteArrayList<String>()
        val sharedTransport = object : ModelTransport {
            override fun exchange(url: String, body: String, token: String): Flow<TransportEvent> {
                val self = this
                return flow {
                    seenTransports += self
                    seenTokens += token
                    seenModels += Regex("\"model\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                    emit(TransportEvent.Headers(200, "text/event-stream", null))
                    emit(TransportEvent.Bytes(sseText("reply-${seenTokens.size}").toByteArray(Charsets.UTF_8)))
                }
            }
        }

        val agentStore = AndroidAgentStore.open(root, BundledSQLiteDriver())
        val budget = PluginExecutionBudget()
        val host = HostToolBridgeRoot(budget).initialize { agentStore }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val owner = ChatSessionOwner(
            scope = scope,
            service = { host },
            configuration = credStore::snapshotForNewRun,
            targetForRun = sharedTargetFactory(sharedTransport),
        )
        try {
            owner.open().join()
            owner.send("first turn").join()
            awaitState(owner) { !it.active && it.result?.state == RunState.COMPLETED && it.messages.size >= 2 }

            // Update credentials and model between runs; the second run must still reuse the same transport.
            credStore.replace(OpenAiProviderSettings("chat", "https://example.invalid/v1", "model-two"), "synthetic-key-2")
            owner.send("second turn").join()
            awaitState(owner) { !it.active && it.result?.state == RunState.COMPLETED && it.messages.size >= 4 }

            assertEquals(2, seenTransports.size)
            assertSame(sharedTransport, seenTransports[0])
            assertSame(sharedTransport, seenTransports[1])
            assertEquals(listOf("synthetic-key-1", "synthetic-key-2"), seenTokens)
            assertEquals(listOf("model-one", "model-two"), seenModels)
        } finally {
            owner.stop().join()
            awaitState(owner) { !it.active }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close()
            agentStore.close()
        }
    }

    @Test fun defaultChatSessionOwnerTargetReusesSingleOkHttpConnectionPoolAcrossRuns() = runBlocking {
        val root = temp.newFolder()
        val credStore = settingsStore(root)
        KeepAliveSseServer { index -> sseText("ok-$index") }.use { server ->
            credStore.replace(OpenAiProviderSettings("chat", server.baseUrl, "local-model"), "synthetic-loopback-key")
            val agentStore = AndroidAgentStore.open(root, BundledSQLiteDriver())
            val budget = PluginExecutionBudget()
            val host = HostToolBridgeRoot(budget).initialize { agentStore }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            // Uses default targetForRun (lazy shared OkHttpModelTransport).
            val owner = ChatSessionOwner(
                scope = scope,
                service = { host },
                configuration = credStore::snapshotForNewRun,
            )
            try {
                owner.open().join()
                owner.send("run one").join()
                awaitState(owner) { !it.active && it.result?.state == RunState.COMPLETED && it.messages.size >= 2 }

                owner.send("run two").join()
                awaitState(owner) { !it.active && it.result?.state == RunState.COMPLETED && it.messages.size >= 4 }

                assertEquals(2, server.requests.size)
                assertEquals(1, server.connections.get())
                assertTrue(server.requests.all { it.authorization == "Bearer synthetic-loopback-key" })
            } finally {
                owner.stop().join()
                awaitState(owner) { !it.active }
                scope.coroutineContext[Job]!!.cancelAndJoin()
                budget.close()
                agentStore.close()
            }
        }
    }

    @Test fun storageAdmissionToAgentRunLoopIntegrationSharesOneOkHttpModelTransport() = runBlocking {
        val root = temp.newFolder()
        val credStore = settingsStore(root)
        val sharedTransport = OkHttpModelTransport()

        KeepAliveSseServer { index ->
            when (index) {
                0 -> sseToolCall("host_clock", "{}")
                1 -> sseText("clock-checked")
                else -> sseText("second-run-done")
            }
        }.use { server ->
            credStore.replace(OpenAiProviderSettings("chat", server.baseUrl, "model-v1"), "synthetic-secret-v1")
            val agentStore = AndroidAgentStore.open(root, BundledSQLiteDriver())
            val budget = PluginExecutionBudget()
            val host = HostToolBridgeRoot(budget).initialize { agentStore }
            val history = agentStore.createHistory("session-issue59")
            try {
                val chain = history.chatTaskChainId()
                history.saveChatContext(ChatTaskContext(chain, "what time is it", 0, null, null))
                // Run 1: storage snapshot -> shared OkHttpModelTransport -> AgentRunLoop with real tool call.
                val snapshot1 = credStore.snapshotForNewRun()
                val defs1 = host.catalog.freeze("run-1").values.toList()
                val request1 = snapshot1.request {
                    tools = defs1.map {
                        modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema).jsonObject)
                    }
                }
                val id1 = RunIdentity(chain, "session-issue59", "run-1", unattended = false)
                history.appendChatInput(history.revision(), message(Role.USER, textPart("what time is it")), id1)
                val executor1 = LedgerToolExecutor(host.ledger, host.catalog.gate, defs1, host.runBridge(), RunApprovals { Decision.ALLOW_ONCE })
                val result1 = AgentRunLoop(
                    id1,
                    snapshot1.target(sharedTransport),
                    request1,
                    history,
                    executor1,
                    journal = StoredRunJournal(history),
                ).runChatTask()
                assertEquals(RunState.COMPLETED, result1.state)
                history.saveChatResult(result1)

                // Update stored credentials/model; Run 2 admits fresh snapshot on the SAME OkHttpModelTransport.
                credStore.replace(OpenAiProviderSettings("chat", server.baseUrl, "model-v2"), "synthetic-secret-v2")
                val snapshot2 = credStore.snapshotForNewRun()
                val defs2 = host.catalog.freeze("run-2").values.toList()
                val chain2 = history.chatTaskChainId()
                if (history.chatContext() == null) {
                    history.saveChatContext(ChatTaskContext(chain2, "follow up", 0, null, null))
                }
                val id2 = RunIdentity(chain2, "session-issue59", "run-2", unattended = false)
                history.appendChatInput(history.revision(), message(Role.USER, textPart("follow up")), id2)
                val executor2 = LedgerToolExecutor(host.ledger, host.catalog.gate, defs2, host.runBridge(), RunApprovals { Decision.ALLOW_ONCE })
                val request2 = snapshot2.request {
                    tools = defs2.map {
                        modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema).jsonObject)
                    }
                }
                val result2 = AgentRunLoop(
                    id2,
                    snapshot2.target(sharedTransport),
                    request2,
                    history,
                    executor2,
                    journal = StoredRunJournal(history),
                ).runChatTask()
                assertEquals(RunState.COMPLETED, result2.state)

                // All 3 HTTP requests across 2 runs reused the single keep-alive TCP connection.
                assertEquals(3, server.requests.size)
                assertEquals(1, server.connections.get())
                assertEquals("Bearer synthetic-secret-v1", server.requests[0].authorization)
                assertEquals("Bearer synthetic-secret-v1", server.requests[1].authorization)
                assertEquals("Bearer synthetic-secret-v2", server.requests[2].authorization)
                assertTrue(server.requests[0].body.contains("\"model\":\"model-v1\""))
                assertTrue(server.requests[2].body.contains("\"model\":\"model-v2\""))

                // Secrets never leak into durable history or ledger records.
                val persisted = listOf(history.page(null, 40).messages, agentStore.records()).toString()
                assertFalse(persisted.contains("synthetic-secret-v1"))
                assertFalse(persisted.contains("synthetic-secret-v2"))
            } finally {
                history.close()
                budget.close()
                agentStore.close()
            }
        }
    }
}
