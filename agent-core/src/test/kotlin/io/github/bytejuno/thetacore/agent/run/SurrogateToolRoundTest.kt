package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class SurrogateToolRoundTest {
    private val durability = Durability.PROCESS_CRASH_ONLY
    private val identity = RunIdentity("chain", "session", "run")
    private val text = "high\uD800 low\uDC00 emoji😀 literal\\ud800"
    private val replay = replayData("p$text", "m$text", "payload$text")
    private fun richTurn() = listOf(
        Events.started("response", "m"), Events.text(0, text), Events.end(0, replay),
        Events.reasoning(1, text), Events.end(1, replay), Events.toolStart(2, "wire", "tool"),
        Events.toolDelta(2, "{}"), Events.end(2, replay), Events.finished(FinishReason.TOOL_CALLS))
    private fun stored(history: RunHistory) = history.snapshot().messages.map(HistoryCodec::encodeMessage)

    @Test fun textReasoningAndReplaySurviveCompletedToolRoundAndReopen() = runTest {
        val path = Files.createTempDirectory("surrogate-round")
        val f = RunFixture(listOf(testDefinition()))
        val p = ScriptProvider(listOf(richTurn(), answer()))
        lateinit var expected: StoredMessage
        TestRunHistory.create(path, "session", durability).use { h ->
            assertEquals(RunState.COMPLETED, f.loop(p, history = h).run().state)
            val original = f.journal.attempts().first().partial
            assertEquals(text, (original.parts[0] as ContentPart.Text).text)
            assertEquals(text, (original.parts[1] as ContentPart.Reasoning).summary)
            assertTrue(original.parts.all { it.replay == replay })
            expected = HistoryCodec.encodeMessage(original)
            assertEquals(1, f.submitted.size)
            assertEquals(expected, stored(h).first())
            assertEquals(expected, HistoryCodec.encodeMessage(p.requests[1].messages.first()))
            validateHistory(h.snapshot().messages)
        }
        TestRunHistory.open(path, durability).use { h ->
            assertEquals(expected, stored(h).first())
            assertFalse(h.recoverPending(f.ledger).recovered)
            val next = ScriptProvider(listOf(answer()))
            assertEquals(RunState.COMPLETED, f.loop(next, history = h).run().state)
            assertEquals(1, f.submitted.size) // committed call is not replayed
        }
    }

    @Test fun invalidCallFieldsPauseWholeBatchBeforeAnyRegistrationWithMemoryOrSqlite() = runTest {
        // A valid first sibling must not execute when the second call is invalid.
        for (disk in listOf(false, true)) for (field in listOf("args-high", "args-low", "name", "wire", "response", "provider")) {
            val f = RunFixture(listOf(testDefinition()))
            val bad = "bad\uD800"
            val events = buildList {
                add(Events.started(if (field == "response") bad else "response", "m"))
                add(Events.toolStart(0, "first", "tool")); add(Events.toolDelta(0, "{}")); add(Events.end(0))
                add(Events.toolStart(1, if (field == "wire") bad else "second", if (field == "name") bad else "tool"))
                add(Events.toolDelta(1, when (field) {
                    "args-high" -> "{\"value\":\"\uD800\"}"
                    "args-low" -> "{\"value\":\"\uDC00\"}"
                    else -> "{}"
                }))
                add(Events.end(1)); add(Events.finished(FinishReason.TOOL_CALLS))
            }
            val path = if (disk) Files.createTempDirectory("invalid-surrogate-round") else null
            val h = path?.let { TestRunHistory.create(it, "session", durability) } ?: MemoryRunHistory()
            val before = stored(h)
            try {
                val p = ScriptProvider(listOf(events))
                val result = AgentRunLoop(identity,
                    ModelTarget(if (field == "provider") bad else "provider", p, Credentials { "fake" }), f.request(), h,
                    LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge), f.journal).run()
                assertEquals(RunState.PAUSED, result.state, "$disk/$field")
                assertTrue(result.reason.contains("unpaired Unicode surrogate"), "$disk/$field: ${result.reason}")
                assertFalse(result.serverExecutionUnknown)
                assertEquals(1, result.steps); assertEquals(0, result.retries)
                assertEquals(1, p.requests.size)
                assertEquals(before, stored(h)); assertEquals(0L, h.revision())
                assertTrue(f.store.records().isEmpty()); assertTrue(f.submitted.isEmpty()); assertTrue(f.order.isEmpty())
                assertTrue(f.cancelled.isEmpty()); validateHistory(h.snapshot().messages)
                val original = ModelEventReducer(if (field == "provider") bad else "provider", p.requests.single().requestId)
                    .apply { events.forEach(::accept) }.assistantMessage()
                // The rejected response remains in the configured forensic journal, not paired history.
                // Call IDs are generated by each reducer; compare source fields independently.
                assertEquals(HistoryCodec.encodeMessage(original).parts.map { it.copy(binding = it.binding?.copy(callId = "id")) },
                    HistoryCodec.encodeMessage(f.journal.attempts().single().partial).parts.map { it.copy(binding = it.binding?.copy(callId = "id")) })
                if (h is TestRunHistory) assertFalse(h.hasValue("pending"))
            } finally { (h as? TestRunHistory)?.close() }
            if (path != null) TestRunHistory.open(path, durability).use { reopened ->
                assertFalse(reopened.recoverPending(f.ledger).recovered)
                assertEquals(before, stored(reopened))
                assertEquals(RunState.COMPLETED, f.loop(ScriptProvider(listOf(answer())), history = reopened).run().state)
            }
        }
    }

    @Test fun invalidCallDoesNotClearPossibleServerExecutionAcrossReopen() = runTest {
        val path = Files.createTempDirectory("surrogate-server-unknown")
        val f = RunFixture(listOf(testDefinition()))
        TestRunHistory.create(path, "session", durability).use { h ->
            val p = ScriptProvider(listOf(calls("tool", args = "{\"x\":\"\uD800\"}")), ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
            val result = f.loop(p, history = h).run()
            assertEquals(RunState.PAUSED, result.state); assertTrue(result.serverExecutionUnknown)
            assertNotNull(h.loadBarrier()); assertFalse(h.hasValue("pending"))
            assertTrue(f.submitted.isEmpty()); assertTrue(f.store.records().isEmpty())
        }
        TestRunHistory.open(path, durability).use { h ->
            val next = ScriptProvider(listOf(answer()))
            val result = f.loop(next, history = h).run()
            assertEquals(RunState.PAUSED, result.state); assertTrue(result.serverExecutionUnknown)
            assertTrue(next.requests.isEmpty()); assertTrue(h.snapshot().messages.isEmpty())
        }
    }

    @Test fun pendingAndCommittedRichTurnsRecoverAgainstReloadedLedgerWithoutLosingCodeUnits() {
        for (cut in listOf("pending", "registered", "committed")) {
            val path = Files.createTempDirectory("surrogate-recovery")
            val ledgerPath = Files.createTempDirectory("surrogate-recovery-ledger")
            val assistant = ModelEventReducer("provider", "request").apply { richTurn().forEach(::accept) }.assistantMessage()
            val call = assistant.parts.filterIsInstance<ContentPart.ToolCall>().single()
            TestRunHistory.create(path, "session", durability).close()
            TestInvocationStore.create(ledgerPath).use { store ->
                val definition = testDefinition()
                val gate = DefinitionGate().apply { install(definition); freezeRun("run", listOf(definition)) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("before")
                TestRunHistory.open(path, durability) {
                    if (cut == "committed" && it.file == "append" && it.stage == HistoryWriteStage.AFTER_COMMIT)
                        throw IOException("lost commit acknowledgement")
                }.use { h ->
                    h.prepareStep(0, identity, "step", assistant)
                    if (cut != "pending") {
                        assertIs<Mutation.Applied>(ledger.recordInvocation(InvocationInput(
                            BindingValue.copyOf(call.binding), "chain", "session", "run", "step", 0, "{}",
                            definition, false, Risk.READ, false, "tool")))
                        ledger.validateAndPrepare(call.binding.callId)
                        val ticket = assertIs<Mutation.Applied>(ledger.claimExecution(call.binding.callId,
                            ledger.get(call.binding.callId)!!.revision)).ticket!!
                        assertIs<Mutation.Applied>(ledger.complete(call.binding.callId, ticket.attemptId, Status.SUCCEEDED, "done"))
                    }
                    if (cut == "committed") assertFailsWith<HistoryStorageException> {
                        h.append(0, listOf(assistant, ledger.resultsForStep("session", "run", "step", listOf(call.binding.callId))!!))
                    }
                }
            }
            TestInvocationStore.open(ledgerPath).use { store ->
                val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("after")
                TestRunHistory.open(path, durability).use { h ->
                    assertTrue(h.recoverPending(ledger).recovered)
                    assertFalse(h.recoverPending(ledger).recovered)
                    assertEquals(1L, h.revision()); assertFalse(h.hasValue("pending"))
                    assertEquals(HistoryCodec.encodeMessage(assistant), stored(h).first())
                    assertEquals(2, h.snapshot().messages.size); validateHistory(h.snapshot().messages)
                    val record = store.records().single()
                    assertEquals(if (cut == "pending") Status.CANCELLED else Status.SUCCEEDED, record.state)
                    assertEquals(Effect.NONE, record.result!!.effect)
                    if (cut == "pending") assertNull(record.instance)
                    else assertEquals(InstanceState.EXIT_CONFIRMED, record.instance!!.state)
                }
            }
        }
    }

    @Test fun validPairsAndLiteralEscapesRemainExecutable() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val args = "{\"emoji\":\"😀\",\"literal\":\"\\ud800\"}"
        val p = ScriptProvider(listOf(calls("tool", args = args), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertEquals(args, f.submitted.single().argsJson)
        validateHistory(f.history.snapshot().messages)
    }
}
