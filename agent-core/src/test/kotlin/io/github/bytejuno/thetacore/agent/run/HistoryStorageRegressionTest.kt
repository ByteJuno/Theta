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
            FileRunHistory.create(path, "session", durability).use { h ->
                val before = h.io()
                assertFails { h.prepareStep(0, run, "step", message) }
                assertEquals(before.bytesWritten, h.io().bytesWritten)
                assertFalse(Files.exists(path.resolve("pending.json")))
                assertTrue(temps(path).isEmpty())
                assertEquals(0, h.revision())
            }
            FileRunHistory.open(path, durability).use { h ->
                h.prepareStep(0, identity, "valid", assistant(call()))
                assertTrue(h.recoverPending(ledger()).recovered)
                assertEquals(2, h.snapshot().messages.size)
            }
        }
    }

    @Test fun usedIdentityAndBlankStepAreRejectedBeforeIntentButInvalidArgumentsRemainEvidence() {
        val path = Files.createTempDirectory("history-identity")
        val c = call()
        FileRunHistory.create(path, "session", durability).use { h ->
            assertFails { h.prepareStep(0, identity, " ", assistant(c)) }
            h.append(0, listOf(assistant(c), message(Role.TOOL, toolResultPart(c.binding.callId, "done"))))
            assertFails { h.prepareStep(1, identity, "step", assistant(c)) }
            assertFalse(Files.exists(path.resolve("pending.json")))
        }
        FileRunHistory.open(path, durability).use { h ->
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
        val c = call()
        val text = "\u0000".repeat(10 * 1024 * 1024) + "终点😀\\\"\n"
        val d = testDefinition()
        JsonInvocationStore.create(ledgerPath, durability).use { store ->
            val gate = DefinitionGate().apply { install(d); freezeRun("run", listOf(d)) }
            val l = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("before")
            FileRunHistory.create(path, "session", durability).use {
                it.prepareStep(0, identity, "step", assistant(c))
            }
            l.recordInvocation(InvocationInput(BindingValue.copyOf(c.binding), "chain", "session", "run", "step",
                0, "{}", d, false, Risk.READ, false, "tool"))
            l.validateAndPrepare(c.binding.callId)
            val ticket = assertIs<Mutation.Applied>(l.claimExecution(c.binding.callId, l.get(c.binding.callId)!!.revision)).ticket!!
            assertIs<Mutation.Applied>(l.complete(c.binding.callId, ticket.attemptId, Status.SUCCEEDED, text))
        }
        JsonInvocationStore.open(ledgerPath, durability).use { store ->
            val l = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("after")
            FileRunHistory.open(path, durability) {
                if (it.file == "head.json" && it.stage == HistoryWriteStage.BEFORE_REPLACE) throw IOException("lost commit")
            }.use { h -> assertFailsWith<HistoryStorageException> { h.recoverPending(l) } }
            assertTrue(Files.size(path.resolve("batches/1.json")) > 64L * 1024 * 1024)
            FileRunHistory.open(path, durability).use { h ->
                assertEquals(0, h.revision())
                assertTrue(h.recoverPending(l).recovered)
                assertFalse(h.recoverPending(l).recovered)
                assertEquals(1, h.revision())
                assertFalse(Files.exists(path.resolve("pending.json")))
            }
        }
        FileRunHistory.open(path, durability).use { h ->
            val page = h.recent(1)
            assertEquals(2, page.messages.size) // transport frames never split a transaction/page
            assertNull(page.older)
            val result = page.messages.last().parts.single() as ContentPart.ToolResult
            assertEquals(c.binding.callId, result.callId)
            assertEquals(text, Json.parseToJsonElement(result.text).jsonObject.getValue("message").jsonPrimitive.content)
        }
        println("ISSUE19_LARGE_RESULT rawChars=${text.length}; batchBytes=${Files.size(path.resolve("batches/1.json"))}; ledger=$ledgerPath; history=$path")
    }

    @Test fun legacyCheckedJsonBatchCanBeReadAndExtendedByFramedWrites() {
        val path = Files.createTempDirectory("history-legacy")
        val m = message(Role.USER, textPart("legacy\n原样"))
        FileRunHistory.create(path, "session", durability).use { it.append(0, listOf(m)) }
        val batch = HistoryBatch(sessionId = "session", revision = 1, previousHash = "ROOT", previousCount = 0,
            messages = listOf(HistoryCodec.encodeMessage(m)))
        val payload = HistoryCodec.json.encodeToString(HistoryBatch.serializer(), batch)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val legacy = HistoryCodec.json.encodeToString(CheckedHistoryValue.serializer(), CheckedHistoryValue(payload, digest))
        Files.write(path.resolve("batches/1.json"), legacy.toByteArray(Charsets.UTF_8))
        FileRunHistory.open(path, durability).use { h ->
            h.append(1, listOf(message(Role.USER, textPart("new"))))
            assertEquals(listOf("legacy\n原样", "new"), h.snapshot().messages.map { (it.parts.single() as ContentPart.Text).text })
        }
    }

    @Test fun framedHistoryPreservesLoneSurrogatesInResultsWithoutChangingLedgerTruth() {
        val path = Files.createTempDirectory("history-result-surrogates")
        val c = call()
        val text = "partial\uD800 text \uDC00"
        FileRunHistory.create(path, "session", durability).use { h ->
            h.append(0, listOf(assistant(c), message(Role.TOOL, toolResultPart(c.binding.callId, text))))
        }
        FileRunHistory.open(path, durability).use { h ->
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
        JsonInvocationStore.create(ledgerPath, durability).use { store ->
            val gate = DefinitionGate().apply { install(d); freezeRun("run", listOf(d)) }
            val l = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("before")
            FileRunHistory.create(path, "session", durability).close()
            FileRunHistory.open(path, durability) {
                if (it.file == "head.json" && it.stage == HistoryWriteStage.AFTER_REPLACE) throw IOException("ack lost")
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
        JsonInvocationStore.open(ledgerPath, durability).use { store ->
            val l = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
            l.recoverColdStart("after")
            FileRunHistory.open(path, durability).use { h ->
                assertTrue(h.recoverPending(l).recovered)
                assertFalse(Files.exists(path.resolve("pending.json")))
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
        FileRunHistory.create(path, "session", durability).close()
        FileRunHistory.open(path, durability) {
            if (it.file == "head.json" && it.stage == HistoryWriteStage.AFTER_REPLACE) throw IOException("ack lost")
        }.use { h ->
            h.prepareStep(0, identity, "step", a)
            l.recordInvocation(InvocationInput(BindingValue.copyOf(c.binding), "chain", "session", "run", "step",
                0, "{}", null, false, Risk.READ, true, "tool"))
            assertFailsWith<HistoryStorageException> {
                h.append(0, listOf(a, message(Role.TOOL, toolResultPart(c.binding.callId, "not what the ledger says"))))
            }
        }
        FileRunHistory.open(path, durability).use { h ->
            assertFails { h.recoverPending(l) }
            assertTrue(Files.exists(path.resolve("pending.json")))
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

    @Test fun temporaryCleanupIsRestartableAndPreservesUnknownAndCorruptFinalEvidence() {
        val path = Files.createTempDirectory("history-temp-restart")
        FileRunHistory.create(path, "session", durability).close()
        val known = listOf(path.resolve(".pending.json.tmp"), path.resolve(".head.json.tmp"),
            path.resolve("batches/.1.json.tmp"), path.resolve("calls/.${"a".repeat(64)}.json.tmp"),
            path.resolve("calls/.${"b".repeat(64)}.json.tmp"))
        known.forEach { Files.write(it, byteArrayOf(1, 2, 3)) }
        val evidence = listOf(path.resolve("calls/unknown.tmp"), path.resolve("calls/${"c".repeat(64)}.json"),
            path.resolve("batches/unknown.json"))
        evidence.forEach { Files.write(it, byteArrayOf(4, 5, 6)) }
        assertFails { FileRunHistory.open(path, durability) {
            if (it.file.endsWith(".tmp") && it.stage == HistoryWriteStage.AFTER_REPLACE) throw IOException("cleanup interrupted")
        } }
        assertEquals(known.size - 1, known.count { Files.exists(it) })
        repeat(2) {
            FileRunHistory.open(path, durability).use { h -> assertEquals(0, h.revision()) }
            assertTrue(known.none { Files.exists(it) })
            evidence.forEach { assertContentEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(it)) }
        }
    }

    @Test fun repeatedCallWriteFailuresDoNotAccumulateTemporaryFiles() {
        val path = Files.createTempDirectory("history-temp-faults")
        FileRunHistory.create(path, "session", durability).close()
        for (stage in HistoryWriteStage.entries) {
            val c = call()
            FileRunHistory.open(path, durability) {
                if (it.file.startsWith("calls/") && it.stage == stage) throw IOException("call failure")
            }.use { h -> assertFails { h.append(0, listOf(assistant(c), message(Role.TOOL, toolResultPart(c.binding.callId, "r")))) } }
            assertTrue(temps(path).isEmpty(), "ordinary failure must clean temp: $stage")
            FileRunHistory.open(path, durability).use { assertEquals(0, it.revision()) }
            Files.newDirectoryStream(path.resolve("calls")).use { assertFalse(it.iterator().hasNext()) }
        }
    }

    @Test fun directoryChainSyncsOnlyMissingDirectoriesAndTheirParentsBeforeChildren() {
        val base = Files.createTempDirectory("history-parent-sync").toAbsolutePath().normalize()
        val one = base.resolve("one")
        val two = one.resolve("two")
        val target = two.resolve("session")
        val expected = listOf(base, one, one, two, two, target)
        val synced = mutableListOf<Path>()
        FileRunHistory.createDirectoryChain(target, Durability.REQUIRE_DIRECTORY_SYNC) { p, d ->
            if (!p.startsWith(base)) throw java.nio.file.AccessDeniedException(p.toString())
            assertEquals(Durability.REQUIRE_DIRECTORY_SYNC, d)
            assertEquals(expected[synced.size], p)
            assertTrue(Files.isDirectory(p))
            val child = listOf(two, two, target, target, null, null)[synced.size]
            if (child != null) assertFalse(Files.exists(child), "child created before directory sync: $child")
            synced.add(p)
        }
        assertEquals(expected, synced)
    }

    @Test fun precreatedSessionSyncsOnlyItselfAndItsDirectParent() {
        val base = Files.createTempDirectory("history-precreated-sync").toAbsolutePath().normalize()
        val parent = Files.createDirectories(base.resolve("existing/parent"))
        val target = Files.createDirectory(parent.resolve("session"))
        val synced = mutableListOf<Path>()
        FileRunHistory.createDirectoryChain(target, Durability.REQUIRE_DIRECTORY_SYNC) { p, _ ->
            if (!p.startsWith(parent)) throw java.nio.file.AccessDeniedException(p.toString())
            synced.add(p)
        }
        assertEquals(listOf(parent, target), synced)
    }

    @Test fun directoryChainPropagatesEverySyncFailureBeforeCreatingFurtherChildren() {
        for (failureIndex in 0..5) {
            val base = Files.createTempDirectory("history-failed-sync").toAbsolutePath().normalize()
            val one = base.resolve("one")
            val two = one.resolve("two")
            val target = two.resolve("session")
            val expected = listOf(base, one, one, two, two, target)
            val synced = mutableListOf<Path>()
            val failure = IOException("directory fsync failure $failureIndex")
            assertSame(failure, assertFailsWith<IOException> {
                FileRunHistory.createDirectoryChain(target, Durability.REQUIRE_DIRECTORY_SYNC) { p, _ ->
                    if (!p.startsWith(base)) throw java.nio.file.AccessDeniedException(p.toString())
                    synced.add(p)
                    if (synced.size == failureIndex + 1) throw failure
                }
            })
            assertEquals(expected.take(failureIndex + 1), synced)
            assertTrue(Files.isDirectory(one))
            assertEquals(failureIndex >= 2, Files.exists(two))
            assertEquals(failureIndex >= 4, Files.exists(target))
        }
        for (failureIndex in 0..1) {
            val parent = Files.createTempDirectory("history-existing-failed-sync").toAbsolutePath().normalize()
            val target = Files.createDirectory(parent.resolve("session"))
            val synced = mutableListOf<Path>()
            val failure = IOException("precreated fsync failure")
            assertSame(failure, assertFailsWith<IOException> {
                FileRunHistory.createDirectoryChain(target, Durability.REQUIRE_DIRECTORY_SYNC) { p, _ ->
                    synced.add(p)
                    if (synced.size == failureIndex + 1) throw failure
                }
            })
            assertEquals(listOf(parent, target).take(failureIndex + 1), synced)
        }
    }

    @Test fun directoryChainRejectsExistingFilesWithoutSyncingAncestors() {
        val base = Files.createTempDirectory("history-chain-file")
        val file = Files.write(base.resolve("file"), byteArrayOf(1))
        for (target in listOf(file, file.resolve("child/session"))) {
            var syncs = 0
            assertFails { FileRunHistory.createDirectoryChain(target, durability) { _, _ -> syncs++ } }
            assertEquals(0, syncs)
            assertContentEquals(byteArrayOf(1), Files.readAllBytes(file))
        }
    }

    @Test fun directoryChainAcceptsConcurrentlyCreatedDirectoryButRejectsFile() {
        for (asDirectory in listOf(true, false)) {
            val base = Files.createTempDirectory("history-chain-race").toAbsolutePath().normalize()
            val one = base.resolve("one")
            val two = one.resolve("two")
            val target = two.resolve("session")
            val synced = mutableListOf<Path>()
            val create = {
                FileRunHistory.createDirectoryChain(target, durability) { p, _ ->
                    synced.add(p)
                    if (p == base) {
                        if (asDirectory) Files.createDirectory(two) else Files.write(two, byteArrayOf(1))
                    }
                }
            }
            if (asDirectory) {
                create()
                assertEquals(listOf(base, one, one, two, two, target), synced)
                assertTrue(Files.isDirectory(target))
            } else {
                assertFailsWith<java.nio.file.FileAlreadyExistsException> { create() }
                assertEquals(listOf(base, one), synced)
                assertFalse(Files.exists(target))
            }
        }
    }

    @Test fun realDirectorySyncEitherSucceedsOrFailsExplicitlyOnThisOs() {
        val base = Files.createTempDirectory("history-directory-sync")
        val supported = runCatching { FileChannel.open(base, READ).use { it.force(true) } }.isSuccess
        val target = base.resolve("a/b/session")
        if (supported) {
            FileRunHistory.create(target, "session", Durability.REQUIRE_DIRECTORY_SYNC).use {
                it.append(0, listOf(message(Role.USER, textPart("durable"))))
            }
            FileRunHistory.open(target, Durability.REQUIRE_DIRECTORY_SYNC).use { assertEquals(1, it.revision()) }
        } else assertFails { FileRunHistory.create(target, "session", Durability.REQUIRE_DIRECTORY_SYNC) }
        println("ISSUE19_DIRECTORY_SYNC supported=$supported; os=${System.getProperty("os.name")}; power_loss_tested=false")
    }
}
