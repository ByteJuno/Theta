package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Kernel-boundary fault injection, not evidence of a production history import or migration path. */
class HistoryLedgerConsistencyTest {
    private val durability = Durability.PROCESS_CRASH_ONLY
    private fun assistant(count: Int = 1) = message(Role.ASSISTANT, *(0 until count).map { order ->
        toolCallPart(callBinding("provider", "request", "response", order, "wire-$order", UUID.randomUUID().toString()),
            "tool", "{}")
    }.toTypedArray())
    private fun calls(assistant: Message) = assistant.parts.filterIsInstance<ContentPart.ToolCall>()
    private fun input(call: ContentPart.ToolCall, order: Int) = InvocationInput(
        BindingValue.copyOf(call.binding), "chain", "session", "old-run", "step", order,
        call.argsJson, null, false, Risk.READ, true, call.name)
    private fun result(call: ContentPart.ToolCall) = toolResultPart(call.binding.callId,
        ModelResultText.project(LogicalResult(Status.CANCELLED, Effect.NONE, "Truncated model turn: not executed", false)), true)
    private fun pair(assistant: Message) = listOf(assistant, message(Role.TOOL, *calls(assistant).map(::result).toTypedArray()))
    private fun canonical(h: RunHistory) = h.snapshot().messages.map(HistoryCodec::encodeMessage)

    private suspend fun assertRejected(f: RunFixture, h: RunHistory, reason: String, partial: Boolean = false) {
        val before = canonical(h)
        val revision = h.revision()
        val ledger = f.store.records()
        validateHistory(h.snapshot().messages) // a complete syntactically valid pair is not ledger authority
        repeat(2) {
            val provider = ScriptProvider(listOf(answer()))
            val failure = assertFailsWith<IllegalStateException> { f.loop(provider, history = h).run() }
            assertIs<ToolStepBlocked>(failure)
            assertEquals(reason, failure.message)
            assertTrue(provider.requests.isEmpty())
            assertTrue(f.submitted.isEmpty())
            assertTrue(f.cancelled.isEmpty())
            assertTrue(f.order.isEmpty()) // includes bridge await, not just submission
            assertEquals(before, canonical(h))
            assertEquals(revision, h.revision())
            assertEquals(ledger, f.store.records())
        }
        if (partial) assertEquals(1, ledger.size)
    }

    @Test fun completeHistoricalPairWithMissingLedgerRowFailsBeforeProviderOnFreshLoops() = runTest {
        val f = RunFixture()
        val h = MemoryRunHistory(pair(assistant()))
        assertRejected(f, h, "Checkpoint missing ledger record")
    }

    @Test fun partiallyRegisteredMultiCallHistoryIsNotRepairedOrReplayed() = runTest {
        val f = RunFixture()
        val a = assistant(2)
        assertIs<Mutation.Applied>(f.ledger.recordInvocation(input(calls(a).first(), 0)))
        assertRejected(f, MemoryRunHistory(pair(a)), "Incomplete checkpoint step", partial = true)
    }

    @Test fun unexpectedLedgerCallCannotBeHiddenByExpectedIds() = runTest {
        val f = RunFixture()
        val a = assistant(2)
        calls(a).forEachIndexed { order, call -> assertIs<Mutation.Applied>(f.ledger.recordInvocation(input(call, order))) }
        val onlyFirst = message(Role.ASSISTANT, calls(a).first())
        assertRejected(f, MemoryRunHistory(pair(onlyFirst)), "Incomplete checkpoint step")
    }

    @Test fun resultAndSourceDisagreementsRemainStrictOnFreshLoops() = runTest {
        for (mismatch in listOf("result", "binding", "arguments", "name")) {
            val f = RunFixture()
            val a = assistant()
            val call = calls(a).single()
            assertIs<Mutation.Applied>(f.ledger.recordInvocation(input(call, 0)))
            val source = when (mismatch) {
                "binding" -> message(Role.ASSISTANT, toolCallPart(
                    BindingValue.copyOf(call.binding).copy(requestId = "different-request"), call.name, call.argsJson))
                "arguments" -> message(Role.ASSISTANT, toolCallPart(call.binding, call.name, "{\"changed\":true}"))
                "name" -> message(Role.ASSISTANT, toolCallPart(call.binding, "other-tool", call.argsJson))
                else -> a
            }
            val h = MemoryRunHistory(listOf(source, if (mismatch == "result")
                message(Role.TOOL, toolResultPart(call.binding.callId, "fabricated result", true)) else pair(a).last()))
            val before = canonical(h)
            val ledger = f.store.records()
            validateHistory(h.snapshot().messages)
            repeat(2) {
                val provider = ScriptProvider(listOf(answer()))
                val failure = assertFailsWith<IllegalArgumentException> { f.loop(provider, history = h).run() }
                assertEquals(if (mismatch == "result") "Checkpoint result disagrees with ledger"
                    else "Checkpoint source disagrees with ledger", failure.message)
                assertTrue(provider.requests.isEmpty())
                assertTrue(f.submitted.isEmpty()); assertTrue(f.cancelled.isEmpty()); assertTrue(f.order.isEmpty())
                assertEquals(before, canonical(h))
                assertEquals(0L, h.revision())
                assertEquals(ledger, f.store.records())
            }
        }
    }

