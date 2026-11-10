package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class UnknownLegacyProjectionTest {
    private val shortText = "ordinary failure \"quoted\" \\ \n中文"
    private val longText = ("\"\\\u0000\n中文😀".repeat(10_000))

    @Test fun shortLegacyUnknownCommittedBeforePendingCleanupContinues() = runTest { recover(shortText) }
    @Test fun escapedTruncatedLegacyUnknownCommittedBeforePendingCleanupContinues() = runTest { recover(longText) }
    @Test fun mismatchingLegacyUnknownStillFailsStrictEquality() = runTest { recover(shortText, mismatch = true) }
    @Test fun corruptLegacyUnknownBatchStillFailsClosed() = runTest { recover(shortText, corrupt = true) }

    private suspend fun recover(raw: String, mismatch: Boolean = false, corrupt: Boolean = false) {
        Fixture().use { f ->
            val input = f.input(definition(Risk.WRITE))
            val claim = f.claim(f.ready(input))
            val id = claim.record.callId
            f.ledger.complete(id, claim.ticket!!.attemptId, Status.FAILED, raw)
            val canonical = f.ledger.get(id)!!.result!!
            assertEquals(Effect.UNKNOWN, canonical.effect)
            val legacy = LegacyModelResultTextFixture.project(canonical)
            assertTrue(legacy.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            assertFalse(legacy.contains("\"notice\""))
            if (raw == longText) {
                assertTrue(legacy.contains("Truncated ledger message"))
                assertTrue(legacy.toByteArray(Charsets.UTF_8).size > 32 * 1024 - 8)
            }
            val assistant = message(Role.ASSISTANT, toolCallPart(input.binding, input.modelName, input.argsJson))
            val pair = listOf(assistant, message(Role.TOOL, toolResultPart(id, if (mismatch) legacy + " " else legacy, true)))
            val path = Files.createTempDirectory("unknown-legacy-history")
            try {
                FileRunHistory.create(path, "session", Durability.PROCESS_CRASH_ONLY).use { h ->
                    h.prepareStep(0, RunIdentity("chain", "session", input.runId), "step", assistant)
                }
                FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY) {
                    if (it.file == "head.json" && it.stage == HistoryWriteStage.AFTER_REPLACE)
                        error("crash after committed pair before pending cleanup")
                }.use { h -> assertFailsWith<HistoryStorageException> { h.append(0, pair) } }
                assertTrue(Files.exists(path.resolve("pending.json")))
                val committedBytes = Files.readAllBytes(path.resolve("batches/1.json"))
                f.reopen()
                assertEquals(raw, f.ledger.get(id)!!.result!!.text)
                if (corrupt) {
                    Files.write(path.resolve("batches/1.json"), byteArrayOf(0))
                    assertFails { FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY) }
                    assertTrue(Files.exists(path.resolve("pending.json")))
                    return
                }
                FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                    if (mismatch) {
                        val failure = assertFailsWith<IllegalArgumentException> { h.recoverPending(f.ledger) }
                        assertEquals("Ledger/history disagreement", failure.message)
                        assertTrue(Files.exists(path.resolve("pending.json")))
                        assertFails { h.snapshot() }
                        assertContentEquals(committedBytes, Files.readAllBytes(path.resolve("batches/1.json")))
                        return
                    }
                    val recovered = h.recoverPending(f.ledger)
                    assertTrue(recovered.recovered)
                    assertTrue(recovered.requiresDecision)
                    assertEquals(1, h.revision())
                    assertFalse(Files.exists(path.resolve("pending.json")))
                    assertFalse(h.recoverPending(f.ledger).recovered)
                    assertEquals(pair.map(HistoryCodec::encodeMessage), h.snapshot().messages.map(HistoryCodec::encodeMessage))
                    val p = ScriptProvider(listOf(answer()))
                    assertEquals(RunState.COMPLETED, loop(f, h, p).run().state)
                    assertRequest(p.requests.single(), id, legacy, pair)
                }
                // The warning itself is durable and is not duplicated on another process/loop reopen.
                f.reopen("epoch-3")
                FileRunHistory.open(path, Durability.PROCESS_CRASH_ONLY).use { h ->
                    val p = ScriptProvider(listOf(answer()))
                    assertEquals(RunState.COMPLETED, loop(f, h, p).run().state)
                    assertRequest(p.requests.single(), id, legacy, pair)
                }
                assertEquals(canonical, f.ledger.get(id)!!.result)
                assertContentEquals(committedBytes, Files.readAllBytes(path.resolve("batches/1.json")))
            } finally { path.toFile().deleteRecursively() }
        }
    }

    private fun assertRequest(request: ChatRequest, id: String, legacy: String, pair: List<Message>) {
        assertEquals(pair.map(HistoryCodec::encodeMessage), request.messages.take(2).map(HistoryCodec::encodeMessage))
        val result = request.messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().single()
        assertEquals(id, result.callId)
        assertEquals(legacy, result.text)
        assertTrue(result.isError)
        val warning = "Tool invocation $id: canonical result reports UNKNOWN. " +
            "Side effects are unknown. The operation may have taken effect; verify the outcome before retrying. " +
            "The canonical FAILED / UNKNOWN result is unchanged."
        val notices = request.messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
            .filter { it.text.contains("Side effects are unknown") }
        assertEquals(listOf(warning), notices.map { it.text })
        assertTrue(notices.single().text.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        validateHistory(request.messages)
    }

    private fun loop(f: Fixture, h: RunHistory, p: ScriptProvider) = AgentRunLoop(
        RunIdentity("chain", "session", "resumed"), ModelTarget("provider", p, Credentials { "fake" }),
        chatRequest("m") {}, h, LedgerToolExecutor(f.ledger, f.gate, emptyList(), object : ToolExecutionBridge {
            override fun submit(ticket: ExecutionTicket) = error("No submission")
            override suspend fun awaitTerminal(callId: String) = error("No await")
            override fun requestCancel(callId: String) = error("No cancellation")
        }))
}
