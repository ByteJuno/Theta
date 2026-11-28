package io.github.bytejuno.thetacore.root

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class RootReceiptTest {
    /** A dead su client with fully closed pipes does not prove daemon-owned children exited. */
    private class DeadClient : Process() {
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 1
        override fun exitValue() = 1
        override fun destroy() = Unit
    }

    @Test fun missingOrInvalidReceiptsNeverProveExitOrNoEffect() {
        val directory = Files.createTempDirectory("theta-root-receipt-").toFile()
        val process = SuRootProcess(DeadClient(), directory)
        try {
            assertFalse(process.actualExit)
            assertTrue(process.commandStarted)
            directory.resolve("clean").writeText("")
            assertFalse(process.actualExit)
            directory.resolve("clean").writeText("garbage")
            assertFalse(process.actualExit)
            directory.resolve("clean").delete()
            directory.delete()
            assertFalse(process.actualExit)
            assertTrue(process.commandStarted)
        } finally { directory.deleteRecursively() }
    }

    @Test fun lostCodeCannotTurnAnExecutedCommandIntoNoEffect() {
        val directory = Files.createTempDirectory("theta-root-receipt-").toFile()
        val process = SuRootProcess(DeadClient(), directory)
        try {
            directory.resolve("ready").writeText("ready")
            directory.resolve("clean").writeText("clean")
            assertTrue(process.commandStarted)
            assertEquals(RootOutcome.START_FAILED, process.outcome)
            directory.resolve("code").mkdir()
            assertNull(process.exitCode)
        } finally { directory.deleteRecursively() }
    }

    @Test fun unusableScratchDirectoryFailsBeforeInvokingSu() {
        val scratch = Files.createTempFile("theta-root-invalid-", ".file").toFile()
        try {
            assertThrows(java.io.IOException::class.java) { SuRootBackend(scratch).start("id") }
        } finally { scratch.delete() }
    }

    @Test fun explicitRejectionIsRequiredForKnownNonExecution() {
        val directory = Files.createTempDirectory("theta-root-rejected-").toFile()
        val process = SuRootProcess(DeadClient(), directory)
        try {
            directory.resolve("not-started").writeText("not-started")
            directory.resolve("rejected").writeText("denied")
            assertFalse(process.commandStarted)
            assertEquals(RootOutcome.DENIED, process.outcome)
            directory.resolve("rejected").writeText("start_failed")
            assertEquals(RootOutcome.START_FAILED, process.outcome)
        } finally { directory.deleteRecursively() }
    }
}
