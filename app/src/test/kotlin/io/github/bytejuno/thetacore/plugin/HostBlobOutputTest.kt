package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class HostBlobOutputTest {
    private fun calls(name: String, args: String, request: String) = ModelEventReducer("recorded", request).apply {
        listOf(Events.started("response", "model"), Events.toolStart(0, "wire", name),
            Events.toolDelta(0, args), Events.end(0), Events.finished(FinishReason.TOOL_CALLS)).forEach(::accept)
    }
    // Core diagnostics stay internal: inspect their backing counters rather than widening API.
    private fun counter(target: Any, name: String): Long {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.getLong(target)
    }
    private fun indexCounter(target: Any, fieldName: String, name: String): Long {
        val index = target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(target)
        return counter(index, name)
    }
    @Test fun actualRetrievalCallsHaveNoHistoryGcAndNormalizeHugeReturnOnce() = runBlocking {
        val root = Files.createTempDirectory("host-blob-work")
        val budget = PluginExecutionBudget()
        val media = ByteArray(8192 * 24 + 7) { (it % 251).toByte() }
        val text = "\u0000😀".repeat(400000)
        val tool = object : Tool {
            override val id = "huge"
            override val spec = ToolSpec("huge", "huge", JsonObject(emptyMap()), timeoutMs = 60000)
            override suspend fun call(args: JsonObject, env: ToolEnv) = ToolResult(text)
                .withContent(listOf(ToolContent.Media(ToolMediaKind.DOCUMENT, "application/octet-stream", media)))
        }
        try {
            JsonInvocationStore.create(root, Durability.PROCESS_CRASH_ONLY).use { store ->
                val service = HostToolBridgeRoot(budget, tool).initialize { store }
                val frozen = service.catalog.freeze("run")
                val source = frozen.values.single { it.toolKey == "host/clock" }
                val reader = frozen.values.single { it.toolKey == "host/blob_read" }
                val response = service.executeStep(calls(source.modelName, "{}", "huge"), "chain", "session", "run", "source")!!
                val record = service.ledger.get((response.parts.single() as ContentPart.ToolResult).callId)!!
                assertEquals(1L, service.normalizationCount)
                assertEquals(1, record.evidence.count { it.outcome != null })
                val ref = record.result!!.output!!.media.single()
                val scans = counter(store, "orphanDirectoryScans")
                val gc = counter(store, "orphanCandidates")
                val roots = indexCounter(store, "roots", "rootVisits")
                val ledgerRoots = indexCounter(service.ledger, "blobRoots", "rootVisits")
                val records = indexCounter(store, "roots", "recordVisits")
                val ledgerRecords = indexCounter(service.ledger, "blobRoots", "recordVisits")
                val downloaded = java.io.ByteArrayOutputStream()
                var offset = 0L
                var count = 0
                while (offset < ref.sizeBytes) {
                    val args = buildJsonObject { put("id", ref.id); put("offset", offset) }.toString()
                    val reply = service.executeStep(calls(reader.modelName, args, "chunk-$count"),
                        "chain", "session", "run", "chunk-${count++}")!!
                    val part = reply.parts.single() as ContentPart.ToolResult
                    val retrieval = service.ledger.get(part.callId)!!
                    assertEquals(5L, retrieval.revision)
                    assertNull(retrieval.result!!.output)
                    val chunk = Json.parseToJsonElement(retrieval.result!!.text).jsonObject
                    val bytes = Base64.getDecoder().decode(chunk.getValue("data").jsonPrimitive.content)
                    downloaded.write(bytes); offset += bytes.size
                }
                assertEquals(25, count)
                assertArrayEquals(media, downloaded.toByteArray())
                assertEquals(scans, counter(store, "orphanDirectoryScans"))
                assertEquals(gc, counter(store, "orphanCandidates"))
                assertEquals(roots, indexCounter(store, "roots", "rootVisits"))
                assertEquals(ledgerRoots, indexCounter(service.ledger, "blobRoots", "rootVisits"))
                assertEquals(9L * count, indexCounter(store, "roots", "recordVisits") - records)
                assertEquals(9L * count, indexCounter(service.ledger, "blobRoots", "recordVisits") - ledgerRecords)
                assertEquals(1L, service.normalizationCount)
                println("BLOB_HOST_WORK chunks=$count saves=${5 * count} rootVisits=0 directoryScans=0 gcCandidates=0 indexRecordVisits=${9 * count} normalizations=1")
            }
        } finally { budget.close(); root.toFile().deleteRecursively() }
    }
    @Test fun cancelledHugeLateReturnNormalizesOnceAndPersistsBeforeActualExit() = runBlocking {
        val root = Files.createTempDirectory("host-late-blob").toFile()
        val budget = PluginExecutionBudget()
        val entered = CountDownLatch(1)
        val returnBody = CountDownLatch(1)
        val releaseChild = CountDownLatch(1)
        val text = "late😀\u0000".repeat(200000)
        val tool = object : Tool {
            override val id = "late"
            override val spec = ToolSpec("late", "late", JsonObject(emptyMap()), timeoutMs = 60000)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
                    withContext(NonCancellable) { entered.countDown(); releaseChild.await() }
                }
                returnBody.await()
                return ToolResult(text, effect = ToolEffect.UNKNOWN)
            }
        }
        try {
            lateinit var ref: BlobRef
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget, tool).initialize { store }
                val d = service.catalog.freeze("run").values.single { it.toolKey == "host/clock" }
                val input = InvocationInput(BindingValue.copyOf(callBinding("test", "late", null, 0, "wire")),
                    "chain", "session", "run", "step", 0, "{}", d, false, Risk.READ)
                val id = input.binding.callId
                service.prepare(input); service.dispatch(id)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                service.cancel(id)
                returnBody.countDown()
                withTimeout(15000) { while (service.ledger.get(id)!!.evidence.none { it.outcome?.output != null }) delay(5) }
                val late = service.ledger.get(id)!!
                assertEquals(Status.CANCELLED, late.state)
                assertEquals(InstanceState.UNCONFIRMED, late.instance!!.state)
                ref = late.evidence.single { it.outcome?.output != null }.outcome!!.output!!.raw
                assertEquals(1L, service.normalizationCount)
                assertTrue(service.ledger.readBlob("run", ref.id, 0).second.isNotEmpty())
                releaseChild.countDown(); service.awaitObservedExit(id)
                assertEquals(1L, service.normalizationCount)
                assertEquals(1, service.ledger.get(id)!!.evidence.count { it.outcome?.output != null })
                assertEquals(Status.CANCELLED, service.ledger.get(id)!!.state)
            }
            AndroidInvocationStore.open(root) {}.use { store ->
                val bytes = java.io.ByteArrayOutputStream()
                var offset = 0L
                while (offset < ref.sizeBytes) { val chunk = store.readBlob(ref, offset, 8192); bytes.write(chunk); offset += chunk.size }
                assertEquals(text, Json.decodeFromString(RawToolOutput.serializer(), bytes.toString("UTF-8")).content.single().text)
            }
        } finally { returnBody.countDown(); releaseChild.countDown(); budget.close(); root.deleteRecursively() }
    }
    @Test fun plainEscapedOutputsUseBlobPathEvenBelowRawByteLimit() = runBlocking {
        for (text in listOf("a".repeat(32768), "\n".repeat(32768), "\u0000".repeat(32768), "😀".repeat(8192))) {
            val root = Files.createTempDirectory("host-plain-budget").toFile()
            val budget = PluginExecutionBudget()
            val tool = object : Tool {
                override val id = "plain"
                override val spec = ToolSpec("plain", "plain", JsonObject(emptyMap()))
                override suspend fun call(args: JsonObject, env: ToolEnv) = ToolResult(text)
            }
            try {
                AndroidInvocationStore.open(root) {}.use { store ->
                    val service = HostToolBridgeRoot(budget, tool).initialize { store }
                    val frozen = service.catalog.freeze("run")
                    val source = frozen.values.single { it.toolKey == "host/clock" }
                    val response = service.executeStep(calls(source.modelName, "{}", "plain"), "chain", "session", "run", "step")!!
                    val part = response.parts.single() as ContentPart.ToolResult
                    assertTrue(part.text.toByteArray().size <= 32768)
                    val record = service.ledger.get(part.callId)!!
                    assertNotNull(record.result!!.output)
                    assertTrue(part.text.contains(record.result!!.output!!.raw.id))
                    assertTrue(Json.parseToJsonElement(part.text).jsonObject.getValue("message").jsonPrimitive.content
                        .endsWith("not a complete structured value.]"))
                }
            } finally { budget.close(); root.deleteRecursively() }
        }
    }

    @Test fun productionBridgePublishesRawMediaAndFrozenRetrievalReadsStableChunks() = runBlocking {
        val root = Files.createTempDirectory("host-blob-output").toFile()
        val budget = PluginExecutionBudget()
        val text = "界😀".repeat(15000)
        val media = ByteArray(20000) { (it % 255).toByte() }
        val tool = object : Tool {
            override val id = "large"
            override val spec = ToolSpec("large", "large", JsonObject(emptyMap()), timeoutMs = 10000)
            override suspend fun call(args: JsonObject, env: ToolEnv) = ToolResult(text,
                structured = buildJsonObject { put("ok", true) }, effect = ToolEffect.NONE)
                .withContent(listOf(ToolContent.Media(ToolMediaKind.IMAGE, "image/png", media)))
        }
        try {
            lateinit var ref: BlobRef
            lateinit var callId: String
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget, tool).initialize { store }
                val frozen = service.catalog.freeze("run")
                val source = frozen.values.single { it.toolKey == "host/clock" }
                val reader = frozen.values.single { it.toolKey == "host/blob_read" }
                val result = service.executeStep(calls(source.modelName, "{}", "large"), "chain", "session", "run", "step")!!
                val part = result.parts.single() as ContentPart.ToolResult
                callId = part.callId
                val record = service.ledger.get(callId)!!
                assertEquals(Status.SUCCEEDED, record.state)
                assertEquals(InstanceState.EXIT_CONFIRMED, record.instance!!.state)
                assertTrue(record.result!!.text.toByteArray().size <= 32768)
                assertTrue(part.text.contains(reader.modelName))
                assertTrue(record.result!!.text.startsWith("[structured JSON]"))
                ref = record.result!!.output!!.raw
                val mediaRef = record.result!!.output!!.media.single()
                var offset = 0L
                val downloaded = java.io.ByteArrayOutputStream()
                var index = 0
                do {
                    val args = buildJsonObject { put("id", mediaRef.id); put("offset", offset) }.toString()
                    val reply = service.executeStep(calls(reader.modelName, args, "read-$index"), "chain", "session", "run", "read-${index++}")!!
                    val read = service.ledger.get((reply.parts.single() as ContentPart.ToolResult).callId)!!
                    assertNull(read.result!!.output)
                    val chunk = Json.parseToJsonElement(read.result!!.text).jsonObject
                    downloaded.write(Base64.getDecoder().decode(chunk.getValue("data").jsonPrimitive.content))
                    offset = chunk["nextOffset"]?.jsonPrimitive?.longOrNull ?: -1
                } while (offset >= 0)
                assertArrayEquals(media, downloaded.toByteArray())
                val rawBytes = java.io.ByteArrayOutputStream()
                var rawOffset = 0L
                while (rawOffset < ref.sizeBytes) {
                    val bytes = service.ledger.readBlob("run", ref.id, rawOffset).second
                    rawBytes.write(bytes); rawOffset += bytes.size
                }
                val raw = Json.decodeFromString(RawToolOutput.serializer(), rawBytes.toString("UTF-8"))
                assertEquals(text, raw.content.first().text)
                assertEquals(mediaRef, raw.content.last().blob)
                assertTrue(Files.size(root.resolve("${AndroidInvocationStore.DIRECTORY}/$callId.json").toPath()) < 50000)
            }
            AndroidInvocationStore.open(root) {}.use { store ->
                assertEquals(ref, store.records().single { it.callId == callId }.result!!.output!!.raw)
                assertTrue(store.readBlob(ref, 0, 8192).isNotEmpty())
            }
        } finally { budget.close(); root.deleteRecursively() }
    }
}
