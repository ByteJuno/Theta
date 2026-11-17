package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class RecoveryCancellationTextTest {
    private val identity = RunIdentity("chain", "session", "run")
    private val recoveredText = "Process interrupted before tool submission: not executed"
    private val truncatedText = "Truncated model turn: not executed"
    private fun ledger(store: InvocationStore) = InvocationLedger(store, DefinitionGate(),
        ArgumentsValidator { _, _ -> true }).also { it.recoverColdStart("reopened") }

    @Test fun recoveryReasonSurvivesLedgerAndHistoryCommitInterruptions() {
        for (cut in listOf("ledger-before", "ledger-after", "history-before", "history-after")) {
            val file = Files.createTempDirectory("recovery-reason").resolve(AgentDatabase.FILE_NAME)
            val call = toolCallPart(callBinding("p", "request", "response", 0, "wire"), "tool", "{}")
            val assistant = message(Role.ASSISTANT, call)
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "session").use { it.prepareStep(0, identity, "step", assistant) }
            }
            var hit = false
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db) { stage ->
                    if ((cut == "ledger-before" && stage == SqliteWriteStage.BEFORE_COMMIT) ||
                        (cut == "ledger-after" && stage == SqliteWriteStage.AFTER_COMMIT)) {
                        hit = true; error(cut)
                    }
                }.use { store ->
                    val ledger = ledger(store)
                    SqliteRunHistory.open(db, "session") { point ->
                        if (point.file == "append" &&
                            ((cut == "history-before" && point.stage == HistoryWriteStage.BEFORE_COMMIT) ||
                                (cut == "history-after" && point.stage == HistoryWriteStage.AFTER_COMMIT))) {
                            hit = true; error(cut)
                        }
                    }.use { h -> assertFails { h.recoverPending(ledger) } }
                }
            }
            assertTrue(hit, cut)
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = ledger(store)
                    SqliteRunHistory.open(db, "session").use { h ->
                        assertEquals(if (cut == "history-after") 1L else 0L, h.revision())
                        val recovery = h.recoverPending(ledger)
                        assertTrue(recovery.recovered); assertFalse(recovery.requiresDecision)
                        val record = requireNotNull(ledger.get(call.binding.callId))
                        assertEquals(LogicalResult(Status.CANCELLED, Effect.NONE, recoveredText, false), record.result)
                        assertEquals(Status.CANCELLED, record.state)
                        assertNull(record.instance); assertNull(record.pending); assertNull(record.isolation)
                        assertTrue(record.receipts.isEmpty()); assertTrue(record.evidence.isEmpty())
                        val result = h.snapshot().messages.last().parts.single() as ContentPart.ToolResult
                        assertEquals(ModelResultText.project(record.result!!), result.text)
                        assertFalse(result.text.contains("Truncated")); assertTrue(result.isError)
                        assertEquals(call.binding.callId, result.callId)
                        assertEquals(1L, h.revision()); assertEquals(2, h.snapshot().messages.size)
                        assertFalse(h.recoverPending(ledger).recovered)
                        assertEquals(listOf(record), store.records())
                    }
                }
            }
            // Both stores reopen again without rewriting the result or duplicating the pair.
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = ledger(store)
                    SqliteRunHistory.open(db, "session").use { h ->
                        assertFalse(h.recoverPending(ledger).recovered)
                        assertEquals(recoveredText, store.records().single().result!!.text)
                        assertEquals(1L, h.revision()); assertEquals(2, h.snapshot().messages.size)
                    }
                }
            }
        }
    }

    @Test fun existingTruncationTextIsNotRewrittenDuringRecoveryOrCommittedCleanup() {
        for (committed in listOf(false, true)) {
            val file = Files.createTempDirectory("existing-cancellation").resolve(AgentDatabase.FILE_NAME)
            val call = toolCallPart(callBinding("p", "request", "response", 0, "wire"), "tool", "{}")
            val assistant = message(Role.ASSISTANT, call)
            lateinit var original: InvocationRecord
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = ledger(store)
                    SqliteRunHistory.create(db, "session") { point ->
                        if (committed && point.file == "append" && point.stage == HistoryWriteStage.AFTER_COMMIT)
                            error("lost history acknowledgement")
                    }.use { h ->
                        h.prepareStep(0, identity, "step", assistant)
                        original = assertIs<Mutation.Applied>(ledger.recordInvocation(InvocationInput(
                            BindingValue.copyOf(call.binding), "chain", "session", "run", "step", 0,
                            "{}", null, false, Risk.READ, true, "tool"))).record
                        assertEquals(truncatedText, original.result!!.text)
                        if (committed) assertFailsWith<HistoryStorageException> {
                            h.append(0, listOf(assistant, requireNotNull(ledger.resultsForStep(
                                "session", "run", "step", listOf(call.binding.callId)))))
                        }
                    }
                }
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val ledger = ledger(store)
                    SqliteRunHistory.open(db, "session").use { h ->
                        assertTrue(h.recoverPending(ledger).recovered)
                        assertEquals(original, ledger.get(call.binding.callId))
                        val result = h.snapshot().messages.last().parts.single() as ContentPart.ToolResult
                        assertEquals(ModelResultText.project(original.result!!), result.text)
                        assertEquals(1L, h.revision())
                        assertFalse(h.recoverPending(ledger).recovered)
                    }
                }
            }
        }
    }
}
