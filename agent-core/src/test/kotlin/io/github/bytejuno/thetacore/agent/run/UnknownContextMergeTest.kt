package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** PR89/PR70 integration: real ledger transitions, durable context and actual provider requests. */
class UnknownContextMergeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val durability = Durability.PROCESS_CRASH_ONLY
    private val identity = RunIdentity("chain", "session", "next")
    private val bridge = object : ToolExecutionBridge {
        override fun submit(ticket: ExecutionTicket) = error("No tool may be replayed")
        override suspend fun awaitTerminal(callId: String) = error("No execution expected")
        override fun requestCancel(callId: String) = error("No cancellation expected")
    }
    private fun request(d: FrozenDefinition) = chatRequest("m") {
        maxOutputTokens = 100
        tools = listOf(modelTool(d.modelName, d.description, Json.parseToJsonElement(d.inputSchema) as JsonObject))
    }
    private fun append(h: TestRunHistory, f: Fixture, input: InvocationInput) {
        val assistant = message(Role.ASSISTANT, toolCallPart(input.binding, input.modelName, input.argsJson))
        h.prepareStep(h.revision(), RunIdentity(input.taskChainId, input.sessionId, input.runId, input.unattended), input.stepId, assistant)
        h.append(h.revision(), listOf(assistant,
            assertNotNull(f.ledger.resultsForStep(input.sessionId, input.runId, input.stepId, listOf(input.binding.callId)))))
    }
    private fun checkpoint(h: TestRunHistory, f: Fixture, d: FrozenDefinition, kind: String): ContextCheckpoint {
        val source = h.snapshot()
        val executor = LedgerToolExecutor(f.ledger, f.gate, listOf(d), bridge)
        return ContextCheckpoint(id = "checkpoint", sessionId = "session", taskChainId = "chain", runId = "previous",
            nextRunId = if (kind == "CONTINUATION") "next" else null, unattended = false,
            sourceRevision = source.revision, sourceCount = source.messages.size, sourceDigest = sourceDigest(source.messages),
            settingsKey = settingsKey("provider", request(d)), goal = "goal", steps = 2, retries = 1,
            kind = kind, summary = "completed work", reason = "test", serverUnresolved = false,
            ledger = executor.checkpointRecords(source.messages), catalog = listOf(d))
    }
    private suspend fun run(h: TestRunHistory, f: Fixture, d: FrozenDefinition, p: ScriptProvider,
                            policy: ContextPolicy, id: RunIdentity = identity): RunResult = AgentRunLoop(
        id, ModelTarget("provider", p, Credentials { "test" }), request(d), h,
        LedgerToolExecutor(f.ledger, f.gate, listOf(d), bridge), contextPolicy = policy).run()

    @Test fun `compacted unknown preserves ledger equality without injecting messages after disk reopen`() = runTest {
        Fixture().use { f ->
            val d = definition(Risk.WRITE)
            val input = f.input(d)
            val claim = f.claim(f.ready(input))
            f.ledger.complete(claim.record.callId, claim.ticket!!.attemptId, Status.FAILED, "uncertain", Effect.UNKNOWN)
            f.ledger.resolveRecovery(claim.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
            val path = temporary.root.toPath().resolve("view")
            lateinit var original: List<StoredMessage>
            lateinit var saved: ContextCheckpoint
            TestRunHistory.create(path, "session", durability).use { h ->
                append(h, f, input)
                val result = h.snapshot().messages.last().parts.single() as ContentPart.ToolResult
                assertTrue(result.text.contains("Side effects are unknown"))
                assertEquals(ModelResultText.project(f.ledger.get(claim.record.callId)!!.result!!), result.text)
                original = h.snapshot().messages.map(HistoryCodec::encodeMessage)
                saved = checkpoint(h, f, d, "VIEW")
                h.saveCheckpoint(saved)
            }
            repeat(2) { n ->
                f.reopen("epoch-$n-next")
                TestRunHistory.open(path, durability).use { h ->
                    val p = ScriptProvider(listOf(answer()))
                    assertEquals(RunState.COMPLETED, run(h, f, d, p,
                        ContextPolicy("goal", saved.sourceCount), identity.copy(runId = "run-$n")).state)
                    val sent = p.requests.single().messages
                    assertTrue((sent.first().parts.single() as ContentPart.Text).text.startsWith("Summary continuation"))
                    assertEquals(listOf(Role.USER) + List(n) { Role.ASSISTANT }, sent.map { it.role })
                    assertEquals(0, sent.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
                        .count { it.text.contains("Side effects are unknown") })
                    assertEquals(saved.ledger.single().atLedgerPrecision(),
                        f.ledger.get(claim.record.callId)!!.atLedgerPrecision())
                    assertTrue(sent.flatMap { it.parts }.none { it is ContentPart.ToolCall || it is ContentPart.ToolResult })
                    assertEquals(original, h.snapshot().messages.take(original.size).map(HistoryCodec::encodeMessage))
                    assertEquals(saved, h.loadView())
                    assertTrue(saved.matches(h.snapshot(), saved.settingsKey))
                    assertEquals(0, h.snapshot().messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
                        .count { it.text.contains("Side effects are unknown") })
                    val stored = h.snapshot().messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().single()
                    assertEquals(ModelResultText.project(f.ledger.get(stored.callId)!!.result!!), stored.text)
                }
            }
        }
    }

    @Test fun `checkpoint denial reason and acknowledgement survive nested disk roundtrip and explicit admission`() = runTest {
        Fixture().use { f ->
            val d = definition(Risk.WRITE)
            val old = f.claim(f.ready(f.input(d)))
            f.ledger.complete(old.record.callId, old.ticket!!.attemptId, Status.FAILED, "uncertain", Effect.UNKNOWN)
            val input = f.input(d, args = "{\"new\":true}", unattended = true)
            val denied = f.prepare(input)
            assertEquals(ApprovalDenialReason.UNATTENDED_UNKNOWN, denied.receipts.single().reason)
            // Clear original uncertainty, but the denial still requires its own acknowledgement.
            f.ledger.resolveRecovery(old.record.callId, RecoveryResolution.VERIFIED_CLEAR)
            val executor = LedgerToolExecutor(f.ledger, f.gate, listOf(d), bridge)
            assertTrue(executor.localExecutionBlocked(listOf(denied)))
            assertTrue(executor.checkpointBlocked(listOf(denied)))
            val path = temporary.root.toPath().resolve("receipt")
            TestRunHistory.create(path, "session", durability).use { h ->
                append(h, f, input)
                val pending = checkpoint(h, f, d, "PAUSED").copy(summary = null, pauseClass = "LOCAL_WAIT")
                h.saveCheckpoint(pending)
            }
            f.reopen()
            TestRunHistory.open(path, durability).use { h ->
                assertEquals(denied.atLedgerPrecision(), h.loadCheckpoint()!!.ledger.single().atLedgerPrecision())
                val p = ScriptProvider(listOf(answer()))
                assertEquals(RunState.PAUSED, run(h, f, d, p, ContextPolicy("goal")).state)
                assertTrue(p.requests.isEmpty())
                assertIs<Mutation.Applied>(f.ledger.resolveRecovery(denied.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
                val acknowledged = f.ledger.get(denied.callId)!!
                assertEquals(denied.receipts.single().request.id, acknowledged.acknowledgedUnknownDenial)
                assertFalse(executor.localExecutionBlocked(listOf(acknowledged)))
                assertFalse(executor.checkpointBlocked(listOf(acknowledged)))
                h.saveCheckpoint(checkpoint(h, f, d, "CONTINUATION"))
            }
            f.reopen("third")
            TestRunHistory.open(path, durability).use { h ->
                val saved = assertNotNull(h.loadCheckpoint())
                val record = saved.ledger.single()
                assertEquals(ApprovalDenialReason.UNATTENDED_UNKNOWN, record.receipts.single().reason)
                assertEquals(record.receipts.single().request.id, record.acknowledgedUnknownDenial)
                assertEquals(f.ledger.get(record.callId)!!.atLedgerPrecision(), record.atLedgerPrecision())
                val p = ScriptProvider(listOf(answer()))
                assertEquals(RunState.COMPLETED, run(h, f, d, p,
                    ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).state)
                assertEquals(1, p.requests.size)
                assertTrue(p.requests.single().messages.flatMap { it.parts }.none { it is ContentPart.ToolCall })
            }
        }
    }

    @Test fun `accepted resource isolation blocks local and strict gates while resource free unknown allows ordinary run`() = runTest {
        for (resource in listOf(null, "screen")) Fixture().use { f ->
            val d = definition(Risk.WRITE, resource = resource)
            val input = f.input(d)
            val claim = f.claim(f.ready(input))
            f.ledger.complete(claim.record.callId, claim.ticket!!.attemptId, Status.FAILED, "uncertain", Effect.UNKNOWN)
            f.ledger.resolveRecovery(claim.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
            val executor = LedgerToolExecutor(f.ledger, f.gate, listOf(d), bridge)
            val record = f.ledger.get(claim.record.callId)!!
            assertEquals(resource != null, executor.localExecutionBlocked(listOf(record)))
            assertTrue(executor.checkpointBlocked(listOf(record)))
            val path = temporary.root.toPath().resolve("isolation-${resource ?: "none"}")
            TestRunHistory.create(path, "session", durability).use { h ->
                append(h, f, input)
                val p = ScriptProvider(listOf(answer()))
                assertEquals(if (resource == null) RunState.COMPLETED else RunState.PAUSED,
                    run(h, f, d, p, ContextPolicy("goal")).state)
                assertEquals(if (resource == null) 1 else 0, p.requests.size)
                assertEquals(Effect.UNKNOWN, f.ledger.get(record.callId)!!.result!!.effect)
            }
        }
    }
}
