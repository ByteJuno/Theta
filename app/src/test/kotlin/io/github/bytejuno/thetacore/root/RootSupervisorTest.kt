package io.github.bytejuno.thetacore.root

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** Real POSIX child/process-group tests. Android paths and UID check are adapted for Linux CI;
 * this is supervisor evidence, not su/Magisk/KernelSU/APatch device acceptance. */
class RootSupervisorTest {
    private suspend fun run(command: String, timeoutMs: Long = 5_000): RootResult {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Linux"))
        val directory = Files.createTempDirectory("theta-supervisor-").toFile()
        val backend = RootBackend { text ->
            val script = rootSupervisorScript(text, directory).replace("/system/bin/", "/usr/bin/")
                .replace("PATH=/system/bin:/system/xbin", "PATH=/usr/bin:/bin")
                .replace("!= 0", "!= \"${'$'}(/usr/bin/id -u)\"")
            val process = ProcessBuilder("/bin/sh", "-c", script).apply {
                environment().clear(); environment()["PATH"] = "/usr/bin:/bin"
            }.start()
            SuRootProcess(process, directory)
        }
        return withTimeout(15_000) { RootBridge(backend).execute(command, timeoutMs) }
    }

    @Test fun commandExitCodeAndBothStreamsSurviveSupervisorCleanup() = runBlocking {
        val result = run("printf hello; printf warning >&2; exit 7")
        assertEquals(RootOutcome.COMPLETED, result.outcome)
        assertEquals(7, result.exitCode)
        assertEquals("hello", result.stdout)
        assertTrue(result.stderr.contains("warning"))
        assertTrue(result.actualExit)
    }

    @Test fun timeoutKillsTheGroupAndWaitsForTheReceipt() = runBlocking {
        val result = run("sleep 30", 200)
        assertEquals(RootOutcome.TIMED_OUT, result.outcome)
        assertTrue(result.actualExit)
    }

    @Test fun realOutputReadersDrainAndBoundLargeWrites() = runBlocking {
        val result = run("head -c 2097152 /dev/zero", 5_000)
        assertEquals(0, result.exitCode)
        assertTrue(result.outputTruncated)
        assertEquals(ROOT_OUTPUT_LIMIT, result.stdout.toByteArray().size)
    }
}
