package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.Durability
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class FileRunAccountingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val durability = Durability.PROCESS_CRASH_ONLY
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun path(dir: Path, chain: String) = dir.resolve("accounting/${hash(chain)}.json")
    private fun value(chain: String, steps: Int = 3, retries: Int = 2) = RunAccounting("session", chain, "run", steps, retries)
    private fun legacy(dir: Path, value: RunAccounting) {
        val payload = HistoryCodec.json.encodeToString(value)
        Files.writeString(dir.resolve("accounting.json"), HistoryCodec.json.encodeToString(CheckedHistoryValue(payload, hash(payload))))
    }

    @Test fun `hashed paths bind exact chain and session and writes stay bounded`() {
        val dir = temporary.root.toPath().resolve("h")
        FileRunHistory.create(dir, "session", durability).use { h ->
            for (chain in listOf("../A", "A", "a", "链", "B")) h.saveAccounting(value(chain))
            assertNull(h.loadAccounting("missing"))
            for (chain in listOf("../A", "A", "a", "链", "B")) assertEquals(value(chain), h.loadAccounting(chain))
            val before = h.io()
            h.saveAccounting(value("A", 1, 5))
            assertEquals(value("A", 3, 5), h.loadAccounting("A"))
            assertEquals(1, h.io().filesWritten - before.filesWritten)
            assertTrue(h.io().bytesWritten - before.bytesWritten < 1024)
            assertEquals(0, h.io().messagesDecoded - before.messagesDecoded)
            assertFailsWith<IllegalArgumentException> { h.saveAccounting(value("bad\uD800")) }
            assertFailsWith<IllegalArgumentException> { h.saveAccounting(value("A").copy(sessionId = "other")) }
            Files.copy(path(dir, "B"), path(dir, "A"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFailsWith<IllegalArgumentException> { h.loadAccounting("A") }
            assertFailsWith<IllegalArgumentException> { h.saveAccounting(value("A", 20, 20)) }
            assertEquals(value("B"), h.loadAccounting("B"))
        }
        // A well-checksummed wrong-session value cannot be imported through migration either.
        legacy(dir, value("C").copy(sessionId = "other"))
        assertFailsWith<HistoryStorageException> { FileRunHistory.open(dir, durability) }
        assertTrue(Files.exists(dir.resolve("accounting.json")))
    }

    @Test fun `every counter write failure reopens to old or new complete value without harming other chains`() {
        for (stage in HistoryWriteStage.entries) {
            val dir = temporary.root.toPath().resolve(stage.name)
            FileRunHistory.create(dir, "session", durability).use { h -> h.saveAccounting(value("A")); h.saveAccounting(value("B", 7, 6)) }
            FileRunHistory.open(dir, durability, fault = {
                if (it.file == "accounting/${hash("A")}.json" && it.stage == stage) error("injected $stage")
            }).use { h ->
                assertFailsWith<HistoryStorageException> { h.saveAccounting(value("A", 4, 3)) }
                assertFailsWith<IllegalStateException> { h.loadAccounting("B") }
            }
            // Simulate a process leaving an incomplete temp: opening must discard it, never import it.
            Files.writeString(path(dir, "A").resolveSibling(".${hash("A")}.json.tmp"), "partial")
            FileRunHistory.open(dir, durability).use { h ->
                val committed = stage in setOf(HistoryWriteStage.AFTER_REPLACE, HistoryWriteStage.AFTER_DIRECTORY_SYNC)
                assertEquals(if (committed) value("A", 4, 3) else value("A"), h.loadAccounting("A"))
                assertEquals(value("B", 7, 6), h.loadAccounting("B"))
                h.saveAccounting(value("A", 1, 0))
                assertEquals(if (committed) value("A", 4, 3) else value("A"), h.loadAccounting("A"))
            }
        }
    }

    @Test fun `legacy slot migrates monotonically and every failed migration is restartable`() {
        for (stage in HistoryWriteStage.entries) {
            val dir = temporary.root.toPath().resolve("migration-$stage")
            FileRunHistory.create(dir, "session", durability).use { h -> h.saveAccounting(value("A", 5, 1)); h.saveAccounting(value("B", 8, 7)) }
            legacy(dir, value("A", 3, 4))
            assertFailsWith<HistoryStorageException> {
                FileRunHistory.open(dir, durability, fault = {
                    if (it.file == "accounting/${hash("A")}.json" && it.stage == stage) error("migration $stage")
                })
            }
            assertTrue(Files.exists(dir.resolve("accounting.json")))
            FileRunHistory.open(dir, durability).use { h ->
                assertEquals(value("A", 5, 4), h.loadAccounting("A"))
                assertEquals(value("B", 8, 7), h.loadAccounting("B"))
                assertFalse(Files.exists(dir.resolve("accounting.json")))
            }
        }
        // A pre-upgrade session has no accounting directory, and only its single legacy slot.
        val dir = temporary.root.toPath().resolve("legacy-only")
        FileRunHistory.create(dir, "session", durability).close()
        Files.delete(dir.resolve("accounting")); legacy(dir, value("A"))
        FileRunHistory.open(dir, durability).use { h -> assertEquals(value("A"), h.loadAccounting("A")); h.saveAccounting(value("B")) }
        FileRunHistory.open(dir, durability).use { h -> assertEquals(value("A"), h.loadAccounting("A")); assertEquals(value("B"), h.loadAccounting("B")) }
    }

    @Test fun `corrupt finals or legacy slots fail closed rather than resetting counts`() {
        for (old in listOf(false, true)) {
            val dir = temporary.root.toPath().resolve("corrupt-$old")
            FileRunHistory.create(dir, "session", durability).use { it.saveAccounting(value("A")) }
            val file = if (old) dir.resolve("accounting.json") else path(dir, "A")
            Files.writeString(file, "partial")
            if (old) assertFailsWith<HistoryStorageException> { FileRunHistory.open(dir, durability) }
            else FileRunHistory.open(dir, durability).use { h ->
                assertFailsWith<HistoryStorageException> { h.loadAccounting("A") }
                assertFailsWith<HistoryStorageException> { h.saveAccounting(value("A", 0, 0)) }
                assertNull(h.loadAccounting("B"))
            }
            assertEquals("partial", Files.readString(file))
        }
    }
}
