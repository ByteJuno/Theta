package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import kotlin.test.*

class HistoryStorageRegressionTest {
    private val durability = Durability.PROCESS_CRASH_ONLY
    private val identity = RunIdentity("chain", "session", "run")
    private fun call(id: String = UUID.randomUUID().toString(), name: String = "tool", args: String = "{}",
                     index: Int = 0, wire: String? = "wire") =
        toolCallPart(callBinding("p", "request", "response", index, wire, id), name, args)
    private fun assistant(vararg parts: ContentPart) = message(Role.ASSISTANT, *parts)
    private fun ledger() = InvocationLedger(TestStore(), DefinitionGate(), ArgumentsValidator { _, _ -> true })
        .also { it.recoverColdStart("epoch") }
    private fun temps(path: Path) = Files.walk(path).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".tmp") }.toArray().toList()
    }

    @Test fun invalidIntentNeverWritesAndReopenedSessionRemainsUsable() {
        val good = call()
        val badBinding = object : CallBinding by good.binding { override val callId = "not-a-uuid" }
        val cases = listOf(
            identity.copy(taskChainId = " ") to assistant(good),
            identity.copy(runId = "") to assistant(good),
            identity.copy(sessionId = "other") to assistant(good),
            identity to assistant(good, good),
            identity to assistant(call(name = " ")),
            identity to assistant(call(index = -1)),
            identity to assistant(call(id = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")),
            identity to assistant(toolCallPart(badBinding, "tool", "{}")),
            identity to assistant(good, toolResultPart(good.binding.callId, "orphan")),
            identity to message(Role.USER, good),
            identity to assistant(textPart("no call")),
            identity to assistant(call(args = "{\"x\":\"\uD800\"}")),
            identity to assistant(call(name = "tool\uDC00")),
            identity.copy(runId = "run\uD800") to assistant(good),
            identity to assistant(good, textPart("text", replayData("p", "m", "bad\uD800"))),
        )
        for ((run, message) in cases) {
            val path = Files.createTempDirectory("history-bad-intent")
            TestRunHistory.create(path, "session", durability).use { h ->
                val before = h.io()
                assertFails { h.prepareStep(0, run, "step", message) }
                assertEquals(before.bytesWritten, h.io().bytesWritten)
                assertFalse(h.hasValue("pending"))
                assertTrue(temps(path).isEmpty())
                assertEquals(0, h.revision())
            }
            TestRunHistory.open(path, durability).use { h ->
                h.prepareStep(0, identity, "valid", assistant(call()))
                assertTrue(h.recoverPending(ledger()).recovered)
                assertEquals(2, h.snapshot().messages.size)
            }
        }
    }

    @Test fun usedIdentityAndBlankStepAreRejectedBeforeIntentButInvalidArgumentsRemainEvidence() {
        val path = Files.createTempDirectory("history-identity")
        val c = call()
        TestRunHistory.create(path, "session", durability).use { h ->
            assertFails { h.prepareStep(0, identity, " ", assistant(c)) }
            h.append(0, listOf(assistant(c), message(Role.TOOL, toolResultPart(c.binding.callId, "done"))))
            assertFails { h.prepareStep(1, identity, "step", assistant(c)) }
            assertFalse(h.hasValue("pending"))
        }
        TestRunHistory.open(path, durability).use { h ->
            val l = ledger()
            // Do not invent stricter argument JSON rules than InvocationInput: truncated and
            // malformed inputs must still be recorded, cancelled and projected losslessly.
            for ((i, args) in listOf("", " ", "null", "[]", "{broken", "{\"x\":1}").withIndex()) {
                val a = assistant(call(args = args))
                h.prepareStep(h.revision(), identity, "step$i", a)
                assertTrue(h.recoverPending(l).recovered)
                val restored = h.recent(1).messages.first().parts.single() as ContentPart.ToolCall
                assertEquals(args, restored.argsJson)
            }
        }
    }

    @Test fun escapingAmplifiedLedgerResultBeyond64MiBRecoversAfterFailedCommit() {
        val path = Files.createTempDirectory("history-large-result")
        val ledgerPath = Files.createTempDirectory("history-large-ledger")
        // PROTOCOL 3.8 caps each projected result at 32 KiB, so only a whole step can pass 64 MiB.
        val calls = List(1800) { call(index = it, wire = "wire$it") }
        val text = "\u0000".repeat(6000) + "终点😀\\\"\n"
        val d = testDefinition()
        TestInvocationStore.create(ledgerPath).use { store ->
            val gate = DefinitionGate().apply { install(d); freezeRun("run", listOf(d)) }
            val l = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("before")
            TestRunHistory.create(path, "session", durability).use {
                it.prepareStep(0, identity, "step", assistant(*calls.toTypedArray()))
            }
            calls.forEachIndexed { order, c ->
                l.recordInvocation(InvocationInput(BindingValue.copyOf(c.binding), "chain", "session", "run", "step",
                    order, "{}", d, false, Risk.READ, false, "tool"))
                l.validateAndPrepare(c.binding.callId)
                val ticket = assertIs<Mutation.Applied>(l.claimExecution(c.binding.callId, l.get(c.binding.callId)!!.revision)).ticket!!
                assertIs<Mutation.Applied>(l.complete(c.binding.callId, ticket.attemptId, Status.SUCCEEDED, text))
            }
        }
        TestInvocationStore.open(ledgerPath).use { store ->
            val l = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("after")
            TestRunHistory.open(path, durability) {
                if (it.file == "append" && it.stage == HistoryWriteStage.BEFORE_COMMIT) throw IOException("lost commit")
            }.use { h -> assertFailsWith<HistoryStorageException> { h.recoverPending(l) } }

            TestRunHistory.open(path, durability).use { h ->
                assertEquals(0, h.revision())
                assertTrue(h.recoverPending(l).recovered)
                assertFalse(h.recoverPending(l).recovered)
                assertEquals(1, h.revision())
                assertFalse(h.hasValue("pending"))
            }
        }
        TestRunHistory.open(path, durability).use { h ->
            val page = h.recent(1)
            assertTrue(TestRunHistory.bytes(path, "batches/1").size > 64L * 1024 * 1024)
            assertEquals(2, page.messages.size) // transport frames never split a transaction/page
            assertNull(page.older)
            val results = page.messages.last().parts.map { it as ContentPart.ToolResult }
            assertEquals(calls.map { it.binding.callId }, results.map { it.callId })
            val expected = ModelResultText.project(LogicalResult(Status.SUCCEEDED, Effect.NONE, text, false))
            assertTrue(expected.toByteArray(Charsets.UTF_8).size <= ModelResultText.LIMIT)
            results.forEach { assertEquals(expected, it.text) }
        }
        println("ISSUE19_LARGE_RESULT calls=${calls.size}; rawChars=${text.length}; batchBytes=${TestRunHistory.bytes(path, "batches/1").size}; ledger=$ledgerPath; history=$path")
    }

    @Test fun framedHistoryPreservesLoneSurrogatesInResultsWithoutChangingLedgerTruth() {
        val path = Files.createTempDirectory("history-result-surrogates")
        val c = call()
        val text = "partial\uD800 text \uDC00"
        TestRunHistory.create(path, "session", durability).use { h ->
            h.append(0, listOf(assistant(c), message(Role.TOOL, toolResultPart(c.binding.callId, text))))
        }
        TestRunHistory.open(path, durability).use { h ->
            assertEquals(text, (h.recent(1).messages.last().parts.single() as ContentPart.ToolResult).text)
        }
    }

    @Test fun committedStepWithLoneSurrogateResultStillClearsPendingAfterLedgerReload() {
        val path = Files.createTempDirectory("history-stale-surrogate")
        val ledgerPath = Files.createTempDirectory("history-stale-surrogate-ledger")
        val c = call()
        val id = c.binding.callId
        val a = assistant(c)
        val text = "cut at a code unit \uD83D"
        val d = testDefinition()
        TestInvocationStore.create(ledgerPath).use { store ->
            val gate = DefinitionGate().apply { install(d); freezeRun("run", listOf(d)) }
            val l = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("before")
            TestRunHistory.create(path, "session", durability).close()
            TestRunHistory.open(path, durability) {
                if (it.file == "append" && it.stage == HistoryWriteStage.AFTER_COMMIT) throw IOException("ack lost")
            }.use { h ->
                h.prepareStep(0, identity, "step", a)
                l.recordInvocation(InvocationInput(BindingValue.copyOf(c.binding), "chain", "session", "run", "step",
                    0, "{}", d, false, Risk.READ, false, "tool"))
                l.validateAndPrepare(id)
                val ticket = assertIs<Mutation.Applied>(l.claimExecution(id, l.get(id)!!.revision)).ticket!!
                assertIs<Mutation.Applied>(l.complete(id, ticket.attemptId, Status.SUCCEEDED, text))
                assertFailsWith<HistoryStorageException> { h.append(0, listOf(a, l.resultsForStep("session", "run", "step", listOf(id))!!)) }
            }
        }
        TestInvocationStore.open(ledgerPath).use { store ->
            val l = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("after")
            TestRunHistory.open(path, durability).use { h ->
                assertTrue(h.recoverPending(l).recovered)
                assertFalse(h.hasValue("pending"))
                assertEquals(1, h.revision())
                val result = h.snapshot().messages.last().parts.single() as ContentPart.ToolResult
                assertEquals(text, Json.parseToJsonElement(result.text).jsonObject.getValue("message").jsonPrimitive.content)
            }
        }
    }

    @Test fun differentCommittedResultStillFailsClosedOnLedgerComparison() {
        val path = Files.createTempDirectory("history-stale-mismatch")
        val c = call()
        val a = assistant(c)
        val l = ledger()
        TestRunHistory.create(path, "session", durability).close()
        TestRunHistory.open(path, durability) {
            if (it.file == "append" && it.stage == HistoryWriteStage.AFTER_COMMIT) throw IOException("ack lost")
        }.use { h ->
            h.prepareStep(0, identity, "step", a)
            l.recordInvocation(InvocationInput(BindingValue.copyOf(c.binding), "chain", "session", "run", "step",
                0, "{}", null, false, Risk.READ, true, "tool"))
            assertFailsWith<HistoryStorageException> {
                h.append(0, listOf(a, message(Role.TOOL, toolResultPart(c.binding.callId, "not what the ledger says"))))
            }
        }
        TestRunHistory.open(path, durability).use { h ->
            assertFails { h.recoverPending(l) }
            assertTrue(h.hasValue("pending"))
        }
    }

    @Test fun streamingEncodingMatchesLegacySerializerIncludingEveryEscape() {
        val chars = (0..255).map { it.toChar() }.joinToString("") + "原样😀"
        val a = assistant(call(args = chars), textPart(chars, replayData(chars, chars, chars)), reasoningPart(null, null))
        val b = HistoryBatch(sessionId = chars, revision = 1, previousHash = "ROOT", previousCount = 0,
            messages = listOf(HistoryCodec.encodeMessage(a), HistoryCodec.encodeMessage(message(Role.TOOL, toolResultPart("id", chars)))))
        val out = ByteArrayOutputStream()
        HistoryCodec.writeValue(b, out)
        assertEquals(HistoryCodec.json.encodeToString(HistoryBatch.serializer(), b), out.toString("UTF-8"))
        val p = StoredPending(revision = 0, taskChainId = "chain", sessionId = "session", runId = "run",
            unattended = true, stepId = "step", assistant = HistoryCodec.encodeMessage(a))
        out.reset(); HistoryCodec.writeValue(p, out)
        assertEquals(HistoryCodec.json.encodeToString(StoredPending.serializer(), p), out.toString("UTF-8"))
    }

    @Test fun framesRejectOversizeTruncationReorderingChecksumAndTrailingBytes() {
        val out = ByteArrayOutputStream()
        val source = ByteArray(HISTORY_FRAME_BYTES * 2 + 3) { (it % 251).toByte() }
        HistoryFrameOutput(out).apply { write(source); finish() }
        val good = out.toByteArray()
        assertContentEquals(source, HistoryFrameInput(ByteArrayInputStream(good)).readBytes())
        val oversize = good.copyOf().also { ByteBuffer.wrap(it, 4, 4).putInt(Int.MAX_VALUE) }
        val reordered = good.copyOf().also {
            val n = HISTORY_FRAME_BYTES + 4
            good.copyInto(it, 4, 4 + n, 4 + 2 * n)
            good.copyInto(it, 4 + n, 4, 4 + n)
        }
        for (bad in listOf(good.copyOf(7), good.copyOf(good.size - 1), good + 1.toByte(), oversize, reordered,
            good.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })) {
            assertFails { HistoryFrameInput(ByteArrayInputStream(bad)).readBytes() }
        }
    }


}
