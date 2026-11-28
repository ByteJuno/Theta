package io.github.bytejuno.thetacore.agent.memory

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class MemoryStoreTest {
    private fun database() = AgentDatabase.open(Files.createTempDirectory("memories").resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver())

    @Test fun versionEightUpgradeKeepsHistoryLedgerAndBlobBytes() {
        val file = Files.createTempDirectory("memory-upgrade").resolve(AgentDatabase.FILE_NAME)
        var records = emptyList<InvocationRecord>()
        var blob: io.github.bytejuno.thetacore.agent.ledger.BlobRef? = null
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "old-session").use {
                it.append(0, listOf(message(Role.USER, textPart("history survives"))))
                blob = SessionDocumentBlobs(db).put("old-session", "document survives")
            }
            SqliteInvocationStore.open(db).use { store ->
                val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("before")
                val input = InvocationInput(BindingValue.copyOf(callBinding("p", "r", null, 0, "w")),
                    "chain", "old-session", "run", "step", 0, "{}", null, false, Risk.READ, true, "tool")
                assertIs<Mutation.Applied>(ledger.recordInvocation(input)); records = store.records()
            }
            db.connection.execute("DROP TABLE memories"); db.connection.execute("DROP TABLE memory_settings")
            db.connection.execute("PRAGMA user_version=8")
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            assertEquals(9L, db.connection.longValue("PRAGMA user_version"))
            assertEquals("wal", db.connection.textValue("PRAGMA journal_mode"))
            assertEquals(2L, db.connection.longValue("PRAGMA synchronous"))
            SqliteInvocationStore.open(db).use { assertEquals(records, it.records()) }
            SqliteRunHistory.open(db, "old-session").use { assertEquals(1L, it.revision())
                assertEquals("history survives", (it.snapshot().messages.single().parts.single() as ContentPart.Text).text) }
            assertEquals("document survives", SessionDocumentBlobs(db).read("old-session", blob!!.id, 0)!!.second.toString(Charsets.UTF_8))
            MemoryStore(db).save("prefers Chinese", sourceSessionId = "old-session")
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            assertEquals("prefers Chinese", MemoryStore(db).search().single().content)
        }
    }

    @Test fun crudDedupLiteralSearchPagingAndRecency() = database().use { db ->
        var time = 100L
        val memories = MemoryStore(db) { time++ }
        val first = memories.save("  cafe\u0301  ", "preferences", true, "first")
        val repeated = memories.save("café", "preferences", sourceSessionId = "second")
        assertEquals(first.id, repeated.id); assertEquals(first.createdAt, repeated.createdAt)
        assertTrue(repeated.pinned); assertTrue(repeated.updatedAt > first.updatedAt)
        assertEquals("second", repeated.sourceSessionId); assertEquals(1, memories.usage().count)
        val literal = memories.save("100%_中文", "device")
        assertEquals(listOf(literal), memories.search("%_"))
        assertEquals(listOf(literal), memories.search("中文", "device"))
        assertTrue(memories.search("中文", "other").isEmpty())
        assertEquals(first.id, memories.search(limit = 1).single().id)
        assertEquals(literal.id, memories.search(limit = 1, offset = 1).single().id)
        memories.pin(first.id, false)
        assertFalse(memories.get(first.id)!!.pinned)
        val edited = memories.save("tea", "preferences", false, id = first.id)
        assertEquals("second", edited.sourceSessionId)
        assertFailsWith<IllegalArgumentException> { memories.save("100%_中文", "device", id = first.id) }
        assertTrue(memories.delete(literal.id)); assertFalse(memories.delete(literal.id))
        memories.clear(); assertEquals(MemoryUsage(0, 0), memories.usage())
        db.ready()
    }

    @Test fun originalUnicodeAndLineEndingInputStillFindsNormalizedFacts() = database().use { db ->
        val store = MemoryStore(db)
        val original = "cafe\u0301\r\nau lait"
        val originalCategory = "the\u0301"
        val entry = store.save(original, originalCategory)
        assertEquals(listOf(entry), store.search(original))
        assertEquals(listOf(entry), store.search("cafe\u0301"))
        assertEquals(listOf(entry), store.search("café"))
        assertEquals(listOf(entry), store.search(originalCategory))
        assertEquals(listOf(entry), store.search(original, originalCategory))
    }

    @Test fun invalidAndCountCapacityRejectionsNeverPoisonSharedDatabase() = database().use { db ->
        val store = MemoryStore(db)
        for (text in listOf("", " ", "x".repeat(2001), "\uD800", "x\u0000y"))
            assertFailsWith<IllegalArgumentException> { store.save(text) }
        assertFailsWith<IllegalArgumentException> { store.save("ok", "\n") }
        assertFailsWith<IllegalArgumentException> { store.search(limit = 101) }
        assertFailsWith<IllegalArgumentException> { store.search("x".repeat(201)) }
        repeat(MemoryStore.MAX_ENTRIES) { store.save("fact-$it") }
        assertFailsWith<MemoryCapacityException> { store.save("overflow") }
        store.save("fact-0", pinned = true) // Dedup remains possible at capacity.
        store.delete(store.search(limit = 1).single().id)
        store.save("replacement")
        SqliteRunHistory.create(db, "unaffected").use { it.append(0, listOf(message(Role.USER, textPart("still writable")))) }
    }

    @Test fun byteCapacityRejectsUpdateAtomicallyAndRecoversAfterDeletion() = database().use { db ->
        val store = MemoryStore(db)
        var full = false
        for (i in 0..200) {
            try { store.save("中".repeat(1990) + i) }
            catch (_: MemoryCapacityException) { full = true; break }
        }
        assertTrue(full); assertTrue(store.usage().bytes <= MemoryStore.MAX_TOTAL_BYTES)
        assertTrue(store.usage().count < MemoryStore.MAX_ENTRIES)
        store.clear(); store.save("healthy")
        assertEquals(1, store.usage().count); db.ready()
    }

    @Test fun disabledSurvivesReopenAndStillAllowsExplicitManagement() {
        val file = Files.createTempDirectory("memory-toggle").resolve(AgentDatabase.FILE_NAME)
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            val store = MemoryStore(db); store.save("retained"); store.setEnabled(false)
            assertTrue(store.promptEntries().isEmpty())
            assertFailsWith<MemoryDisabledException> { store.save("model-write", requireEnabled = true) }
            assertFailsWith<MemoryDisabledException> { store.search(requireEnabled = true) }
            assertFailsWith<MemoryDisabledException> { store.delete(store.search().single().id, requireEnabled = true) }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            val store = MemoryStore(db); assertFalse(store.enabled()); assertEquals(1, store.search().size)
            store.save("manual edit"); store.setEnabled(true); assertEquals(2, store.promptEntries().size)
        }
    }

    @Test fun promptBudgetIncludesEscapingAndNeverMutatesOriginalPrompts() = database().use { db ->
        val store = MemoryStore(db)
        store.save("Pinned fact", pinned = true)
        repeat(10) { store.save("\"\n😀".repeat(300) + it) }
        val section = MemoryPrompt.section(store.promptEntries())
        assertTrue(section.length <= MemoryPrompt.MAX_CHARS)
        assertTrue(section.contains("Pinned fact"))
        assertEquals("", MemoryPrompt.section(store.promptEntries(), 1))
        assertEquals("base\n\n$section", MemoryPrompt.append("base", section))
        store.setEnabled(false)
        assertEquals("base", MemoryPrompt.append("base", MemoryPrompt.section(store.promptEntries())))
    }
}
