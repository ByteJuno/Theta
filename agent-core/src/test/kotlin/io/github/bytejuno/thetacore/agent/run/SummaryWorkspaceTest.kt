package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
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
    private fun workspace(dir: Path, id: String = "s"): Path {
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return dir.resolve("${AgentDatabase.FILE_NAME}.summaries/$key/summary-tmp")
    }
    private fun files(dir: Path): List<Path> = if (Files.isDirectory(dir))
        Files.newDirectoryStream(dir).use { it.toList() } else emptyList()

    @Test fun `session reopen removes abandoned summary only`() {
        val dir = root.resolve("session")
        TestRunHistory.create(dir, "session", durability).use {
            it.append(0, listOf(message(Role.USER, textPart("original conversation"))))
        }
        val workspace = workspace(dir, "session")
        Files.createDirectories(workspace)
        // Seed the on-disk state left when finally did not run. This is not a process-kill test.
        val abandoned = workspace.resolve("theta-summary-123.data")
        Files.write(abandoned, "conversation/tool content".toByteArray())
        val unknown = workspace.resolve("source.data")
        Files.write(unknown, "keep".toByteArray())
        // These are opaque retention sentinels, not valid recovery records or ledger fixtures.
        val retained = listOf("context.json", "view.json", "execution.json", "ledger.data").map { dir.resolve(it) }
        retained.forEach { Files.write(it, "retained evidence".toByteArray()) }
        val source = TestRunHistory.bytes(dir, "head")
        TestRunHistory.open(dir, durability).use {
            assertFalse(Files.exists(abandoned), "reopen must remove abandoned summary data")
            assertTrue(Files.exists(unknown))
            retained.forEach { assertEquals("retained evidence", String(Files.readAllBytes(it))) }
            assertContentEquals(source, TestRunHistory.bytes(dir, "head"))
            assertEquals("original conversation", (it.snapshot().messages.single().parts.single() as ContentPart.Text).text)
        }
    }

    @Test fun `active spools and another session survive rejected ownership and reopen`() {
        val a = root.resolve("a"); val b = root.resolve("b")
        TestRunHistory.create(a, "a", durability).use { first ->
            val one = first.createSummarySpool(); val two = first.createSummarySpool()
            try {
                Files.write(one.path, "active one".toByteArray())
                Files.write(two.path, "active two".toByteArray())
                assertFailsWith<IllegalStateException> { first.close() }
                assertFailsWith<HistoryStorageException> { TestRunHistory.open(a, durability) }
                TestRunHistory.create(b, "b", durability).close()
                val abandoned = workspace(b, "b").resolve("theta-summary-42.data")
                Files.write(abandoned, "abandoned".toByteArray())
                TestRunHistory.open(b, durability).use { assertFalse(Files.exists(abandoned)) }
                assertEquals("active one", String(Files.readAllBytes(one.path)))
                assertEquals("active two", String(Files.readAllBytes(two.path)))
                one.close()
                assertFalse(Files.exists(one.path)); assertTrue(Files.exists(two.path))
            } finally { one.close(); two.close() }
        }
    }

    @Test fun `closed owner cannot create new scratch files`() {
        val h = TestRunHistory.create(root.resolve("closed"), "s", durability)
        h.close()
        assertFailsWith<IllegalStateException> { h.createSummarySpool() }
        assertTrue(files(workspace(root.resolve("closed"))).isEmpty())
    }

    @Test fun `active run cannot release session lock before summary starts`() {
        val dir = root.resolve("active-run")
        TestRunHistory.create(dir, "s", durability).use { h ->
            h.beginRun()
            try {
                assertFailsWith<IllegalStateException> { h.close() }
                assertFailsWith<IllegalStateException> { h.beginRun() }
                assertFailsWith<HistoryStorageException> { TestRunHistory.open(dir, durability) }
            } finally { h.endRun() }
        }
        TestRunHistory.open(dir, durability).close()
    }

    @Test fun `shared database owner stays pinned and session namespaces remain isolated`() {
        val file = root.resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
            val first = SqliteRunHistory.create(db, "../a")
            val second = SqliteRunHistory.create(db, "b")
            first.use { a -> second.use { b ->
                val one = a.createSummarySpool()
                one.use {
                    Files.write(one.path, "active".toByteArray())
                    assertFailsWith<IllegalStateException> { db.close() }
                    assertFails { AgentDatabase.open(file, androidx.sqlite.driver.bundled.BundledSQLiteDriver()) }
                    assertFails { SqliteRunHistory.open(db, "../a") }
                    val two = b.createSummarySpool()
                    val abandoned = two.path.parent.resolve("theta-summary-42.data")
                    Files.write(abandoned, "abandoned".toByteArray())
                    two.close(); b.close()
                    SqliteRunHistory.open(db, "b").use { reopened ->
                        assertFalse(Files.exists(abandoned))
                        reopened.createSummarySpool().use { assertNotEquals(one.path.parent, it.path.parent) }
                    }
                    assertEquals("active", String(Files.readAllBytes(one.path)))
                    assertTrue(one.path.startsWith(root.resolve("${AgentDatabase.FILE_NAME}.summaries")))
                }
                a.beginRun()
                try { assertFailsWith<IllegalStateException> { db.close() } }
                finally { a.endRun() }
            } }
        }
    }

    @Test fun `poisoned database retains owner until active spool is cleaned`() {
        AgentDatabase.open(root.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "s").use { h ->
                val spool = h.createSummarySpool()
                db.poison()
                try {
                    assertFails { h.createSummarySpool() }
                    assertFails { h.close() }
                    assertFails { db.close() }
                    assertTrue(Files.exists(spool.path))
                } finally { spool.close() }
                assertFalse(Files.exists(spool.path))
            }
        }
    }

    private fun link(link: Path, target: Path) {
        try { Files.createSymbolicLink(link, target) }
        catch (e: java.io.IOException) { org.junit.Assume.assumeNoException("Host cannot create symbolic links", e) }
        catch (e: UnsupportedOperationException) { org.junit.Assume.assumeNoException(e) }
    }

    @Test fun `cleanup never follows workspace or spool links`() {
        val dir = root.resolve("links")
        TestRunHistory.create(dir, "s", durability).close()
        val outside = Files.createDirectory(root.resolve("outside"))
        val target = outside.resolve("theta-summary-9.data")
        Files.write(target, "external source".toByteArray())
        val workspace = workspace(dir)
        Files.delete(workspace)
        link(workspace, outside)
        assertFailsWith<HistoryStorageException> { TestRunHistory.open(dir, durability) }
        assertEquals("external source", String(Files.readAllBytes(target)))
        Files.delete(workspace)
        Files.createDirectory(workspace)
        val spoolLink = workspace.resolve("theta-summary-9.data")
        link(spoolLink, target)
        val nested = Files.createDirectory(workspace.resolve("theta-summary-8.data"))
        Files.write(nested.resolve("keep"), byteArrayOf(1))
        TestRunHistory.open(dir, durability).use {
            assertTrue(Files.isSymbolicLink(spoolLink))
            assertEquals("external source", String(Files.readAllBytes(target)))
            assertTrue(Files.exists(nested.resolve("keep")))
        }
    }

    @Test fun `database files and recovery sidecars reject links before driver open`() {
        for (suffix in listOf("", ".writer.lock", "-wal", "-shm", "-journal")) {
            val dir = Files.createDirectory(root.resolve("sidecar-${suffix.ifEmpty { "main" }}"))
            val file = dir.resolve(AgentDatabase.FILE_NAME)
            AgentDatabase.open(file, androidx.sqlite.driver.bundled.BundledSQLiteDriver()).close()
            val path = dir.resolve("${AgentDatabase.FILE_NAME}$suffix")
            val outside = root.resolve("outside-${suffix.ifEmpty { "main" }}")
            if (Files.exists(path)) Files.move(path, outside)
            else Files.write(outside, "external evidence".toByteArray())
            link(path, outside)
            val before = Files.readAllBytes(outside)
            var opened = false
            val driver = object : androidx.sqlite.SQLiteDriver {
                override fun open(fileName: String): androidx.sqlite.SQLiteConnection {
                    opened = true
                    error("Path rejection must precede SQLite recovery")
                }
            }
            assertFailsWith<StoreException> { AgentDatabase.open(file, driver) }
            assertFalse(opened)
            assertContentEquals(before, Files.readAllBytes(outside))
        }
    }

    @Test fun `linked namespace roots reject before database or session bootstrap writes`() {
        for (level in listOf("root", "session")) {
            val dir = Files.createDirectory(root.resolve("namespace-$level"))
            TestRunHistory.create(dir, "s", durability).close()
            val workspace = workspace(dir)
            val path = if (level == "root") workspace.parent.parent else workspace.parent
            val outside = root.resolve("namespace-outside-$level")
            Files.move(path, outside)
            link(path, outside)
            val databaseBytes = Files.readAllBytes(dir.resolve(AgentDatabase.FILE_NAME))
            assertFailsWith<HistoryStorageException> { TestRunHistory.open(dir, durability) }
            assertContentEquals(databaseBytes, Files.readAllBytes(dir.resolve(AgentDatabase.FILE_NAME)))
        }
        // Revalidate again when borrowing an already-open database, before session creation.
        val dir = Files.createDirectory(root.resolve("bootstrap"))
        AgentDatabase.open(dir.resolve(AgentDatabase.FILE_NAME), androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { db ->
            val outside = Files.createDirectory(root.resolve("bootstrap-outside"))
            link(dir.resolve("${AgentDatabase.FILE_NAME}.summaries"), outside)
            assertFails { SqliteRunHistory.create(db, "never-created") }
            assertEquals(0L, db.connection.longValue("SELECT count(*) FROM sessions"))
            assertTrue(files(outside).isEmpty())
            db.ready() // read-only rejection must not poison the shared owner
        }
    }

    @Test fun `cleanup rejects linked ancestor without touching external files`() {
        val dir = root.resolve("real")
        TestRunHistory.create(dir, "s", durability).close()
        val abandoned = workspace(dir).resolve("theta-summary-7.data")
        Files.write(abandoned, "retain on rejected ownership".toByteArray())
        val alias = root.resolve("alias")
        link(alias, dir)
        assertFailsWith<HistoryStorageException> { TestRunHistory.open(alias, durability) }
        assertTrue(Files.exists(abandoned))
        TestRunHistory.open(dir, durability).use { assertFalse(Files.exists(abandoned)) }
    }

    @Test fun `ambiguous components are rejected before workspace cleanup or history creation`() {
        val dir = root.resolve("session")
        TestRunHistory.create(dir, "s", durability).use { h ->
            val spool = h.createSummarySpool()
            spool.use {
                Files.write(spool.path, "active".toByteArray())
                Files.createDirectory(root.resolve("child"))
                for (raw in listOf(root.resolve("child/../session"), root.resolve("./session"))) {
                    assertFailsWith<IllegalArgumentException> { SummaryWorkspace(raw) }
                    assertEquals("active", String(Files.readAllBytes(spool.path)))
                    assertFailsWith<HistoryStorageException> { TestRunHistory.open(raw, durability) }
                }
            }
        }
        val raw = root.resolve("missing/../new-session")
        assertFails { TestRunHistory.create(raw, "new", durability).close() }
        assertFalse(Files.exists(root.resolve("new-session")))
        assertFalse(Files.exists(root.resolve("missing")))
    }

    @Test fun `link plus parent traversal cannot clean a different active session`() {
        val deep = Files.createDirectories(root.resolve("other/deep"))
        val alias = root.resolve("link")
        link(alias, deep)
        val actual = root.resolve("other/session")
        TestRunHistory.create(actual, "other", durability).close()
        TestRunHistory.create(root.resolve("session"), "active", durability).use { h ->
            h.createSummarySpool().use { spool ->
                Files.write(spool.path, "active source".toByteArray())
                val raw = alias.resolve("../session")
                val followsLinkBeforeParent = Files.isSameFile(raw, actual)
                println("link-dotdot platform=${System.getProperty("os.name")} raw=$raw real=${raw.toRealPath()} normalized=${raw.toAbsolutePath().normalize()} linkBeforeParent=$followsLinkBeforeParent")
                org.junit.Assume.assumeTrue("Host resolves dotdot before symbolic links; POSIX directory-identity reproduction not supported", followsLinkBeforeParent)
                assertFailsWith<HistoryStorageException> { TestRunHistory.open(raw, durability).close() }
                assertEquals("active source", String(Files.readAllBytes(spool.path)))
            }
        }
    }

    // SQLite replaces speculative JSON/migration with transactional recovery. Rejection must
    // precede *all* database/provenance writes as well as scratch cleanup.
    private fun seedRecovery(dir: Path): List<Path> {
        TestRunHistory.create(dir, "s", durability).use {
            it.append(0, listOf(message(Role.USER, textPart("committed"))))
            it.saveAccounting(RunAccounting("s", "chain", "run", 3, 2))
        }
        TestRunHistory.open(dir, durability) {
            if (it.file == "append" && it.stage == HistoryWriteStage.BEFORE_COMMIT)
                throw java.io.IOException("rollback speculative batch")
        }.use { h ->
            assertFailsWith<HistoryStorageException> {
                h.append(1, listOf(message(Role.USER, textPart("uncommitted"))))
            }
        }
        val abandoned = workspace(dir).resolve("theta-summary-7.data")
        Files.write(abandoned, "retain on rejection".toByteArray())
        return Files.walk(dir).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
    }

    private fun assertRejectedWithoutRecovery(dir: Path, input: Path) {
        val before = seedRecovery(dir).associateWith { Files.readAllBytes(it) }
        val paths = Files.walk(dir).use { it.toList().toSet() }
        assertFailsWith<HistoryStorageException> { TestRunHistory.open(input, durability).close() }
        val changed = before.filter { (path, bytes) -> !Files.exists(path) || !Files.readAllBytes(path).contentEquals(bytes) }.keys
        assertTrue(changed.isEmpty(), "Changed=$changed")
        assertEquals(paths, Files.walk(dir).use { it.toList().toSet() })
    }

    @Test fun `linked session rejects before lock provenance and recovery writes`() {
        val dir = Files.createDirectory(root.resolve("real-recovery"))
        val alias = root.resolve("alias-recovery")
        link(alias, dir)
        assertRejectedWithoutRecovery(dir, alias)
        TestRunHistory.open(dir, durability).use {
            assertEquals(1L, it.revision())
            assertEquals(3, it.loadAccounting("chain")?.steps)
            assertFalse(TestRunHistory.exists(dir, "batches/2"))
            assertFalse(Files.exists(workspace(dir).resolve("theta-summary-7.data")))
        }
    }

    @Test fun `linked workspace rejects before database recovery writes`() {
        val dir = root.resolve("workspace-recovery")
        val before = seedRecovery(dir).associateWith { Files.readAllBytes(it) }
        val workspace = workspace(dir)
        val outside = root.resolve("outside-workspace")
        Files.move(workspace, outside)
        link(workspace, outside)
        val paths = Files.walk(dir).use { it.toList().toSet() }
        assertFailsWith<HistoryStorageException> { TestRunHistory.open(dir, durability).close() }
        val changed = before.filter { (path, bytes) -> !Files.exists(path) || !Files.readAllBytes(path).contentEquals(bytes) }.keys
        assertTrue(changed.isEmpty(), "Changed=$changed")
        assertEquals(paths, Files.walk(dir).use { it.toList().toSet() })
    }

    @Test fun `create through linked ancestor leaves target untouched`() {
        val outside = Files.createDirectory(root.resolve("create-target"))
        val alias = root.resolve("create-alias")
        link(alias, outside)
        assertFails { TestRunHistory.create(alias.resolve("missing/session"), "s", durability).close() }
        assertTrue(files(outside).isEmpty(), "Rejected create must not create even a parent directory")
        assertFails { TestRunHistory.create(alias, "s", durability).close() }
        assertTrue(files(outside).isEmpty(), "Rejected create must not write history or lock files")
    }

    @Test fun `summary finally cleans on cancellation provider failure and rejected attempt`() = runTest {
        for (failure in listOf("cancel", "provider", "reject")) {
            val dir = root.resolve(failure)
            TestRunHistory.create(dir, "s", durability).use { h ->
                val provider = object : ModelProvider by ScriptProvider(emptyList()) {
                    override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                        assertEquals(1, files(workspace(dir)).size)
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
                assertTrue(files(workspace(dir)).isEmpty())
            }
        }
    }

    @Test fun `run loop spools inside owned session and cleans normally`() = runTest {
        val dir = root.resolve("session")
        val fixture = RunFixture()
        var observed = false
        TestRunHistory.create(dir, "session", durability).use { history ->
            history.append(0, listOf(message(Role.USER, textPart("private source ".repeat(2000))),
                message(Role.USER, textPart("goal"))))
            val provider = object : ModelProvider by ScriptProvider(emptyList()) {
                override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                    if (request.messages.any { m -> m.parts.any { (it as? ContentPart.Text)?.text?.contains("private source") == true } }) {
                        val spools = files(workspace(dir, "session"))
                        assertEquals(1, spools.size, "source must not be spooled in global temp")
                        assertTrue(String(Files.readAllBytes(spools.single()), Charsets.UTF_8).contains("private source"))
                        observed = true
                        assertFailsWith<IllegalStateException> { history.close() }
                        assertFailsWith<HistoryStorageException> { TestRunHistory.open(dir, durability) }
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
            assertTrue(files(workspace(dir, "session")).isEmpty())
        }
    }
}
