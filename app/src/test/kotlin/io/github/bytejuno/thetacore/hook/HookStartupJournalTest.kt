package io.github.bytejuno.thetacore.hook

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HookStartupJournalTest {
    @Test fun thirdIncompleteStartupMakesNextAdmissionSkip() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val key = "a".repeat(64)
            repeat(3) { assertTrue(HookStartupJournal(root, key).begin()) }
            assertFalse(HookStartupJournal(root, key).begin())
            assertTrue(HookStartupJournal(root, "b".repeat(64)).begin())
        } finally { root.deleteRecursively() }
    }
    @Test fun stableStartupResetsAndCallbackQuarantinePersists() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val journal = HookStartupJournal(root, "a".repeat(64))
            repeat(5) { assertTrue(journal.begin()); journal.stable() }
            journal.quarantine()
            assertFalse(journal.begin())
        } finally { root.deleteRecursively() }
    }
    @Test fun quarantineThenStableCannotClearFailure() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val key = "a".repeat(64)
            val journal = HookStartupJournal(root, key)
            assertTrue(journal.begin())
            journal.quarantine()
            journal.stable()
            assertFalse(journal.begin())
            val reopened = HookStartupJournal(root, key)
            reopened.stable() // A non-admitted instance cannot reset persisted quarantine.
            assertFalse(reopened.begin())
        } finally { root.deleteRecursively() }
    }
    @Test fun stableThenQuarantineRemainsQuarantined() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val key = "a".repeat(64)
            val journal = HookStartupJournal(root, key)
            assertTrue(journal.begin())
            journal.stable()
            journal.quarantine()
            journal.stable()
            assertFalse(HookStartupJournal(root, key).begin())
        } finally { root.deleteRecursively() }
    }
    @Test fun concurrentStableAndQuarantineAlwaysPersistQuarantine() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(32) { index ->
                val key = index.toString(16).padStart(64, '0')
                val journal = HookStartupJournal(root, key)
                assertTrue(journal.begin())
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)
                val results = listOf<() -> Unit>({ journal.stable() }, { journal.quarantine() }).map { operation ->
                    workers.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        operation()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                results.forEach { it.get(5, TimeUnit.SECONDS) }
                assertFalse(HookStartupJournal(root, key).begin())
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
    @Test fun thirdAdmittedStartupCanStillBecomeStable() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val key = "a".repeat(64)
            repeat(2) { assertTrue(HookStartupJournal(root, key).begin()) }
            val third = HookStartupJournal(root, key)
            assertTrue(third.begin())
            third.stable()
            assertTrue(HookStartupJournal(root, key).begin())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedQuarantineWriteCannotBeClearedByStable() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val key = "a".repeat(64)
            val journal = HookStartupJournal(root, key)
            assertTrue(journal.begin())
            val pending = root.resolve("$key.pending")
            assertTrue(pending.mkdir())
            assertThrows(java.io.IOException::class.java) { journal.quarantine() }
            assertTrue(pending.delete())
            journal.stable()
            assertEquals("1", root.resolve("$key.state").readText())
            assertFalse(journal.begin())
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedWriteDoesNotAdmit() {
        val root = Files.createTempDirectory("hook-journal").toFile()
        try {
            val key = "a".repeat(64)
            root.resolve("$key.pending").writeText("1")
            assertThrows(IllegalArgumentException::class.java) { HookStartupJournal(root, key).begin() }
        } finally { root.deleteRecursively() }
    }
}
