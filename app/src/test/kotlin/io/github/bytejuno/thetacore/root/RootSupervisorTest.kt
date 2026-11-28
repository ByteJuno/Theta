package io.github.bytejuno.thetacore.root

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit

/** Real POSIX child/process-group tests. Android paths and UID check are adapted for Linux CI;
 * this is supervisor evidence, not su/Magisk/KernelSU/APatch device acceptance. */
class RootSupervisorTest {
    private fun script(command: String, directory: File) = rootSupervisorScript(command, directory)
        .replace("/system/bin/", "/usr/bin/")
        .replace("PATH=/system/bin:/system/xbin", "PATH=/usr/bin:/bin")
        .replace("!= 0", "!= \"${'$'}(/usr/bin/id -u)\"")

    private fun launch(command: String, directory: File, detachedOutput: Boolean = false): Process =
        ProcessBuilder("/bin/sh", "-c", script(command, directory)).apply {
            environment().clear(); environment()["PATH"] = "/usr/bin:/bin"
            if (detachedOutput) { redirectOutput(File("/dev/null")); redirectError(File("/dev/null")) }
        }.start()

    private suspend fun awaitFile(file: File) = withTimeout(5_000) {
        while (!file.isFile || file.length() == 0L) delay(20)
    }

    private fun killGroup(group: String) {
        ProcessBuilder("/usr/bin/kill", "-KILL", "--", "-$group").start().waitFor()
    }

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

    @Test fun longQuotedCommandPreservesOutputWithinTheAdvertisedInputLimit() = runBlocking {
        val expected = "'".repeat(16_000)
        val command = "printf '%s' \"$expected\""
        assertTrue(command.length <= 16_384)
        val result = run(command)
        assertEquals(RootOutcome.COMPLETED, result.outcome)
        assertEquals(0, result.exitCode)
        assertEquals(expected, result.stdout)
        assertFalse(result.outputTruncated)
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

    @Test fun invalidDirectoryAndFailedCriticalReceiptWritesPreventCommandLaunch() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Linux"))
        for (failure in listOf("missing-directory", "ready", "launched", "group")) {
            val parent = Files.createTempDirectory("theta-root-start-").toFile()
            val directory = parent.resolve("receipts").apply { mkdir() }
            val executed = parent.resolve("executed")
            if (failure == "missing-directory") directory.delete() else directory.resolve(failure).mkdir()
            val process = launch("printf '%s' \"${'$'}PPID\" > '${executed.absolutePath}'", directory, true)
            try {
                withTimeout(5_000) {
                    while (process.isAlive && !executed.exists()) delay(20)
                }
                assertFalse("command ran after $failure", executed.exists())
                assertFalse(process.isAlive)
            } finally {
                val group = directory.resolve("group").takeIf { it.isFile }?.readText()
                    ?: executed.takeIf { it.isFile }?.readText()
                if (group != null) killGroup(group)
                process.outputStream.close()
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
                parent.deleteRecursively()
            }
        }
    }

    @Test fun lostReceiptsAndDeadClientDoNotConfirmLiveCommandExit() = runBlocking {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Linux"))
        val directory = Files.createTempDirectory("theta-root-lost-").toFile()
        // Detached streams model su daemons whose children do not keep the client pipes open.
        val client = launch("exec sleep 30", directory, true)
        val process = SuRootProcess(client, directory)
        var group: String? = null
        try {
            awaitFile(directory.resolve("group"))
            group = directory.resolve("group").readText()
            listOf("ready", "launched", "group", "code", "stop", "clean").forEach { directory.resolve(it).delete() }
            directory.delete()
            client.destroyForcibly(); assertTrue(client.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, ProcessBuilder("/usr/bin/kill", "-0", "--", "-$group").start().waitFor())
            assertFalse(process.actualExit)
            assertTrue(process.commandStarted)
        } finally {
            if (group != null) killGroup(group)
            client.destroyForcibly(); client.waitFor(5, TimeUnit.SECONDS)
            client.outputStream.close()
            directory.deleteRecursively()
        }
    }
}
