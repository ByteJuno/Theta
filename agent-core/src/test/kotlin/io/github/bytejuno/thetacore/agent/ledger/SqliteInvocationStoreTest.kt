package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class SqliteInvocationStoreTest {
    @Test fun exclusiveWriterMissingStoreAndTemporaryFiles() = Fixture().use { f ->
        val r = f.record()
        assertFails { InvocationLedger(f.store, DefinitionGate(), ArgumentsValidator { _, _ -> true }) }
        assertFailsWith<StoreException> { TestInvocationStore.open(f.directory) }
        assertFails { TestInvocationStore.create(f.directory) }
        Files.writeString(f.directory.resolve(".abandoned.tmp"), "{partial")
        f.reopen()
        assertEquals(r, f.ledger.get(r.callId))
        val missing = f.directory.resolve("missing")
        assertFails { TestInvocationStore.open(missing) }
        assertFalse(Files.exists(missing))
    }

    @Test fun malformedAggregatesFailClosed() {
        val corruptions: List<(String) -> String> = listOf(
            { it.replace("\"formatVersion\":2", "\"formatVersion\":99") },
            { it.replace("\"formatVersion\":2", "\"formatVersion\":1") },
            { it.replace(",\"recoveryPending\":false", "") },
            { it.replace("\"state\":\"RECEIVED\"", "\"state\":\"FUTURE\"") },
            { it.dropLast(5) },
            { it.replace("\"revision\":1,", "") },
            { it.replace("\"state\":\"RECEIVED\"", "\"state\":\"SUCCEEDED\"") },
            { it.replace("\"definitionDigest\":\"", "\"definitionDigest\":\"bad") },
        )
        for (corrupt in corruptions) Fixture().use { f ->
            val r = f.record()
            f.store.close()
            val original = String(payload(f.directory, r.callId), Charsets.UTF_8)
            assertNotEquals(original, corrupt(original))
            replacePayload(f.directory, r.callId, corrupt(original).toByteArray())
            val e = assertFailsWith<StoreException> { TestInvocationStore.open(f.directory) }
            assertTrue(e.message!!.contains(r.callId))
            assertTrue(Files.exists(f.directory.resolve(AgentDatabase.FILE_NAME)))
        }
    }

    @Test fun malformedUtf8FailsClosedInsteadOfBeingReplaced() = Fixture().use { f ->
        val r = f.record()
        f.store.close()
        val valid = payload(f.directory, r.callId)
        replacePayload(f.directory, r.callId, valid + byteArrayOf(0xC3.toByte(), 0x28))
        val e = assertFailsWith<StoreException> { TestInvocationStore.open(f.directory) }
        assertTrue(e.message!!.contains(r.callId))
        replacePayload(f.directory, r.callId, valid)
        f.store = TestInvocationStore.open(f.directory)
        assertEquals(r, f.store.records().single())
    }

    @Test fun unknownNamesReplayAfterUtf8DiskReopen() {
        for (value in surrogateInputs()) Fixture().use { f ->
            val input = f.input(args = "{\"value\":\"$value\"}").let {
                it.copy(definition = null, modelName = "missing-$value",
                    binding = it.binding.copy(provider = value, requestId = value, responseId = value, wireId = value))
            }
            val first = f.record(input)
            assertEquals(Status.REJECTED, first.state)
            assertEquals(input, assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input)).record.input)
            val bytes = payload(f.directory, input.binding.callId)
            val stored = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            assertTrue(stored.contains("\"storageVersion\":1"))
            f.reopen()
            val replay = assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input)).record
            assertEquals(input, replay.input)
            assertEquals(Status.REJECTED, replay.state)
            // Result text keeps its established UTF-8 normalization; input identity does not.
            assertEquals(String(first.result!!.text.toByteArray(Charsets.UTF_8), Charsets.UTF_8), replay.result!!.text)
            assertContentEquals(bytes, payload(f.directory, input.binding.callId))
            assertIs<Mutation.Conflict>(f.ledger.recordInvocation(input.copy(modelName = "different")))
        }
    }

    @Test fun knownArgumentsAndNamesReplayAndExecuteAfterDiskReopen() {
        for (value in surrogateInputs()) Fixture().use { f ->
            val args = "{\"value\":\"$value\"}"
            val input = f.input(d = definition().copy(modelName = "tool-$value"), args = args)
            val ready = f.ready(input)
            assertEquals(Status.READY, ready.state)
            assertEquals(args, ready.executionArgs)
            assertEquals(ready, assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input)).record)
            val bytes = payload(f.directory, input.binding.callId)
            f.reopen()
            assertEquals(ready, assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input)).record)
            assertContentEquals(bytes, payload(f.directory, input.binding.callId))
            val claimed = f.claim(ready)
            assertEquals(args, claimed.ticket!!.argsJson)
            assertEquals(input.modelName, claimed.ticket.definition.modelName)
            val done = assertIs<Mutation.Applied>(f.ledger.complete(ready.callId, claimed.ticket.attemptId,
                Status.SUCCEEDED, "done")).record
            f.reopen("epoch-3")
            assertEquals(done, assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(input)).record)
            assertIs<Mutation.Conflict>(f.ledger.recordInvocation(input.copy(argsJson = "{}")))
        }
    }

    private fun surrogateInputs() = listOf(
        "\uD800", "\uDC00", "\uD800𐀀\uDC00", "\uDC00😀\uD800",
        "emoji-😀-literal-\\ud800-\\udc00-control-\\u0000-\\n-quote-\\\"-slash-\\\\",
    )

    @Test fun everyWritePhaseIsOldOrNewCompleteAndUncertainHalts() {
        // Claim-only writes have no content/manifest rows; those cutpoints are covered
        // by SqliteProcessTest's real publication transactions.
        for (stage in SqliteWriteStage.entries.filter { it !in setOf(SqliteWriteStage.AFTER_MANIFEST_CHUNK, SqliteWriteStage.AFTER_CONTENT_CHUNK) }) Fixture().use { f ->
            val ready = f.ready()
            f.stage = stage
            val result = assertIs<Mutation.StorageFailure>(f.ledger.claimExecution(ready.callId, ready.revision))
            assertEquals(stage == SqliteWriteStage.AFTER_COMMIT, result.uncertain)
            val beforeReopen = f.ledger.get(ready.callId)!!
            assertTrue(beforeReopen.state in setOf(Status.READY, Status.RUNNING))
            if (result.uncertain) {
                assertIs<Mutation.Blocked>(f.ledger.claimExecution(ready.callId, ready.revision))
                assertIs<Mutation.Blocked>(f.ledger.cancel(ready.callId))
            } else assertEquals(ready, beforeReopen)
            f.reopen()
            val recovered = f.ledger.get(ready.callId)!!
            if (result.uncertain) {
                assertEquals(Status.LOST, recovered.state)
                assertNotNull(recovered.instance)
                assertIs<Mutation.Conflict>(f.ledger.claimExecution(recovered.callId, recovered.revision))
            } else assertEquals(ready, recovered)
        }
    }

    @Test fun persistenceFailureDoesNotPublishApprovalOrTerminal() = Fixture().use { f ->
        val pending = f.prepare(f.input(definition(Risk.WRITE)))
        f.stage = SqliteWriteStage.AFTER_RECORD
        assertIs<Mutation.StorageFailure>(f.ledger.resolveApproval(pending.callId, pending.pending!!, Decision.ALLOW_ONCE))
        assertEquals(pending, f.ledger.get(pending.callId))
        assertIs<Mutation.StorageFailure>(f.ledger.cancel(pending.callId))
        assertEquals(pending, f.ledger.get(pending.callId))
        f.stage = null
        val ready = f.approve(pending)
        assertEquals(Status.READY, ready.state)
    }

    @Test fun walFullAndFutureSchemaRefusal() = Fixture().use { f ->
        sql(f.directory) { db -> assertEquals("wal", db.textValue("PRAGMA journal_mode")) }
        f.store.close()
        sql(f.directory) { it.execute("PRAGMA user_version=999") }
        assertFailsWith<StoreException> { TestInvocationStore.open(f.directory) }
        sql(f.directory) { assertEquals(999L, it.longValue("PRAGMA user_version")) }
    }
}
