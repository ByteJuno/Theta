package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.ledger.BlobRef
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class HostSessionBlobTest {
    private fun calls(name: String, args: String, request: String) = ModelEventReducer("recorded", request).apply {
        listOf(Events.started("response", "model"), Events.toolStart(0, "wire", name),
            Events.toolDelta(0, args), Events.end(0), Events.finished(FinishReason.TOOL_CALLS)).forEach(::accept)
    }
    @Test fun realToolReadsEarlierRunAfterRestartAndDoesNotLeakForeignIds() = runBlocking {
        val root = Files.createTempDirectory("host-session-blob")
        val media = ByteArray(17003) { (it % 251).toByte() }
        val tool = object : Tool {
            override val id = "source"
            override val spec = ToolSpec("source", "source", JsonObject(emptyMap()))
            override suspend fun call(args: JsonObject, env: ToolEnv) = ToolResult("large result")
                .withContent(listOf(ToolContent.Media(ToolMediaKind.DOCUMENT, "application/octet-stream", media)))
        }
        lateinit var ref: BlobRef
        try {
            repeat(2) { pass ->
                val budget = PluginExecutionBudget()
                try {
                    AndroidAgentStore.open(root.toFile(), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { store ->
                        val service = HostToolBridgeRoot(budget, tool).initialize { store }
                        if (pass == 0) {
                            val source = service.catalog.freeze("source-run").values.single { it.toolKey == "host/clock" }
                            val response = service.executeStep(calls(source.modelName, "{}", "source"),
                                "chain", "session", "source-run", "source")!!
                            val call = (response.parts.single() as ContentPart.ToolResult).callId
                            ref = service.ledger.get(call)!!.result!!.output!!.media.single()
                        }
                        val run = "later-$pass"
                        val reader = service.catalog.freeze(run).values.single { it.toolKey == "host/blob_read" }
                        suspend fun read(session: String, id: String, offset: Number, step: String): InvocationRecord {
                            val args = buildJsonObject { put("id", id); put("offset", offset) }.toString()
                            val reply = service.executeStep(calls(reader.modelName, args, step), session, session, run, step)!!
                            return service.ledger.get((reply.parts.single() as ContentPart.ToolResult).callId)!!
                        }
                        val bytes = java.io.ByteArrayOutputStream()
                        while (bytes.size() < media.size) {
                            val record = read("session", ref.id, bytes.size().toLong(), "chunk-${bytes.size()}")
                            assertEquals(Status.SUCCEEDED, record.state)
                            val value = Json.parseToJsonElement(record.result!!.text).jsonObject
                            bytes.write(Base64.getDecoder().decode(value.getValue("data").jsonPrimitive.content))
                            if (bytes.size() == media.size) assertEquals(JsonNull, value["nextOffset"])
                            else assertEquals(bytes.size().toLong(), value.getValue("nextOffset").jsonPrimitive.long)
                        }
                        assertArrayEquals(media, bytes.toByteArray())
                        val foreign = read("other", ref.id, 0, "foreign")
                        val absent = read("other", "f".repeat(64), 0, "absent")
                        assertEquals(Status.FAILED, foreign.state)
                        assertEquals(absent.result!!.text, foreign.result!!.text)
                        assertEquals("Result content is unavailable in this session.", foreign.result!!.text)
                        val invalid = read("session", ref.id, ref.sizeBytes + 1, "invalid-offset")
                        assertEquals(Status.FAILED, invalid.state)
                        assertTrue(invalid.result!!.text.startsWith("Invalid result range"))
                        assertFalse(invalid.result!!.text.contains("Exception"))
                        val oversized = Long.MAX_VALUE.toBigInteger() + java.math.BigInteger.ONE
                        assertEquals(invalid.result!!.text, read("session", ref.id, oversized, "huge-owned").result!!.text)
                        for ((index, id) in listOf(ref.id, "f".repeat(64)).withIndex()) {
                            val denied = read("other", id, oversized, "huge-denied-$index")
                            assertEquals(Status.FAILED, denied.state)
                            assertEquals(foreign.result!!.text, denied.result!!.text)
                        }
                        if (pass == 1) {
                            service.ledger.deleteSession("session")
                            val denied = read("other", ref.id, 0, "deleted")
                            assertEquals(foreign.result!!.text, denied.result!!.text)
                        }
                    }
                } finally { budget.close() }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
