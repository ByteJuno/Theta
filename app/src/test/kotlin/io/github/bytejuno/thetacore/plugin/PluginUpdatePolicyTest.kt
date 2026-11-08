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

/** Exact-install consent and data-preserving rollback; no manager/UI dependencies. */
class PluginUpdatePolicyTest {
    private val id = "test.policy"
    private val signers = PluginSigners(listOf("a".repeat(64)))

    private inner class Fixture(val root: File) {
        var clock = 1_000L
        var copyFails = false
        var copies = 0
        val badVersions = mutableSetOf<String>()
        val reports = mutableListOf<String>()
        val store = fixturePluginStore(root, root.resolve("cache"), object : PluginSignatureVerifier {
            override fun verify(file: File): PluginSigners {
                if (file.parentFile.name in badVersions) throw PluginPackageException("Bad signer")
                return signers
            }
        })
        fun coldUpdates() = PluginUpdates(root, store, { clock }) { source, target ->
            copies++
            if (copyFails) throw IOException("No snapshot space")
            copyPluginTree(source, target)
        }
        val updates = coldUpdates()
        val data = root.resolve("plugin-data/$id/value.txt")
        val snapshot = root.resolve("plugin-snapshots/$id/1/value.txt")
        val record = root.resolve("plugin-updates/$id.properties")
        val policy = root.resolve("plugin-updates/install-intents/$id.properties")

        fun publish(version: Long) = store.install(store.stage(ByteArrayInputStream(apk(version))))
        fun save(value: String) { data.parentFile.mkdirs(); data.writeText(value) }
        fun recover(interrupted: Boolean = false) = updates.recover(id, interrupted, false, reports::add)
        fun initial() { publish(1); save("old") }
        fun optedUpdate(): PluginUpdateInstallIntent {
            initial()
            val intent = updates.prepareInstall(id, 1, 2, allowCodeOnlyRollback = true)
            publish(2)
            updates.commitInstall(intent)
            recover()
            save("new schema")
            return intent
        }
        fun staleSnapshot() { snapshot.parentFile.mkdirs(); snapshot.writeText("stale data") }
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("theta-update-policy").toFile()
        try { Fixture(root).block() } finally { deleteFixtureRoot(root) }
    }

