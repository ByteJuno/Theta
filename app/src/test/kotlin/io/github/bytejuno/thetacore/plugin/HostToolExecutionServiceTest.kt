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
        var fail: (InvocationRecord) -> Boolean = { false }
        override fun bindCoordinator() { check(!bound); bound = true }
        override fun records() = data.values.toList()
        override fun save(record: InvocationRecord, expectedRevision: Long?) {
            if (fail(record)) throw StoreException("injected write failure")
            record.validate()
            check(data[record.callId]?.revision == expectedRevision)
            data[record.callId] = record
        }
        override fun close() {}
    }
    private class Fixture(policy: ApprovalPolicy = ApprovalPolicy()) : AutoCloseable {
        val budget = PluginExecutionBudget(2, 2)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryStore()
        val gate = DefinitionGate()
        val validator = Schema202012Validator()
        val ledger = InvocationLedger(store, gate, validator, policy)
        val catalog = FrozenToolCatalog(gate, budget, owner, validator)
        val service = HostToolExecutionService(budget, owner, catalog, ledger, validator)
        init { ledger.recoverColdStart(UUID.randomUUID().toString()) }
        suspend fun register(tool: Tool = HostClockTool { 42 }): FrozenDefinition {
            val d = catalog.registerHost("host/test", "1", tool)
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
        body: suspend (ToolEnv) -> ToolResult) = object : Tool {
        override val id = "controlled"
        override val spec = ToolSpec("controlled", "actual SDK tool", emptySchema, risk = risk,
            timeoutMs = timeout, exclusiveResource = resource)
        override suspend fun call(args: JsonObject, env: ToolEnv) = body(env)
    }
    private suspend fun waitUntil(check: () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }
    private suspend fun approve(f: Fixture, id: String) {
        val pending = f.ledger.get(id)!!.pending!!
        assertTrue(f.service.decide(id, pending.id, Decision.ALLOW_ONCE) is Mutation.Applied)
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
            f.catalog.revoke(i.definition.toolKey)
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

    @Test fun timeoutRetainsWorkerAndLateUnknownQuarantinesReadResource() = runBlocking {
        Fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            try {
                val d = f.register(tool(timeout = 150, resource = "shared") {
                    entered.countDown(); release.await(); ToolResult("late", effect = ToolEffect.UNKNOWN)
                })
                val i = f.input(d); f.service.prepare(i); f.service.dispatch(i.binding.callId)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                waitUntil { f.ledger.get(i.binding.callId)!!.state == Status.TIMED_OUT }
                val before = f.ledger.get(i.binding.callId)!!
                assertEquals(InstanceState.UNCONFIRMED, before.instance!!.state)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(i.binding.callId)
                val after = f.ledger.get(i.binding.callId)!!
                assertEquals(Status.TIMED_OUT, after.state)
                assertEquals(InstanceState.EXIT_CONFIRMED, after.instance!!.state)
                assertTrue(after.evidence.any { it.outcome?.effect == Effect.UNKNOWN })
                assertNotNull(after.isolation)
                assertEquals(0, f.budget.occupiedSlots())
            } finally { release.countDown() }
        }
    }

    @Test fun lateUnknownIsPersistedWhileChildStillPreventsActualExit() = runBlocking {
        Fixture().use { f ->
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
                waitUntil { f.ledger.get(i.binding.callId)!!.state == Status.TIMED_OUT }
                returnBody.countDown()
                waitUntil { f.ledger.get(i.binding.callId)!!.evidence.any { it.outcome?.effect == Effect.UNKNOWN } }
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
                assertNotNull(f.ledger.get(i.binding.callId)!!.isolation)
                releaseChild.countDown(); f.service.awaitObservedExit(i.binding.callId)
            } finally { returnBody.countDown(); releaseChild.countDown() }
        }
    }

    @Test fun cancellationRetainsSlotUntilBlockedHandlerActuallyExits() = runBlocking {
        Fixture().use { f ->
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
                assertEquals(Status.CANCELLED, f.ledger.get(i.binding.callId)!!.state)
                assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
                assertEquals(1, f.budget.occupiedSlots())
                release.countDown(); f.service.awaitObservedExit(i.binding.callId)
                assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(i.binding.callId)!!.instance!!.state)
            } finally { release.countDown() }
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
                val revoke = async(Dispatchers.Default) { start.await(); f.catalog.revoke(i.definition.toolKey) }
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
            val service = HostToolBridgeRoot(budget).initialize(store)
            val d = service.catalog.freeze("run").values.single()
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

    @Test fun rootReusesCoordinatorAndRejectsNewWriterInSameProcess() = runBlocking {
        val budget = PluginExecutionBudget()
        try {
            val root = HostToolBridgeRoot(budget)
            val store = MemoryStore()
            assertSame(root.initialize(store), root.initialize(store))
            try { root.initialize(MemoryStore()); fail("must not cold recover a new writer") }
            catch (_: IllegalStateException) {}
        } finally { budget.close() }
    }
}
