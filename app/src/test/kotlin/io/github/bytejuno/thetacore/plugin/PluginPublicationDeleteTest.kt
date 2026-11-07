package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual store/publication paths and the manager's tombstone/deleteStored ordering.
 * ZIP fixtures use an injected verifier; this is not Android signature acceptance.
 */
class PluginPublicationDeleteTest {
    private val id = "test.publicationDelete"
    private val signer = PluginSigners(listOf("a".repeat(64)))
    private val verifier = PluginSignatureVerifier { signer }
    private fun root(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("theta-publication-delete").toFile()
        try { action(dir) } finally { deleteFixtureRoot(dir) }
    }
    private fun bundle(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            it.write("""{"schema":1,"type":"dex","id":"$id","name":"Delete","version":"1","versionCode":1,"apiLevel":1,"entry":"test.Entry"}""".toByteArray())
            it.closeEntry()
        }
        return out.toByteArray()
    }
    private fun deleteStored(files: File, store: PluginStore, blocked: MutableSet<String>) = runBlocking {
        deletePluginRecovery(id, blocked) {
            store.delete(id, PluginStateStore(files).read(id).clearData)
            PluginUpdates(files, store).forget(id)
            PluginStateStore(files).remove(id)
        }
    }

    private fun clearDataAfterPendingPublication(base: File, committed: Boolean, restart: Boolean) {
        val live = base.resolve("live").apply { mkdirs() }
        var denied = true
        var failBeforeMove = !committed
        fun store(files: File, cleanup: PluginStageCleanup) = fixturePluginStore(files, files.resolve("cache"), verifier, cleanup,
            deleteStage = {
                if (it.parentFile.parentFile.name == "plugin-code-publication" && denied) throw IOException("temporary busy")
                deleteFixtureFile(files, it)
            }, publicationBoundary = {
                if (it == PluginPublicationBoundary.PARENT_READY && failBeforeMove) throw IOException("before move")
            }, deletePublicationIntent = {
                if (denied) throw IOException("journal busy")
                Files.delete(it.toPath())
            })
        val initialCleanup = PluginStageCleanup()
        val initial = store(live, initialCleanup)
        val stage = initial.stage(bundle().inputStream())
        val rejected = mutableListOf<String>()
        if (committed) {
            assertTrue(initial.install(stage).apk.isFile)
        } else {
            val failure = assertThrows(IOException::class.java) {
                withPluginInstallRecovery(id, { false }, initial::retainedIds, rejected,
                    cleanup = { initial.discard(stage) }) { initial.install(stage) }
            }
            assertEquals("before move", failure.message)
            assertTrue(failure.suppressed.any { it is IOException && it.message == "temporary busy" })
            assertEquals(listOf(id), rejected)
        }
        assertTrue(initialCleanup.hasPending)
        val data = live.resolve("plugin-data/$id/value").apply { parentFile.mkdirs(); writeText("retain until cleanup") }
        val states = PluginStateStore(live)
        // uninstallRejected writes a fresh tombstone; ordinary uninstall persists desired state.
        if (committed) persistPluginUninstall(states, id, true)
        else states.write(id, PluginState(enabled = false, pendingDelete = true, clearData = true))
        val files = if (restart) base.resolve("cold").also { check(live.copyRecursively(it)) } else live
        val cleanup = if (restart) PluginStageCleanup() else initialCleanup
        val deleting = if (restart) store(files, cleanup) else initial
        if (restart) assertThrows(IOException::class.java) { deleting.cleanupColdStages() }
        val blocked = mutableSetOf(id)
        assertThrows(IOException::class.java) { deleteStored(files, deleting, blocked) }
        assertEquals(signer, PluginTrustStore(files).read(id))
        assertTrue(PluginStateStore(files).read(id).pendingDelete)
        assertTrue(id in blocked)
        assertTrue(files.resolve("plugin-code-publication/intent.properties").isFile)
        assertEquals("retain until cleanup", files.resolve(data.relativeTo(live)).readText())
        assertTrue(cleanup.hasPending)
        failBeforeMove = false
        denied = false
        deleteStored(files, deleting, blocked)
        assertFalse(id in blocked)
        assertFalse(files.resolve("plugins/$id").exists())
        assertFalse(files.resolve("plugin-data/$id").exists())
        assertFalse(files.resolve("plugin-code-publication/intent.properties").exists())
        assertNull(PluginTrustStore(files).read(id))
        assertTrue(PluginStateStore(files).ids().isEmpty())
        assertFalse(cleanup.hasPending) // exact publication ticket was consumed, not merely its journal
        // Both the current store and a new process can import again after clear-data.
        assertTrue(deleting.install(deleting.stage(bundle().inputStream())).apk.isFile)
        val cold = fixturePluginStore(files, files.resolve("cache"), verifier)
        cold.cleanupColdStages()
        assertEquals(listOf(1L), cold.versions(id))
    }

    @Test fun rejectedClearDataRetriesFailedTemporaryCleanupInSameProcess() = root {
        clearDataAfterPendingPublication(it, committed = false, restart = false)
    }
    @Test fun rejectedClearDataRetriesFailedTemporaryCleanupAfterRestart() = root {
        clearDataAfterPendingPublication(it, committed = false, restart = true)
    }
    @Test fun ordinaryClearDataRetriesCommittedJournalCleanupInSameProcess() = root {
        clearDataAfterPendingPublication(it, committed = true, restart = false)
    }
    @Test fun ordinaryClearDataRetriesCommittedJournalCleanupAfterRestart() = root {
        clearDataAfterPendingPublication(it, committed = true, restart = true)
    }

    @Test fun deletingAnotherIdDoesNotConsumePendingPublication() = root { files ->
        val cleanup = PluginStageCleanup()
        val store = fixturePluginStore(files, files.resolve("cache"), verifier, cleanup,
            publicationBoundary = { if (it == PluginPublicationBoundary.INTENT) throw IOException("before temporary") },
            deletePublicationIntent = { throw IOException("journal busy") })
        assertThrows(IOException::class.java) { store.install(store.stage(bundle().inputStream())) }
        val intent = files.resolve("plugin-code-publication/intent.properties")
        val bytes = intent.readBytes()
        val other = "test.other"
        PluginTrustStore(files).bindConfirmed(other, signer)
        store.delete(other, clearData = true)
        assertNull(PluginTrustStore(files).read(other))
        assertEquals(signer, PluginTrustStore(files).read(id))
        assertArrayEquals(bytes, intent.readBytes())
        assertTrue(cleanup.hasPending)
    }

    @Test fun clearDataCannotUsePendingIntentToDeleteMixedOrUnownedTrees() = root { files ->
        val store = fixturePluginStore(files, files.resolve("cache"), verifier,
            deleteStage = { if (it.parentFile.parentFile.name == "plugin-code-publication") throw IOException("temporary busy")
                deleteFixtureFile(files, it) },
            publicationBoundary = { if (it == PluginPublicationBoundary.PARENT_READY) throw IOException("before move") })
        assertThrows(IOException::class.java) { store.install(store.stage(bundle().inputStream())) }
        val unknown = files.resolve("plugins/$id/9/unknown").apply { parentFile.mkdirs(); writeText("keep") }
        assertThrows(PluginPackageException::class.java) { store.delete(id, true) }
        assertEquals("keep", unknown.readText())
        assertEquals(signer, PluginTrustStore(files).read(id))
        assertTrue(files.resolve("plugin-code-publication/intent.properties").isFile)
        Files.delete(files.resolve("plugin-trust/$id.signers").toPath())
        assertThrows(PluginPackageException::class.java) { store.delete(id, true) }
        assertEquals("keep", unknown.readText())
    }
}
