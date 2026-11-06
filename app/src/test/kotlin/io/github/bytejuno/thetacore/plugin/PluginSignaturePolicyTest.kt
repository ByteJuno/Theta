package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/**
 * POLICY ONLY. ZIP fixtures and injected fakes are deliberately NOT signed APKs.
 * These tests prove trust/lifecycle decisions, NOT platform cryptographic verification,
 * v1/v2 enforcement, tamper detection by Android, or behavior across OEM devices.
 */
class PluginSignaturePolicyTest {
    private val id = "test.signature"
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val signerA = PluginSigners(listOf(a))
    private val signerB = PluginSigners(listOf(b))

    private class FakeVerifier(var signers: PluginSigners) : PluginSignatureVerifier {
        var calls = 0
        var reject = false
        val rejection = PluginPackageException("Fake verifier rejected APK (policy-only)")
        val files = mutableListOf<File>()
        override fun verify(file: File): PluginSigners {
            calls++
            files += file
            if (reject) throw rejection
            return signers
        }
    }

    private class Fixture(val root: File, val verifier: FakeVerifier) {
        val store = PluginStore(root, root.resolve("cache"), verifier)
        val trust = PluginTrustStore(root)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("theta-signature-policy").toFile()
        try { block(Fixture(root, FakeVerifier(signerA))) } finally { root.deleteRecursively() }
    }

    private fun manifest(version: Long = 1, schema: String = "1", type: String = "dex"): String =
        """{"schema":$schema,"type":"$type","id":"$id","name":"Policy","version":"$version","versionCode":$version,"apiLevel":1,"entry":"test.Entry"}"""