    // Byte snapshots of committed/pending history artifacts; no hashes or build-artifact audit.
    private fun files(path: Path): Map<String, List<Byte>> = Files.walk(path).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "writer.lock" }
            .toList().associate { path.relativize(it).toString() to Files.readAllBytes(it).toList() }
    }

    @Test fun persistedHistoryReopenWithEmptyLedgerStillFailsWithoutChangingEitherStore() = runTest {
        val path = Files.createTempDirectory("history-ledger-consistency")
        try {
            FileRunHistory.create(path, "session", durability).use { it.append(0, pair(assistant())) }
            val before = files(path)
            repeat(2) {
                FileRunHistory.open(path, durability).use { h ->
                    val f = RunFixture() // deliberately pair a valid persisted history with a different empty ledger
                    assertRejected(f, h, "Checkpoint missing ledger record")
                }
                assertEquals(before, files(path))
            }
        } finally { path.toFile().deleteRecursively() }
    }

    @Test fun committedPendingMissingRowIsRefusedWithoutCleanupOrSyntheticLedgerWrite() {
        val path = Files.createTempDirectory("history-committed-missing-row")
        val a = assistant()
        try {
            FileRunHistory.create(path, "session", durability).use { h ->
                h.prepareStep(0, RunIdentity("chain", "session", "old-run"), "step", a)
            }
            FileRunHistory.open(path, durability) {
                if (it.file == "head.json" && it.stage == HistoryWriteStage.AFTER_REPLACE) error("lost acknowledgement")
            }.use { h -> assertFailsWith<HistoryStorageException> { h.append(0, pair(a)) } }
            val before = files(path)
            repeat(2) {
                val f = RunFixture()
                FileRunHistory.open(path, durability).use { h ->
                    assertEquals(1L, h.revision())
                    val failure = assertFailsWith<IllegalStateException> { h.recoverPending(f.ledger) }
                    assertEquals("Committed step missing ledger record", failure.message)
                    assertTrue(f.store.records().isEmpty())
                    assertTrue(Files.exists(path.resolve("pending.json")))
                    assertFails { h.snapshot() }
                    assertEquals(1L, h.revision())
                }
                assertEquals(before, files(path))
            }
        } finally { path.toFile().deleteRecursively() }
    }

    @Test fun uncommittedPendingIntentMayRecoverMissingRegistrationsWithoutExecutingTools() = runTest {
        val path = Files.createTempDirectory("history-uncommitted-missing-row")
        val a = assistant(2)
        val f = RunFixture()
        try {
            FileRunHistory.create(path, "session", durability).use { h ->
                h.prepareStep(0, RunIdentity("chain", "session", "old-run"), "step", a)
            }
            FileRunHistory.open(path, durability).use { h ->
                val recovery = h.recoverPending(f.ledger)
                assertTrue(recovery.recovered)
                assertFalse(recovery.requiresDecision)
                assertEquals(pair(a).map(HistoryCodec::encodeMessage), canonical(h))
                assertEquals(1L, h.revision())
                assertEquals(2, f.store.records().size)
                f.store.records().forEach {
                    assertEquals(Status.CANCELLED, it.state)
                    assertEquals(Effect.NONE, it.result!!.effect)
                    assertNull(it.instance)
                }
                assertFalse(Files.exists(path.resolve("pending.json")))
            }
            FileRunHistory.open(path, durability).use { h ->
                val before = f.store.records()
                assertFalse(h.recoverPending(f.ledger).recovered)
                val provider = ScriptProvider(listOf(answer()))
                assertEquals(RunState.COMPLETED, f.loop(provider, history = h).run().state)
                assertEquals(1, provider.requests.size)
                assertEquals(before, f.store.records())
                assertTrue(f.submitted.isEmpty()); assertTrue(f.cancelled.isEmpty()); assertTrue(f.order.isEmpty())
            }
        } finally { path.toFile().deleteRecursively() }
    }
}
