package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SummaryWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root: Path get() = temporary.root.toPath()
    private val durability = Durability.PROCESS_CRASH_ONLY
    private fun files(dir: Path): List<Path> = if (Files.isDirectory(dir))
        Files.newDirectoryStream(dir).use { it.toList() } else emptyList()

    @Test fun `session reopen removes abandoned summary only`() {
        val dir = root.resolve("session")
        FileRunHistory.create(dir, "session", durability).use {
            it.append(0, listOf(message(Role.USER, textPart("original conversation"))))
        }
        val workspace = dir.resolve("summary-tmp")
        Files.createDirectories(workspace)
        // Seed the on-disk state left when finally did not run. This is not a process-kill test.
        val abandoned = workspace.resolve("theta-summary-123.data")
        Files.write(abandoned, "conversation/tool content".toByteArray())
        val unknown = workspace.resolve("source.data")
        Files.write(unknown, "keep".toByteArray())
        // These are opaque retention sentinels, not valid recovery records or ledger fixtures.
        val retained = listOf("context.json", "view.json", "execution.json", "ledger.data").map { dir.resolve(it) }
        retained.forEach { Files.write(it, "retained evidence".toByteArray()) }
        val source = Files.readAllBytes(dir.resolve("head.json"))
        FileRunHistory.open(dir, durability).use {
            assertFalse(Files.exists(abandoned), "reopen must remove abandoned summary data")
            assertTrue(Files.exists(unknown))
            retained.forEach { assertEquals("retained evidence", String(Files.readAllBytes(it))) }
            assertContentEquals(source, Files.readAllBytes(dir.resolve("head.json")))
            assertEquals("original conversation", (it.snapshot().messages.single().parts.single() as ContentPart.Text).text)
        }
    }

    @Test fun `active spools and another session survive rejected ownership and reopen`() {
        val a = root.resolve("a"); val b = root.resolve("b")
        FileRunHistory.create(a, "a", durability).use { first ->
            val one = first.createSummarySpool(); val two = first.createSummarySpool()
            try {
                Files.write(one.path, "active one".toByteArray())
                Files.write(two.path, "active two".toByteArray())
                assertFailsWith<IllegalStateException> { first.close() }
                assertFailsWith<HistoryStorageException> { FileRunHistory.open(a, durability) }
                FileRunHistory.create(b, "b", durability).close()
                val abandoned = b.resolve("summary-tmp/theta-summary-42.data")
                Files.write(abandoned, "abandoned".toByteArray())
                FileRunHistory.open(b, durability).use { assertFalse(Files.exists(abandoned)) }
                assertEquals("active one", String(Files.readAllBytes(one.path)))
                assertEquals("active two", String(Files.readAllBytes(two.path)))
                one.close()
                assertFalse(Files.exists(one.path)); assertTrue(Files.exists(two.path))
            } finally { one.close(); two.close() }
        }
    }

    @Test fun `closed owner cannot create new scratch files`() {
        val h = FileRunHistory.create(root.resolve("closed"), "s", durability)
        h.close()
        assertFailsWith<IllegalStateException> { h.createSummarySpool() }
        assertTrue(files(root.resolve("closed/summary-tmp")).isEmpty())
    }

    @Test fun `active run cannot release session lock before summary starts`() {
        val dir = root.resolve("active-run")
        FileRunHistory.create(dir, "s", durability).use { h ->
            h.beginRun()
            try {
                assertFailsWith<IllegalStateException> { h.close() }
                assertFailsWith<IllegalStateException> { h.beginRun() }
                assertFailsWith<HistoryStorageException> { FileRunHistory.open(dir, durability) }
            } finally { h.endRun() }
        }
        FileRunHistory.open(dir, durability).close()
    }

    private fun link(link: Path, target: Path) {
        try { Files.createSymbolicLink(link, target) }
        catch (e: java.io.IOException) { org.junit.Assume.assumeNoException("Host cannot create symbolic links", e) }
        catch (e: UnsupportedOperationException) { org.junit.Assume.assumeNoException(e) }
    }

    @Test fun `cleanup never follows workspace or spool links`() {
        val dir = root.resolve("links")
        FileRunHistory.create(dir, "s", durability).close()
        val outside = Files.createDirectory(root.resolve("outside"))
        val target = outside.resolve("theta-summary-9.data")
        Files.write(target, "external source".toByteArray())
        val workspace = dir.resolve("summary-tmp")
        Files.delete(workspace)
        link(workspace, outside)
        assertFailsWith<HistoryStorageException> { FileRunHistory.open(dir, durability) }
        assertEquals("external source", String(Files.readAllBytes(target)))
        Files.delete(workspace)
        Files.createDirectory(workspace)
        val spoolLink = workspace.resolve("theta-summary-9.data")
        link(spoolLink, target)
        val nested = Files.createDirectory(workspace.resolve("theta-summary-8.data"))
        Files.write(nested.resolve("keep"), byteArrayOf(1))
        FileRunHistory.open(dir, durability).use {
            assertTrue(Files.isSymbolicLink(spoolLink))
            assertEquals("external source", String(Files.readAllBytes(target)))
            assertTrue(Files.exists(nested.resolve("keep")))
        }
    }

    @Test fun `cleanup rejects linked ancestor without touching external files`() {
        val dir = root.resolve("real")
        FileRunHistory.create(dir, "s", durability).close()
        val abandoned = dir.resolve("summary-tmp/theta-summary-7.data")
        Files.write(abandoned, "retain on rejected ownership".toByteArray())
        val alias = root.resolve("alias")
        link(alias, dir)
        assertFailsWith<HistoryStorageException> { FileRunHistory.open(alias, durability) }
        assertTrue(Files.exists(abandoned))
        FileRunHistory.open(dir, durability).use { assertFalse(Files.exists(abandoned)) }
    }

    @Test fun `ambiguous components are rejected before workspace cleanup or history creation`() {
        val dir = root.resolve("session")
        FileRunHistory.create(dir, "s", durability).use { h ->
            val spool = h.createSummarySpool()
            spool.use {
                Files.write(spool.path, "active".toByteArray())
                Files.createDirectory(root.resolve("child"))
                for (raw in listOf(root.resolve("child/../session"), root.resolve("./session"))) {
                    assertFailsWith<IllegalArgumentException> { SummaryWorkspace(raw) }
                    assertEquals("active", String(Files.readAllBytes(spool.path)))
                    assertFailsWith<HistoryStorageException> { FileRunHistory.open(raw, durability) }
                }
            }
        }
        val raw = root.resolve("missing/../new-session")
        assertFails { FileRunHistory.create(raw, "new", durability).close() }
        assertFalse(Files.exists(root.resolve("new-session")))
        assertFalse(Files.exists(root.resolve("missing")))
    }

    @Test fun `link plus parent traversal cannot clean a different active session`() {
        val deep = Files.createDirectories(root.resolve("other/deep"))
        val alias = root.resolve("link")
        link(alias, deep)
        val actual = root.resolve("other/session")
        FileRunHistory.create(actual, "other", durability).close()
        FileRunHistory.create(root.resolve("session"), "active", durability).use { h ->
            h.createSummarySpool().use { spool ->
                Files.write(spool.path, "active source".toByteArray())
                val raw = alias.resolve("../session")
                val followsLinkBeforeParent = Files.isSameFile(raw, actual)
                println("link-dotdot platform=${System.getProperty("os.name")} raw=$raw real=${raw.toRealPath()} normalized=${raw.toAbsolutePath().normalize()} linkBeforeParent=$followsLinkBeforeParent")
                org.junit.Assume.assumeTrue("Host resolves dotdot before symbolic links; POSIX directory-identity reproduction not supported", followsLinkBeforeParent)
                assertFailsWith<HistoryStorageException> { FileRunHistory.open(raw, durability).close() }
                assertEquals("active source", String(Files.readAllBytes(spool.path)))
            }
        }
    }

    private fun seedRecovery(dir: Path): List<Path> {
        FileRunHistory.create(dir, "s", durability).use {
            it.append(0, listOf(message(Role.USER, textPart("committed"))))
        }
        FileRunHistory.open(dir, durability) {
            if (it.file == "head.json" && it.stage == HistoryWriteStage.BEFORE_WRITE)
                throw java.io.IOException("leave speculative batch")
        }.use { h ->
            assertFailsWith<HistoryStorageException> {
                h.append(1, listOf(message(Role.USER, textPart("uncommitted"))))
            }
        }
        Files.delete(dir.resolve("writer.lock"))
        Files.delete(dir.resolve("accounting"))
        val payload = HistoryCodec.json.encodeToString(RunAccounting("s", "chain", "run", 3, 2))
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        Files.write(dir.resolve("accounting.json"), HistoryCodec.json.encodeToString(CheckedHistoryValue(payload, hash)).toByteArray())
        val temporaryFiles = listOf(".head.json.tmp", "batches/.2.json.tmp", "calls/.${"a".repeat(64)}.json.tmp",
            "summary-tmp/theta-summary-7.data").map { dir.resolve(it) }
        temporaryFiles.forEach { Files.write(it, "retain on rejection".toByteArray()) }
        return temporaryFiles + listOf("head.json", "batches/1.json", "batches/2.json", "accounting.json").map { dir.resolve(it) }
    }

    private fun assertRejectedWithoutRecovery(dir: Path, input: Path) {
        val before = seedRecovery(dir).associateWith { Files.readAllBytes(it) }
        assertFailsWith<HistoryStorageException> { FileRunHistory.open(input, durability).close() }
        // Assert every sentinel even if one was lost, so red evidence identifies all side effects.
        val changed = before.filter { (path, bytes) -> !Files.exists(path) || !Files.readAllBytes(path).contentEquals(bytes) }.keys
        assertTrue(changed.isEmpty() && !Files.exists(dir.resolve("accounting")) && !Files.exists(dir.resolve("writer.lock")),
            "Changed=$changed accountingCreated=${Files.exists(dir.resolve("accounting"))} lockCreated=${Files.exists(dir.resolve("writer.lock"))}")
    }

    @Test fun `linked session rejects before lock creation cleanup and accounting migration`() {
        // Create the directory first: Windows otherwise creates a file-type link to the absent target.
        val dir = Files.createDirectory(root.resolve("real-recovery"))
        val alias = root.resolve("alias-recovery")
        link(alias, dir)
        assertRejectedWithoutRecovery(dir, alias)
        FileRunHistory.open(dir, durability).use {
            assertEquals(1, it.revision())
            assertEquals(3, it.loadAccounting("chain")?.steps)
            assertFalse(Files.exists(dir.resolve("batches/2.json")))
            assertFalse(Files.exists(dir.resolve(".head.json.tmp")))
        }
    }

    @Test fun `linked workspace rejects before history recovery writes`() {
        val dir = root.resolve("workspace-recovery")
        val before = seedRecovery(dir).associateWith { Files.readAllBytes(it) }
        val workspace = dir.resolve("summary-tmp")
        val outside = root.resolve("outside-workspace")
        Files.move(workspace, outside)
        link(workspace, outside)
        assertFailsWith<HistoryStorageException> { FileRunHistory.open(dir, durability).close() }
        val changed = before.filter { (path, bytes) -> !Files.exists(path) || !Files.readAllBytes(path).contentEquals(bytes) }.keys
        assertTrue(changed.isEmpty() && !Files.exists(dir.resolve("accounting")) && !Files.exists(dir.resolve("writer.lock")),
            "Changed=$changed accountingCreated=${Files.exists(dir.resolve("accounting"))} lockCreated=${Files.exists(dir.resolve("writer.lock"))}")
    }

    @Test fun `create through linked ancestor leaves target untouched`() {
        val outside = Files.createDirectory(root.resolve("create-target"))
        val alias = root.resolve("create-alias")
        link(alias, outside)
        assertFails { FileRunHistory.create(alias.resolve("missing/session"), "s", durability).close() }
        assertTrue(files(outside).isEmpty(), "Rejected create must not create even a parent directory")
        assertFails { FileRunHistory.create(alias, "s", durability).close() }
        assertTrue(files(outside).isEmpty(), "Rejected create must not write history or lock files")
    }

    @Test fun `summary finally cleans on cancellation provider failure and rejected attempt`() = runTest {
        for (failure in listOf("cancel", "provider", "reject")) {
            val dir = root.resolve(failure)
            FileRunHistory.create(dir, "s", durability).use { h ->
                val provider = object : ModelProvider by ScriptProvider(emptyList()) {
                    override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                        assertEquals(1, files(dir.resolve("summary-tmp")).size)
                        if (failure == "cancel") kotlinx.coroutines.awaitCancellation()
                        throw IllegalStateException("provider failed")
                    }
                }
                val model = SummaryModel(ModelTarget("s", provider, Credentials { "test" }),
                    chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100 },
                    ContextBudget({ r -> r.messages.sumOf { m -> m.parts.sumOf { p ->
                        (p as? ContentPart.Text)?.text?.length?.toLong() ?: 0L
                    } } }, { 1800 }))
                val summarizer = ContextSummarizer(model, MemoryRunJournal(), EventTimeouts(100, 100), h::createSummarySpool) {
                    failure != "reject"
                }
                val source = listOf(message(Role.USER, textPart("source".repeat(2000))))
                when (failure) {
                    "cancel" -> assertFailsWith<kotlinx.coroutines.CancellationException> {
                        kotlinx.coroutines.withTimeout(10) { summarizer.compact(source, "s@1") }
                    }
                    "provider" -> assertFailsWith<UncertainSummaryResponse> { summarizer.compact(source, "s@1") }
                    else -> assertNull(summarizer.compact(source, "s@1"))
                }
                assertTrue(files(dir.resolve("summary-tmp")).isEmpty())
            }
        }
    }

    @Test fun `run loop spools inside owned session and cleans normally`() = runTest {
        val dir = root.resolve("session")
        val fixture = RunFixture()
        var observed = false
        FileRunHistory.create(dir, "session", durability).use { history ->
            history.append(0, listOf(message(Role.USER, textPart("private source ".repeat(2000))),
                message(Role.USER, textPart("goal"))))
            val provider = object : ModelProvider by ScriptProvider(emptyList()) {
                override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                    if (request.messages.any { m -> m.parts.any { (it as? ContentPart.Text)?.text?.contains("private source") == true } }) {
                        val spools = files(dir.resolve("summary-tmp"))
                        assertEquals(1, spools.size, "source must not be spooled in global temp")
                        assertTrue(String(Files.readAllBytes(spools.single()), Charsets.UTF_8).contains("private source"))
                        observed = true
                        assertFailsWith<IllegalStateException> { history.close() }
                        assertFailsWith<HistoryStorageException> { FileRunHistory.open(dir, durability) }
                        assertTrue(Files.exists(spools.single()))
                    }
                    answer("facts").forEach { emit(it) }
                }
            }
            val budget = ContextBudget({ r -> r.messages.sumOf { m -> m.parts.sumOf { p ->
                (p as? ContentPart.Text)?.text?.length?.toLong() ?: 0L
            } } }, { 1800 })
            val summary = SummaryModel(ModelTarget("summary", provider, Credentials { "test" }),
                chatRequest("m") { toolChoice = ToolChoice.None; maxOutputTokens = 100 }, budget)
            AgentRunLoop(RunIdentity("chain", "session", "run"),
                ModelTarget("main", ScriptProvider(listOf(answer())), Credentials { "test" }),
                fixture.request(), history,
                LedgerToolExecutor(fixture.ledger, fixture.gate, fixture.definitions, fixture.bridge),
                budget = budget, contextPolicy = ContextPolicy("goal", 1, summary)).run()
            assertTrue(observed)
            assertTrue(files(dir.resolve("summary-tmp")).isEmpty())
        }
    }
}
