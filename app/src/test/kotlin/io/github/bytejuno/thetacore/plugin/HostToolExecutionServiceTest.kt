package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiChatCodec
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiChatConfig
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiChatProvider
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.agent.transport.TransportEvent
import kotlinx.coroutines.flow.flow
import io.github.bytejuno.thetacore.plugin.tools.HostClockTool
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostToolExecutionServiceTest {
    private class MemoryStore : InvocationStore {
        val data = linkedMapOf<String, InvocationRecord>()
        var bound = false
        val writes = kotlinx.coroutines.channels.Channel<InvocationRecord>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var fail: (InvocationRecord) -> Boolean = { false }
        override fun bindCoordinator() { check(!bound); bound = true }
        override fun records() = data.values.toList()
        override fun save(record: InvocationRecord, expectedRevision: Long?) {
            if (fail(record)) throw StoreException("injected write failure")
            record.validate()
            check(data[record.callId]?.revision == expectedRevision)
            data[record.callId] = record
            writes.trySend(record)
        }
        private val blobs = mutableMapOf<String, ByteArray>()
        override fun saveWithBlobs(record: InvocationRecord, expectedRevision: Long?, blobs: List<BlobWrite>) {
            blobs.forEach { this.blobs[it.ref.id] = it.bytes() }
            save(record, expectedRevision)
        }
        override fun readBlob(ref: BlobRef, offset: Long, limit: Int): ByteArray =
            blobs.getValue(ref.id).let { it.copyOfRange(offset.toInt(), minOf(it.size, offset.toInt() + limit)) }
        override fun close() {}
    }
    private class Fixture(policy: ApprovalPolicy = ApprovalPolicy(), maxClaims: Int = Int.MAX_VALUE,
        slots: Int = 2, exitGraceMillis: Long = 2_000,
        afterRuntimeApprovalStart: suspend () -> Unit = {}) : AutoCloseable {
        val budget = PluginExecutionBudget(slots, slots)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val gate = DefinitionGate()
        val validator = Schema202012Validator()
        val ledger = InvocationLedger(store, gate, validator, policy, maxClaims)
        val catalog = FrozenToolCatalog(gate, budget, owner, validator)
        val service = HostToolExecutionService(budget, owner, catalog, ledger, validator, exitGraceMillis, afterRuntimeApprovalStart)
        init { ledger.recoverColdStart(UUID.randomUUID().toString()) }
        suspend fun register(tool: Tool = HostClockTool { 42 }): FrozenDefinition {
            val d = catalog.registerHost("host/test", "1", tool)
            catalog.registerHost("host/blob_read", "1", io.github.bytejuno.thetacore.plugin.tools.HostBlobReadTool(ledger))
            catalog.freeze("run")
            return d
        }
        fun input(d: FrozenDefinition, args: String = "{}", order: Int = 0, step: String = "step") = InvocationInput(
            io.github.bytejuno.thetacore.agent.ledger.BindingValue.copyOf(callBinding("recorded", "request", "response", order, "wire-$order")),
            "chain", "session", "run", step, order, args, d, false, d.declaredRisk)
        override fun close() { budget.close(); owner.cancel() }
    }
    private val emptySchema = Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject
    private fun tool(risk: ToolRisk = ToolRisk.READ, timeout: Long = 2_000, resource: String? = null,
        parallel: Boolean = false, body: suspend (ToolEnv) -> ToolResult) = object : Tool {
        override val id = "controlled"
        override val spec = ToolSpec("controlled", "actual SDK tool", emptySchema, risk = risk,
            timeoutMs = timeout, exclusiveResource = resource, parallelSafe = parallel)
        override suspend fun call(args: JsonObject, env: ToolEnv) = body(env)
    }
    private suspend fun waitUntil(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
    private suspend fun approve(f: Fixture, id: String) {
        val pending = f.ledger.get(id)!!.pending!!
        assertTrue(f.service.decide(id, pending.id, Decision.ALLOW_ONCE) is Mutation.Applied)
    }

    @Test fun approvalAfterRestartRequiresExplicitRunDirectoryWithoutMutation() = runBlocking {
        Fixture().use { f ->
            val write = tool(risk = ToolRisk.WRITE) { ToolResult(text = "write") }
            val definition = f.register(write)
            val pending = (f.service.prepare(f.input(definition)) as Mutation.Applied).record
            assertNotNull(pending.pending)
            val coldGate = DefinitionGate()
            val coldCatalog = FrozenToolCatalog(coldGate, f.budget, f.owner, f.validator)
            // Reopen the durable content under a fresh process coordinator/catalog.
            val coldStore = MemoryStore().also { it.data.putAll(f.store.data) }
            val coldLedger = InvocationLedger(coldStore, coldGate, f.validator)
            coldLedger.recoverColdStart(UUID.randomUUID().toString())
            coldCatalog.registerHost("host/test", "1", write)
            val cold = HostToolExecutionService(f.budget, f.owner, coldCatalog, coldLedger, f.validator)
            for (decision in Decision.entries) {
                assertTrue(cold.decide(pending.callId, pending.pending!!.id, decision) is Mutation.Blocked)
                assertEquals(pending, coldLedger.get(pending.callId))
            }
            assertTrue(cold.prepare(pending.input) is Mutation.Blocked)
            assertEquals(pending, coldLedger.get(pending.callId))
            coldCatalog.freeze("run")
            val approved = cold.decide(pending.callId, pending.pending!!.id, Decision.ALLOW_ONCE) as Mutation.Applied
            assertEquals(Status.READY, approved.record.state)
        }
    }

    @Test fun collidingModelNamesDispatchToDistinctToolsInFrozenOrder() = runBlocking {
        Fixture().use { f ->
            val keys = listOf("host/a/b", "host/a_b", "host/" + "long.name".repeat(15))
            keys.forEach { key ->
                f.catalog.registerHost(key, "1", tool { ToolResult(text = key) })
            }
            val snapshot = f.catalog.freeze("run")
            assertEquals(keys.size, snapshot.size)
            assertEquals(snapshot.keys.sorted(), snapshot.keys.toList())
            snapshot.forEach { (name, definition) ->
                assertTrue(name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
                assertEquals(definition, f.catalog.definition("run", name))
                assertNotNull(f.catalog.current(definition))
            }
            assertNull(f.catalog.definition("run", "host_a_b"))
            val reducer = completedCalls(snapshot.keys.toList(), "aliases")
            val result = f.service.executeStep(reducer, "chain", "session", "run", "step")!!
            val parts = result.parts.map { it as ContentPart.ToolResult }
            assertEquals(snapshot.values.map { it.toolKey }, parts.map { f.ledger.get(it.callId)!!.result!!.text })
            parts.forEach { assertEquals(Status.SUCCEEDED, f.ledger.get(it.callId)!!.state) }
        }
    }

    @Test fun catalogNamesAndOrderDoNotDependOnRegistrationOrder() = runBlocking {
        val keys = listOf("host/z", "host/a/b", "host/a_b", "host/a_b_02c2b0e6aca1", "host/a.b",
            "host/" + "x".repeat(90))
        Fixture().use { first ->
            Fixture().use { second ->
                keys.forEach { first.catalog.registerHost(it, "1", HostClockTool()) }
                keys.reversed().forEach { second.catalog.registerHost(it, "1", HostClockTool()) }
                assertEquals(first.catalog.freeze("run").toList(), second.catalog.freeze("run").toList())
            }
        }
    }

    @Test fun literalHashAliasIsResolvedOnlyWithinItsFrozenRun() = runBlocking {
        Fixture().use { f ->
            val a = "host/a/b"
            val b = "host/a_b"
            val literal = "host/a_b_02c2b0e6aca1"
            val literalRetry = "host/a_b_02c2b0e6aca1_1"
            listOf(a, b).forEach { key ->
                f.catalog.registerHost(key, "1", tool { ToolResult(text = key) })
            }
            val old = f.catalog.freeze("old")
            listOf(literalRetry, literal).forEach { key ->
                f.catalog.registerHost(key, "1", tool { ToolResult(text = key) })
            }
            val fresh = f.catalog.freeze("fresh")
            val sharedName = "host_a_b_02c2b0e6aca1"
            assertEquals(a, old.getValue(sharedName).toolKey)
            assertEquals(literal, fresh.getValue(sharedName).toolKey)
            assertEquals(a, fresh.getValue("${sharedName}_2").toolKey)
            assertEquals(setOf(a, b, literal, literalRetry), fresh.values.map { it.toolKey }.toSet())
            assertEquals(old, f.catalog.freeze("old"))
            for ((run, snapshot) in listOf("old" to old, "fresh" to fresh)) {
                snapshot.forEach { (name, definition) ->
                    assertEquals(definition, f.catalog.definition(run, name))
                }
                val message = f.service.executeStep(completedCalls(snapshot.keys.toList(), "request-$run"),
                    "chain", "session", run, "step")!!
                val records = message.parts.map { f.ledger.get((it as ContentPart.ToolResult).callId)!! }
                assertEquals(snapshot.values.map { it.toolKey }, records.map { it.result!!.text })
                records.forEach { assertEquals(Status.SUCCEEDED, it.state) }
            }
            assertNull(f.catalog.definition("old", "${sharedName}_2"))
        }
    }

    @Test fun oldRunAliasesSurviveNewCollisionsButRevokedAndReplacedToolsReject() = runBlocking {
        Fixture().use { f ->
            val a = "host/a/b"
            val b = "host/a_b"
            f.catalog.registerHost(a, "1", tool { ToolResult(text = "original-a") })
            val old = f.catalog.freeze("old")
            f.catalog.registerHost(b, "1", tool { ToolResult(text = "b") })
            val newer = f.catalog.freeze("new")
            assertEquals(old, f.catalog.freeze("old"))
            assertEquals(old, f.catalog.frozen("old"))
            assertEquals("host_a_b", old.keys.single())
            assertFalse(newer.containsKey(old.keys.single()))
            suspend fun execute(run: String, names: List<String>, request: String) =
                f.service.executeStep(completedCalls(names, request), "chain", "session", run, request)!!
                    .parts.map { it as ContentPart.ToolResult }
            fun output(part: ContentPart.ToolResult) = f.ledger.get(part.callId)!!.result!!.text
            assertEquals("original-a", output(execute("old", old.keys.toList(), "old-before").single()))
            assertEquals(listOf("original-a", "b"), execute("new", newer.keys.toList(), "new-before").map(::output))
            // Returned map containers cannot mutate the catalog's internal snapshot.
            (old as MutableMap).clear()
            assertEquals("host_a_b", f.catalog.freeze("old").keys.single())
            f.catalog.revoke(b)
            f.catalog.registerHost(a, "2", tool { ToolResult(text = "replacement-a") })
            newer.values.forEach { assertNull(f.catalog.current(it)) }
            assertEquals(newer, f.catalog.freeze("new"))
            val rejected = execute("new", newer.keys.toList(), "new-after")
            rejected.forEach { assertEquals(Status.REJECTED, f.ledger.get(it.callId)!!.state) }
            val oldRejected = execute("old", listOf("host_a_b"), "old-after").single()
            assertEquals(Status.REJECTED, f.ledger.get(oldRejected.callId)!!.state)
            val latest = f.catalog.freeze("latest")
            assertEquals("host_a_b", latest.keys.single())
            assertEquals("replacement-a", output(execute("latest", latest.keys.toList(), "latest").single()))
        }
    }

    @Test fun recordedModelEventsInvokeRealClockAndProduceOneOrderedToolMessage() = runBlocking {
        Fixture().use { f ->
            val d = f.register()
            val reducer = ModelEventReducer("recorded", "request")
            listOf(Events.started("response", "no-api"), Events.toolStart(0, "wire", d.modelName),
                Events.toolDelta(0, "{}"), Events.end(0), Events.finished(FinishReason.STOP)).forEach(reducer::accept)
            val result = f.service.executeStep(reducer, "chain", "session", "run", "step")!!
            assertEquals(Role.TOOL, result.role)
            assertEquals(1, result.parts.size)
            val part = result.parts.single() as ContentPart.ToolResult
            assertTrue(part.text.contains("42"))
            assertTrue(part.text.contains("structured JSON"))
            assertEquals(reducer.calls().single().binding.callId, part.callId)
            val r = f.ledger.get(part.callId)!!
            assertEquals(Status.SUCCEEDED, r.state)
            assertEquals(Effect.NONE, r.result!!.effect)
            assertEquals(InstanceState.EXIT_CONFIRMED, r.instance!!.state)
        }
    }

    private fun completedCall(name: String, args: String, request: String = "request",
        reason: FinishReason = FinishReason.STOP) = ModelEventReducer("recorded", request).also { reducer ->
        listOf(Events.started("response-$request", "no-api"), Events.toolStart(0, "reused-wire", name),
            Events.toolDelta(0, args), Events.end(0), Events.finished(reason)).forEach(reducer::accept)
    }

    private fun completedCalls(names: List<String>, request: String, reason: FinishReason = FinishReason.STOP,
        args: String = "{}") = ModelEventReducer("recorded", request).also { reducer ->
        reducer.accept(Events.started("response-$request", "no-api"))
        names.forEachIndexed { index, name ->
            reducer.accept(Events.toolStart(index, "wire-$index", name))
            reducer.accept(Events.toolDelta(index, args))
            reducer.accept(Events.end(index))
        }
        reducer.accept(Events.finished(reason))
    }

    @Test fun parallelBatchOverlapsAndKeepsCallOrderAfterReverseCompletionAndFailure() = runBlocking {
        Fixture().use { f ->
            val bothEntered = CountDownLatch(2)
            val releaseFirst = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            val executions = AtomicInteger()
            val first = f.catalog.registerHost("host/first", "1", tool(parallel = true, timeout = 10_000) {
                executions.incrementAndGet(); bothEntered.countDown(); releaseFirst.await()
                ToolResult("first result", effect = ToolEffect.NONE)
            })
            val second = f.catalog.registerHost("host/second", "1", tool(parallel = true, timeout = 10_000) {
                executions.incrementAndGet(); bothEntered.countDown(); releaseSecond.await()
                error("second failed")
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(first.modelName, second.modelName), "parallel")
            val ids = reducer.calls().map { it.binding.callId }
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue("Both bodies must enter before either is released", bothEntered.await(5, TimeUnit.SECONDS))
                assertEquals(2, f.budget.occupiedSlots())
                releaseSecond.countDown(); withTimeout(5_000) { f.service.awaitObservedExit(ids[1]) }
                assertEquals(Status.FAILED, f.ledger.get(ids[1])!!.state)
                assertEquals(Status.RUNNING, f.ledger.get(ids[0])!!.state)
                assertFalse(step.isCompleted)
                releaseFirst.countDown()
                val result = withTimeout(5_000) { step.await() }!!
                assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                assertTrue((result.parts[0] as ContentPart.ToolResult).text.contains("first result"))
                assertFalse((result.parts[0] as ContentPart.ToolResult).isError)
                assertTrue((result.parts[1] as ContentPart.ToolResult).isError)
                assertNotNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
                assertEquals(2, executions.get())
                assertEquals(0, f.budget.occupiedSlots())
            } finally { releaseFirst.countDown(); releaseSecond.countDown(); step.cancel() }
        }
    }

    @Test fun serialBarrierWaitsForParallelBatchAndBlocksFollowingBatch() = runBlocking {
        Fixture(slots = 3).use { f ->
            val bothEntered = CountDownLatch(2)
            val releaseBefore = CountDownLatch(1)
            val serialEntered = CountDownLatch(1)
            val releaseSerial = CountDownLatch(1)
            val afterEntered = CountDownLatch(2)
            val releaseAfter = CountDownLatch(1)
            val before = f.catalog.registerHost("host/before", "1", tool(parallel = true, timeout = 10_000) {
                bothEntered.countDown(); releaseBefore.await()
                ToolResult("before", effect = ToolEffect.NONE)
            })
            lateinit var ids: List<String>
            val serial = f.catalog.registerHost("host/serial", "1", tool(timeout = 10_000) {
                assertTrue(ids.take(2).all { f.ledger.get(it)!!.instance!!.state == InstanceState.EXIT_CONFIRMED })
                serialEntered.countDown(); releaseSerial.await(); ToolResult("serial", effect = ToolEffect.NONE)
            })
            val after = f.catalog.registerHost("host/after", "1", tool(parallel = true, timeout = 10_000) {
                assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(ids[2])!!.instance!!.state)
                afterEntered.countDown(); releaseAfter.await(); ToolResult("after", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(before.modelName, before.modelName, serial.modelName,
                after.modelName, after.modelName), "barriers")
            ids = reducer.calls().map { it.binding.callId }
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(bothEntered.await(5, TimeUnit.SECONDS))
                assertTrue(ids.take(2).all { f.ledger.get(it)!!.state == Status.RUNNING })
                assertTrue(ids.take(2).all { f.ledger.get(it)!!.instance!!.state == InstanceState.UNCONFIRMED })
                assertNull(f.ledger.get(ids[2])!!.instance)
                releaseBefore.countDown()
                assertTrue(serialEntered.await(5, TimeUnit.SECONDS))
                assertTrue(ids.drop(3).all { f.ledger.get(it)!!.instance == null })
                releaseSerial.countDown()
                assertTrue(afterEntered.await(5, TimeUnit.SECONDS))
                releaseAfter.countDown()
                val result = withTimeout(5_000) { step.await() }!!
                assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                assertTrue(ids.all { f.ledger.get(it)!!.state == Status.SUCCEEDED })
            } finally { releaseBefore.countDown(); releaseSerial.countDown(); releaseAfter.countDown(); step.cancel() }
        }
    }

    @Test fun parallelBatchLargerThanBudgetDrainsWithoutDroppingCalls() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val d = f.register(tool(parallel = true, timeout = 10_000) {
                val n = calls.incrementAndGet()
                if (n <= 2) { entered.countDown(); release.await() }
                ToolResult("result-$n", effect = ToolEffect.NONE)
            })
            val reducer = completedCalls(List(5) { d.modelName }, "capacity")
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(2, f.budget.occupiedSlots())
                assertNull(f.ledger.get(reducer.calls()[2].binding.callId)!!.instance)
                release.countDown()
                assertEquals(5, withTimeout(5_000) { step.await() }!!.parts.size)
                assertEquals(5, calls.get())
                assertEquals(0, f.budget.occupiedSlots())
            } finally { release.countDown(); step.cancel() }
        }
    }

    @Test fun capacityBackpressureUsesFreedSlotBeforeSlowSiblingExits() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            val thirdEntered = CountDownLatch(1)
            val slow = f.catalog.registerHost("host/slow", "1", tool(parallel = true, timeout = 10_000) {
                entered.countDown(); release.await(); ToolResult("slow", effect = ToolEffect.NONE)
            })
            val fast = f.catalog.registerHost("host/fast", "1", tool(parallel = true) {
                entered.countDown(); entered.await(); ToolResult("fast", effect = ToolEffect.NONE)
            })
            val third = f.catalog.registerHost("host/third", "1", tool(parallel = true) {
                thirdEntered.countDown(); ToolResult("third", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(slow.modelName, fast.modelName, third.modelName), "free-slot")
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(thirdEntered.await(5, TimeUnit.SECONDS))
                assertEquals(Status.RUNNING, f.ledger.get(reducer.calls()[0].binding.callId)!!.state)
                release.countDown()
                assertEquals(3, withTimeout(5_000) { step.await() }!!.parts.size)
            } finally { release.countDown(); step.cancel() }
        }
    }

    @Test fun cancellingCallQueuedForCapacityDoesNotStallLaterSiblings() = runBlocking {
        Fixture(slots = 1).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val queuedRuns = AtomicInteger(); val thirdRuns = AtomicInteger()
            val slow = f.catalog.registerHost("host/slow", "1", tool(parallel = true, timeout = 10_000) {
                entered.countDown(); release.await(); ToolResult("slow", effect = ToolEffect.NONE)
            })
            val queued = f.catalog.registerHost("host/queued", "1", tool(parallel = true) {
                queuedRuns.incrementAndGet(); ToolResult("queued", effect = ToolEffect.NONE)
            })
            val third = f.catalog.registerHost("host/third", "1", tool(parallel = true) {
                thirdRuns.incrementAndGet(); ToolResult("third", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(slow.modelName, queued.modelName, third.modelName), "queued-cancel")
            val ids = reducer.calls().map { it.binding.callId }
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(ids[1])?.state == Status.READY }
                delay(200) // Let the step reach its capacity wait for the queued call.
                assertTrue(f.service.cancel(ids[1]) is Mutation.Applied)
                release.countDown()
                val result = withTimeout(5_000) { step.await() }!!
                assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                assertEquals(listOf(Status.SUCCEEDED, Status.CANCELLED, Status.SUCCEEDED), ids.map { f.ledger.get(it)!!.state })
                assertEquals(Effect.NONE, f.ledger.get(ids[1])!!.result!!.effect)
                assertEquals(0, queuedRuns.get())
                assertEquals(1, thirdRuns.get())
            } finally { release.countDown(); step.cancel() }
        }
    }

    @Test fun parallelSafeCallsSharingResourceStillSerializeWithinStep() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val calls = AtomicInteger()
            lateinit var firstId: String
            val d = f.register(tool(resource = "shared", parallel = true, timeout = 10_000) {
                if (calls.incrementAndGet() == 1) { entered.countDown(); release.await() }
                else assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(firstId)!!.instance!!.state)
                ToolResult("ok", effect = ToolEffect.NONE)
            })
            val reducer = completedCalls(List(2) { d.modelName }, "resource")
            firstId = reducer.calls()[0].binding.callId
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNull(f.ledger.get(reducer.calls()[1].binding.callId)!!.instance)
                release.countDown()
                val results = withTimeout(5_000) { step.await() }!!.parts
                assertEquals(2, results.size)
                assertTrue(results.all { !(it as ContentPart.ToolResult).isError })
                assertEquals(2, calls.get())
            } finally { release.countDown(); step.cancel() }
        }
    }

    @Test fun parallelTimeoutOrCancellationDoesNotReleaseSerialBarrier() = runBlocking {
        for (cancel in listOf(false, true)) Fixture(slots = 3).use { f ->
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            val parallel = f.catalog.registerHost("host/parallel", "1",
                tool(parallel = true, timeout = if (cancel) 10_000 else 300) {
                    entered.countDown(); release.await(); ToolResult("late", effect = ToolEffect.NONE)
                })
            val serialCalls = AtomicInteger()
            val serial = f.catalog.registerHost("host/serial", "1", tool {
                serialCalls.incrementAndGet(); ToolResult("serial", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(parallel.modelName, parallel.modelName, serial.modelName), "terminal-$cancel")
            val ids = reducer.calls().map { it.binding.callId }
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (cancel) ids.take(2).forEach { f.service.cancel(it) }
                assertNull(withTimeout(5_000) { step.await() })
                assertEquals(0, serialCalls.get())
                assertEquals(2, f.budget.occupiedSlots())
                ids.take(2).forEach {
                    assertEquals(if (cancel) Status.CANCELLED else Status.TIMED_OUT, f.ledger.get(it)!!.state)
                    assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(it)!!.instance!!.state)
                }
                assertNull(f.ledger.get(ids[2])!!.instance)
                release.countDown()
                ids.take(2).forEach { withTimeout(5_000) { f.service.awaitObservedExit(it) } }
                // Late completion cannot replace either logical result or cause a second claim.
                val result = f.service.executeStep(reducer, "chain", "session", "run", "step")!!
                assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                assertEquals(1, serialCalls.get())
                ids.take(2).forEach {
                    assertEquals(if (cancel) Status.CANCELLED else Status.TIMED_OUT, f.ledger.get(it)!!.state)
                }
            } finally { release.countDown(); step.cancel() }
        }
    }

    @Test fun parallelApprovalsStayOrderedAndDeniedCallDoesNotBlockApprovedSibling() = runBlocking {
        Fixture().use { f ->
            val calls = AtomicInteger()
            val d = f.register(tool(ToolRisk.WRITE, parallel = true) {
                calls.incrementAndGet(); ToolResult("approved", effect = ToolEffect.NONE)
            })
            val reducer = completedCalls(List(3) { d.modelName }, "approval-order")
            val ids = reducer.calls().map { it.binding.callId }
            assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
            assertEquals(Status.WAITING_APPROVAL, f.ledger.get(ids[0])!!.state)
            assertEquals(Status.RECEIVED, f.ledger.get(ids[1])!!.state)
            assertEquals(0, calls.get())
            val request = f.ledger.get(ids[0])!!.pending!!
            f.service.decide(ids[0], request.id, Decision.DENY)
            assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
            assertEquals(Status.WAITING_APPROVAL, f.ledger.get(ids[1])!!.state)
            assertEquals(Status.RECEIVED, f.ledger.get(ids[2])!!.state)
            val next = f.ledger.get(ids[1])!!.pending!!
            f.service.decide(ids[1], next.id, Decision.ALLOW_SESSION)
            val result = withTimeout(5_000) { f.service.executeStep(reducer, "chain", "session", "run", "step") }!!
            assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
            assertEquals(listOf(Status.REJECTED, Status.SUCCEEDED, Status.SUCCEEDED), ids.map { f.ledger.get(it)!!.state })
            assertEquals(2, calls.get())
        }
    }

    @Test fun resumingApprovalObservesRunningSiblingWithoutDuplicateClaim() = runBlocking {
        Fixture().use { f ->
            val readEntered = CountDownLatch(1); val writeEntered = CountDownLatch(1)
            val release = CountDownLatch(1); val calls = AtomicInteger()
            val read = f.catalog.registerHost("host/read", "1", tool(parallel = true, timeout = 10_000) {
                calls.incrementAndGet(); readEntered.countDown(); release.await()
                ToolResult("read", effect = ToolEffect.NONE)
            })
            val write = f.catalog.registerHost("host/write", "1", tool(ToolRisk.WRITE, parallel = true, timeout = 10_000) {
                calls.incrementAndGet(); writeEntered.countDown(); release.await()
                ToolResult("write", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(read.modelName, write.modelName), "resume")
            val ids = reducer.calls().map { it.binding.callId }
            try {
                assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
                assertTrue(readEntered.await(5, TimeUnit.SECONDS))
                val attempt = f.ledger.get(ids[0])!!.instance!!.attemptId
                assertEquals(Status.WAITING_APPROVAL, f.ledger.get(ids[1])!!.state)
                assertEquals(1L, writeEntered.count)
                approve(f, ids[1])
                val resumed = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
                try {
                    assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
                    assertEquals(2, f.budget.occupiedSlots())
                    assertEquals(attempt, f.ledger.get(ids[0])!!.instance!!.attemptId)
                    release.countDown()
                    assertEquals(ids, withTimeout(5_000) { resumed.await() }!!.parts.map { (it as ContentPart.ToolResult).callId })
                    assertEquals(2, calls.get())
                } finally { release.countDown(); resumed.cancel() }
            } finally { release.countDown() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun approvalResumeWaitsForNormalResultCompletionHandlerBeforeSerialClaim() = runBlocking {
        Fixture().use { f ->
            val handler = CountDownLatch(1); val release = CountDownLatch(1)
            val read = f.catalog.registerHost("host/read", "1", tool(parallel = true, timeout = 10_000) {
                currentCoroutineContext()[Job]!!.parent!!.invokeOnCompletion { handler.countDown(); release.await() }
                ToolResult("read", effect = ToolEffect.NONE)
            })
            val writes = AtomicInteger()
            val write = f.catalog.registerHost("host/write", "1", tool(ToolRisk.WRITE) {
                writes.incrementAndGet(); ToolResult("write", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(read.modelName, write.modelName), "normal-resume")
            val ids = reducer.calls().map { it.binding.callId }
            try {
                assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
                assertTrue(handler.await(5, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(ids[0])!!.state == Status.SUCCEEDED }
                val attempt = f.ledger.get(ids[0])!!.instance!!.attemptId
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(ids[0])!!.instance!!.state)
                approve(f, ids[1])
                val resumed = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
                try {
                    delay(100)
                    assertFalse("Resume must observe the live completion handler", resumed.isCompleted)
                    assertEquals(0, writes.get())
                    release.countDown()
                    val result = withTimeout(5_000) { resumed.await() }!!
                    assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                    assertEquals(attempt, f.ledger.get(ids[0])!!.instance!!.attemptId)
                    assertEquals(1, writes.get())
                } finally { release.countDown(); resumed.cancel() }
            } finally { release.countDown() }
        }
    }

    @Test fun siblingStorageFailureInterruptsWaitingForSlowFirstCall() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val slow = f.catalog.registerHost("host/slow", "1", tool(parallel = true, timeout = 10_000) {
                entered.countDown(); release.await(); ToolResult("slow", effect = ToolEffect.NONE)
            })
            val fast = f.catalog.registerHost("host/fast", "1", tool(parallel = true) {
                ToolResult("fast", effect = ToolEffect.NONE)
            })
            f.catalog.freeze("run")
            val reducer = completedCalls(listOf(slow.modelName, fast.modelName), "storage-failure")
            val ids = reducer.calls().map { it.binding.callId }
            f.store.fail = { it.callId == ids[1] && it.state == Status.SUCCEEDED }
            val step = async(Dispatchers.IO) { f.service.executeStep(reducer, "chain", "session", "run", "step") }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNull(withTimeout(2_000) { step.await() })
                assertEquals(Status.RUNNING, f.ledger.get(ids[0])!!.state)
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(ids[1])!!.instance!!.state)
            } finally { release.countDown(); step.cancel() }
        }
    }

    @Test fun parallelBatchCannotExceedDurableClaimBudget() = runBlocking {
        Fixture(maxClaims = 1).use { f ->
            val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val d = f.register(tool(parallel = true, timeout = 10_000) {
                calls.incrementAndGet(); entered.countDown(); release.await()
                ToolResult("ok", effect = ToolEffect.NONE)
            })
            val reducer = completedCalls(List(2) { d.modelName }, "claim-budget")
            try {
                // Exhaustion is permanent: return without waiting for the running sibling.
                assertNull(withTimeout(2_000) { f.service.executeStep(reducer, "chain", "session", "run", "step") })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
                assertNull(f.ledger.get(reducer.calls()[1].binding.callId)!!.instance)
                release.countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(reducer.calls()[0].binding.callId) }
                assertEquals(0, f.budget.occupiedSlots())
            } finally { release.countDown() }
        }
    }

    @Test fun mixedUnknownCallsKeepOrderUniqueResultsAndIdempotentStepReplay() = runBlocking {
        Fixture().use { f ->
            val executions = AtomicInteger()
            val d = f.register(tool { executions.incrementAndGet(); ToolResult("known", effect = ToolEffect.NONE) })
            val allIds = mutableSetOf<String>()
            repeat(2) { turn ->
                val reducer = completedCalls(listOf("missing", d.modelName, "missing", d.modelName), "mixed-$turn")
                val result = f.service.executeStep(reducer, "chain", "session", "run", "step-$turn")!!
                val ids = reducer.calls().map { it.binding.callId }
                assertEquals(ids, result.parts.map { (it as ContentPart.ToolResult).callId })
                assertEquals(4, ids.distinct().size)
                assertTrue(ids.all { allIds.add(it) })
                ids.forEachIndexed { index, id ->
                    val record = f.ledger.get(id)!!
                    assertEquals(index, record.input.callOrder)
                    assertEquals(if (index % 2 == 0) Status.REJECTED else Status.SUCCEEDED, record.state)
                    assertEquals(Effect.NONE, record.result!!.effect)
                    assertEquals(index % 2 == 0, (result.parts[index] as ContentPart.ToolResult).isError)
                    if (index % 2 == 0) {
                        assertEquals("missing", record.input.modelName)
                        assertNull(record.input.definition)
                        assertNull(record.instance)
                        assertTrue(f.service.dispatch(id) is Mutation.Conflict)
                    }
                }
                val before = f.store.records()
                val replay = f.service.executeStep(reducer, "chain", "session", "run", "step-$turn")!!
                assertEquals(result.parts.map { (it as ContentPart.ToolResult).text },
                    replay.parts.map { (it as ContentPart.ToolResult).text })
                assertEquals(before, f.store.records())
                assertEquals((turn + 1) * 2, executions.get())
            }
            assertEquals(8, f.store.records().size)
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun missingRunCatalogLeavesNoRejectionAndStepRunsOnceCatalogIsFrozen() = runBlocking {
        Fixture().use { f ->
            val executions = AtomicInteger()
            val d = f.register(tool { executions.incrementAndGet(); ToolResult("known", effect = ToolEffect.NONE) })
            val reducer = completedCalls(listOf(d.modelName, "missing"), "unfrozen")
            assertThrows(IllegalStateException::class.java) {
                runBlocking { f.service.executeStep(reducer, "chain", "session", "later", "step") }
            }
            assertTrue(f.store.records().isEmpty())
            f.catalog.freeze("later")
            val result = f.service.executeStep(reducer, "chain", "session", "later", "step")!!
            assertEquals(2, result.parts.size)
            assertEquals(1, executions.get())
            assertEquals(listOf(Status.SUCCEEDED, Status.REJECTED),
                reducer.calls().map { f.ledger.get(it.binding.callId)!!.state })
        }
    }

    @Test fun unknownRegistrationFailurePreventsEveryBodyInTheStep() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool { error("Partial step must not execute") })
            f.store.fail = { it.input.definition == null }
            val reducer = completedCalls(listOf(d.modelName, "missing", d.modelName), "failed-write")
            assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
            assertEquals(Status.RECEIVED, f.store.records().single().state)
            assertTrue(f.store.records().all { it.instance == null })
            assertEquals(0, f.budget.occupiedSlots())
            f.store.fail = { false }
            assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
        }
    }

    @Test fun unknownCallsRespectTruncatedAndDiscardedTurnDisposition() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool { error("Truncated turn must not execute") })
            for ((index, reason) in listOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED).withIndex()) {
                val reducer = completedCalls(listOf("missing", d.modelName), "truncated-$index", reason)
                val result = f.service.executeStep(reducer, "chain", "session", "run", "step-$index")!!
                assertEquals(2, result.parts.size)
                reducer.calls().forEach { call ->
                    val record = f.ledger.get(call.binding.callId)!!
                    assertEquals(Status.CANCELLED, record.state)
                    assertEquals(Effect.NONE, record.result!!.effect)
                    assertNull(record.instance)
                }
                val discarded = completedCalls(listOf("missing"), "discarded-$index", reason, "{")
                assertNull(f.service.executeStep(discarded, "chain", "session", "run", "discarded-$index"))
            }
            assertEquals(4, f.store.records().size)
        }
    }

    @Test fun mixedUnknownStepReopensAndReplaysWithoutExecutingAgain() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("unknown-bridge-store")
        val budget = PluginExecutionBudget()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var store: JsonInvocationStore? = null
        try {
            val gate = DefinitionGate()
            val validator = Schema202012Validator()
            val catalog = FrozenToolCatalog(gate, budget, owner, validator)
            val executions = AtomicInteger()
            val d = catalog.registerHost("host/test", "1", tool {
                executions.incrementAndGet(); ToolResult("known", effect = ToolEffect.NONE)
            })
            catalog.freeze("run")
            store = JsonInvocationStore.create(dir, Durability.PROCESS_CRASH_ONLY)
            val ledger = InvocationLedger(store, gate, validator)
            ledger.recoverColdStart("before-reopen")
            val service = HostToolExecutionService(budget, owner, catalog, ledger, validator)
            val reducer = completedCalls(listOf("missing", d.modelName, "missing"), "disk-mixed")
            val result = service.executeStep(reducer, "chain", "session", "run", "step")!!
            val before = store.records().sortedBy { it.input.callOrder }
            store.close()
            store = JsonInvocationStore.open(dir, Durability.PROCESS_CRASH_ONLY)
            val recovered = InvocationLedger(store, gate, validator)
            recovered.recoverColdStart("after-reopen")
            val reopenedService = HostToolExecutionService(budget, owner, catalog, recovered, validator)
            val replay = reopenedService.executeStep(reducer, "chain", "session", "run", "step")!!
            assertEquals(before, store.records().sortedBy { it.input.callOrder })
            assertEquals(result.parts.map { (it as ContentPart.ToolResult).text },
                replay.parts.map { (it as ContentPart.ToolResult).text })
            assertEquals(1, executions.get())
            assertEquals(2, before.count { it.state == Status.REJECTED && it.instance == null && it.result!!.effect == Effect.NONE })
        } finally {
            store?.close(); budget.close(); owner.cancel(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun emptyAndNullArgumentsUseFrozenSchemaWithoutLosingOriginalText() = runBlocking {
        for (required in listOf(false, true)) Fixture().use { f ->
            val calls = AtomicInteger()
            val schema = if (required) Json.parseToJsonElement(
                """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""").jsonObject else emptySchema
            val d = f.register(object : Tool {
                override val id = "empty-check"
                override val spec = ToolSpec("empty_check", "empty argument validation", schema)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    calls.incrementAndGet(); assertEquals(JsonObject(emptyMap()), args)
                    return ToolResult("ok", effect = ToolEffect.NONE)
                }
            })
            for ((index, raw) in listOf("", "null", " \t\n", "{", "[]").withIndex()) {
                val reducer = completedCall(d.modelName, raw, "case-$index")
                assertEquals(raw.isEmpty(), reducer.calls().single().emptyArguments)
                if (raw.isEmpty()) assertEquals(FinishReason.TOOL_CALLS, (reducer.terminal as ModelEvent.Finished).reason)
                val result = f.service.executeStep(reducer, "chain", "session", "run", "case-$index")!!
                val record = f.ledger.get(reducer.calls().single().binding.callId)!!
                val allowed = !required && (raw.isEmpty() || raw == "null")
                assertEquals(raw, record.input.argsJson)
                assertEquals(if (allowed) Status.SUCCEEDED else Status.REJECTED, record.state)
                assertEquals(if (allowed) "{}" else null, record.executionArgs)
                assertEquals(!allowed, (result.parts.single() as ContentPart.ToolResult).isError)
                if (!allowed) assertNull(record.instance)
            }
            assertEquals(if (required) 0 else 2, calls.get())
        }
    }

    @Test fun emptyArgumentsOnTruncatedTurnNeverReachLedgerOrTool() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool { error("truncated empty arguments must not execute") })
            for (reason in listOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)) {
                val reducer = completedCall(d.modelName, "", reason = reason)
                assertTrue(reducer.calls().single().emptyArguments)
                assertNull(f.service.executeStep(reducer, "chain", "session", "run", "step"))
            }
            assertTrue(f.store.records().isEmpty())
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun reusedWireIdsKeepDistinctLedgerBindingsAndStableReplayPairs() = runBlocking {
        Fixture().use { f ->
            val d = f.register()
            val history = mutableListOf<Message>()
            for (turn in 1..2) {
                val reducer = completedCall(d.modelName, "{}", "turn-$turn")
                val result = f.service.executeStep(reducer, "chain", "session", "run", "step-$turn")!!
                val record = f.ledger.get(reducer.calls().single().binding.callId)!!
                assertEquals("turn-$turn", record.input.binding.requestId)
                assertEquals("response-turn-$turn", record.input.binding.responseId)
                assertEquals("reused-wire", record.input.binding.wireId)
                history += message(Role.ASSISTANT, toolCallPart(record.input.binding, d.modelName, record.input.argsJson))
                history += result
            }
            val records = f.store.records()
            assertEquals(2, records.map { it.callId }.distinct().size)
            val codec = OpenAiChatCodec(OpenAiChatConfig("recorded", "https://example.invalid/v1"))
            val request = chatRequest("no-api") { messages = history }
            val encoded = codec.encode(request)
            assertEquals(encoded, codec.encode(request))
            val wire = Json.parseToJsonElement(encoded).jsonObject["messages"]!!.jsonArray
            val mapped = "call_${records[1].callId.replace("-", "")}"
            for ((turn, expected) in listOf("reused-wire", mapped).withIndex()) {
                assertEquals(expected, wire[turn * 2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals(expected, wire[turn * 2 + 1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
            }
            assertTrue(records.all { it.input.binding.wireId == "reused-wire" })
        }
    }

    @Test fun providerTerminalMustBeValidatedBeforeBridgeDispatch() = runBlocking {
        for (suffix in listOf(": ping\n\n", "data: {}\n\n")) Fixture().use { f ->
            val calls = AtomicInteger()
            val d = f.register(tool { calls.incrementAndGet(); ToolResult("ok") })
            val wire = "data: " + """{"id":"r","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"w","type":"function","function":{"name":"${d.modelName}","arguments":""}}]},"finish_reason":null}]}""" + "\n\n" +
                "data: " + """{"id":"r","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" + "\n\ndata: [DONE]\n\n" + suffix
            val transport = ModelTransport { _, _, _ -> flow {
                emit(TransportEvent.Headers(200, "text/event-stream", null))
                emit(TransportEvent.Bytes(wire.toByteArray()))
            } }
            val reducer = ModelEventReducer("recorded", "terminal")
            OpenAiChatProvider(OpenAiChatConfig("recorded", "https://example.invalid/v1"), transport)
                .chat(chatRequest("no-api") { messages = listOf(message(Role.USER, textPart("test"))) }, Credentials { "fixture" })
                .validated("recorded", "terminal").collect(reducer::accept)
            val result = f.service.executeStep(reducer, "chain", "session", "run", "step")
            if (suffix.startsWith(":")) {
                assertNotNull(result); assertEquals(1, calls.get())
                assertEquals(Status.SUCCEEDED, f.store.records().single().state)
            } else {
                assertTrue(reducer.terminal is ModelEvent.Failed)
                assertNull(result); assertEquals(0, calls.get()); assertTrue(f.store.records().isEmpty())
            }
        }
    }

    @Test fun frozenDefinitionAndOriginalArgumentsReachActualToolWithLedgerId() = runBlocking {
        Fixture().use { f ->
            val properties = mutableMapOf<String, JsonElement>("type" to JsonPrimitive("object"))
            var specs = 0
            var received: String? = null
            val t = object : Tool {
                override val id = "mutable"
                override val spec: ToolSpec get() {
                    specs++
                    return ToolSpec("mutable", "original", JsonObject(properties))
                }
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    received = env.callId
                    assertEquals("  value  ", args["key"]!!.jsonPrimitive.content)
                    return ToolResult("ok", effect = ToolEffect.NONE)
                }
            }
            val d = f.register(t)
            properties["type"] = JsonPrimitive("array")
            val input = f.input(d, " { \"key\" : \"  value  \" } ")
            f.service.prepare(input)
            f.service.dispatch(input.binding.callId)
            f.service.awaitObservedExit(input.binding.callId)
            assertEquals(1, specs)
            assertEquals(input.binding.callId, received)
            assertEquals(input.argsJson, f.ledger.get(received!!)!!.executionArgs)
        }
    }

    @Test fun duplicateConcurrentDispatchClaimsExactlyOnce() = runBlocking {
        Fixture().use { f ->
            val calls = AtomicInteger()
            val d = f.register(tool { calls.incrementAndGet(); ToolResult("ok") })
            val i = f.input(d); f.service.prepare(i)
            val results = List(12) { async(Dispatchers.Default) { f.service.dispatch(i.binding.callId) } }.awaitAll()
            f.service.awaitObservedExit(i.binding.callId)
            assertEquals(1, results.count { it is Mutation.Applied && it.ticket != null })
            assertEquals(1, calls.get())
        }
    }

    @Test fun malformedAndSchemaInvalidArgumentsAreRejectedWithoutBody() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool { error("must not execute") })
            listOf("{", "[]", "5", "{\"extra\":1}").forEachIndexed { order, args ->
                val i = f.input(d, args, order)
                f.service.prepare(i)
                assertEquals(Status.REJECTED, f.ledger.get(i.binding.callId)!!.state)
                assertTrue(f.service.dispatch(i.binding.callId) is Mutation.Conflict)
            }
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun denyAndForgedApprovalCannotInvokeTool() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool(ToolRisk.WRITE) { error("must not execute") })
            val i = f.input(d); f.service.prepare(i)
            assertEquals(Status.WAITING_APPROVAL, f.ledger.get(i.binding.callId)!!.state)
            assertTrue(f.service.decide(i.binding.callId, "forged", Decision.ALLOW_ONCE) is Mutation.Conflict)
            val request = f.ledger.get(i.binding.callId)!!.pending!!
            f.service.decide(i.binding.callId, request.id, Decision.DENY)
            assertEquals(Status.REJECTED, f.ledger.get(i.binding.callId)!!.state)
            assertTrue(f.service.decide(i.binding.callId, request.id, Decision.ALLOW_ONCE) is Mutation.Conflict)
            assertTrue(f.service.dispatch(i.binding.callId) is Mutation.Conflict)
        }
    }

    @Test fun policyDenialAndRevocationBeforeClaimAreFailClosed() = runBlocking {
        Fixture(ApprovalPolicy(denied = { true })).use { f ->
            val i = f.input(f.register(tool { error("denied") })); f.service.prepare(i)
            assertEquals(Status.REJECTED, f.ledger.get(i.binding.callId)!!.state)
        }
        Fixture().use { f ->
            val i = f.input(f.register(tool { error("revoked") })); f.service.prepare(i)
            f.catalog.revoke(requireNotNull(i.definition).toolKey)
            assertTrue(f.service.dispatch(i.binding.callId) is Mutation.Applied)
            assertEquals(Status.REJECTED, f.ledger.get(i.binding.callId)!!.state)
            assertNull(f.ledger.get(i.binding.callId)!!.instance)
        }
    }

    @Test fun claimWriteFailureReleasesReservationAndClosesFurtherSubmissions() = runBlocking {
        Fixture().use { f ->
            val i = f.input(f.register(tool { error("unacknowledged claim") })); f.service.prepare(i)
            f.store.fail = { it.state == Status.RUNNING }
            assertTrue(f.service.dispatch(i.binding.callId) is Mutation.StorageFailure)
            assertEquals(0, f.budget.occupiedSlots())
            assertNull(f.ledger.get(i.binding.callId)!!.instance)
            f.store.fail = { false }
            assertTrue(f.service.dispatch(i.binding.callId) is Mutation.Blocked)
        }
    }

    @Test fun capacityRejectionDoesNotCreateLedgerInstance() = runBlocking {
        Fixture().use { f ->
            val i = f.input(f.register(tool { error("full") })); f.service.prepare(i)
            val a = f.budget.reserve("other", f.owner, {}, {})!!
            val b = f.budget.reserve("other", f.owner, {}, {})!!
            try {
                assertTrue(f.service.dispatch(i.binding.callId) is Mutation.Blocked)
                assertNull(f.ledger.get(i.binding.callId)!!.instance)
            } finally { a.abandon(); b.abandon() }
        }
    }

    @Test fun graceExpiredTimeoutBlocksUntilExitThenReusesOriginalToolAndReleasesResource() = runBlocking {
        Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val original = tool(timeout = 100, resource = "shared") {
                if (calls.incrementAndGet() == 1) { entered.countDown(); release.await() }
                ToolResult("done", effect = ToolEffect.NONE)
            }
            try {
                val d = f.register(original)
                val first = f.input(d)
                val next = f.input(d, step = "next")
                f.service.prepare(first); f.service.prepare(next)
                f.service.dispatch(first.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(first.binding.callId)!!.restartRequired }
                val before = f.ledger.get(first.binding.callId)!!
                val message = f.ledger.resultsForStep("session", "run", "step", listOf(first.binding.callId))
                assertTrue(before.restartRequiredUntilExit)
                assertEquals(InstanceState.UNCONFIRMED, before.instance!!.state)
                repeat(3) {
                    assertEquals("Plugin restart required", (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                    assertEquals(1, f.budget.occupiedSlots())
                    assertNull(f.ledger.get(next.binding.callId)!!.instance)
                    delay(20)
                }
                release.countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
                val after = f.ledger.get(first.binding.callId)!!
                assertFalse(after.restartRequired)
                assertFalse(after.restartRequiredUntilExit)
                assertEquals(before.result, after.result)
                assertEquals(before.instance!!.attemptId, after.instance!!.attemptId)
                assertEquals(InstanceState.EXIT_CONFIRMED, after.instance!!.state)
                assertEquals(message, f.ledger.resultsForStep("session", "run", "step", listOf(first.binding.callId)))
                assertTrue(after.evidence.any { it.outcome?.status == Status.SUCCEEDED })
                assertEquals(0, f.budget.occupiedSlots())
                assertSame(original, f.catalog.current(d)!!.tool)
                assertNotNull((f.service.dispatch(next.binding.callId) as Mutation.Applied).ticket)
                withTimeout(5_000) { f.service.awaitObservedExit(next.binding.callId) }
                assertEquals(2, calls.get())
                assertSame(original, f.catalog.current(d)!!.tool)
            } finally { release.countDown() }
        }
    }

    @Test fun allGraceExpiredCallsOfOwnerMustExitBeforeItCanScheduleAgain() = runBlocking {
        Fixture(slots = 3, exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(2)
            val releases = listOf(CountDownLatch(1), CountDownLatch(1))
            val calls = AtomicInteger()
            val timerHeld = CountDownLatch(1); val releaseTimeouts = CountDownLatch(1)
            // Hold the existing deadline executor, not the workers or definition gate. Both
            // claims can enter before either real SDK timeout is allowed to fire.
            assertNotNull(f.budget.schedule(0) { timerHeld.countDown(); releaseTimeouts.await() })
            try {
                assertTrue(timerHeld.await(5, TimeUnit.SECONDS))
                val d = f.register(tool(timeout = 300, parallel = true) {
                    val index = calls.getAndIncrement()
                    if (index < 2) { entered.countDown(); releases[index].await() }
                    ToolResult("done", effect = ToolEffect.NONE)
                })
                val first = f.input(d); val second = f.input(d, order = 1)
                val next = f.input(d, step = "next")
                listOf(first, second, next).forEach { f.service.prepare(it) }
                assertNotNull((f.service.dispatch(first.binding.callId) as Mutation.Applied).ticket)
                // Fix entry order so releasing index 0 corresponds to the first ledger attempt.
                waitUntil { calls.get() == 1 }
                // Deliberately exceed the old 300 ms race window before the second dispatch.
                delay(400)
                assertNotNull((f.service.dispatch(second.binding.callId) as Mutation.Applied).ticket)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                listOf(first, second).forEach {
                    assertEquals(Status.RUNNING, f.ledger.get(it.binding.callId)!!.state)
                }
                releaseTimeouts.countDown()
                waitUntil { listOf(first, second).all { f.ledger.get(it.binding.callId)!!.restartRequired } }
                listOf(first, second).forEach {
                    assertEquals(Status.TIMED_OUT, f.ledger.get(it.binding.callId)!!.state)
                    assertTrue(f.ledger.get(it.binding.callId)!!.restartRequiredUntilExit)
                }
                assertEquals(2, f.budget.occupiedSlots())
                releases[0].countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
                assertFalse(f.ledger.get(first.binding.callId)!!.restartRequired)
                assertTrue(f.ledger.get(second.binding.callId)!!.restartRequired)
                assertEquals(1, f.budget.occupiedSlots())
                assertEquals("Plugin restart required", (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                releases[1].countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(second.binding.callId) }
                assertEquals(0, f.budget.occupiedSlots())
                assertNotNull((f.service.dispatch(next.binding.callId) as Mutation.Applied).ticket)
                withTimeout(5_000) { f.service.awaitObservedExit(next.binding.callId) }
                assertEquals(3, calls.get())
            } finally { releaseTimeouts.countDown(); releases.forEach { it.countDown() } }
        }
    }

    @Test fun graceExitDoesNotClearOtherRestartCauseBeforeOrAfterGraceExpiry() = runBlocking {
        for (stickyFirst in listOf(true, false)) Fixture(exitGraceMillis = 100).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val d = f.register(tool(timeout = 100) {
                    entered.countDown(); release.await(); ToolResult("late")
                })
                val first = f.input(d); val next = f.input(d, step = "next")
                f.service.prepare(first); f.service.prepare(next)
                val ticket = (f.service.dispatch(first.binding.callId) as Mutation.Applied).ticket!!
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (!stickyFirst) waitUntil { f.ledger.get(first.binding.callId)!!.restartRequiredUntilExit }
                assertTrue(f.ledger.markRestartRequired(first.binding.callId, ticket.attemptId) is Mutation.Applied)
                waitUntil { f.ledger.get(first.binding.callId)!!.state == Status.TIMED_OUT }
                // Exercise a later grace mark deterministically as well as the service timer.
                assertTrue(f.ledger.markRestartRequired(first.binding.callId, ticket.attemptId,
                    untilActualExit = true) is Mutation.AlreadyApplied)
                release.countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
                assertTrue(f.ledger.get(first.binding.callId)!!.restartRequired)
                assertFalse(f.ledger.get(first.binding.callId)!!.restartRequiredUntilExit)
                assertEquals(0, f.budget.occupiedSlots())
                assertEquals("Plugin restart required", (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
            } finally { release.countDown() }
        }
    }

    @Test fun graceExitDoesNotRestoreRevokedDefinition() = runBlocking {
        Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val d = f.register(tool(timeout = 100) {
                    entered.countDown(); release.await(); ToolResult("late")
                })
                val first = f.input(d); val next = f.input(d, step = "next")
                f.service.prepare(first); f.service.prepare(next); f.service.dispatch(first.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(first.binding.callId)!!.restartRequiredUntilExit }
                f.catalog.revoke(d.toolKey) // Disable/cleanup keeps its independent definition gate.
                release.countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
                assertFalse(f.ledger.get(first.binding.callId)!!.restartRequired)
                assertNull(f.catalog.current(d))
                assertEquals(Status.REJECTED, (f.service.dispatch(next.binding.callId) as Mutation.Applied).record.state)
                assertNull(f.ledger.get(next.binding.callId)!!.instance)
            } finally { release.countDown() }
        }
    }

    @Test fun timeoutRetainsWorkerAndLateUnknownQuarantinesReadResource() = runBlocking {
        Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val d = f.register(tool(timeout = 150, resource = "shared") {
                    entered.countDown(); release.await(); ToolResult("late", effect = ToolEffect.UNKNOWN)
                })
                val i = f.input(d); f.service.prepare(i); f.service.dispatch(i.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(i.binding.callId)!!.restartRequiredUntilExit }
                val before = f.ledger.get(i.binding.callId)!!
                assertEquals(InstanceState.UNCONFIRMED, before.instance!!.state)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(i.binding.callId)
                val after = f.ledger.get(i.binding.callId)!!
                assertEquals(Status.TIMED_OUT, after.state)
                assertEquals(InstanceState.EXIT_CONFIRMED, after.instance!!.state)
                assertTrue(after.evidence.any { it.outcome?.effect == Effect.UNKNOWN })
                assertEquals(before.result, after.result)
                assertFalse(after.restartRequired)
                assertNotNull(after.isolation)
                assertEquals(0, f.budget.occupiedSlots())
                val next = f.input(d, step = "next")
                f.service.prepare(next)
                assertEquals(listOf(i.binding.callId), f.ledger.get(next.binding.callId)!!.pending!!.unknownCalls)
                approve(f, next.binding.callId)
                assertEquals("Resource occupied or externally isolated",
                    (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                f.ledger.resolveRecovery(i.binding.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
                assertTrue(f.service.dispatch(next.binding.callId) is Mutation.Blocked)
                f.ledger.resolveRecovery(i.binding.callId, RecoveryResolution.VERIFIED_CLEAR)
                assertNotNull((f.service.dispatch(next.binding.callId) as Mutation.Applied).ticket)
                withTimeout(5_000) { f.service.awaitObservedExit(next.binding.callId) }
            } finally { release.countDown() }
        }
    }

    @Test fun lateUnknownIsPersistedWhileChildStillPreventsActualExit() = runBlocking {
        Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val returnBody = CountDownLatch(1); val releaseChild = CountDownLatch(1)
            try {
                val i = f.input(f.register(tool(timeout = 100, resource = "shared") {
                    CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
                        withContext(NonCancellable) { entered.countDown(); releaseChild.await() }
                    }
                    returnBody.await()
                    ToolResult("late return", effect = ToolEffect.UNKNOWN)
                }))
                f.service.prepare(i); f.service.dispatch(i.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(i.binding.callId)!!.restartRequiredUntilExit }
                returnBody.countDown()
                waitUntil { f.ledger.get(i.binding.callId)!!.evidence.any { it.outcome?.effect == Effect.UNKNOWN } }
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
                assertNotNull(f.ledger.get(i.binding.callId)!!.isolation)
                assertTrue(f.ledger.get(i.binding.callId)!!.restartRequired)
                assertEquals(1, f.budget.occupiedSlots())
                releaseChild.countDown(); f.service.awaitObservedExit(i.binding.callId)
                assertFalse(f.ledger.get(i.binding.callId)!!.restartRequired)
                assertNotNull(f.ledger.get(i.binding.callId)!!.isolation)
                assertEquals(0, f.budget.occupiedSlots())
            } finally { returnBody.countDown(); releaseChild.countDown() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun durableCancellationBeforeObservedSdkSuccessStaysRestartRequiredAfterActualExit() = runBlocking {
        for (effect in listOf(ToolEffect.NONE, ToolEffect.UNKNOWN)) Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val returnBody = CountDownLatch(1)
            val handler = CountDownLatch(1); val releaseHandler = CountDownLatch(1)
            val calls = AtomicInteger()
            try {
                val original = tool(timeout = 60_000, parallel = true, resource = "shared") {
                    if (calls.incrementAndGet() == 1) {
                        // This parent completes after PluginInvocation publishes SDK success.
                        currentCoroutineContext()[Job]!!.parent!!.invokeOnCompletion {
                            handler.countDown(); releaseHandler.await()
                        }
                        entered.countDown(); returnBody.await()
                    }
                    ToolResult("returned", effect = effect)
                }
                val d = f.register(original)
                val first = f.input(d); val next = f.input(d, step = "next")
                f.service.prepare(first); f.service.prepare(next)
                val ticket = (f.service.dispatch(first.binding.callId) as Mutation.Applied).ticket!!
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val cancelled = f.gate.withGate {
                    // Reproduce the host cancel ordering: durable cancel wins, then the worker
                    // publishes SDK success before cancellation delivery or monitor persistence.
                    val record = (f.ledger.cancel(first.binding.callId) as Mutation.Applied).record
                    returnBody.countDown()
                    assertTrue(handler.await(5, TimeUnit.SECONDS))
                    assertTrue(f.ledger.get(first.binding.callId)!!.evidence.isEmpty())
                    record
                }
                assertTrue(f.service.cancel(first.binding.callId) is Mutation.AlreadyApplied)
                waitUntil { f.ledger.get(first.binding.callId)!!.restartRequired }
                val blocked = f.ledger.get(first.binding.callId)!!
                assertFalse(blocked.restartRequiredUntilExit)
                assertEquals(cancelled.result, blocked.result)
                assertEquals(Status.CANCELLED, blocked.state)
                assertEquals(InstanceState.UNCONFIRMED, blocked.instance!!.state)
                assertTrue(blocked.evidence.any { it.outcome?.status == Status.SUCCEEDED &&
                    it.outcome?.effect == Effect.valueOf(effect.name) })
                assertEquals(effect == ToolEffect.UNKNOWN, blocked.isolation != null)
                assertEquals(1, f.budget.occupiedSlots())
                if (effect == ToolEffect.NONE) {
                    assertEquals("Plugin restart required", (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                }
                releaseHandler.countDown()
                withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
                val exited = f.ledger.get(first.binding.callId)!!
                assertEquals(cancelled.result, exited.result)
                assertEquals(ticket.attemptId, exited.instance!!.attemptId)
                assertEquals(InstanceState.EXIT_CONFIRMED, exited.instance!!.state)
                // DESIGN 13.11 releases only timeouts on actual exit; cancellation needs a restart.
                assertTrue(exited.restartRequired)
                assertFalse(exited.restartRequiredUntilExit)
                assertTrue(exited.evidence.containsAll(blocked.evidence))
                assertEquals(effect == ToolEffect.UNKNOWN, exited.isolation != null)
                assertEquals(0, f.budget.occupiedSlots())
                assertSame(original, f.catalog.current(d)!!.tool)
                if (effect == ToolEffect.NONE) {
                    assertEquals("Plugin restart required", (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                } else {
                    // Terminal UNKNOWN return evidence still requires review before the restart gate.
                    assertEquals(Status.WAITING_APPROVAL,
                        (f.service.dispatch(next.binding.callId) as Mutation.Applied).record.state)
                    approve(f, next.binding.callId)
                    assertEquals("Plugin restart required", (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
                }
                assertEquals(1, calls.get())
            } finally { returnBody.countDown(); releaseHandler.countDown() }
        }
    }

    @Test fun cancellationRetainsSlotUntilBlockedHandlerActuallyExits() = runBlocking {
        Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val cancelling = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val i = f.input(f.register(tool {
                    currentCoroutineContext()[Job]!!.invokeOnCompletion { cancelling.countDown(); release.await() }
                    entered.countDown(); awaitCancellation()
                }))
                f.service.prepare(i); f.service.dispatch(i.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                f.service.cancel(i.binding.callId)
                assertTrue(cancelling.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(i.binding.callId)!!.restartRequired }
                assertFalse(f.ledger.get(i.binding.callId)!!.restartRequiredUntilExit)
                assertEquals(Status.CANCELLED, f.ledger.get(i.binding.callId)!!.state)
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(i.binding.callId)
                assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
                assertTrue(f.ledger.get(i.binding.callId)!!.restartRequired)
                assertEquals(Status.CANCELLED, f.ledger.get(i.binding.callId)!!.state)
                assertEquals(0, f.budget.occupiedSlots())
            } finally { release.countDown() }
        }
    }

    private suspend fun pendingUpgrade(f: Fixture, id: String): ApprovalRequest = withTimeout(5_000) {
        var r = f.store.writes.receive()
        while (r.callId != id || r.pending?.internal != true) r = f.store.writes.receive()
        r.pending!!
    }

    @Test fun immediateRuntimeRequestCannotAdoptOrWithdrawConcurrentHighRiskWaiter() = runBlocking {
        for (grantedWrite in listOf(false, true)) for (cancelImmediate in listOf(false, true)) {
            val firstAtGap = CompletableDeferred<Unit>(); val releaseFirst = CompletableDeferred<Unit>()
            val starts = AtomicInteger()
            Fixture(afterRuntimeApprovalStart = {
                if (starts.incrementAndGet() == 1) { firstAtGap.complete(Unit); releaseFirst.await() }
            }).use { f ->
                val envReady = CompletableDeferred<ToolEnv>(); val finish = CompletableDeferred<Unit>()
                val d = f.register(tool(timeout = 30_000) { env ->
                    envReady.complete(env); finish.await(); ToolResult("done", effect = ToolEffect.NONE)
                })
                if (grantedWrite) {
                    // Seed a real durable ALWAYS receipt, without consuming the scheduling seam.
                    val seed = f.input(d, step = "grant")
                    val ready = (f.service.prepare(seed) as Mutation.Applied).record
                    val claim = f.ledger.claimExecution(ready.callId, ready.revision) as Mutation.Applied
                    val ask = (f.ledger.requestRiskUpgrade(ready.callId, claim.ticket!!.attemptId, Risk.WRITE) as Mutation.Applied).record.pending!!
                    f.ledger.decideApproval(ready.callId, ask.id, Decision.ALLOW_ALWAYS)
                    f.ledger.complete(ready.callId, claim.ticket!!.attemptId, Status.SUCCEEDED, "grant", Effect.NONE)
                }
                val i = f.input(d); val id = i.binding.callId
                f.service.prepare(i); f.service.dispatch(id)
                val env = withTimeout(5_000) { envReady.await() }
                val a = async { env.requestApproval("A immediate", if (grantedWrite) ToolRisk.WRITE else ToolRisk.READ) }
                withTimeout(5_000) { firstAtGap.await() }
                val b = async { env.requestApproval("B owns pending", ToolRisk.ROOT) }
                var pending = pendingUpgrade(f, id)
                while (pending.summary != "B owns pending") pending = pendingUpgrade(f, id)
                assertFalse(b.isCompleted)
                if (cancelImmediate) a.cancelAndJoin() else {
                    releaseFirst.complete(Unit)
                    assertTrue(withTimeout(5_000) { a.await() })
                }
                assertEquals(pending.id, f.ledger.get(id)!!.pending!!.id)
                assertFalse(b.isCompleted)
                assertTrue(f.service.decide(id, pending.id, Decision.ALLOW_ONCE) is Mutation.Applied)
                assertTrue(withTimeout(5_000) { b.await() })
                assertNull(f.ledger.get(id)!!.pending)
                // Ownership was released; a fresh request must not be rejected by a leaked waiter.
                val c = async { env.requestApproval("C fresh", ToolRisk.ROOT) }
                val fresh = pendingUpgrade(f, id)
                assertEquals("C fresh", fresh.summary)
                f.service.decide(id, fresh.id, Decision.DENY)
                assertFalse(withTimeout(5_000) { c.await() })
                finish.complete(Unit); withTimeout(5_000) { f.service.awaitObservedExit(id) }
                assertEquals(0, f.budget.occupiedSlots())
                assertTrue(f.ledger.pendingApprovals().isEmpty())
            }
        }
    }

    @Test fun pendingRuntimeDenialResumesFalseAfterDefinitionChangesWithoutEndingInvocation() = runBlocking {
        for (stale in listOf(false, true)) Fixture().use { f ->
            val returned = CompletableDeferred<Boolean>(); val finish = CompletableDeferred<Unit>()
            val d = f.register(tool(timeout = 30_000) { env ->
                returned.complete(env.requestApproval("pending before refresh", ToolRisk.WRITE))
                finish.await(); ToolResult("done", effect = ToolEffect.NONE)
            })
            val id = f.input(d).also { f.service.prepare(it) }.binding.callId
            f.service.dispatch(id)
            val ask = pendingUpgrade(f, id)
            if (stale) f.gate.install(d.copy(description = "changed")) else f.gate.beginRefresh(d.toolKey)
            assertTrue(f.service.decide(id, "obsolete", Decision.DENY) is Mutation.Conflict)
            assertTrue(f.service.decide(id, ask.id, Decision.DENY) is Mutation.Applied)
            assertFalse(withTimeout(5_000) { returned.await() })
            val record = f.ledger.get(id)!!
            assertNull(record.pending)
            assertEquals(ApprovalReceipt(ask, Decision.DENY), record.receipts.last())
            assertEquals(Status.RUNNING, record.state)
            assertEquals(Risk.READ, record.effectiveRisk)
            assertEquals(InstanceState.UNCONFIRMED, record.instance!!.state)
            assertEquals(1, f.budget.occupiedSlots())
            finish.complete(Unit); withTimeout(5_000) { f.service.awaitObservedExit(id) }
            assertEquals(Status.SUCCEEDED, f.ledger.get(id)!!.state)
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun durableRuntimeAllowRechecksCurrentPolicyAndDefinitionAtPausedConsumption() = runBlocking {
        for (change in listOf("root-disabled", "denied", "refresh", "stale")) {
            for (decision in listOf(Decision.ALLOW_SESSION, Decision.ALLOW_ALWAYS, Decision.ALLOW_ONCE)) {
                var longTerm = true; var denied = false
                val paused = CompletableDeferred<Unit>(); val consume = CompletableDeferred<Unit>()
                Fixture(policy = ApprovalPolicy(denied = { denied }, allowRootLongTerm = { longTerm }),
                    afterRuntimeApprovalStart = { paused.complete(Unit); consume.await() }).use { f ->
                    val returned = CompletableDeferred<Boolean>(); val finish = CompletableDeferred<Unit>()
                    val d = f.register(tool(timeout = 30_000) { env ->
                        returned.complete(env.requestApproval("root action", ToolRisk.ROOT))
                        finish.await(); ToolResult("done", effect = ToolEffect.NONE)
                    })
                    val id = f.input(d).also { f.service.prepare(it) }.binding.callId
                    f.service.dispatch(id)
                    withTimeout(5_000) { paused.await() }
                    val ask = pendingUpgrade(f, id)
                    val approved = f.service.decide(id, ask.id, decision) as Mutation.Applied
                    assertNull(approved.record.pending)
                    assertEquals(ApprovalReceipt(ask, decision), approved.record.receipts.last())
                    assertEquals(Risk.ROOT, approved.record.effectiveRisk)
                    assertFalse(returned.isCompleted)
                    f.gate.withGate {
                        when (change) {
                            "root-disabled" -> longTerm = false
                            "denied" -> denied = true
                            "refresh" -> f.gate.beginRefresh(d.toolKey)
                            "stale" -> f.gate.install(d.copy(description = "changed"))
                        }
                    }
                    consume.complete(Unit)
                    assertEquals(change == "root-disabled" && decision == Decision.ALLOW_ONCE,
                        withTimeout(5_000) { returned.await() })
                    assertEquals(approved.record, f.ledger.get(id))
                    assertEquals(1, f.budget.occupiedSlots())
                    finish.complete(Unit); withTimeout(5_000) { f.service.awaitObservedExit(id) }
                    assertEquals(Status.SUCCEEDED, f.ledger.get(id)!!.state)
                    assertEquals(0, f.budget.occupiedSlots())
                }
            }
        }
    }

    @Test fun unknownAfterDurableAllowBeforeConsumptionRenewsOnlyOwnedRuntimeRequest() = runBlocking {
        val paused = CompletableDeferred<Unit>(); val consume = CompletableDeferred<Unit>()
        Fixture(afterRuntimeApprovalStart = { paused.complete(Unit); consume.await() }).use { f ->
            val returned = CompletableDeferred<Boolean>(); val finish = CompletableDeferred<Unit>()
            val d = f.register(tool(timeout = 30_000, parallel = true) { env ->
                returned.complete(env.requestApproval("write", ToolRisk.WRITE))
                finish.await(); ToolResult("done", effect = ToolEffect.NONE)
            })
            val id = f.input(d).also { f.service.prepare(it) }.binding.callId
            f.service.dispatch(id)
            withTimeout(5_000) { paused.await() }
            val ask = pendingUpgrade(f, id)
            assertTrue(f.service.decide(id, ask.id, Decision.ALLOW_ONCE) is Mutation.Applied)
            assertNull(f.ledger.get(id)!!.pending)
            assertFalse(returned.isCompleted)
            val other = (f.service.prepare(f.input(d, step = "other")) as Mutation.Applied).record
            val claim = f.ledger.claimExecution(other.callId, other.revision) as Mutation.Applied
            f.ledger.complete(other.callId, claim.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
            consume.complete(Unit)
            val renewed = pendingUpgrade(f, id)
            assertNotEquals(ask.id, renewed.id)
            assertEquals(listOf(other.callId), renewed.unknownCalls)
            assertFalse(returned.isCompleted)
            assertTrue(f.service.decide(id, ask.id, Decision.ALLOW_ONCE) is Mutation.Conflict)
            assertTrue(f.service.decide(id, renewed.id, Decision.DENY) is Mutation.Applied)
            assertFalse(withTimeout(5_000) { returned.await() })
            assertNull(f.ledger.get(id)!!.pending)
            assertEquals(Risk.WRITE, f.ledger.get(id)!!.effectiveRisk)
            finish.complete(Unit); withTimeout(5_000) { f.service.awaitObservedExit(id) }
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun reachedRuntimeRiskDenialReturnsFalseAndCurrentChecksCannotBeSkipped() = runBlocking {
        var deny = false
        Fixture(policy = ApprovalPolicy(denied = { deny })).use { f ->
            val envReady = CompletableDeferred<ToolEnv>(); val finish = CompletableDeferred<Unit>()
            val d = f.register(tool(risk = ToolRisk.WRITE, timeout = 30_000) { env ->
                envReady.complete(env); finish.await(); ToolResult("done", effect = ToolEffect.NONE)
            })
            val i = f.input(d); val id = i.binding.callId
            f.service.prepare(i); approve(f, id); f.service.dispatch(id)
            val env = withTimeout(5_000) { envReady.await() }
            val denied = async { env.requestApproval("already WRITE", ToolRisk.WRITE) }
            val ask = pendingUpgrade(f, id)
            f.service.decide(id, ask.id, Decision.DENY)
            assertFalse(withTimeout(5_000) { denied.await() })
            assertEquals(Risk.WRITE, f.ledger.get(id)!!.effectiveRisk)
            deny = true
            assertFalse(env.requestApproval("current deny", ToolRisk.WRITE))
            deny = false
            f.gate.beginRefresh(d.toolKey)
            assertFalse(env.requestApproval("refresh pending", ToolRisk.WRITE))
            f.gate.install(d.copy(description = "changed"))
            assertFalse(env.requestApproval("stale", ToolRisk.WRITE))
            f.gate.install(d)
            val granted = async { env.requestApproval("always", ToolRisk.WRITE) }
            val always = pendingUpgrade(f, id)
            f.service.decide(id, always.id, Decision.ALLOW_ALWAYS)
            assertTrue(withTimeout(5_000) { granted.await() })
            assertTrue(env.requestApproval("always reused", ToolRisk.WRITE))
            // A new UNKNOWN overrides the ALWAYS grant even though WRITE is already reached.
            val other = f.input(d, step = "other").copy(taskChainId = "chain")
            val ready = (f.service.prepare(other) as Mutation.Applied).record
            val claim = f.ledger.claimExecution(ready.callId, ready.revision) as Mutation.Applied
            f.ledger.complete(ready.callId, claim.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
            val afterUnknown = async { env.requestApproval("new UNKNOWN", ToolRisk.WRITE) }
            val unknownAsk = pendingUpgrade(f, id)
            assertEquals(listOf(ready.callId), unknownAsk.unknownCalls)
            f.service.decide(id, unknownAsk.id, Decision.DENY)
            assertFalse(withTimeout(5_000) { afterUnknown.await() })
            finish.complete(Unit); withTimeout(5_000) { f.service.awaitObservedExit(id) }
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun unknownWithoutResourceBlocksDispatchWithSpareBudgetBeforeRestartGrace() = runBlocking {
        for (cancel in listOf(false, true)) Fixture(slots = 3, exitGraceMillis = 60_000).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val a = f.catalog.registerHost("host/a", "1", tool(risk = ToolRisk.WRITE,
                    timeout = if (cancel) 30_000 else 150, parallel = true) {
                    entered.countDown(); release.await(); ToolResult("late", effect = ToolEffect.NONE)
                })
                val bRuns = AtomicInteger()
                val b = f.catalog.registerHost("host/b", "1", tool(risk = ToolRisk.WRITE, parallel = true) {
                    bRuns.incrementAndGet(); ToolResult("b", effect = ToolEffect.NONE)
                })
                f.catalog.freeze("run")
                val ia = f.input(a); val id = ia.binding.callId
                f.service.prepare(ia); approve(f, id); f.service.dispatch(id)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (cancel) f.service.cancel(id)
                withTimeout(5_000) {
                    var r = f.store.writes.receive()
                    while (r.callId != id || r.state != if (cancel) Status.CANCELLED else Status.TIMED_OUT) r = f.store.writes.receive()
                }
                assertFalse(f.ledger.get(id)!!.restartRequired)
                assertEquals(1, f.budget.occupiedSlots())
                val ib = f.input(b, step = "different-step")
                f.service.prepare(ib); approve(f, ib.binding.callId)
                val blocked = f.service.dispatch(ib.binding.callId) as Mutation.Blocked
                assertEquals("Task chain awaits unknown instance actual exit", blocked.reason)
                assertEquals(0, bRuns.get())
                assertEquals(1, f.budget.occupiedSlots()) // failed reservation was abandoned
                release.countDown(); withTimeout(5_000) { f.service.awaitObservedExit(id) }
                assertEquals(Effect.UNKNOWN, f.ledger.get(id)!!.result!!.effect)
                assertTrue(f.service.dispatch(ib.binding.callId) is Mutation.Applied)
                withTimeout(5_000) { f.service.awaitObservedExit(ib.binding.callId) }
                assertEquals(1, bRuns.get())
                assertEquals(0, f.budget.occupiedSlots())
            } finally { release.countDown() }
        }
    }

    @Test fun runtimeAskSuspendsAndHostApiCommitsRiskBeforeReturningTrue() = runBlocking {
        Fixture().use { f ->
            val returned = CompletableDeferred<Boolean>()
            val release = CompletableDeferred<Unit>()
            val i = f.input(f.register(tool(timeout = 30_000) { env ->
                val allowed = env.requestApproval("actual write summary", ToolRisk.WRITE)
                assertEquals(Risk.WRITE, f.ledger.get(env.callId)!!.effectiveRisk)
                returned.complete(allowed)
                release.await()
                ToolResult("wrote")
            }))
            val id = i.binding.callId
            f.service.prepare(i); f.service.dispatch(id)
            val pending = pendingUpgrade(f, id)
            assertEquals("actual write summary", pending.summary)
            assertFalse(returned.isCompleted)
            assertEquals(1, f.budget.occupiedSlots())
            val api = HostToolRuntimeApi(f.service)
            val payload = buildJsonObject {
                put("operation", "decide"); put("callId", id); put("approvalId", pending.id)
                put("decision", "ALLOW_ONCE"); put("risk", "ROOT"); put("digest", "forged")
            }.toString()
            val start = CompletableDeferred<Unit>()
            val decisions = List(2) { async { start.await(); Json.parseToJsonElement(api.request(payload)).jsonObject["kind"]!!.jsonPrimitive.content } }
            start.complete(Unit)
            assertEquals(setOf("applied", "conflict"), decisions.awaitAll().toSet())
            assertTrue(withTimeout(5_000) { returned.await() })
            assertTrue(f.ledger.pendingApprovals().isEmpty())
            release.complete(Unit); f.service.awaitObservedExit(id)
            assertEquals(Effect.APPLIED, f.ledger.get(id)!!.result!!.effect)
        }
    }

    @Test fun newUnknownEvidenceRenewsRuntimeRequestWithoutWakingToolAsAllowed() = runBlocking {
        Fixture().use { f ->
            val returned = CompletableDeferred<Boolean>()
            val d = f.register(tool(timeout = 30_000, parallel = true) { env ->
                returned.complete(env.requestApproval("write", ToolRisk.WRITE))
                ToolResult("done", effect = ToolEffect.NONE)
            })
            val i = f.input(d); val id = i.binding.callId
            f.service.prepare(i); f.service.dispatch(id)
            val first = pendingUpgrade(f, id)
            val other = f.input(d, step = "other")
            val ready = (f.service.prepare(other) as Mutation.Applied).record
            val claim = f.ledger.claimExecution(ready.callId, ready.revision) as Mutation.Applied
            f.ledger.complete(ready.callId, claim.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
            val renewed = f.service.decide(id, first.id, Decision.ALLOW_ONCE) as Mutation.Applied
            assertNotEquals(first.id, renewed.record.pending!!.id)
            assertFalse(returned.isCompleted)
            assertEquals(Risk.READ, renewed.record.effectiveRisk)
            assertTrue(f.service.decide(id, first.id, Decision.ALLOW_ONCE) is Mutation.Conflict)
            assertTrue(f.service.decide(id, renewed.record.pending!!.id, Decision.ALLOW_ONCE) is Mutation.Applied)
            assertTrue(withTimeout(5_000) { returned.await() })
            f.service.awaitObservedExit(id)
        }
    }

    @Test fun cancelledChildWaiterWithdrawsOnlyItsPendingStep() = runBlocking {
        Fixture().use { f ->
            val child = CompletableDeferred<Job>()
            val continueTool = CompletableDeferred<Unit>()
            val answer = CompletableDeferred<Boolean>()
            val d = f.register(tool(timeout = 30_000) { env ->
                coroutineScope {
                    val waiting = launch { env.requestApproval("abandoned", ToolRisk.WRITE) }
                    child.complete(waiting)
                    waiting.join()
                }
                continueTool.await()
                answer.complete(env.requestApproval("fresh", ToolRisk.WRITE))
                ToolResult("done", effect = ToolEffect.NONE)
            })
            val i = f.input(d); val id = i.binding.callId
            f.service.prepare(i); f.service.dispatch(id)
            val abandoned = pendingUpgrade(f, id)
            withTimeout(5_000) { child.await().cancelAndJoin() }
            assertNull(f.ledger.get(id)!!.pending)
            assertEquals(Status.RUNNING, f.ledger.get(id)!!.state)
            assertTrue(f.service.decide(id, abandoned.id, Decision.ALLOW_ALWAYS) is Mutation.Conflict)
            continueTool.complete(Unit)
            val fresh = pendingUpgrade(f, id)
            assertNotEquals(abandoned.id, fresh.id)
            f.service.decide(id, fresh.id, Decision.ALLOW_ONCE)
            assertTrue(withTimeout(5_000) { answer.await() })
            f.service.awaitObservedExit(id)
        }
    }

    @Test fun runtimeDenyOnlyDeniesStepAndNextRequestHasFreshId() = runBlocking {
        Fixture().use { f ->
            val answers = CompletableDeferred<Pair<Boolean, Boolean>>()
            val i = f.input(f.register(tool(timeout = 30_000) { env ->
                val first = env.requestApproval("first", ToolRisk.WRITE)
                val second = env.requestApproval("second", ToolRisk.WRITE)
                answers.complete(first to second)
                ToolResult("done", effect = ToolEffect.NONE)
            }))
            val id = i.binding.callId
            f.service.prepare(i); f.service.dispatch(id)
            val first = pendingUpgrade(f, id)
            assertTrue(f.service.decide(id, first.id, Decision.DENY) is Mutation.Applied)
            val second = pendingUpgrade(f, id)
            assertNotEquals(first.id, second.id)
            assertTrue(f.service.decide(id, first.id, Decision.ALLOW_ONCE) is Mutation.Conflict)
            assertEquals(Status.RUNNING, f.ledger.get(id)!!.state)
            assertTrue(f.service.decide(id, second.id, Decision.ALLOW_ONCE) is Mutation.Applied)
            assertEquals(false to true, withTimeout(5_000) { answers.await() })
            f.service.awaitObservedExit(id)
        }
    }

    @Test fun cancelledPendingApprovalRejectsLateDecisionAndRetainsLeaseUntilHandlerExit() = runBlocking {
        Fixture().use { f ->
            val handler = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val i = f.input(f.register(tool(timeout = 30_000, resource = "screen") { env ->
                    currentCoroutineContext()[Job]!!.invokeOnCompletion { handler.countDown(); release.await() }
                    env.requestApproval("write", ToolRisk.WRITE)
                    ToolResult("late")
                }))
                val id = i.binding.callId
                f.service.prepare(i); f.service.dispatch(id)
                val pending = pendingUpgrade(f, id)
                f.service.cancel(id)
                assertTrue(handler.await(5, TimeUnit.SECONDS))
                assertTrue(f.service.decide(id, pending.id, Decision.ALLOW_ONCE) is Mutation.Conflict)
                assertEquals(Status.CANCELLED, f.ledger.get(id)!!.state)
                assertEquals(Effect.NONE, f.ledger.get(id)!!.result!!.effect)
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(id)!!.instance!!.state)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(id)
                assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(id)!!.instance!!.state)
                assertEquals(0, f.budget.occupiedSlots())
            } finally { release.countDown() }
        }
    }

    @Test fun timeoutDuringPendingUpgradeRetainsPriorRiskAndRejectsLateDecision() = runBlocking {
        Fixture().use { f ->
            val release = CountDownLatch(1); val handler = CountDownLatch(1)
            try {
                val i = f.input(f.register(tool(risk = ToolRisk.WRITE, timeout = 1_000) { env ->
                    currentCoroutineContext()[Job]!!.invokeOnCompletion { handler.countDown(); release.await() }
                    env.requestApproval("root", ToolRisk.ROOT)
                    ToolResult("not authorized")
                }))
                val id = i.binding.callId
                f.service.prepare(i); approve(f, id); f.service.dispatch(id)
                val pending = pendingUpgrade(f, id)
                withTimeout(5_000) {
                    var r = f.store.writes.receive()
                    while (r.callId != id || r.state != Status.TIMED_OUT) r = f.store.writes.receive()
                }
                assertTrue(handler.await(5, TimeUnit.SECONDS))
                assertTrue(f.service.decide(id, pending.id, Decision.ALLOW_ONCE) is Mutation.Conflict)
                val timed = f.ledger.get(id)!!
                assertEquals(Risk.WRITE, timed.effectiveRisk)
                assertEquals(Effect.UNKNOWN, timed.result!!.effect)
                assertEquals(InstanceState.UNCONFIRMED, timed.instance!!.state)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(id)
                assertEquals(timed.result, f.ledger.get(id)!!.result)
                assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(id)!!.instance!!.state)
            } finally { release.countDown() }
        }
    }

    @Test fun unattendedRuntimeUsesAlwaysGrantButNeverCreatesPrompt() = runBlocking {
        Fixture().use { f ->
            val answers = kotlinx.coroutines.channels.Channel<Boolean>(3)
            val d = f.register(tool(timeout = 30_000) { env ->
                answers.send(env.requestApproval("write", ToolRisk.WRITE))
                ToolResult("done", effect = ToolEffect.NONE)
            })
            suspend fun invoke(step: String, unattended: Boolean): String {
                val i = f.input(d, step = step).copy(unattended = unattended)
                f.service.prepare(i); f.service.dispatch(i.binding.callId)
                return i.binding.callId
            }
            val denied = invoke("denied", true)
            assertFalse(withTimeout(5_000) { answers.receive() }); f.service.awaitObservedExit(denied)
            assertTrue(f.ledger.pendingApprovals().isEmpty())
            val grant = invoke("grant", false)
            val pending = pendingUpgrade(f, grant)
            assertTrue(f.service.decide(grant, pending.id, Decision.ALLOW_ALWAYS) is Mutation.Applied)
            assertTrue(withTimeout(5_000) { answers.receive() }); f.service.awaitObservedExit(grant)
            val allowed = invoke("allowed", true)
            assertTrue(withTimeout(5_000) { answers.receive() }); f.service.awaitObservedExit(allowed)
            assertEquals(Risk.WRITE, f.ledger.get(allowed)!!.effectiveRisk)
            assertTrue(f.ledger.pendingApprovals().isEmpty())
        }
    }

    @Test fun internalUpgradeFailureReturnsFalseAndNeverDowngradesEffectEvidence() = runBlocking {
        Fixture().use { f ->
            val allowed = CompletableDeferred<Boolean>()
            val i = f.input(f.register(tool { env ->
                allowed.complete(env.requestApproval("write", ToolRisk.WRITE))
                ToolResult("read only", effect = ToolEffect.NONE)
            }))
            f.service.prepare(i)
            f.store.fail = { it.pending?.internal == true }
            f.service.dispatch(i.binding.callId)
            assertFalse(withTimeout(5_000) { allowed.await() })
            f.service.awaitObservedExit(i.binding.callId)
            assertEquals(Risk.READ, f.ledger.get(i.binding.callId)!!.effectiveRisk)
        }
    }

    @Test fun lengthCancelsCompleteCallsAndFailedOrTruncatedStreamDoesNotDispatch() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool { error("truncated") })
            val reducer = ModelEventReducer("recorded", "req")
            listOf(Events.started("r", "m"), Events.toolStart(0, "wire", d.modelName), Events.toolDelta(0, "{}"),
                Events.end(0), Events.finished(FinishReason.LENGTH)).forEach(reducer::accept)
            val message = f.service.executeStep(reducer, "chain", "session", "run", "step")!!
            assertTrue((message.parts.single() as ContentPart.ToolResult).text.contains("cancelled"))
            assertNull(f.ledger.get(reducer.calls().single().binding.callId)!!.instance)
            val bad = ModelEventReducer("recorded", "bad")
            listOf(Events.started("r", "m"), Events.toolStart(0, "wire", d.modelName), Events.toolDelta(0, "{"),
                Events.end(0), Events.finished(FinishReason.LENGTH)).forEach(bad::accept)
            assertNull(f.service.executeStep(bad, "chain", "session", "run", "bad"))
        }
    }

    @Test fun revokeRacingDispatchHasOneLegalWinner() = runBlocking {
        repeat(10) {
            Fixture().use { f ->
                val calls = AtomicInteger()
                val i = f.input(f.register(tool { calls.incrementAndGet(); ToolResult("ok") }))
                f.service.prepare(i)
                val start = CompletableDeferred<Unit>()
                val dispatch = async(Dispatchers.Default) { start.await(); f.service.dispatch(i.binding.callId) }
                val revoke = async(Dispatchers.Default) { start.await(); f.catalog.revoke(requireNotNull(i.definition).toolKey) }
                start.complete(Unit)
                val result = dispatch.await(); revoke.await()
                f.service.awaitObservedExit(i.binding.callId)
                if (result is Mutation.Applied && result.ticket != null) assertEquals(1, calls.get())
                else { assertEquals(0, calls.get()); assertNull(f.ledger.get(i.binding.callId)!!.instance) }
            }
        }
    }

    @Test fun reservationUsesExactlyOneSlotAndCancelledReservationDoesNotLeak() = runBlocking {
        val budget = PluginExecutionBudget(1, 1)
        val owner = CoroutineScope(SupervisorJob())
        try {
            val reserve = budget.reserve("host", owner, {}, {})!!
            val call = PluginInvocation(budget, "host", owner, HostClockTool { 7 }, JsonObject(emptyMap()),
                ToolInvocationRequest("r", "s"), frozenSpec = HostClockTool().spec, reservation = reserve)
            call.start()
            assertEquals(PluginInvocationStatus.SUCCEEDED, withTimeout(5_000) { call.terminal.await() }.status)
            withTimeout(5_000) { call.actualExited.await() }
            assertEquals(0, budget.occupiedSlots())
            val reserved = budget.reserve("host", owner, {}, {})!!
            val cancelled = PluginInvocation(budget, "host", owner, tool { error("cancelled before commit") },
                JsonObject(emptyMap()), ToolInvocationRequest("r", "s"), reservation = reserved)
            cancelled.cancel(); cancelled.start()
            withTimeout(5_000) { cancelled.actualExited.await() }
            assertEquals(PluginInvocationStatus.CANCELLED, cancelled.terminal.await().status)
            assertEquals(0, budget.occupiedSlots())
        } finally { budget.close(); owner.cancel() }
    }

    @Test fun failedLateUnknownWriteMustNotPersistActualExit() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val i = f.input(f.register(tool(timeout = 100, resource = "shared") {
                    entered.countDown(); release.await(); ToolResult("late", effect = ToolEffect.UNKNOWN)
                }))
                f.service.prepare(i); f.service.dispatch(i.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(i.binding.callId)!!.restartRequired }
                // Fail every UNKNOWN evidence write but allow actual-exit writes. Losing the
                // evidence must not make cold recovery see an already-exited, harmless READ.
                f.gate.withGate { f.store.fail = { r -> r.evidence.any { it.outcome?.effect == Effect.UNKNOWN } } }
                release.countDown()
                val failure = runCatching { withTimeout(5_000) { f.service.awaitObservedExit(i.binding.callId) } }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                waitUntil { f.budget.occupiedSlots() == 0 }
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
                // Simulate a new process from the last acknowledged records only.
                val reopened = MemoryStore().also { copy -> f.gate.withGate { copy.data.putAll(f.store.data) } }
                val recovered = InvocationLedger(reopened, DefinitionGate(), f.validator)
                recovered.recoverColdStart(UUID.randomUUID().toString())
                assertTrue(recovered.get(i.binding.callId)!!.recoveryPending)
                assertNotNull(recovered.get(i.binding.callId)!!.isolation)
                assertTrue(f.service.dispatch(i.binding.callId) is Mutation.Blocked)
            } finally { release.countDown() }
        }
    }

    @Test fun failedActualExitWriteDoesNotClearGraceRestrictionOrReopenSubmissions() = runBlocking {
        Fixture(exitGraceMillis = 30).use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val d = f.register(tool(timeout = 100) {
                    entered.countDown(); release.await(); ToolResult("late", effect = ToolEffect.NONE)
                })
                val first = f.input(d); val next = f.input(d, step = "next")
                f.service.prepare(first); f.service.prepare(next); f.service.dispatch(first.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(first.binding.callId)!!.restartRequiredUntilExit }
                val result = f.ledger.get(first.binding.callId)!!.result
                f.gate.withGate { f.store.fail = { it.instance?.state == InstanceState.EXIT_CONFIRMED } }
                release.countDown()
                val failure = runCatching {
                    withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
                }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals(0, f.budget.occupiedSlots())
                val retained = f.ledger.get(first.binding.callId)!!
                assertEquals(result, retained.result)
                assertTrue(retained.restartRequiredUntilExit)
                assertTrue(retained.restartRequired)
                assertEquals(InstanceState.UNCONFIRMED, retained.instance!!.state)
                assertEquals("Storage failed; submissions closed",
                    (f.service.dispatch(next.binding.callId) as Mutation.Blocked).reason)
            } finally { release.countDown() }
        }
    }

    @Test fun staleReadyDefinitionProducesRejectedResultWithoutCallingTool() = runBlocking {
        Fixture().use { f ->
            val d = f.register(tool { error("stale tool must not execute") })
            val reducer = ModelEventReducer("recorded", "stale")
            listOf(Events.started("response", "no-api"), Events.toolStart(0, "wire", d.modelName),
                Events.toolDelta(0, "{}"), Events.end(0), Events.finished(FinishReason.STOP)).forEach(reducer::accept)
            val binding = io.github.bytejuno.thetacore.agent.ledger.BindingValue.copyOf(reducer.calls().single().binding)
            f.service.prepare(InvocationInput(binding, "chain", "session", "run", "step", 0, "{}", d, false, d.declaredRisk))
            f.catalog.revoke(d.toolKey)
            val result = f.service.executeStep(reducer, "chain", "session", "run", "step")
            assertNotNull(result)
            assertEquals(Status.REJECTED, f.ledger.get(binding.callId)!!.state)
            assertNull(f.ledger.get(binding.callId)!!.instance)
        }
    }

    @Test fun sharedResourceBlocksOtherSessionUntilActualExit() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val count = AtomicInteger()
            try {
                val d = f.register(tool(resource = "shared") {
                    count.incrementAndGet(); entered.countDown(); release.await(); ToolResult("ok", effect = ToolEffect.NONE)
                })
                val first = f.input(d)
                val second = f.input(d).copy(binding = io.github.bytejuno.thetacore.agent.ledger.BindingValue.copyOf(
                    callBinding("recorded", "other-request", "other-response", 0, "wire")), sessionId = "other", taskChainId = "other-chain")
                f.service.prepare(first); f.service.prepare(second)
                f.service.dispatch(first.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertTrue(f.service.dispatch(second.binding.callId) is Mutation.Blocked)
                assertNull(f.ledger.get(second.binding.callId)!!.instance)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(first.binding.callId)
                assertTrue(f.service.dispatch(second.binding.callId) is Mutation.Applied)
                f.service.awaitObservedExit(second.binding.callId)
                assertEquals(2, count.get())
            } finally { release.countDown() }
        }
    }

    @Test fun ownerCancellationDuringDurableClaimNeverEntersBodyOrLeaksBudget() = runBlocking {
        Fixture().use { f ->
            val calls = AtomicInteger()
            val i = f.input(f.register(tool { calls.incrementAndGet(); ToolResult("unexpected") }))
            f.service.prepare(i)
            f.store.fail = { record ->
                if (record.state == Status.RUNNING) f.owner.cancel()
                false
            }
            f.service.dispatch(i.binding.callId)
            withTimeout(5_000) { f.service.awaitObservedExit(i.binding.callId) }
            val r = f.ledger.get(i.binding.callId)!!
            assertEquals(0, calls.get())
            assertEquals(Effect.NONE, r.result!!.effect)
            assertEquals(InstanceState.EXIT_CONFIRMED, r.instance!!.state)
            assertEquals(0, f.budget.occupiedSlots())
        }
    }

    @Test fun realClockBridgePersistsWithProductionStoreAndReopensWithoutReplay() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("host-bridge-store")
        val budget = PluginExecutionBudget()
        var store: JsonInvocationStore? = null
        try {
            store = JsonInvocationStore.create(dir, Durability.PROCESS_CRASH_ONLY)
            val service = HostToolBridgeRoot(budget).initialize { checkNotNull(store) }
            val d = service.catalog.freeze("run").values.single { it.toolKey == "host/clock" }
            val reducer = ModelEventReducer("recorded", "disk")
            listOf(Events.started("response", "no-api"), Events.toolStart(0, "wire", d.modelName),
                Events.toolDelta(0, "{}"), Events.end(0), Events.finished(FinishReason.STOP)).forEach(reducer::accept)
            assertNotNull(service.executeStep(reducer, "chain", "session", "run", "step"))
            val id = reducer.calls().single().binding.callId
            val persisted = service.ledger.get(id)!!
            store.close()
            store = JsonInvocationStore.open(dir, Durability.PROCESS_CRASH_ONLY)
            assertEquals(persisted, store.records().single())
            assertEquals(Status.SUCCEEDED, persisted.state)
            assertEquals(InstanceState.EXIT_CONFIRMED, persisted.instance!!.state)
        } finally {
            store?.close(); budget.close(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun cooperativeCancellationDoesNotRequireRestart() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1)
            val calls = AtomicInteger()
            val d = f.register(tool {
                if (calls.incrementAndGet() == 1) { entered.countDown(); awaitCancellation() }
                ToolResult("second", effect = ToolEffect.NONE)
            })
            val first = f.input(d)
            f.service.prepare(first); f.service.dispatch(first.binding.callId)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            f.service.cancel(first.binding.callId)
            withTimeout(5_000) { f.service.awaitObservedExit(first.binding.callId) }
            assertFalse(f.ledger.get(first.binding.callId)!!.restartRequired)
            val second = f.input(d, step = "next")
            f.service.prepare(second)
            assertNotNull((f.service.dispatch(second.binding.callId) as Mutation.Applied).ticket)
            withTimeout(5_000) { f.service.awaitObservedExit(second.binding.callId) }
            assertEquals(2, calls.get())
        }
    }

    private fun writer(text: String) = object : Tool {
        override val id = "writer"
        override val spec = ToolSpec("writer", "strict output", emptySchema,
            outputSchema = Json.parseToJsonElement("""{"type":"object","required":["id"]}""").jsonObject,
            risk = ToolRisk.WRITE, timeoutMs = 2_000)
        override suspend fun call(args: JsonObject, env: ToolEnv) = ToolResult(text, buildJsonObject { put("wrong", 1) })
    }

    @Test fun invalidStructuredOutputKeepsContentAndOnlyEmptyResultIsAnError() = runBlocking {
        for (text in listOf("row written", "")) Fixture().use { f ->
            val i = f.input(f.register(writer(text)))
            f.service.prepare(i)
            approve(f, i.binding.callId)
            f.service.dispatch(i.binding.callId)
            withTimeout(5_000) { f.service.awaitObservedExit(i.binding.callId) }
            val result = f.ledger.get(i.binding.callId)!!.result!!
            if (text.isEmpty()) {
                assertEquals(Status.FAILED, result.status)
                assertEquals("Tool returned invalid structured output", result.text)
                assertEquals(Effect.UNKNOWN, result.effect)
            } else {
                assertEquals(Status.SUCCEEDED, result.status)
                assertEquals(text, result.text)
                assertEquals(Effect.APPLIED, result.effect)
            }
        }
    }

    @Test fun completedCallsReleaseServiceBookkeeping() = runBlocking {
        Fixture().use { f ->
            val d = f.register()
            repeat(3) { n ->
                val i = f.input(d, step = "step-$n")
                f.service.prepare(i); f.service.dispatch(i.binding.callId)
                withTimeout(5_000) { f.service.awaitObservedExit(i.binding.callId) }
            }
            waitUntil {
                listOf("active", "observations", "logicalResults").all { name ->
                    val field = HostToolExecutionService::class.java.getDeclaredField(name).apply { isAccessible = true }
                    f.gate.withGate { (field.get(f.service) as Map<*, *>).isEmpty() }
                }
            }
        }
    }

    @Test fun revokeDuringRefreshRejectsFrozenCallInsteadOfPausingForever() = runBlocking {
        Fixture().use { f ->
            val d = f.register()
            f.gate.beginRefresh(d.toolKey)
            f.catalog.revoke(d.toolKey)
            val i = f.input(d)
            assertTrue(f.service.prepare(i) is Mutation.Applied)
            assertEquals(Status.REJECTED, f.ledger.get(i.binding.callId)!!.state)
            assertNull(f.ledger.get(i.binding.callId)!!.instance)
        }
    }

    @Test fun rootReusesCoordinatorWithoutOpeningAnotherWriterInSameProcess() = runBlocking {
        val budget = PluginExecutionBudget()
        try {
            val root = HostToolBridgeRoot(budget)
            val store = MemoryStore()
            val service = root.initialize { store }
            assertSame(service, root.initialize { error("must not open or cold recover a new writer") })
        } finally { budget.close() }
    }
}
