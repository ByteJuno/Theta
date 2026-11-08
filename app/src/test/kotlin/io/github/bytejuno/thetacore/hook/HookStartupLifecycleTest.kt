package io.github.bytejuno.thetacore.hook

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HookStartupLifecycleTest {
    private val key = "a".repeat(64)

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("hook-startup-lifecycle").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private fun admitted(root: File): HookStartupLifecycle {
        val journal = HookStartupJournal(root, key)
        assertTrue(journal.begin())
        return HookStartupLifecycle(journal)
    }

    @Test fun repeatedSuccessfulInstallAndManualDisableDoNotAccumulateIncompleteStarts() = withRoot { root ->
        repeat(6) {
            val startup = admitted(root)
            startup.installed()
            startup.disable()
            // No timer: the process may be force-stopped immediately after disabling.
            assertEquals("0", root.resolve("$key.state").readText())
        }
        assertTrue(HookStartupJournal(root, key).begin())
    }

    @Test fun disableDuringInstallWaitsForSuccessfulCompletion() = withRoot { root ->
        repeat(6) {
            val startup = admitted(root)
            startup.disable()
            startup.stable() // A timer alone must not acknowledge unfinished install.
            assertEquals("1", root.resolve("$key.state").readText())
            startup.installed()
            assertEquals("0", root.resolve("$key.state").readText())
        }
    }

    @Test fun deliberateDisableCannotHideIncompleteInstall() = withRoot { root ->
        repeat(3) {
            val startup = admitted(root)
            startup.disable()
            startup.stable()
        }
        assertFalse(HookStartupJournal(root, key).begin())
    }

    @Test fun enabledInstallExitingBeforeTimerStillCountsAsIncomplete() = withRoot { root ->
        repeat(3) { admitted(root).installed() }
        assertFalse(HookStartupJournal(root, key).begin())
    }

    @Test fun successfulThirdInstallCanBeAcknowledgedByTimer() = withRoot { root ->
        repeat(2) { admitted(root).installed() }
        val startup = admitted(root)
        startup.installed()
        startup.stable()
        assertTrue(HookStartupJournal(root, key).begin())
    }

    @Test fun installFailureRemainsQuarantinedAfterDisableAndTimer() = withRoot { root ->
        val startup = admitted(root)
        startup.failure()
        startup.disable()
        startup.stable()
        assertFalse(HookStartupJournal(root, key).begin())
    }

    @Test fun disableDuringInstallCannotMaskSubsequentInstallFailure() = withRoot { root ->
        val startup = admitted(root)
        startup.disable()
        startup.failure()
        startup.installed()
        startup.stable()
        assertFalse(HookStartupJournal(root, key).begin())
    }

    @Test fun callbackFailureRemainsQuarantinedAfterDisableAndTimer() = withRoot { root ->
        val startup = admitted(root)
        startup.installed()
        startup.failure()
        startup.disable()
        startup.stable()
        assertFalse(HookStartupJournal(root, key).begin())
    }

    @Test fun inFlightCallbackFailureAfterDisableStillQuarantines() = withRoot { root ->
        val startup = admitted(root)
        startup.installed()
        startup.disable()
        startup.failure()
        startup.stable()
        assertFalse(HookStartupJournal(root, key).begin())
    }

    @Test fun concurrentCallbackFailureDisableAndTimerAlwaysQuarantine() = withRoot { root ->
        val workers = Executors.newFixedThreadPool(3)
        try {
            repeat(16) { index ->
                val runRoot = File(root, index.toString())
                val startup = admitted(runRoot)
                startup.installed()
                val ready = CountDownLatch(3)
                val start = CountDownLatch(1)
                val results = listOf<() -> Unit>(
                    { startup.failure() }, { startup.disable() }, { startup.stable() }
                ).map { operation ->
                    workers.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        operation()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                results.forEach { it.get(5, TimeUnit.SECONDS) }
                assertFalse(HookStartupJournal(runRoot, key).begin())
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
