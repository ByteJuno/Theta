package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.Durability
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class FileRunAccountingTest {
    private val durability = Durability.PROCESS_CRASH_ONLY
    private fun key(chain: String) = "accounting/" + MessageDigest.getInstance("SHA-256")
        .digest(chain.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun value(chain: String, steps: Int = 3, retries: Int = 2) = RunAccounting("session", chain, "run", steps, retries)
    @Test fun countersBindExactChainAndWritesStayBounded() {
        val dir = Files.createTempDirectory("sqlite-accounting")
        TestRunHistory.create(dir, "session", durability).use { h ->
            for (chain in listOf("../A", "A", "a", "链", "B")) h.saveAccounting(value(chain))
            assertNull(h.loadAccounting("missing"))
            for (chain in listOf("../A", "A", "a", "链", "B")) assertEquals(value(chain), h.loadAccounting(chain))
            val before = h.io()
            h.saveAccounting(value("A", 1, 5))
            assertEquals(value("A", 3, 5), h.loadAccounting("A"))
            assertEquals(1, h.io().filesWritten - before.filesWritten)
            assertTrue(h.io().bytesWritten - before.bytesWritten < 1024)
            assertEquals(0, h.io().messagesDecoded - before.messagesDecoded)
            assertFails { h.saveAccounting(value("bad\uD800")) }
            assertFails { h.saveAccounting(value("A").copy(sessionId = "other")) }
        }
    }
    @Test fun counterTransactionFaultsPreserveOtherChainsAndMonotonicity() {
        for (stage in HistoryWriteStage.entries) {
            val dir = Files.createTempDirectory("sqlite-accounting-fault")
            TestRunHistory.create(dir, "session", durability).use { h -> h.saveAccounting(value("A")); h.saveAccounting(value("B", 7, 6)) }
            TestRunHistory.open(dir, durability) {
                if (it.file == key("A") && it.stage == stage) error("injected")
            }.use { h ->
                assertFailsWith<HistoryStorageException> { h.saveAccounting(value("A", 4, 3)) }
                assertFails { h.loadAccounting("B") }
            }
            TestRunHistory.open(dir, durability).use { h ->
                val expected = if (stage == HistoryWriteStage.AFTER_COMMIT) value("A", 4, 3) else value("A")
                assertEquals(expected, h.loadAccounting("A"))
                assertEquals(value("B", 7, 6), h.loadAccounting("B"))
                h.saveAccounting(value("A", 1, 0)); assertEquals(expected, h.loadAccounting("A"))
            }
        }
    }
    @Test fun corruptCounterNeverResetsAndLegacyFilesAreNotImported() {
        val dir = Files.createTempDirectory("sqlite-accounting-corrupt")
        TestRunHistory.create(dir, "session", durability).use { it.saveAccounting(value("A")) }
        Files.write(dir.resolve("accounting.json"), "legacy evidence".toByteArray())
        TestRunHistory.corrupt(dir, key("A"))
        TestRunHistory.open(dir, durability).use { h ->
            assertFailsWith<HistoryStorageException> { h.loadAccounting("A") }
            assertFailsWith<HistoryStorageException> { h.saveAccounting(value("A", 0, 0)) }
            assertNull(h.loadAccounting("B"))
        }
        assertEquals("legacy evidence", String(Files.readAllBytes(dir.resolve("accounting.json"))))
    }
}
