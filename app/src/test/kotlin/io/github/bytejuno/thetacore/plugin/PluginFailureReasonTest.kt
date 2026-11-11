package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PluginFailureReasonTest {
    private fun apk(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write("""{"schema":1,"type":"dex","id":"test.reason","name":"Reason","version":"1","versionCode":1,"apiLevel":1,"entry":"test.Entry"}""".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun failedCleanupBlocksNewImportWithActionableReason() {
        val cleanup = PluginStageCleanup()
        val ticket = cleanup.reserve { throw IOException("synthetic delete failure") }
        assertThrows(IOException::class.java) { cleanup.discard(ticket) }
        assertTrue(cleanup.hasPending)
        val failure = assertThrows(PluginPackageException::class.java) { cleanup.reserve {} }
        assertEquals(PluginFailureReason.IMPORT_CLEANUP_PENDING, failure.reason)
    }

    @Test fun actualDigestMismatchIsDistinctFromUnrelatedPackageFailure() {
        val root = Files.createTempDirectory("theta-reason").toFile()
        try {
            val store = fixturePluginStore(root, root.resolve("cache"), PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) })
            val stage = store.stage(apk().inputStream())
            stage.file.appendBytes(byteArrayOf(83))
            val failure = assertThrows(PluginPackageException::class.java) { store.install(stage) }
            assertEquals(PluginFailureReason.PACKAGE_DIGEST_MISMATCH, failure.reason)
            assertTrue(store.versions("test.reason").isEmpty())
            val unrelated = assertThrows(PluginPackageException::class.java) { store.install(stage) }
            assertNotEquals(PluginFailureReason.PACKAGE_DIGEST_MISMATCH, unrelated.reason)
            assertNotEquals(PluginFailureReason.PACKAGE_DIGEST_MISMATCH,
                PluginPackageException("Package changed while installing").reason)
        } finally { deleteFixtureRoot(root) }
    }

    @Test fun actualUpdatePrecheckRejectsSameAndLowerWithoutPublication() {
        val root = Files.createTempDirectory("theta-version-reason").toFile()
        try {
            val store = fixturePluginStore(root, root.resolve("cache"), PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) })
            val installed = store.install(store.stage(apk().inputStream()))
            val original = installed.apk.readBytes()
            val updates = PluginUpdates(root, store)
            for (version in listOf(1L, 0L)) {
                val failure = assertThrows(PluginPackageException::class.java) {
                    updates.prepareInstall("test.reason", 1, version)
                }
                assertEquals(PluginFailureReason.HIGHER_VERSION_REQUIRED, failure.reason)
                assertEquals(io.github.bytejuno.thetacore.R.string.failure_higher_version,
                    io.github.bytejuno.thetacore.uiFailure(failure).key)
                assertEquals(listOf(1L), store.versions("test.reason"))
                assertArrayEquals(original, installed.apk.readBytes())
                assertNull(updates.read("test.reason"))
                assertFalse(root.resolve("plugin-updates/install-intents/test.reason.properties").exists())
            }
        } finally { deleteFixtureRoot(root) }
    }

    @Test fun missingAndDeniedSourcesRetainCauseAtActualOpenBoundary() {
        for (cause in listOf(java.io.FileNotFoundException("deleted"), SecurityException("revoked"), IllegalArgumentException("invalid URI"))) {
            val failure = assertThrows(PluginPackageException::class.java) { openPluginImportStream { throw cause } }
            assertEquals(PluginFailureReason.FILE_UNAVAILABLE, failure.reason)
            assertSame(cause, failure.cause)
            assertEquals(io.github.bytejuno.thetacore.R.string.failure_file_unavailable,
                io.github.bytejuno.thetacore.uiFailure(failure).key)
        }
    }

    @Test fun sourceBoundaryDoesNotRelabelUnrelatedFailuresOrCancellation() {
        for (cause in listOf(IOException("transport"), IllegalStateException("provider bug"), java.util.concurrent.CancellationException("cancelled"), AssertionError("fatal"))) {
            assertSame(cause, assertThrows(Throwable::class.java) { openPluginImportStream { throw cause } })
        }
        val failure = assertThrows(PluginPackageException::class.java) { openPluginImportStream { null } }
        assertEquals(PluginFailureReason.FILE_UNAVAILABLE, failure.reason)
        val input = byteArrayOf(1, 2).inputStream()
        assertSame(input, openPluginImportStream { input })
        assertEquals(1, input.read())
    }

    @Test fun signerMismatchIsTypedAtTheTrustBoundary() {
        val root = Files.createTempDirectory("theta-signer-reason").toFile()
        try {
            val trust = PluginTrustStore(root)
            trust.bindConfirmed("test.reason", PluginSigners(listOf("a".repeat(64))))
            val failure = assertThrows(PluginPackageException::class.java) {
                trust.requireMatch("test.reason", PluginSigners(listOf("b".repeat(64))))
            }
            assertEquals(PluginFailureReason.SIGNER_MISMATCH, failure.reason)
        } finally { deleteFixtureRoot(root) }
    }
}