    private fun apk(version: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write(
                """{"schema":1,"type":"dex","id":"$id","name":"Policy","version":"$version","versionCode":$version,"apiLevel":1,"entry":"test.Entry"}""".toByteArray(),
            )
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun defaultStillCancelsOnSnapshotFailure() = fixture {
        initial()
        val intent = updates.prepareInstall(id, 1, 2)
        publish(2)
        updates.commitInstall(intent)
        copyFails = true
        assertFalse(recover())
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
        assertEquals("old", data.readText())
        assertTrue(reports.single().contains("cancelled"))
        assertFalse(policy.exists())
    }

    @Test fun explicitCommittedVersionOptInSkipsSnapshotEvenWhenItWouldFail() = fixture {
        initial()
        val intent = updates.prepareInstall(id, 1, 2, true)
        publish(2)
        updates.commitInstall(intent)
        copyFails = true
        assertFalse(coldUpdates().recover(id, false, false, reports::add))
        assertEquals(PluginUpdate(1, 2, dataRestorable = false), updates.read(id))
        assertEquals(0, copies)
        assertEquals(listOf(1L, 2L), store.versions(id))
        assertFalse(policy.exists())
    }

    @Test fun preparedButUncommittedOptInCannotAuthorizeCodeOnlyActivation() = fixture {
        initial()
        updates.prepareInstall(id, 1, 2, true)
        publish(2) // Crash after publication but before commit.
        copyFails = true
        coldUpdates().recover(id, false, false, reports::add)
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
        assertFalse(policy.exists())
    }

    @Test fun uncommittedPublicationWithWorkingSnapshotRemainsDataRestorable() = fixture {
        initial()
        updates.prepareInstall(id, 1, 2, true)
        publish(2)
        recover()
        assertTrue(updates.read(id)!!.dataRestorable)
        assertEquals("old", snapshot.readText())
    }

    @Test fun invalidVersionsAndPrematureCommitAreRejected() = fixture {
        initial()
        for ((from, to) in listOf(0L to 2L, 1L to 1L, 1L to -1L, 2L to 3L)) {
            assertThrows(PluginPackageException::class.java) { updates.prepareInstall(id, from, to, true) }
        }
        assertFalse(policy.exists())
        val intent = updates.prepareInstall(id, 1, 2, true)
        assertThrows(PluginPackageException::class.java) { updates.commitInstall(intent) }
        assertThrows(PluginPackageException::class.java) { updates.commitInstall(intent.copy(to = 3)) }
        assertThrows(PluginPackageException::class.java) { updates.commitInstall(intent.copy(dataRestorable = true)) }
        updates.cancelInstall(intent)
        assertFalse(policy.exists())
    }

    @Test fun rejectedPreparationPreservesPriorCommittedPolicyBytes() = fixture {
        initial()
        val pending = updates.prepareInstall(id, 1, 2, true)
        publish(2)
        updates.commitInstall(pending)
        val before = policy.readBytes()
        for ((from, to) in listOf(2L to 2L, 2L to 1L, 1L to 3L, 2L to 3L)) {
            // 2 -> 3 must also reject: v2 is published but has never been activated.
            assertThrows(PluginPackageException::class.java) { updates.prepareInstall(id, from, to) }
            assertArrayEquals(before, policy.readBytes())
        }
        recover()
        assertFalse(updates.read(id)!!.dataRestorable)
    }

    @Test fun failedInstallCancellationAndFreshDefaultAttemptCannotReuseConsent() = fixture {
        initial()
        val failed = updates.prepareInstall(id, 1, 2, true)
        updates.cancelInstall(failed) // Store failed before publication.
        updates.cancelInstall(failed) // Cleanup is idempotent.
        val retry = updates.prepareInstall(id, 1, 2)
        assertThrows(PluginPackageException::class.java) { updates.cancelInstall(failed) }
        assertThrows(PluginPackageException::class.java) { updates.commitInstall(failed) }
        publish(2)
        updates.commitInstall(retry)
        copyFails = true
        recover()
        assertEquals(listOf(1L), store.versions(id))
    }

    @Test fun newAttemptReplacesAbandonedConsentEvenWithoutFailureCleanup() = fixture {
        initial()
        updates.prepareInstall(id, 1, 2, true) // Previous process died before store.install.
        val retry = coldUpdates().prepareInstall(id, 1, 2)
        publish(2)
        updates.commitInstall(retry)
        copyFails = true
        recover()
        assertEquals(listOf(1L), store.versions(id))
    }

    @Test fun validButMismatchedPolicyNeverSkipsSnapshot() = fixture {
        initial()
        val intent = updates.prepareInstall(id, 1, 2, true)
        publish(2)
        updates.commitInstall(intent)
        publish(3) // Consent was for 1 -> 2, not the actual 2 -> 3 transition.
        copyFails = true
        recover()
        assertEquals(listOf(1L, 2L), store.versions(id))
        assertNull(updates.read(id))
        assertFalse(policy.exists())
    }

    @Test fun signerVerificationIsRequiredAtCommitAndActivation() = fixture {
        initial()
        val intent = updates.prepareInstall(id, 1, 2, true)
        publish(2)
        badVersions += "2"
        assertThrows(PluginPackageException::class.java) { updates.commitInstall(intent) }
        badVersions.clear()
        updates.commitInstall(intent)
        badVersions += "2"
        recover()
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
        assertFalse(policy.exists())
    }

    @Test fun codeOnlyActivationDiscardsExistingSnapshotWithoutCopyingIt() = fixture {
        initial()
        staleSnapshot()
        val intent = updates.prepareInstall(id, 1, 2, true)
        publish(2)
        updates.commitInstall(intent)
        recover()
        assertFalse(snapshot.exists())
        assertEquals(0, copies)
        assertEquals("old", data.readText())
        assertFalse(updates.read(id)!!.dataRestorable)
    }

    @Test fun codeOnlyRollbackStillVerifiesOldSignerBeforeDeletingNewCode() = fixture {
        optedUpdate()
        updates.requestRollback(id)
        badVersions += "1"
        assertThrows(PluginPackageException::class.java) { recover() }
        assertEquals(UpdatePhase.ROLLBACK, updates.read(id)!!.phase)
        assertEquals(listOf(1L, 2L), store.versions(id))
        assertEquals("new schema", data.readText())
        badVersions.clear()
        assertTrue(recover())
        assertEquals("new schema", data.readText())
    }

    @Test fun manualCodeOnlyRollbackPreservesChangedDataAndIgnoresStaleSnapshot() = fixture {
        optedUpdate()
        updates.started(id, 2)
        staleSnapshot() // Simulate leftover snapshot after interrupted cleanup.
        updates.requestRollback(id)
        assertTrue(recover())
        assertEquals("new schema", data.readText())
        assertEquals(listOf(1L), store.versions(id))
        assertEquals(UpdatePhase.RECOVERED, updates.read(id)!!.phase)
        assertFalse(updates.read(id)!!.dataRestorable)
        updates.acknowledgeRecovery(id)
        assertFalse(snapshot.exists())
        assertEquals("new schema", data.readText())
    }

    @Test fun failedFirstStartCodeOnlyRollbackPreservesChangedData() = fixture {
        optedUpdate()
        staleSnapshot()
        assertTrue(updates.failed(id, 2))
        assertEquals("new schema", data.readText())
        assertTrue(coldUpdates().recover(id, false, false, reports::add))
        assertEquals("new schema", data.readText())
        assertEquals(listOf(1L), store.versions(id))
    }

    @Test fun interruptedFirstStartAndReceiptReplayNeverTouchData() = fixture {
        optedUpdate()
        staleSnapshot()
        assertTrue(coldUpdates().recover(id, true, false, reports::add))
        assertEquals("new schema", data.readText())
        save("written after recovery")
        assertTrue(coldUpdates().recover(id, true, false, reports::add))
        assertEquals("written after recovery", data.readText())
        updates.acknowledgeRecovery(id)
        assertFalse(recover())
    }

    @Test fun rollbackReplayAfterPackageDeletionKeepsData() = fixture {
        optedUpdate()
        updates.requestRollback(id)
        store.deleteVersion(id, 2) // Crash after code deletion, before RECOVERED receipt.
        staleSnapshot()
        assertTrue(coldUpdates().recover(id, false, false, reports::add))
        assertEquals("new schema", data.readText())
        assertEquals(UpdatePhase.RECOVERED, updates.read(id)!!.phase)
    }

    @Test fun recoveryStateWriteFailureRetainsCodeOnlyReceiptAndEnabledIntent() = fixture {
        optedUpdate()
        val states = PluginStateStore(root)
        val disabled = PluginState(enabled = false, initializing = true, resumeAfterRollback = true)
        states.write(id, disabled)
        assertThrows(IOException::class.java) {
            recoverPluginUpdate(states, updates, id, disabled, false, reports::add) { _, _ ->
                throw IOException("state disk failure")
            }
        }
        assertEquals(UpdatePhase.RECOVERED, updates.read(id)!!.phase)
        assertFalse(updates.read(id)!!.dataRestorable)
        assertEquals("new schema", data.readText())
        assertTrue(recoverPluginUpdate(states, coldUpdates(), id, states.read(id), false, reports::add))
        assertEquals(PluginState(), states.read(id))
        assertEquals("new schema", data.readText())
        assertNull(updates.read(id))
    }

    @Test fun replayAfterRecordCommitBeforeIntentRetirementDoesNotResetClockOrMode() = fixture {
        initial()
        val intent = updates.prepareInstall(id, 1, 2, true)
        publish(2)
        updates.commitInstall(intent)
        val pendingBytes = policy.readBytes()
        recover()
        updates.started(id, 2)
        policy.writeBytes(pendingBytes) // Crash cutpoint: committed record plus old intent.
        save("new schema")
        staleSnapshot()
        clock += 100
        coldUpdates().recover(id, false, false, reports::add)
        updates.started(id, 2)
        assertEquals(1_000L, updates.observation(id)!!.startedAt)
        assertFalse(updates.observation(id)!!.dataRestorable)
        assertFalse(policy.exists())
        updates.requestRollback(id)
        assertTrue(recover())
        assertEquals("new schema", data.readText())
    }

    @Test fun legacyRecordDefaultsToRestorableAndRestoresSnapshot() = fixture {
        initial()
        publish(2)
        recover()
        save("new")
        record.writeText("from=1\nto=2\nphase=ROLLBACK\n")
        assertTrue(updates.read(id)!!.dataRestorable)
        assertTrue(recover())
        assertEquals("old", data.readText())
    }

    @Test fun malformedUpdateModeFailsClosedAndRetainsRecoveryOwner() = fixture {
        optedUpdate()
        for (value in listOf("TRUE", "False", "yes", "", "0")) {
            record.writeText("from=1\nto=2\nphase=ROLLBACK\ndataRestorable=$value\n")
            val original = record.readText()
            assertThrows(IOException::class.java) { recover(true) }
            assertEquals(original, record.readText())
            assertEquals(listOf(1L, 2L), store.versions(id))
            assertEquals("new schema", data.readText())
        }
    }

    @Test fun malformedInstallPolicyFailsClosedBeforeRollbackAndCannotBeOverwritten() = fixture {
        initial()
        publish(2)
        recover()
        updates.started(id, 2)
        val validIntent = updates.prepareInstall(id, 2, 3, true)
        val validPolicy = policy.readText()
        val corruptions = listOf(
            validPolicy.replace("dataRestorable=false", "dataRestorable=FALSE"),
            validPolicy.replace("committed=false", "committed=yes"),
            validPolicy.replace("from=2", "from=-1"),
            validPolicy.replace("to=3", "to=2"),
            validPolicy.replace("token=${validIntent.token}", "token=not-a-token"),
            validPolicy.lineSequence().filterNot { it.startsWith("dataRestorable=") }.joinToString("\n"),
            "$validPolicy\nunknown=true\n",
        )
        for (corrupt in corruptions) {
            policy.writeText(corrupt)
            assertThrows(IOException::class.java) { recover(true) }
            assertThrows(IOException::class.java) { updates.prepareInstall(id, 2, 3) }
            assertEquals(corrupt, policy.readText())
            assertEquals(PluginUpdate(1, 2, startedAt = 1_000L), updates.read(id))
            assertEquals(listOf(1L, 2L), store.versions(id))
            assertTrue(snapshot.exists())
        }
        updates.requestRollback(id)
        assertThrows(IOException::class.java) { recover() }
        assertEquals(UpdatePhase.ROLLBACK, updates.read(id)!!.phase)
        assertEquals(listOf(1L, 2L), store.versions(id))
        // Clear-data recovery/uninstall must not need to parse the corrupt policy.
        store.delete(id, true)
        updates.forget(id)
        assertFalse(data.exists())
        assertFalse(policy.exists())
        assertFalse(record.exists())
    }

    @Test fun codeOnlyConsentDoesNotCarryToNextVersion() = fixture {
        optedUpdate()
        updates.started(id, 2)
        clock += 100
        publish(3)
        recover()
        assertEquals(PluginUpdate(2, 3), updates.read(id))
        assertEquals("new schema", root.resolve("plugin-snapshots/$id/2/value.txt").readText())
        assertEquals(listOf(2L, 3L), store.versions(id))
        assertNull(updates.observation(id)!!.startedAt)
    }

    @Test fun newCodeOnlyUpdateRetiresPreviousSnapshotAndStartsItsOwnObservation() = fixture {
        initial()
        publish(2)
        recover()
        updates.started(id, 2)
        save("version two")
        val intent = updates.prepareInstall(id, 2, 3, true)
        publish(3)
        updates.commitInstall(intent)
        recover()
        assertEquals(PluginUpdate(2, 3, dataRestorable = false), updates.read(id))
        assertFalse(snapshot.exists())
        save("version three")
        assertTrue(updates.failed(id, 3))
        assertTrue(recover())
        assertEquals(listOf(2L), store.versions(id))
        assertEquals("version three", data.readText())
    }

    @Test fun failedLaterSnapshotLeavesPreviousCodeOnlyRoundAndClockUntouched() = fixture {
        optedUpdate()
        updates.started(id, 2)
        publish(3)
        copyFails = true
        clock += 500
        recover()
        assertEquals(PluginUpdate(1, 2, startedAt = 1_000L, dataRestorable = false), updates.observation(id))
        assertEquals(listOf(1L, 2L), store.versions(id))
        updates.started(id, 2)
        assertEquals(1_000L, updates.observation(id)!!.startedAt)
    }

    @Test fun codeOnlyObservationExpiresWithoutTouchingDataOrExtendingOnRead() = fixture {
        optedUpdate()
        assertNull(updates.observation(id)!!.startedAt)
        updates.started(id, 2)
        clock += UPDATE_OBSERVATION_MS
        assertEquals(1_000L, updates.observation(id)!!.startedAt)
        assertFalse(updates.canRollback(id))
        assertThrows(PluginPackageException::class.java) { updates.requestRollback(id) }
        recover()
        assertNull(updates.observation(id))
        assertEquals(listOf(2L), store.versions(id))
        assertEquals("new schema", data.readText())
    }
}
