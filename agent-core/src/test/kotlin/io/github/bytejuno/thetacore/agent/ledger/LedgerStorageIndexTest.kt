package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class LedgerStorageIndexTest {
    private class CountingStore(seed: List<InvocationRecord>) : InvocationStore {
        var reads = 0
        private var bound = false
        private val values = seed.associateByTo(linkedMapOf()) { it.callId }
        override fun bindCoordinator() { check(!bound); bound = true }
        override fun records(): List<InvocationRecord> { reads++; return values.values.toList() }
        override fun save(record: InvocationRecord, expectedRevision: Long?) {
            record.validate()
            check(values[record.callId]?.revision == expectedRevision)
            values[record.callId] = record
        }
        override fun close() = Unit
    }

    @Test fun threeThousandRecordsAreLoadedOnceAcrossTransitionsAndRecovery() = Fixture().use { f ->
        val template = f.record()
        val seed = (1..3000).map { n ->
            template.copy(input = template.input.copy(
                binding = template.input.binding.copy(callId = UUID.randomUUID().toString()),
                runId = "old-run-$n",
            ))
        }
        val store = CountingStore(seed)
        val ledger = InvocationLedger(store, f.gate, ArgumentsValidator { _, _ -> true })
        ledger.recoverColdStart("counted-epoch")
        val input = f.input()
        val callId = input.binding.callId
        assertIs<Mutation.Applied>(ledger.recordInvocation(input))
        val ready = assertIs<Mutation.Applied>(ledger.validateAndPrepare(callId)).record
        val claimed = assertIs<Mutation.Applied>(ledger.claimExecution(callId, ready.revision))
        assertIs<Mutation.Applied>(ledger.complete(callId, claimed.ticket!!.attemptId, Status.SUCCEEDED, "done"))
        assertEquals(Status.SUCCEEDED, ledger.get(callId)!!.state)
        assertNotNull(ledger.resultsForStep(input.sessionId, input.runId, input.stepId, listOf(callId)))
        val cancelled = f.input()
        assertIs<Mutation.Applied>(ledger.recordInvocation(cancelled))
        assertIs<Mutation.Applied>(ledger.cancel(cancelled.binding.callId))
        val write = f.input(definition(Risk.WRITE))
        assertIs<Mutation.Applied>(ledger.recordInvocation(write))
        val pending = assertIs<Mutation.Applied>(ledger.validateAndPrepare(write.binding.callId)).record
        assertIs<Mutation.Applied>(ledger.resolveApproval(pending.callId, pending.pending!!, Decision.DENY))
        assertEquals(1, store.reads)
        assertEquals(seed.first(), ledger.get(seed.first().callId))
    }

    @Test fun resultTextOccursOnceAndDistinctLateEvidenceSurvivesReopen() = Fixture().use { f ->
        val claimed = f.claim(f.ready())
        val callId = claimed.record.callId
        val attempt = claimed.ticket!!.attemptId
        val text = "unique-result-结果-😀-".repeat(100)
        val done = assertIs<Mutation.Applied>(f.ledger.complete(callId, attempt, Status.SUCCEEDED, text)).record
        val file = f.directory.resolve("$callId.json")
        val encoded = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertEquals(1, Regex(Regex.escape(text)).findAll(encoded).count())
        assertEquals(listOf(text), Json.parseToJsonElement(encoded).jsonObject.getValue("texts").jsonArray.map { it.jsonPrimitive.content })
        f.reopen()
        assertEquals(done, f.ledger.get(callId))
        val late = assertIs<Mutation.Applied>(f.ledger.recordOutcome(callId, attempt, Status.FAILED, "different late text", Effect.UNKNOWN)).record
        assertEquals(text, late.result!!.text)
        f.reopen("epoch-3")
        assertEquals(late, f.ledger.get(callId))
        assertEquals(listOf(text, "different late text"), late.evidence.map { it.text })
    }

    @Test fun textsCollapsingInUtf8KeepLedgerOpenable(): Unit = Fixture().use { f ->
        val claimed = f.claim(f.ready())
        val callId = claimed.record.callId
        val attempt = claimed.ticket!!.attemptId
        assertIs<Mutation.Applied>(f.ledger.complete(callId, attempt, Status.SUCCEEDED, "cut\uD83D"))
        assertIs<Mutation.Applied>(f.ledger.recordOutcome(callId, attempt, Status.SUCCEEDED, "cut\uD83E"))
        f.reopen()
        val reopened = f.ledger.get(callId)!!
        assertEquals("cut?", reopened.result!!.text)
        assertEquals(listOf("cut?", "cut?"), reopened.evidence.map { it.text })
    }

    @Test fun legacyInlineFilesAreReadWithoutRewriteAndUpgradedOnNextSave() = Fixture().use { f ->
        val claimed = f.claim(f.ready())
        val r = assertIs<Mutation.Applied>(f.ledger.complete(claimed.record.callId, claimed.ticket!!.attemptId, Status.SUCCEEDED, "legacy result")).record
        f.store.close()
        val file = f.directory.resolve("${r.callId}.json")
        val inline = Json { encodeDefaults = true }.encodeToString(r)
        Files.write(file, inline.toByteArray(Charsets.UTF_8))
        f.reopen()
        assertEquals(r, f.ledger.get(r.callId))
        assertEquals(inline, String(Files.readAllBytes(file), Charsets.UTF_8))
        assertIs<Mutation.Applied>(f.ledger.recordActualExit(r.callId, claimed.ticket!!.attemptId, "extra exit evidence"))
        assertTrue(String(Files.readAllBytes(file), Charsets.UTF_8).contains("\"storageVersion\":1"))
        f.reopen("epoch-3")
        assertEquals("legacy result", f.ledger.get(r.callId)!!.result!!.text)
    }

    @Test fun malformedTextReferencesFailClosedAndLeaveEvidence() {
        val corruptions: List<(String) -> String> = listOf(
            { it.replace("\"storageVersion\":1", "\"storageVersion\":99") },
            { it.replace("\"text\":0", "\"text\":-1") },
            { it.replace("\"text\":0", "\"text\":10") },
            { it.replace("\"text\":0", "\"text\":\"0\"") },
            { it.replace("\"texts\":[\"result\"]", "\"texts\":[]") },
        )
        for (corrupt in corruptions) Fixture().use { f ->
            val claimed = f.claim(f.ready())
            val r = assertIs<Mutation.Applied>(f.ledger.complete(claimed.record.callId, claimed.ticket!!.attemptId, Status.SUCCEEDED, "result")).record
            f.store.close()
            val file = f.directory.resolve("${r.callId}.json")
            val original = String(Files.readAllBytes(file), Charsets.UTF_8)
            val broken = corrupt(original)
            assertNotEquals(original, broken)
            Files.write(file, broken.toByteArray(Charsets.UTF_8))
            assertFailsWith<StoreException> { JsonInvocationStore.open(f.directory, Durability.PROCESS_CRASH_ONLY) }
            assertEquals(broken, String(Files.readAllBytes(file), Charsets.UTF_8))
        }
    }

    @Test fun cachedSnapshotsDoNotPublishMutableCollectionAliases() = Fixture().use { f ->
        val claimed = f.claim(f.ready())
        val done = assertIs<Mutation.Applied>(f.ledger.complete(claimed.record.callId, claimed.ticket!!.attemptId, Status.SUCCEEDED, "done")).record
        val mutableEvidence = done.evidence.toMutableList()
        val next = done.copy(revision = done.revision + 1, evidence = mutableEvidence)
        f.store.save(next, done.revision)
        mutableEvidence.clear()
        val returned = f.store.records().single()
        assertEquals(done.evidence, returned.evidence)
        (returned.evidence as? MutableList<ExitEvidence>)?.let { runCatching { it.clear() } }
        assertEquals(done.evidence, f.store.records().single().evidence)
        f.reopen()
        val published = f.ledger.get(done.callId)!!
        (published.evidence as? MutableList<ExitEvidence>)?.let { runCatching { it.clear() } }
        assertEquals(done.evidence, f.ledger.get(done.callId)!!.evidence)
    }

    @Test fun retainedTerminalHistoryKeepsGrantsBudgetAndRecoveryBarriers(): Unit = Fixture(maxClaims = 1).use { f ->
        val ready = f.approve(f.prepare(f.input(definition(Risk.WRITE))), Decision.ALLOW_ALWAYS)
        val claimed = f.claim(ready)
        assertIs<Mutation.Applied>(f.ledger.complete(ready.callId, claimed.ticket!!.attemptId, Status.SUCCEEDED, "done"))
        val waiting = f.record(f.input(chain = "other-chain"))
        f.reopen()
        assertEquals(Status.SUCCEEDED, f.ledger.get(ready.callId)!!.state)
        assertEquals(waiting, f.ledger.get(waiting.callId))
        val granted = f.prepare(f.input(definition(Risk.WRITE), unattended = true))
        assertEquals(Status.READY, granted.state)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(granted.callId, granted.revision))
        val running = f.claim(f.ready(f.input(chain = "lost-chain")))
        f.reopen("epoch-3")
        val lost = f.ledger.get(running.record.callId)!!
        assertTrue(lost.recoveryPending)
        f.reopen("epoch-4")
        assertEquals(lost, f.ledger.get(lost.callId))
        val next = f.ready(f.input(chain = "lost-chain"))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
    }
}
