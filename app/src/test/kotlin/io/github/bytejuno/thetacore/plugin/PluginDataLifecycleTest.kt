package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real archives/filesystem and production catalog/store; sources only, NOT RUN. */
class PluginDataLifecycleTest {
    private val id = "test.data"
    private fun bundle(version: Long = 1, name: String = id, text: String = "# Plain text"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("theta-plugin.json" to """{"schema":1,"type":"data","id":"$name","name":"Data","version":"v$version","versionCode":$version,"apiLevel":1}""",
                "skills/hello/SKILL.md" to text).forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private fun root(action: (File, PluginDataCatalog) -> Unit) {
        val dir = Files.createTempDirectory("theta-data-lifecycle").toFile()
        try { action(dir, PluginDataCatalog(dir, 1)) } finally { dir.deleteRecursively() }
    }
    private fun install(c: PluginDataCatalog, version: Long = 1, name: String = id): SelectedPluginData {
        val stage = c.stage(bundle(version, name).inputStream())
        return c.install(stage, stage.sha256, true)
    }
    private fun failures(c: PluginDataCatalog): Pair<List<SelectedPluginData>, List<String>> {
        val errors = mutableListOf<String>()
        return c.restore(errors::add) to errors
    }

    @Test fun selectedVersionAndDisabledStateSurviveNewCatalog() = root { dir, c ->
        install(c)
        c.setEnabled(id, false)
        val update = install(c, 2)
        assertFalse(update.state.enabled)
        val restored = failures(PluginDataCatalog(dir, 1))
        assertTrue(restored.second.isEmpty())
        assertEquals(update, restored.first.single())
        assertEquals(update.state.sha256, update.state.confirmedSha256)
        assertFalse(dir.resolve("plugin-state/$id.properties").exists())
        assertFalse(dir.resolve("plugin-data/$id").exists())
    }

    @Test fun sameProcessReenableAndSafeModeDoNotUseDexGate() = root { _, c ->
        val first = install(c)
        assertTrue(first.active(false))
        assertFalse(first.active(true))
        assertFalse(c.setEnabled(id, false, first)!!.active(false))
        val enabled = c.setEnabled(id, true)!!
        assertTrue(enabled.active(false))
        assertFalse(enabled.active(true))
        assertEquals("# Plain text", c.content(enabled, "skills/hello/SKILL.md").text)
    }

    @Test fun orphanPublicationNeverSelectsOrSupersedesAcceptedVersion() = root { dir, c ->
        val selected = install(c)
        val store = PluginDataStore(dir, 1)
        val stage = store.stageData(bundle(2).inputStream())
        store.publishConfirmedData(stage, stage.sha256, true)
        assertEquals(selected, failures(PluginDataCatalog(dir, 1)).first.single())
        val orphan = store.stageData(bundle(1, "test.orphan").inputStream())
        store.publishConfirmedData(orphan, orphan.sha256, true)
        assertEquals(listOf(id), failures(PluginDataCatalog(dir, 1)).first.map { it.manifest.id })
        assertTrue(hasDataOwnership(dir, "test.orphan"))
    }

    @Test fun failedSelectionCommitPreservesOldVersionAndPageBytes() = root { dir, c ->
        val old = install(c)
        val failing = PluginDataCatalog(dir, 1, commit = { _, _ -> throw IOException("commit failed") })
        val stage = failing.stage(bundle(2).inputStream())
        assertThrows(IOException::class.java) { failing.install(stage, stage.sha256, true) }
        assertEquals(old, failures(PluginDataCatalog(dir, 1)).first.single())
        assertEquals("# Plain text", c.content(old, "skills/hello/SKILL.md").text)
        assertTrue(dir.resolve("plugins/$id/2/plugin.zip").isFile)
    }

    @Test fun failedDisableCommitKeepsOldSelection() = root { dir, c ->
        val old = install(c)
        val failing = PluginDataCatalog(dir, 1, commit = { _, _ -> throw IOException("disk full") })
        assertThrows(IOException::class.java) { failing.setEnabled(id, false) }
        assertEquals(old, failures(PluginDataCatalog(dir, 1)).first.single())
    }

    @Test fun damagedOrDeletedSelectedArchiveStillDisablesAndRevokesManagerMembership() = root { dir, c ->
        for ((index, damage) in listOf<(File) -> Unit>(
            { it.writeText("damaged ZIP") }, { Files.delete(it.toPath()) },
        ).withIndex()) {
            val name = "test.damage$index"
            val selected = install(c, name = name)
            val oldPage = ManagedPluginData(selected, false)
            val pages = mutableListOf(oldPage)
            assertTrue(isManagedDataPageActive(pages, oldPage))
            damage(dir.resolve("plugins/$name/1/plugin.zip"))
            c.setEnabled(name, false, selected)
            assertFalse(PluginDataStateStore(dir).read(name)!!.enabled)
            // Same production list mutation and identity predicate used by PluginManager.
            val inventory = mutableListOf<ManagedDataRecord>()
            replaceDataInventory(inventory, pages, c.managementRecord(name), false)
            assertFalse(isManagedDataPageActive(pages, oldPage))
            assertTrue(pages.isEmpty())
            assertFalse(inventory.single().state.enabled)
            assertFalse(inventory.single().canEnable)
            assertNotNull(inventory.single().record.error)
            assertThrows(Exception::class.java) { c.content(selected, null) }
            assertThrows(Exception::class.java) { c.setEnabled(name, true) }
            assertFalse(PluginDataStateStore(dir).read(name)!!.enabled)
        }
    }

    @Test fun disableWithoutCachedManifestDoesNotReadArchiveOrInventMetadata() = root { dir, c ->
        install(c)
        Files.delete(dir.resolve("plugins/$id/1/plugin.zip").toPath())
        assertNull(PluginDataCatalog(dir, 1).setEnabled(id, false))
        assertFalse(PluginDataStateStore(dir).read(id)!!.enabled)
    }

    @Test fun failedDisableCommitDoesNotRevokeManagerPage() = root { dir, c ->
        val selected = install(c)
        val oldPage = ManagedPluginData(selected, false)
        val pages = mutableListOf(oldPage)
        val failing = PluginDataCatalog(dir, 1, commit = { _, _ -> throw IOException("disk full") })
        assertThrows(IOException::class.java) {
            failing.setEnabled(id, false, selected)
            replaceDataInventory(mutableListOf(), pages, failing.managementRecord(id), false)
        }
        assertTrue(isManagedDataPageActive(pages, oldPage))
        assertTrue(PluginDataStateStore(dir).read(id)!!.enabled)
    }

    @Test fun freshConfirmationAndChangedStagedDigestCannotActivate() = root { dir, c ->
        val old = install(c)
        var stage = c.stage(bundle(2).inputStream())
        assertThrows(Exception::class.java) { c.install(stage, old.state.sha256, true) }
        stage = c.stage(bundle(2).inputStream())
        dir.resolve("plugin-data-staging").listFiles()!!.single().resolve("plugin.zip").writeBytes(bundle(2, text = "Changed"))
        assertThrows(Exception::class.java) { c.install(stage, stage.sha256, true) }
        assertEquals(old, failures(PluginDataCatalog(dir, 1)).first.single())
    }

    @Test fun updateAndDisableRevokeOldImmutableSelections() = root { _, c ->
        val old = install(c)
        val updated = install(c, 2)
        assertThrows(Exception::class.java) { c.content(old, null) }
        val disabled = c.setEnabled(id, false, updated)!!
        assertThrows(Exception::class.java) { c.content(disabled, null) }
    }

    @Test fun deletionIntentSurvivesFailureAndResumesBeforeActivation() = root { dir, c ->
        val old = install(c)
        val failing = PluginDataCatalog(dir, 1, deletePackages = { _, _ -> throw IOException("delete failed") })
        failing.beginDelete(id)
        assertThrows(Exception::class.java) { c.content(old, null) }
        assertThrows(IOException::class.java) { failing.finishDelete(id) }
        assertTrue(PluginDataStateStore(dir).read(id)!!.pendingDelete)
        assertTrue(failures(PluginDataCatalog(dir, 1)).first.isEmpty())
        assertFalse(dir.resolve("plugins/$id").exists())
        assertTrue(hasDataOwnership(dir, id))
        assertEquals(0L, PluginDataStateStore(dir).read(id)!!.version)
        assertThrows(Exception::class.java) { requireNoDataOwnership(dir, id) }
        assertTrue(install(c).active(false))
    }

    @Test fun corruptRecordAndCorruptArchiveAreIsolatedPerId() = root { dir, c ->
        install(c)
        install(c, name = "test.good")
        dir.resolve("plugin-data-catalog/$id/selection.properties").writeText("broken")
        val result = failures(PluginDataCatalog(dir, 1))
        assertEquals(listOf("test.good"), result.first.map { it.manifest.id })
        assertEquals(1, result.second.size)
        dir.resolve("plugins/test.good/1/plugin.zip").appendText("tamper")
        val corrupt = failures(PluginDataCatalog(dir, 1))
        assertTrue(corrupt.first.isEmpty())
        assertEquals(2, corrupt.second.size)
    }

    @Test fun codeCleanupNeverDeletesDataOrRetainedOwnership() = root { dir, c ->
        install(c)
        val code = PluginStore(dir, dir.resolve("cache"), PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) })
        assertTrue(code.retainedIds().isEmpty())
        assertThrows(Exception::class.java) { code.delete(id, true) }
        assertThrows(Exception::class.java) { code.deleteVersion(id, 1) }
        assertTrue(dir.resolve("plugins/$id/1/plugin.zip").isFile)
        c.beginDelete(id); c.finishDelete(id)
        assertThrows(Exception::class.java) { code.delete(id, true) }
        assertTrue(dir.resolve("plugin-data-catalog/$id").isDirectory)
    }

    @Test fun codeImportRejectsRetainedDataIdBeforeCreatingCodeState() = root { dir, c ->
        install(c)
        c.beginDelete(id); c.finishDelete(id)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write("""{"schema":1,"type":"dex","id":"$id","name":"Code","version":"1","versionCode":1,"apiLevel":1,"entry":"test.Entry"}""".toByteArray())
            zip.closeEntry()
        }
        val code = PluginStore(dir, dir.resolve("cache"), PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) })
        assertThrows(Exception::class.java) { code.stage(out.toByteArray().inputStream()) }
        assertFalse(dir.resolve("plugin-trust/$id.signers").exists())
        assertFalse(dir.resolve("plugin-state/$id.properties").exists())
        assertTrue(dir.resolve("plugin-data-catalog/$id").isDirectory)
    }

    @Test fun contentUsesStrictValidatorAndBoundsBeforeReturning() = root { _, c ->
        val selected = install(c)
        assertEquals(setOf("theta-plugin.json", "skills/hello/SKILL.md"), c.content(selected, null).paths.toSet())
        assertThrows(Exception::class.java) { c.content(selected, "../secret") }
        assertThrows(Exception::class.java) { c.stage(bundle(2, text = "x".repeat(1024 * 1024 + 1)).inputStream()) }
        assertEquals("# Plain text", c.content(selected, "skills/hello/SKILL.md").text)
    }

    @Test fun mixedOwnershipCannotRestoreOrDeleteEitherSide() = root { dir, c ->
        install(c)
        val code = dir.resolve("plugins/$id/1/plugin.apk").apply { writeText("ambiguous") }
        assertTrue(failures(PluginDataCatalog(dir, 1)).first.isEmpty())
        c.beginDelete(id)
        assertThrows(Exception::class.java) { c.finishDelete(id) }
        assertTrue(code.exists())
        assertTrue(dir.resolve("plugins/$id/1/plugin.zip").exists())
    }

    @Test fun retainedCodeOwnershipBlocksDataAndLostStagesAreDiscarded() = root { dir, c ->
        PluginStateStore(dir).write(id, PluginState(enabled = false))
        assertThrows(Exception::class.java) { c.stage(bundle().inputStream()) }
        c.stage(bundle(name = "test.other").inputStream())
        assertTrue(dir.resolve("plugin-data-staging").listFiles()!!.isNotEmpty())
        assertTrue(failures(PluginDataCatalog(dir, 1)).first.isEmpty())
        assertFalse(dir.resolve("plugin-data-staging").exists())
    }
}
