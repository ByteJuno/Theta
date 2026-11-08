package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class PluginDataCrashQuarantineTest {
    private val suspect = "test.suspect"
    private fun bundle(id: String, version: Long): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val manifest = """{"schema":1,"type":"data","id":"$id","name":"Data","version":"v$version","versionCode":$version,"apiLevel":1}"""
            for ((name, text) in listOf("theta-plugin.json" to manifest, "skills/example/SKILL.md" to "Example")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private fun install(catalog: PluginDataCatalog, id: String = suspect, version: Long = 1): SelectedPluginData {
        val stage = catalog.stage(bundle(id, version).inputStream())
        return catalog.install(stage, stage.sha256, true)
    }
    private fun root(action: (File, PluginDataCatalog) -> Unit) {
        val dir = Files.createTempDirectory("theta-data-crash-quarantine").toFile()
        try { action(dir, PluginDataCatalog(dir, 1)) } finally { dir.deleteRecursively() }
    }

    @Test fun restoreDisablesSuspectWithoutReadingMissingArchiveAndKeepsHealthyData() = root { dir, c ->
        install(c)
        install(c, "test.healthy")
        Files.delete(dir.resolve("plugins/$suspect/1/plugin.zip").toPath())
        val errors = mutableListOf<String>()
        val records = PluginDataCatalog(dir, 1).restoreInventory(errors::add, setOf(suspect))
        assertTrue(errors.isEmpty())
        val held = records.single { it.id == suspect }
        assertTrue(held.crashQuarantined)
        assertFalse(held.state.enabled)
        assertFalse(PluginDataStateStore(dir).read(suspect)!!.enabled)
        assertNull(held.selection)
        assertNull(held.error) // Missing bytes were not validated during quarantine.
        val card = ManagedDataRecord(held, null)
        assertTrue(card.canEnable)
        assertTrue(card.status.contains("Crash quarantined"))
        assertTrue(records.single { it.id == "test.healthy" }.selection!!.active(false))
    }

    @Test fun managementQuarantineRevokesPageEvenWhenSelectionIsAccidentallyRetained() = root { _, c ->
        val selected = install(c)
        val oldPage = ManagedPluginData(selected, false)
        val pages = mutableListOf(oldPage)
        val inventory = mutableListOf<ManagedDataRecord>()
        val held = c.managementRecord(suspect, crashQuarantined = true)
        replaceDataInventory(inventory, pages, held.copy(selection = selected), false)
        assertTrue(pages.isEmpty())
        assertNull(inventory.single().page)
        assertFalse(isManagedDataPageActive(pages, oldPage))
    }

    @Test fun explicitEnableValidatesAndOnlyUnflaggedRecordCanPublish() = root { dir, c ->
        install(c)
        val held = c.managementRecord(suspect, crashQuarantined = true)
        val enabled = c.setEnabled(suspect, true)!!
        assertTrue(enabled.state.enabled)
        val pages = mutableListOf<ManagedPluginData>()
        val inventory = mutableListOf<ManagedDataRecord>()
        // A manager must acknowledge the crash journal before switching to an unflagged record.
        replaceDataInventory(inventory, pages, held.copy(selection = enabled), false)
        assertTrue(pages.isEmpty())
        replaceDataInventory(inventory, pages, c.managementRecord(suspect), false)
        assertTrue(pages.single().active)
        assertTrue(PluginDataStateStore(dir).read(suspect)!!.enabled)
    }

    @Test fun corruptSuspectCannotEnableButCanBeRemovedWithoutArchiveParsing() = root { dir, c ->
        install(c)
        dir.resolve("plugins/$suspect/1/plugin.zip").writeText("not a ZIP")
        val held = c.managementRecord(suspect, crashQuarantined = true)
        assertTrue(ManagedDataRecord(held, null).canEnable)
        assertThrows(Exception::class.java) { c.setEnabled(suspect, true) }
        assertFalse(PluginDataStateStore(dir).read(suspect)!!.enabled)
        c.beginDelete(suspect)
        val pending = c.managementRecord(suspect, crashQuarantined = true)
        assertTrue(pending.state.pendingDelete)
        assertFalse(ManagedDataRecord(pending, null).canEnable)
        assertNull(pending.selection)
        c.finishDelete(suspect)
        assertFalse(dir.resolve("plugins/$suspect").exists())
        assertTrue(hasDataOwnership(dir, suspect))
    }

    @Test fun updatesPreserveDisabledIntentAndRemainStateOnlyWhenHeld() = root { dir, c ->
        install(c)
        c.restoreInventory({ fail(it) }, setOf(suspect))
        val update = install(c, version = 2)
        assertFalse(update.state.enabled)
        val held = c.managementRecord(suspect, crashQuarantined = true)
        assertEquals(2L, held.state.version)
        assertTrue(held.crashQuarantined)
        assertNull(held.selection)
        assertFalse(PluginDataStateStore(dir).read(suspect)!!.enabled)
    }

    @Test fun quarantinedUpdatesPruneOnlyOldVersionsAndRemainDisabledAfterColdRestore() = root { dir, c ->
        install(c)
        c.restoreInventory({ fail(it) }, setOf(suspect))
        install(c, version = 2)
        val update = install(c, version = 3)
        assertFalse(update.state.enabled)
        assertEquals(setOf("2", "3"), dir.resolve("plugins/$suspect").list()!!.toSet())
        val held = PluginDataCatalog(dir, 1).restoreInventory({ fail(it) }, setOf(suspect)).single()
        assertEquals(3L, held.state.version)
        assertFalse(held.state.enabled)
        assertTrue(held.crashQuarantined)
        assertNull(held.selection)
        val enabled = PluginDataCatalog(dir, 1).setEnabled(suspect, true)!!
        assertEquals(3L, enabled.manifest.versionCode)
        assertTrue(enabled.state.enabled)
    }

    @Test fun failedDisableReturnsManagementOnlyAndDoesNotClaimDurableSuccess() = root { dir, c ->
        install(c)
        Files.delete(dir.resolve("plugins/$suspect/1/plugin.zip").toPath())
        val failing = PluginDataCatalog(dir, 1, commit = { _, _ -> throw IOException("disk full") })
        val errors = mutableListOf<String>()
        val held = failing.restoreInventory(errors::add, setOf(suspect)).single()
        assertTrue(held.crashQuarantined)
        assertTrue(held.state.enabled)
        assertTrue(PluginDataStateStore(dir).read(suspect)!!.enabled)
        assertNull(held.selection)
        assertTrue(held.error!!.contains("disable failed"))
        assertEquals(1, errors.size)
        assertFalse(ManagedDataRecord(held, null).canEnable)
        val pages = mutableListOf<ManagedPluginData>()
        replaceDataInventory(mutableListOf(), pages, held, false)
        assertTrue(pages.isEmpty())
    }

    @Test fun heldManagementAfterUnacknowledgedEnableRestoresDisabledIntent() = root { dir, c ->
        install(c)
        c.managementRecord(suspect, crashQuarantined = true)
        c.setEnabled(suspect, true)
        // Manager's failed-ack path keeps suspect membership and requests held management.
        val held = c.managementRecord(suspect, crashQuarantined = true)
        assertTrue(held.crashQuarantined)
        assertFalse(held.state.enabled)
        assertNull(held.selection)
        assertFalse(PluginDataStateStore(dir).read(suspect)!!.enabled)
    }
}
