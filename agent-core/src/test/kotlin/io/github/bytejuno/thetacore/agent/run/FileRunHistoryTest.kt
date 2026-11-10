package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class FileRunHistoryTest {
    private val durability = Durability.PROCESS_CRASH_ONLY
    private fun user(text: String) = message(Role.USER, textPart(text))
    private fun pair(id: String = UUID.randomUUID().toString()): List<Message> {
        val replay = replayData("provider", "model", "opaque\u0000 signature\n原样")
        return listOf(message(Role.ASSISTANT, reasoningPart("thought", replay), textPart("before", replay),
            toolCallPart(callBinding("p", "request", "response", 2, "wire", id), "tool", "{\"x\": 1}", replay)),
            message(Role.TOOL, toolResultPart(id, "unknown", true)))
    }
    private fun canonical(messages: List<Message>) = messages.map(HistoryCodec::encodeMessage)

    @Test fun pagingIsOrderedStableAtomicAndLossless() {
        val path = Files.createTempDirectory("history-pages")
        FileRunHistory.create(path, "session", durability).use { h ->
            h.append(0, listOf(user("first")))
            val pair = pair()
            h.append(1, pair)
            h.append(2, listOf(user("last")))
            val latest = h.recent(1)
            assertEquals("last", (latest.messages.single().parts.single() as ContentPart.Text).text)
            h.append(3, listOf(user("newer")))
            val previous = h.page(latest.older, 1)
            assertEquals(canonical(pair), canonical(previous.messages))
            assertEquals(2, previous.messages.size) // soft limit does not split the pair
            assertEquals(canonical(listOf(user("first"))), canonical(h.page(previous.older, 1).messages))
            assertEquals(5, h.snapshot().messages.size)
            assertFails { h.append(3, listOf(user("stale"))) }
            assertFails { h.append(4, pair) }
            assertFails { h.append(4, listOf(pair.first())) }
            assertFails { h.append(4, listOf(pair.last())) }
            assertFails { FileRunHistory.open(path, durability) }
        }
        FileRunHistory.open(path, durability).use { assertEquals(5, it.snapshot().messages.size) }
        val other = Files.createTempDirectory("history-other")
        FileRunHistory.create(other, "other", durability).use { h ->
            assertFails { h.page(HistoryCursor("session", 1, "x", 1)) }
        }
    }

    @Test fun everyWritePhaseLeavesWholeOldOrNewTransaction() {
        for (file in listOf("batches/2.json", "calls/", "head.json")) for (stage in HistoryWriteStage.entries) {
            val path = Files.createTempDirectory("history-fault")
            FileRunHistory.create(path, "session", durability).use { it.append(0, listOf(user("base"))) }
            var hit = false
            val pair = pair()
            FileRunHistory.open(path, durability) { p ->
                if ((p.file == file || file == "calls/" && p.file.startsWith(file)) && p.stage == stage) {
                    hit = true; throw java.io.IOException("fault $p")
                }
            }.use { h ->
                assertFailsWith<HistoryStorageException> { h.append(1, pair) }
                assertTrue(hit)
                assertFails { h.append(1, listOf(user("must not continue"))) }
            }
            FileRunHistory.open(path, durability).use { h ->
                val s = h.snapshot()
                val committed = file == "head.json" && stage in setOf(HistoryWriteStage.AFTER_REPLACE, HistoryWriteStage.AFTER_DIRECTORY_SYNC)
                assertEquals(if (committed) 2L else 1L, s.revision, "$file $stage")
                assertEquals(if (committed) canonical(listOf(user("base")) + pair) else canonical(listOf(user("base"))), canonical(s.messages))
                validateHistory(s.messages)
                if (!committed) h.append(1, pair) // speculative files are not promoted; safe explicit retry
            }
        }
    }

    @Test fun abandonedCallIndexDoesNotReserveAnIdentityAfterDifferentCommit() {
        val path = Files.createTempDirectory("history-abandoned-index")
        val pair = pair()
        FileRunHistory.create(path, "session", durability).close()
        FileRunHistory.open(path, durability) {
            if (it.file == "head.json" && it.stage == HistoryWriteStage.BEFORE_WRITE) throw java.io.IOException("abort")
        }.use { h -> assertFails { h.append(0, pair) } }
        FileRunHistory.open(path, durability).use { h ->
            h.append(0, listOf(user("different transaction")))
            h.append(1, pair)
            assertEquals(3, h.snapshot().messages.size)
        }
    }

    @Test fun missingOrCorruptedCallIndexAndBrokenHashLinksFailClosed() {
        val path = Files.createTempDirectory("history-index-corrupt")
        FileRunHistory.create(path, "session", durability).use { it.append(0, pair()) }
        Files.newDirectoryStream(path.resolve("calls"), "*.json").use { Files.delete(it.single()) }
        assertFails { FileRunHistory.open(path, durability) }
        val chain = Files.createTempDirectory("history-chain-corrupt")
        FileRunHistory.create(chain, "session", durability).use { h ->
            h.append(0, listOf(user("first"))); h.append(1, listOf(user("last")))
        }
        // A valid checked file at the wrong position still cannot pass the chained identity check.
        Files.copy(chain.resolve("batches/2.json"), chain.resolve("batches/1.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        FileRunHistory.open(chain, durability).use { h -> assertFails { h.snapshot() } }
    }

    @Test fun corruptionFailsClosedWhenReachedAndAppendDoesNotScanHistory() {
        val path = Files.createTempDirectory("history-corrupt")
        FileRunHistory.create(path, "session", durability).use { h ->
            repeat(100) { h.append(it.toLong(), listOf(user("m$it"))) }
            val before = h.io()
            h.append(100, listOf(user("last")))
            assertEquals(before.messagesDecoded, h.io().messagesDecoded)
            assertEquals(before.bytesRead, h.io().bytesRead)
        }
        Files.write(path.resolve("batches/1.json"), "broken".toByteArray())
        FileRunHistory.open(path, durability).use { h ->
            assertEquals(1, h.io().messagesDecoded)
            assertEquals(10, h.recent(10).messages.size)
            assertFails { h.snapshot() }
        }
        Files.write(path.resolve("head.json"), "broken".toByteArray())
        assertFails { FileRunHistory.open(path, durability) }
        Files.delete(path.resolve("head.json"))
        assertFails { FileRunHistory.open(path, durability) }
        assertFails { FileRunHistory.create(path, "session", durability) }
    }

    @Test fun diskHistoryIntegratesWithRunLoopWithoutReloadAfterAppend() = runTest {
        val path = Files.createTempDirectory("history-loop")
        val f = RunFixture(listOf(testDefinition()))
        FileRunHistory.create(path, "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val provider = ScriptProvider(listOf(calls("tool"), answer()))
            assertEquals(RunState.COMPLETED, f.loop(provider, history = h).run().state)
            assertEquals(1, f.submitted.size)
            assertEquals(4, h.snapshot().messages.size)
            assertFalse(Files.exists(path.resolve("pending.json")))
            validateHistory(provider.requests.last().messages)
        }
    }

    @Test fun durableLedgerRecoveryPreservesLostUnknownAndUnregisteredCalls() {
        val path = Files.createTempDirectory("history-recovery")
        val ledgerPath = Files.createTempDirectory("history-ledger")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val assistant = message(Role.ASSISTANT,
            toolCallPart(callBinding("p", "request", "response", 0, "w", first), "tool", "{}"),
            toolCallPart(callBinding("p", "request", "response", 1, "w2", second), "tool", "{}"))
        val definition = testDefinition(risk = Risk.WRITE)
        TestInvocationStore.create(ledgerPath).use { store ->
            val gate = DefinitionGate().apply { install(definition); freezeRun("run", listOf(definition)) }
            val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            ledger.recoverColdStart("old-process")
            FileRunHistory.create(path, "session", durability).use { h ->
                h.prepareStep(0, RunIdentity("chain", "session", "run"), "step", assistant)
                val call = assistant.parts.first() as ContentPart.ToolCall
                ledger.recordInvocation(InvocationInput(BindingValue.copyOf(call.binding), "chain", "session", "run", "step", 0,
                    "{}", definition, false, Risk.WRITE, false, "tool"))
                ledger.validateAndPrepare(first)
                ledger.resolveApproval(first, ledger.get(first)!!.pending!!, Decision.ALLOW_ONCE)
                assertIs<Mutation.Applied>(ledger.claimExecution(first, ledger.get(first)!!.revision))
                assertFails { h.snapshot() }
            }
        }
        TestInvocationStore.open(ledgerPath).use { store ->
            val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
            FileRunHistory.open(path, durability).use { h ->
                assertFails { h.recoverPending(ledger) } // never guess a RUNNING result
                ledger.recoverColdStart("new-process")
                val result = h.recoverPending(ledger)
                assertTrue(result.recovered); assertTrue(result.requiresDecision)
                val results = h.snapshot().messages.last().parts.map { it as ContentPart.ToolResult }
                assertEquals(listOf(first, second), results.map { it.callId })
                assertTrue(results[0].text.contains("\"status\":\"lost\""))
                assertTrue(results[0].text.contains("\"effect\":\"unknown\""))
                assertTrue(results[1].text.contains("\"status\":\"cancelled\""))
                assertTrue(results[1].text.contains("\"effect\":\"none\""))
                assertFalse(h.recoverPending(ledger).recovered)
                assertEquals(1, h.revision())
            }
        }
    }

    @Test fun pendingIntentFaultsNeverBecomeReplayableHalfMessages() {
        for (stage in HistoryWriteStage.entries) {
            val path = Files.createTempDirectory("history-intent")
            FileRunHistory.create(path, "session", durability).close()
            FileRunHistory.open(path, durability) { if (it.file == "pending.json" && it.stage == stage) throw java.io.IOException("intent") }.use { h ->
                assertFails { h.prepareStep(0, RunIdentity("chain", "session", "run"), "step", pair().first()) }
            }
            FileRunHistory.open(path, durability).use { h ->
                assertTrue(h.recent().messages.isEmpty())
                val ledger = InvocationLedger(TestStore(), DefinitionGate(), ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("new")
                val committed = stage in setOf(HistoryWriteStage.AFTER_REPLACE, HistoryWriteStage.AFTER_DIRECTORY_SYNC)
                assertEquals(committed, h.recoverPending(ledger).recovered)
                assertEquals(if (committed) 2 else 0, h.snapshot().messages.size)
            }
        }
    }

    @Test fun committedPendingCleanupIsIdempotentEvenAfterLostAcknowledgement() {
        val path = Files.createTempDirectory("history-cleanup")
        FileRunHistory.create(path, "session", durability).close()
        val ledger = InvocationLedger(TestStore(), DefinitionGate(), ArgumentsValidator { _, _ -> true })
        ledger.recoverColdStart("epoch")
        FileRunHistory.open(path, durability) { if (it.file == "head.json" && it.stage == HistoryWriteStage.AFTER_REPLACE) throw java.io.IOException("ack lost") }.use { h ->
            h.prepareStep(0, RunIdentity("chain", "session", "run"), "step", pair().first())
            assertFails { h.recoverPending(ledger) }
        }
        FileRunHistory.open(path, durability).use { h ->
            assertTrue(h.recoverPending(ledger).recovered)
            assertEquals(1, h.revision())
            assertEquals(2, h.snapshot().messages.size)
        }
    }
}
