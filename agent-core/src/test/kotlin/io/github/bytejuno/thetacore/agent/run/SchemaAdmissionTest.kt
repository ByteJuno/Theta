package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class SchemaAdmissionTest {
    private val hidden = testDefinition("hidden").copy(inputSchema =
        """{"type":"object","properties":{"forbidden":false}}""")
    private val ok = testDefinition("ok")
    private val definitions = listOf(hidden, ok)
    private val identity = RunIdentity("chain", "session", "run")
    private val validator = Schema202012Validator()
    private fun request(defs: List<FrozenDefinition> = definitions) = chatRequest("m") {
        tools = defs.map { modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema).jsonObject) }
    }

    private class Wire(vararg replies: Pair<String, String>, private val finish: String = "tool_calls") {
        val bodies = mutableListOf<JsonObject>()
        val diagnostics = mutableListOf<ToolSchemaPreparation>()
        val provider = OpenAiChatProvider(
            OpenAiChatConfig("test", "https://example.invalid", schemaProfile = SchemaProfile.BASIC),
            ModelTransport { _, body, _ ->
                bodies += Json.parseToJsonElement(body).jsonObject
                val delta = buildJsonObject {
                    if (bodies.size == 1) putJsonArray("tool_calls") {
                        replies.forEachIndexed { index, (name, args) -> add(buildJsonObject {
                            put("index", index); put("id", "wire-$index"); put("type", "function")
                            putJsonObject("function") { put("name", name); put("arguments", args) }
                        }) }
                    } else put("content", "done")
                }
                val frame = buildJsonObject {
                    put("id", "response"); put("model", "m")
                    putJsonArray("choices") { add(buildJsonObject {
                        put("index", 0); put("delta", delta)
                        put("finish_reason", if (bodies.size == 1) finish else "stop")
                    }) }
                }
                flowOf(TransportEvent.Headers(200, "text/event-stream", null),
                    TransportEvent.Bytes("data: $frame\n\ndata: [DONE]\n\n".toByteArray()))
            }, onSchemaDiagnostics = { diagnostics += it })
    }

    private fun bridge(ledger: InvocationLedger, submitted: MutableList<ExecutionTicket>) = object : ToolExecutionBridge {
        override fun submit(ticket: ExecutionTicket) {
            submitted += ticket
            assertIs<Mutation.Applied>(ledger.complete(ticket.callId, ticket.attemptId, Status.SUCCEEDED, "done"))
        }
        override suspend fun awaitTerminal(callId: String) = Unit
        override fun requestCancel(callId: String) = Unit
    }

    @Test fun basicExcludedToolIsRejectedAndModelContinuesWithPairedResult() = runTest {
        assertTrue(validator.valid(hidden.inputSchema, "{}"))
        val f = RunFixture(definitions)
        val wire = Wire("hidden" to "{}")
        assertEquals(RunState.COMPLETED, f.loop(wire.provider).run().state)
        assertTrue(f.submitted.isEmpty())
        val record = f.store.records().single()
        assertEquals(Status.REJECTED, record.state)
        assertEquals(Effect.NONE, record.result!!.effect)
        assertNull(record.instance)
        assertTrue(record.input.toolAdmissionRejected)
        assertEquals(hidden, record.input.definition)
        assertEquals(hidden.digest, record.definitionDigest)
        assertEquals(2, wire.bodies.size)
        assertTrue(wire.diagnostics.all { it.excluded.map { x -> x.name } == listOf("hidden") })
        assertEquals(listOf("ok"), wire.bodies.first()["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content })
        val result = wire.bodies.last()["messages"]!!.jsonArray.last().jsonObject
        assertEquals("tool", result["role"]!!.jsonPrimitive.content)
        val envelope = Json.parseToJsonElement(result["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals("rejected", envelope["status"]!!.jsonPrimitive.content)
        assertEquals("none", envelope["effect"]!!.jsonPrimitive.content)
        validateHistory(f.history.snapshot().messages)
    }

    @Test fun excludedAndAvailableCallsKeepOrderAndOnlyAvailableToolIsSubmitted() = runTest {
        for (names in listOf(listOf("hidden", "ok"), listOf("ok", "hidden"))) {
            val f = RunFixture(definitions)
            val wire = Wire(*names.map { it to "{}" }.toTypedArray())
            assertEquals(RunState.COMPLETED, f.loop(wire.provider).run().state)
            assertEquals(listOf("ok"), f.submitted.map { it.definition.modelName })
            assertEquals(names, f.store.records().map { it.input.modelName })
            assertEquals(names.map { if (it == "hidden") Status.REJECTED else Status.SUCCEEDED },
                f.store.records().map { it.state })
            val results = f.history.snapshot().messages[2].parts.filterIsInstance<ContentPart.ToolResult>()
            assertEquals(f.store.records().map { it.callId }, results.map { it.callId })
            assertEquals(names.map { it == "hidden" }, results.map { it.isError })
        }
    }

    @Test fun truncatedCompleteCallsKeepProtocolCancellationWithoutExecution() = runTest {
        val f = RunFixture(definitions)
        val wire = Wire("hidden" to "{}", "ok" to "{}", finish = "length")
        assertEquals(RunState.PAUSED, f.loop(wire.provider).run().state)
        assertTrue(f.submitted.isEmpty())
        assertEquals(listOf(Status.CANCELLED, Status.CANCELLED), f.store.records().map { it.state })
        assertTrue(f.store.records().all { it.result!!.effect == Effect.NONE && it.instance == null })
        validateHistory(f.history.snapshot().messages)
    }

    @Test fun admittedProjectionStillValidatesOriginalSchemaAndKeepsDefinitionDigest() = runTest {
        val original = ok.copy(inputSchema = """{"type":"object","properties":{"n":{"type":"integer","minimum":2}},"required":["n"]}""")
        val store = TestStore(); val gate = DefinitionGate()
        val ledger = InvocationLedger(store, gate, validator)
        ledger.recoverColdStart("epoch"); gate.install(original)
        val submitted = mutableListOf<ExecutionTicket>()
        val wire = Wire("ok" to "{\"n\":1}")
        val history = MemoryRunHistory(listOf(message(Role.USER, textPart("goal"))))
        val executor = LedgerToolExecutor(ledger, gate, listOf(original), bridge(ledger, submitted))
        assertEquals(RunState.COMPLETED, AgentRunLoop(identity,
            ModelTarget("test", wire.provider, Credentials { "" }), request(listOf(original)), history, executor).run().state)
        assertTrue(submitted.isEmpty())
        val record = store.records().single()
        assertFalse(record.input.toolAdmissionRejected)
        assertEquals(Status.REJECTED, record.state)
        assertEquals("Invalid arguments", record.result!!.text)
        assertEquals(original.digest, record.definitionDigest)
        assertEquals(original.inputSchema, record.input.definition!!.inputSchema)
    }

    @Test fun pendingStepResumeUsesDurableAdmissionAndSubmitsOnlyAvailableSibling() = runTest {
        val directory = Files.createTempDirectory("schema-admission-history")
        val f = RunFixture(definitions)
        val wire = Wire("ok" to "{}", "hidden" to "{}")
        val pausedBridge = object : ToolExecutionBridge by f.bridge {
            override suspend fun beforeSubmit(callId: String) { throw ToolStepPaused("Execution capacity unavailable") }
        }
        TestRunHistory.create(directory, "session", Durability.PROCESS_CRASH_ONLY).use { history ->
            history.append(0, listOf(message(Role.USER, textPart("goal"))))
            val executor = LedgerToolExecutor(f.ledger, f.gate, definitions, pausedBridge)
            assertEquals(RunState.PAUSED, AgentRunLoop(identity,
                ModelTarget("test", wire.provider, Credentials { "" }), request(), history, executor).runChatTask().state)
            assertEquals(listOf(Status.READY, Status.REJECTED), history.pendingStep(f.ledger)!!.records.map { it.state })
        }
        // As in the host's pending-step path: reconstruct from persisted definitions, with no
        // provider callback or mutable configuration required to retain the original exclusion.
        TestRunHistory.open(directory, Durability.PROCESS_CRASH_ONLY).use { history ->
            val pending = history.pendingStep(f.ledger)!!
            val restored = pending.assistant.parts.filterIsInstance<ContentPart.ToolCall>().map { part ->
                object : CollectedCall {
                    override val binding = part.binding
                    override val name = part.name
                    override val argsJson = part.argsJson
                    override val complete = true
                    override val emptyArguments = false
                }
            }
            val executor = LedgerToolExecutor(f.ledger, f.gate,
                pending.records.mapNotNull { it.input.definition }.distinctBy { it.modelName }, f.bridge)
            executor.execute(restored)
            history.append(history.revision(), listOf(pending.assistant, executor.results(identity, pending.stepId, restored)))
            assertNull(history.pendingStep(f.ledger))
            validateHistory(history.snapshot().messages)
        }
        assertEquals(listOf("ok"), f.submitted.map { it.definition.modelName })
        assertEquals(listOf(Status.SUCCEEDED, Status.REJECTED), f.store.records().map { it.state })
        assertEquals(1, wire.bodies.size) // resume does not regenerate the tool turn
    }

    @Test fun exclusionIsTerminalInFirstSqliteWriteAndSurvivesColdRecoveryAndHistoryEncoding() = runTest {
        val directory = Files.createTempDirectory("schema-admission-ledger")
        val reducer = ModelEventReducer("test", "request")
        calls("hidden").forEach(reducer::accept)
        val calls = reducer.toolCandidates()
        TestInvocationStore.create(directory).use { store ->
            val gate = DefinitionGate(); val ledger = InvocationLedger(store, gate, validator)
            ledger.recoverColdStart("old"); gate.install(hidden)
            val executor = LedgerToolExecutor(ledger, gate, listOf(hidden), bridge(ledger, mutableListOf()))
            executor.freeze(identity)
            executor.register(identity, "step", calls, cancelled = false, admittedToolNames = emptySet())
            val record = store.records().single()
            assertEquals(1L, record.revision)
            assertEquals(Status.REJECTED, record.state)
            val bytes = ByteArrayOutputStream().also { HistoryCodec.writeValue(record, it) }.toString("UTF-8")
            assertEquals(record, HistoryCodec.json.decodeFromString<InvocationRecord>(bytes))
            assertFailsWith<IllegalArgumentException> { record.copy(state = Status.RECEIVED, result = null).validate() }
        }
        TestInvocationStore.open(directory).use { store ->
            val gate = DefinitionGate(); val ledger = InvocationLedger(store, gate, validator)
            ledger.recoverColdStart("new"); gate.install(hidden)
            val submitted = mutableListOf<ExecutionTicket>()
            LedgerToolExecutor(ledger, gate, listOf(hidden), bridge(ledger, submitted)).execute(calls)
            assertTrue(submitted.isEmpty())
            val record = store.records().single()
            assertTrue(record.input.toolAdmissionRejected)
            assertEquals(Status.REJECTED, record.state)
            assertEquals(hidden.digest, record.definitionDigest)
        }
    }

    @Test fun forcedExcludedToolFailsBeforeTransportCredentialsOrToolSubmission() = runTest {
        for (choice in listOf(namedTool("hidden"), ToolChoice.Required)) {
            val f = RunFixture(listOf(hidden)); val wire = Wire("hidden" to "{}")
            val request = chatRequest("m") { tools = request(listOf(hidden)).tools; toolChoice = choice }
            var credentialsRead = false
            val result = AgentRunLoop(identity, ModelTarget("test", wire.provider, Credentials {
                credentialsRead = true; ""
            }), request, f.history, LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge)).run()
            assertEquals(RunState.FAILED, result.state)
            assertTrue(wire.bodies.isEmpty())
            assertFalse(credentialsRead)
            assertTrue(f.submitted.isEmpty())
            assertTrue(f.store.records().isEmpty())
        }
    }
}