    private fun apk(version: Long = 1, schema: String = "1", type: String = "dex", duplicate: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write(manifest(version, schema, type).toByteArray())
            zip.closeEntry()
            if (duplicate) {
                // ZipOutputStream refuses duplicate names, so patch the equal-length
                // alternate name in both central and local headers after closing.
                zip.putNextEntry(ZipEntry("assets/theta-plugim.json"))
                zip.write(manifest(version, schema, type).toByteArray())
                zip.closeEntry()
            }
        }
        val result = bytes.toByteArray()
        if (duplicate) {
            val from = "assets/theta-plugim.json".toByteArray()
            val to = "assets/theta-plugin.json".toByteArray()
            for (offset in 0..result.size - from.size) {
                if (from.indices.all { result[offset + it] == from[it] }) to.copyInto(result, offset)
            }
        }
        return result
    }

    private fun stage(f: Fixture, version: Long = 1): StagedPlugin =
        f.store.stage(ByteArrayInputStream(apk(version)))

    private fun noPublication(f: Fixture, version: Long = 1) {
        assertFalse(f.root.resolve("plugins/$id/$version/plugin.apk").exists())
    }

    @Test fun completeCurrentSignerSetIsOrderIndependentAndImmutable() = fixture { f ->
        val original = mutableListOf(b, a)
        val signers = PluginSigners(original)
        original.clear()
        assertEquals(listOf(a, b), signers.sha256)
        assertEquals(signers, PluginSigners(listOf(a, b)))
        assertNotEquals(signers, signerA)
        f.verifier.signers = signers
        val staged = stage(f)
        f.verifier.signers = PluginSigners(listOf(a, b))
        f.store.install(staged)
        f.store.verifyForLoad(f.store.installed { fail(it) }.single())
        assertEquals(signers, PluginTrustStore(f.root).read(id))
        // Removing one signer is also a different signer SET.
        f.verifier.signers = signerA
        assertThrows(PluginPackageException::class.java) { stage(f, 2) }
        noPublication(f, 2)
        assertThrows(PluginPackageException::class.java) { PluginSigners(emptyList()) }
        assertThrows(PluginPackageException::class.java) { PluginSigners(listOf("not-a-fingerprint")) }
    }

    @Test fun stagingDoesNotBindButPublicationAndEveryLoadReverify() = fixture { f ->
        val staged = stage(f)
        assertEquals(1, f.verifier.calls)
        assertNull(f.trust.read(id))
        val installed = f.store.install(staged)
        assertEquals(2, f.verifier.calls)
        assertTrue(f.verifier.files[1].name.endsWith(".tmp"))
        assertEquals(signerA, f.trust.read(id))
        f.store.installed { fail(it) }
        assertEquals(3, f.verifier.calls)
        f.store.verifyForLoad(installed) // enable-after-restore / actual loader boundary
        assertEquals(4, f.verifier.calls)
        f.verifier.signers = signerB
        assertThrows(PluginPackageException::class.java) { f.store.verifyForLoad(installed) }
        f.verifier.signers = signerA
        f.verifier.reject = true
        assertThrows(PluginPackageException::class.java) { f.store.verifyForLoad(installed) }
        val failures = mutableListOf<String>()
        assertTrue(f.store.installed(failures::add).isEmpty())
        assertEquals(1, failures.size)
    }

    @Test fun changedSignerCannotUpdateAndCannotReinstallOverPreservedData() = fixture { f ->
        val installed = f.store.install(stage(f))
        val original = installed.apk.readBytes()
        val data = f.root.resolve("plugin-data/$id/value").apply { parentFile.mkdirs(); writeText("keep") }
        f.verifier.signers = signerB
        assertThrows(PluginPackageException::class.java) { stage(f, 2) }
        assertArrayEquals(original, installed.apk.readBytes())
        noPublication(f, 2)
        f.store.delete(id, clearData = false)
        assertEquals("keep", data.readText())
        assertEquals(signerA, f.trust.read(id))
        assertThrows(PluginPackageException::class.java) { stage(f) }
        noPublication(f)
        f.verifier.signers = signerA
        f.store.install(stage(f))
        assertEquals("keep", data.readText())
        f.store.delete(id, clearData = true)
        assertFalse(data.exists())
        assertNull(f.trust.read(id))
        f.verifier.signers = signerB
        f.store.install(stage(f))
        assertEquals(signerB, f.trust.read(id))
    }

    @Test fun missingOrCorruptBindingBlocksLoadAndUpdateWithoutAdoption() {
        for (corrupt in listOf(false, true)) fixture { f ->
            val installed = f.store.install(stage(f))
            val binding = f.root.resolve("plugin-trust/$id.signers")
            if (corrupt) binding.writeText("corrupt") else assertTrue(binding.delete())
            assertThrows(PluginPackageException::class.java) { f.store.verifyForLoad(installed) }
            val failures = mutableListOf<String>()
            val rejected = mutableListOf<String>()
            assertTrue(f.store.installed(failures::add, rejected::add).isEmpty())
            assertEquals(listOf(id), rejected)
            assertTrue(id in f.store.retainedIds())
            assertEquals(1, failures.size)
            assertThrows(PluginPackageException::class.java) { stage(f, 2) }
            noPublication(f, 2)
            if (corrupt) assertEquals("corrupt", binding.readText()) else assertFalse(binding.exists())
        }
    }

    @Test fun retainedDataWithoutBindingCannotBeAdopted() = fixture { f ->
        f.root.resolve("plugin-data/$id").mkdirs()
        assertThrows(PluginPackageException::class.java) { stage(f) }
        assertNull(f.trust.read(id))
        noPublication(f)
    }

    @Test fun legacyUnsignedInstallCannotBeAdoptedEvenByConfirmedCandidate() = fixture { f ->
        val staged = stage(f, 2)
        f.root.resolve("plugins/$id/1/plugin.apk").apply { parentFile.mkdirs(); writeBytes(apk()) }
        assertThrows(PluginPackageException::class.java) { f.store.install(staged) }
        assertNull(f.trust.read(id))
        noPublication(f, 2)
    }

    @Test fun fakeVerifierFailureAtStagingPublishesNothingAndBindsNothing() = fixture { f ->
        f.verifier.reject = true
        assertThrows(PluginPackageException::class.java) { stage(f) }
        noPublication(f)
        assertNull(f.trust.read(id))
        assertTrue(f.root.resolve("cache/plugin-staging").listFiles().orEmpty().isEmpty())
    }

    @Test fun copiedPackageRejectionRetainsBindingAndOnlySameSignerCanRetry() = fixture { f ->
        val staged = stage(f)
        f.verifier.reject = true
        assertThrows(PluginPackageException::class.java) { f.store.install(staged) }
        assertFalse(staged.file.exists())
        noPublication(f)
        assertFalse(f.root.resolve("plugins/$id/1").exists())
        assertEquals(signerA, f.trust.read(id))
        f.verifier.reject = false
        f.verifier.signers = signerB
        assertThrows(PluginPackageException::class.java) { stage(f) }
        f.verifier.signers = signerA
        f.store.install(stage(f))
    }

    // These exercise the SAME error-path helper used by PluginManager with real
    // storage. They do not instantiate the Android manager or test its dispatchers,
    // Compose observation, load gate, or uninstallRejected tombstone orchestration.
    @Test fun failedFirstPublicationIsImmediatelyRecoverableWithoutRecreatingStore() = fixture { f ->
        val rejected = mutableListOf<String>() // Already-restored manager, initially empty.
        assertTrue(f.store.retainedIds().isEmpty())
        repeat(2) {
            f.verifier.reject = false
            val staged = stage(f)
            f.verifier.reject = true
            val thrown = assertThrows(PluginPackageException::class.java) {
                withPluginInstallRecovery(id, { false }, f.store::retainedIds, rejected,
                    cleanup = { staged.file.delete() }) { f.store.install(staged) }
            }
            assertSame(f.verifier.rejection, thrown)
            assertEquals(listOf(id), rejected) // Repeated failures must not duplicate rows.
            assertEquals(signerA, f.trust.read(id))
            assertFalse(staged.file.exists())
            noPublication(f)
        }
        f.verifier.reject = false
        f.verifier.signers = signerB
        assertThrows(PluginPackageException::class.java) { stage(f) }
        assertEquals(signerA, f.trust.read(id)) // Visibility is NOT trust rollback.
        assertTrue(id in rejected)
        f.store.delete(id, clearData = true) // Explicit recovery, same store/process.
        rejected.remove(id)
        assertNull(f.trust.read(id))
        assertTrue(f.store.retainedIds().isEmpty())
        f.store.install(stage(f))
        assertEquals(signerB, f.trust.read(id))
    }

    @Test fun failuresBeforeRetentionDoNotInventRejectedIds() = fixture { f ->
        val staged = stage(f)
        val rejected = mutableListOf<String>()
        val original = IOException("state write failed before binding")
        val thrown = assertThrows(IOException::class.java) {
            withPluginInstallRecovery(id, { false }, f.store::retainedIds, rejected,
                cleanup = { staged.file.delete() }) { throw original }
        }
        assertSame(original, thrown)
        assertTrue(rejected.isEmpty())
        assertNull(f.trust.read(id))
        assertFalse(staged.file.exists())
        noPublication(f)
    }

    @Test fun managedUpdateFailureNeverBecomesRejected() = fixture { f ->
        val installed = f.store.install(stage(f))
        val originalApk = installed.apk.readBytes()
        val update = stage(f, 2)
        val rejected = mutableListOf<String>()
        f.verifier.reject = true
        val thrown = assertThrows(PluginPackageException::class.java) {
            withPluginInstallRecovery(id, { true }, f.store::retainedIds, rejected,
                cleanup = { update.file.delete() }) { f.store.install(update) }
        }
        assertSame(f.verifier.rejection, thrown)
        assertTrue(rejected.isEmpty())
        assertArrayEquals(originalApk, installed.apk.readBytes())
        assertEquals(signerA, f.trust.read(id))
        noPublication(f, 2)
    }

    @Test fun postRegistrationFailureChecksCurrentManagedState() = fixture { f ->
        val staged = stage(f)
        var managed = false
        val rejected = mutableListOf<String>()
        val original = IOException("post-registration failure")
        val thrown = assertThrows(IOException::class.java) {
            withPluginInstallRecovery(id, { managed }, f.store::retainedIds, rejected,
                cleanup = { staged.file.delete() }) {
                f.store.install(staged)
                managed = true // Registration happened AFTER the pre-install snapshot.
                throw original
            }
        }
        assertSame(original, thrown)
        assertTrue(rejected.isEmpty())
        assertEquals(signerA, f.trust.read(id))
        assertTrue(f.root.resolve("plugins/$id/1/plugin.apk").exists())
    }

    @Test fun enumerationAndManagerCleanupFailuresCannotReplacePublicationError() = fixture { f ->
        val staged = stage(f)
        f.verifier.reject = true
        val rejected = mutableListOf<String>()
        val enumeration = IOException("cannot enumerate retained IDs")
        val cleanup = SecurityException("cannot delete stage")
        val thrown = assertThrows(PluginPackageException::class.java) {
            withPluginInstallRecovery(id, { false }, { throw enumeration }, rejected,
                cleanup = { throw cleanup }) { f.store.install(staged) }
        }
        assertSame(f.verifier.rejection, thrown)
        assertEquals(listOf(enumeration, cleanup), thrown.suppressed.toList())
        assertEquals(signerA, f.trust.read(id))
        assertTrue(rejected.isEmpty()) // Enumeration failed; no invented success.
        noPublication(f)
    }

    @Test fun storeStageCleanupFailurePreservesVerifierErrorAndRecoveryVisibility() = fixture { f ->
        val staged = stage(f)
        val cleanup = SecurityException("stage deletion denied")
        val undeletable = object : File(staged.file.absolutePath) {
            override fun delete(): Boolean = throw cleanup
        }
        val candidate = StagedPlugin(undeletable, staged.manifest, staged.sha256, staged.signers)
        val rejected = mutableListOf<String>()
        f.verifier.reject = true
        val thrown = assertThrows(PluginPackageException::class.java) {
            withPluginInstallRecovery(id, { false }, f.store::retainedIds, rejected,
                cleanup = { candidate.file.delete() }) { f.store.install(candidate) }
        }
        assertSame(f.verifier.rejection, thrown)
        assertTrue(thrown.suppressed.isNotEmpty())
        assertTrue(thrown.suppressed.all { it === cleanup })
        assertEquals(listOf(id), rejected)
        assertEquals(signerA, f.trust.read(id))
        noPublication(f)
        assertTrue(staged.file.delete()) // Remove using the real File, not the test double.
    }

    @Test fun cleanupOnlyFailureRemainsVisibleWithoutRejectingManagedInstall() = fixture { f ->
        val staged = stage(f)
        val rejected = mutableListOf<String>()
        var managed = false
        val cleanup = IOException("cleanup failed after successful install")
        val thrown = assertThrows(IOException::class.java) {
            withPluginInstallRecovery(id, { managed }, f.store::retainedIds, rejected,
                cleanup = { throw cleanup }) {
                f.store.install(staged)
                managed = true
            }
        }
        assertSame(cleanup, thrown)
        assertTrue(managed)
        assertTrue(rejected.isEmpty())
        assertTrue(f.root.resolve("plugins/$id/1/plugin.apk").exists())
    }

    // Exercises the shared cleanup wrapper also used by bindConfirmed's temp-file path.
    // This is failure-composition coverage, not filesystem fault injection into fsync/move.
    @Test fun trustTemporaryCleanupPreservesOriginalBindingFailure() {
        val bindingFailure = IOException("binding sync or move failed")
        val cleanupFailure = IOException("temporary binding deletion failed")
        val thrown = assertThrows(IOException::class.java) {
            withPluginCleanup(cleanup = { throw cleanupFailure }) { throw bindingFailure }
        }
        assertSame(bindingFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
    }

    @Test fun changedCopiedSignerOrDigestOrManifestNeverPublishes() {
        for (change in listOf("signer", "digest", "manifest")) fixture { f ->
            val staged = stage(f)
            val candidate = when (change) {
                "manifest" -> StagedPlugin(staged.file, staged.manifest.copy(name = "forged"), staged.sha256, staged.signers)
                else -> staged
            }
            when (change) {
                "signer" -> f.verifier.signers = signerB
                "digest" -> staged.file.appendText("changed after confirmation")
            }
            assertThrows(PluginPackageException::class.java) { f.store.install(candidate) }
            noPublication(f)
            assertFalse(staged.file.exists())
            assertEquals(signerA, f.trust.read(id))
        }
    }

    @Test fun bindingLostBetweenStageAndPublicationFailsClosed() = fixture { f ->
        f.store.install(stage(f))
        val update = stage(f, 2)
        assertTrue(f.root.resolve("plugin-trust/$id.signers").delete())
        assertThrows(PluginPackageException::class.java) { f.store.install(update) }
        noPublication(f, 2)
        assertNull(f.trust.read(id))
    }

    @Test fun strictSchemaTypeAndDuplicateManifestNeverBecomeDataPlugins() = fixture { f ->
        for (bytes in listOf(apk(schema = "2"), apk(schema = "\"1\""), apk(type = "data"), apk(duplicate = true))) {
            assertThrows(PluginPackageException::class.java) { f.store.stage(ByteArrayInputStream(bytes)) }
            noPublication(f)
            assertNull(f.trust.read(id))
        }
        assertEquals(0, f.verifier.calls)
    }

    @Test fun pendingDeleteRecoveryRemovesTrustOnlyAfterPackageAndDataDeletion() = fixture { f ->
        f.store.install(stage(f))
        val states = PluginStateStore(f.root)
        states.write(id, PluginState(enabled = false, pendingDelete = true, clearData = true))
        // A blocked data parent makes package deletion succeed but data deletion fail.
        val outside = Files.createTempDirectory("theta-signature-outside").toFile()
        val link = f.root.resolve("plugin-data")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IOException::class.java) { f.store.delete(id, clearData = true) }
            noPublication(f)
            assertEquals(signerA, f.trust.read(id))
            assertTrue(states.read(id).pendingDelete)
            Files.delete(link.toPath())
            // Same sequence as PluginManager.restoreInternal(), after interruption.
            val recovered = states.read(id)
            PluginStore(f.root, f.root.resolve("cache"), f.verifier).delete(id, recovered.clearData)
            states.remove(id)
            assertNull(f.trust.read(id))
            assertTrue(states.ids().isEmpty())
        } finally {
            Files.deleteIfExists(link.toPath())
            outside.deleteRecursively()
        }
    }
}
