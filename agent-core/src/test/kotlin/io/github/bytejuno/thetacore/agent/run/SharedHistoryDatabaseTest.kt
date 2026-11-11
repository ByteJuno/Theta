package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SharedHistoryDatabaseTest {
    @Test fun realRevisionIsRejectedBeforeHistoryAdmission() = corruptRevision("0.5")
    @Test fun oversizedTextRevisionIsRejectedBeforeHistoryAdmission() = corruptRevision("'0.' || printf('%.*c', 3000000, 'x')")
    @Test fun oversizedBlobRevisionIsRejectedBeforeHistoryAdmission() = corruptRevision("zeroblob(3000000)")
    @Test fun integerBoundaryRevisionIsRejectedWhenHeadDisagrees() = corruptRevision("9223372036854775807")
    private fun corruptRevision(expression: String) {
        val file = Files.createTempDirectory("revision-type").resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "session").close()
            db.connection.execute("UPDATE sessions SET revision=$expression WHERE session_id='session'")
            assertFails { SqliteRunHistory.open(db, "session").use { it.revision() } }
            // Read rejection must not poison the shared owner or retain a session binding.
            db.ready()
            db.connection.execute("UPDATE sessions SET revision=0 WHERE session_id='session'")
            SqliteRunHistory.open(db, "session").use { assertEquals(0, it.revision()) }
            assertFails { db.connection.execute("UPDATE sessions SET revision=NULL") }
        }
    }

    private class InjectedFatal : Error("deterministic transaction failure")
    @Test fun fatalBeforeCommitRollsBackAndPoisonsSharedOwner() = fatalLedger(SqliteWriteStage.AFTER_RECORD)
    @Test fun fatalAfterCommitPoisonsAndPreservesConfirmedProjection() = fatalLedger(SqliteWriteStage.AFTER_COMMIT)
    @Test fun fatalDuringCacheUpdatePoisonsAndPreservesConfirmedProjection() = fatalLedger(null)
    private fun fatalLedger(stage: SqliteWriteStage?) {
        val file = Files.createTempDirectory("fatal-ledger").resolve(AgentDatabase.FILE_NAME)
        val fatal = InjectedFatal()
        var armed = false
        var confirmed: List<InvocationRecord> = emptyList()
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "session").use { history ->
                SqliteInvocationStore.open(db) { if (armed && it == stage) throw fatal }.use { store ->
                    store.afterCacheUpdate = { if (armed && stage == null) throw fatal }
                    val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                    ledger.recoverColdStart("epoch")
                    fun input(step: String) = InvocationInput(BindingValue.copyOf(callBinding("p", step, null, 0, "w")),
                        "chain", "session", "run", step, 0, "{}", null, false, Risk.READ, true, "tool")
                    assertIs<Mutation.Applied>(ledger.recordInvocation(input("confirmed")))
                    confirmed = store.records()
                    armed = true
                    assertSame(fatal, assertFailsWith<InjectedFatal> { ledger.recordInvocation(input("fault")) })
                    assertEquals(confirmed, store.records())
                    assertFalse(db.connection.inTransaction())
                    assertFailsWith<StoreException> { store.checkAdmission() }
                    assertIs<Mutation.StorageFailure>(ledger.executionAdmissionFailure())
                    assertFailsWith<HistoryStorageException> { history.append(0, listOf(message(Role.USER, textPart("blocked")))) }
                }
            }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteInvocationStore.open(db).use { store ->
                assertEquals(if (stage == SqliteWriteStage.AFTER_RECORD) 1 else 2, store.records().size)
                assertTrue(store.records().containsAll(confirmed))
            }
            SqliteRunHistory.open(db, "session").use { assertEquals(0, it.revision()) }
        }
    }

    @Test fun concurrentLedgerAndTwoSessionsUseOneOwnerAndIncrementalTransactions() {
        val file = Files.createTempDirectory("shared-agent-db").resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteInvocationStore.open(db).use { store ->
                SqliteRunHistory.create(db, "one").use { one ->
                    SqliteRunHistory.create(db, "two").use { two ->
                        assertFails { SqliteRunHistory.open(db, "one") }
                        assertFails { SqliteInvocationStore.open(db) }
                        assertFails { AgentDatabase.open(file, BundledSQLiteDriver()) }
                        val pool = Executors.newFixedThreadPool(3)
                        try {
                            val jobs = listOf(one, two).map { h -> pool.submit {
                                repeat(250) { i -> h.append(i.toLong(), listOf(message(Role.USER, textPart("${h.sessionId}-$i")))) }
                            } } + pool.submit {
                                val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                                ledger.recoverColdStart("shared-test")
                                repeat(250) { i ->
                                    val input = InvocationInput(BindingValue.copyOf(callBinding("p", "r$i", null, 0, "w")),
                                        "chain", "one", "run", "step$i", 0, "{}", null, false, Risk.READ, true, "tool")
                                    assertIs<Mutation.Applied>(ledger.recordInvocation(input))
                                }
                            }
                            jobs.forEach { it.get(90, TimeUnit.SECONDS) }
                        } finally { pool.shutdownNow() }
                        assertEquals(250, store.records().size)
                        assertEquals(250, one.revision()); assertEquals(250, two.revision())
                        assertEquals(5, one.recent(5).messages.size)
                        assertEquals(500, db.connection.longValue("SELECT sum(revision) FROM sessions"))
                        assertEquals(250, db.connection.longValue("SELECT count(*) FROM invocations"))
                    }
                    // Closing a borrowed session or ledger never closes the database or sibling.
                    one.append(250, listOf(message(Role.USER, textPart("still alive"))))
                }
            }
            SqliteRunHistory.open(db, "one").use { assertEquals(251, it.revision()) }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteInvocationStore.open(db).use { assertEquals(250, it.records().size) }
            SqliteRunHistory.open(db, "two").use { assertEquals(250, it.revision()) }
        }
        println("ISSUE78_SHARED_DB ledger=250 history=501 sessions=2 concurrent_threads=3")
    }

    @Test fun fullSnapshotAndIndexedPageReleaseDatabaseWhileDecodingForMixedReadersAndWriters() {
        val file = Files.createTempDirectory("mixed-history-io").resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteInvocationStore.open(db).use { store ->
                SqliteRunHistory.create(db, "large").use { large ->
                    SqliteRunHistory.create(db, "small").use { small ->
                        repeat(120) { large.append(it.toLong(), listOf(message(Role.USER, textPart("x".repeat(16_384))))) }
                        val gate = DefinitionGate()
                        val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                        ledger.recoverColdStart("mixed")
                        val pool = Executors.newFixedThreadPool(3)
                        try {
                            for (snapshot in listOf(false, true)) {
                                val decoding = java.util.concurrent.CountDownLatch(1)
                                val resume = java.util.concurrent.CountDownLatch(1)
                                val once = java.util.concurrent.atomic.AtomicBoolean()
                                large.afterReadChunk = {
                                    if (once.compareAndSet(false, true)) {
                                        decoding.countDown(); check(resume.await(15, TimeUnit.SECONDS))
                                    }
                                }
                                val reader = pool.submit<Int> {
                                    if (snapshot) large.snapshot().messages.size else large.recent(5).messages.size
                                }
                                try {
                                    assertTrue(decoding.await(5, TimeUnit.SECONDS))
                                    val writer = pool.submit {
                                        repeat(20) {
                                            small.append(small.revision(), listOf(message(Role.USER, textPart("$snapshot-$it"))))
                                            val input = InvocationInput(BindingValue.copyOf(callBinding("p", "$snapshot-$it", null, 0, "w")),
                                                "chain", "small", "run", "$snapshot-$it", 0, "{}", null, false, Risk.READ, true, "tool")
                                            assertIs<Mutation.Applied>(ledger.recordInvocation(input))
                                        }
                                    }
                                    val siblingReader = pool.submit {
                                        repeat(20) { validateHistory(small.recent(5).messages); store.records(); ledger.pendingApprovals() }
                                    }
                                    writer.get(5, TimeUnit.SECONDS); siblingReader.get(5, TimeUnit.SECONDS)
                                    assertFalse(reader.isDone, "unrelated I/O completed while decoder is paused")
                                } finally { resume.countDown() }
                                assertEquals(if (snapshot) 120 else 5, reader.get(20, TimeUnit.SECONDS))
                                println("ISSUE78_MIXED_IO snapshot=$snapshot other_session_appends=20 ledger_writes=20 sibling_reads=20 decoder_paused=true")
                            }
                            assertEquals(40, small.revision()); assertEquals(40, store.records().size)
                        } finally { large.afterReadChunk = {}; pool.shutdownNow() }
                    }
                }
            }
        }
    }

    @Test fun sqliteV1UpgradePreservesLedgerAndAddsHistoryWithoutFileImport() {
        val root = Files.createTempDirectory("history-schema-upgrade")
        val file = root.resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).close()
        BundledSQLiteDriver().open(file.toString()).use { sql ->
            sql.execute("DROP TABLE history_chunks")
            sql.execute("DROP TABLE history_values")
            sql.execute("DROP TABLE sessions")
            sql.execute("PRAGMA user_version=1")
        }
        Files.write(root.resolve("head.json"), "obsolete file is not a session".toByteArray())
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            assertEquals(2, db.connection.longValue("PRAGMA user_version"))
            assertFails { SqliteRunHistory.open(db, "session") }
            SqliteInvocationStore.open(db).use { assertTrue(it.records().isEmpty()) }
            SqliteRunHistory.create(db, "session").use { assertEquals(0, it.revision()) }
        }
        assertEquals("obsolete file is not a session", String(Files.readAllBytes(root.resolve("head.json"))))
    }

    @Test fun cachedAuthorizationAndClaimBoundariesCloseButTerminalProjectionSurvives() {
        for (boundary in listOf("prepare", "claim", "repeat-approval", "recovery", "continuation")) {
            val file = Files.createTempDirectory("cached-admission").resolve(AgentDatabase.FILE_NAME)
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteInvocationStore.open(db).use { store ->
                    val d = testDefinition(risk = Risk.WRITE)
                    val gate = DefinitionGate().apply { install(d); freezeRun("run", listOf(d)) }
                    val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                    ledger.recoverColdStart("epoch")
                    val input = InvocationInput(BindingValue.copyOf(callBinding("p", "r", null, 0, "w")),
                        "chain", "session", "run", "step", 0, "{}", d, false, Risk.WRITE, false, "tool")
                    val id = input.binding.callId
                    ledger.recordInvocation(input); ledger.validateAndPrepare(id)
                    val ask = ledger.get(id)!!.pending!!
                    ledger.resolveApproval(id, ask, Decision.ALLOW_ONCE)
                    if (boundary == "recovery") ledger.cancel(id)
                    val confirmed = ledger.get(id)!!
                    val projection = ledger.resultsForStep("session", "run", "step", listOf(id))
                    SqliteRunHistory.create(db, "fault").close()
                    SqliteRunHistory.open(db, "fault") {
                        if (it.stage == HistoryWriteStage.AFTER_COMMIT) error("ack lost")
                    }.use { assertFails { it.append(0, listOf(message(Role.USER, textPart("committed")))) } }
                    if (boundary == "continuation") assertTrue(ledger.continuationBlocked("unused-chain"))
                    else assertIs<Mutation.StorageFailure>(when (boundary) {
                        "prepare" -> ledger.validateAndPrepare(id)
                        "claim" -> ledger.claimExecution(id, confirmed.revision)
                        "repeat-approval" -> ledger.resolveApproval(id, ask, Decision.ALLOW_ONCE)
                        else -> ledger.resolveRecovery(id, RecoveryResolution.VERIFIED_CLEAR)
                    })
                    assertEquals(confirmed, ledger.get(id)); assertEquals(listOf(confirmed), store.records())
                    assertEquals(projection, ledger.resultsForStep("session", "run", "step", listOf(id)))
                }
            }
        }
    }

    @Test fun uncertainLedgerCommitStopsHistoryButKeepsLastConfirmedRecordProjection() {
        val file = Files.createTempDirectory("ledger-poisons-history").resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "session").use { h ->
                SqliteInvocationStore.open(db) { if (it == SqliteWriteStage.AFTER_COMMIT) error("ack lost") }.use { store ->
                    val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                    ledger.recoverColdStart("epoch")
                    val input = InvocationInput(BindingValue.copyOf(callBinding("p", "r", null, 0, "w")),
                        "chain", "session", "run", "step", 0, "{}", null, false, Risk.READ, true, "tool")
                    assertTrue(assertIs<Mutation.StorageFailure>(ledger.recordInvocation(input)).uncertain)
                    assertTrue(store.records().isEmpty())
                    assertFailsWith<HistoryStorageException> { h.append(0, listOf(message(Role.USER, textPart("blocked")))) }
                }
            }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteInvocationStore.open(db).use { assertEquals(1, it.records().size) }
            SqliteRunHistory.open(db, "session").use { assertEquals(0, it.revision()) }
        }
    }

    @Test fun uncertainHistoryCommitStopsLedgerAndSiblingUntilDatabaseReopen() {
        val file = Files.createTempDirectory("shared-poison").resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteInvocationStore.open(db).use { store ->
                SqliteRunHistory.create(db, "sibling").use { sibling ->
                    SqliteRunHistory.create(db, "fault").close()
                    SqliteRunHistory.open(db, "fault") {
                        if (it.file == "append" && it.stage == HistoryWriteStage.AFTER_COMMIT) error("lost acknowledgement")
                    }.use { h ->
                        assertFails { h.append(0, listOf(message(Role.USER, textPart("committed")))) }
                        assertFails { sibling.append(0, listOf(message(Role.USER, textPart("blocked")))) }
                        assertTrue(store.records().isEmpty()) // last-confirmed projection only
                        val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                        assertTrue(assertIs<Mutation.StorageFailure>(ledger.recoverColdStart("poisoned")).uncertain)
                        val input = InvocationInput(BindingValue.copyOf(callBinding("p", "r", null, 0, "w")),
                            "chain", "fault", "run", "step", 0, "{}", null, false, Risk.READ, true, "tool")
                        assertIs<Mutation.Blocked>(ledger.recordInvocation(input))
                    }
                }
            }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.open(db, "fault").use { assertEquals(1, it.revision()) }
            SqliteRunHistory.open(db, "sibling").use { assertEquals(0, it.revision()) }
        }
    }
}
