package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real production publication hooks capture crash-time filesystem
 * images before finally-cleanup runs. New stores restore those images with no old tickets.
 * ZIP APK fixtures use an injected verifier: NOT platform signature acceptance.
 */
class PluginPublicationRecoveryTest {
    private val id = "test.publication"
    private val verifier = PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) }
    private fun root(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("theta-publication").toFile()
        try { action(dir) } finally { deleteFixtureRoot(dir) }
    }
    private fun bundle(version: Long, data: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(if (data) "theta-plugin.json" else "assets/theta-plugin.json"))
            val entry = if (data) "" else ",\"entry\":\"test.Entry\""
            zip.write("""{"schema":1,"type":"${if (data) "data" else "dex"}","id":"$id","name":"Publication","version":"$version","versionCode":$version,"apiLevel":1$entry}""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
    private fun install(catalog: PluginDataCatalog, version: Long): SelectedPluginData {
        val stage = catalog.stage(bundle(version).inputStream())
        return catalog.install(stage, stage.sha256, true)
    }
    private fun snapshot(from: File, to: File) {
        check(!to.exists())
        check(from.copyRecursively(to))
    }

    @Test fun dataCrashAtEveryPublicationBoundaryPreservesPreviousDisabledSelection() = root { base ->
        for (point in PluginPublicationBoundary.values()) {
            val live = base.resolve("live-$point").apply { mkdirs() }
            val initial = PluginDataCatalog(live, 1)
            val installed = install(initial, 1)
            val old = initial.setEnabled(id, false, installed)!!
            assertFalse(old.state.enabled)
            assertEquals(old.state, PluginDataStateStore(live).read(id))
            val image = base.resolve("restart-$point")
            val updating = PluginDataCatalog(live, 1, publicationBoundary = {
                if (it == point) snapshot(live, image)
            })
            install(updating, 2)
            val cold = PluginDataCatalog(image, 1)
            assertEquals(old, cold.restoreInventory { fail(it) }.single().selection)
            assertFalse(image.resolve("plugin-data-publication/intent.properties").exists())
            assertTrue(image.resolve("plugins/$id/1/plugin.zip").isFile)
            // A moved-but-not-selected v2 stays inactive; earlier boundaries leave no v2.
            assertEquals(point == PluginPublicationBoundary.MOVED, image.resolve("plugins/$id/2").exists())
            assertFalse(PluginDataStateStore(image).read(id)!!.enabled)
            assertEquals(3L, install(cold, 3).manifest.versionCode)
            cold.beginDelete(id); cold.finishDelete(id)
            assertFalse(image.resolve("plugins/$id").exists())
        }
    }

    @Test fun initialPublicationFailureAndRestartBothPermitExactVersionRetry() = root { base ->
        val live = base.resolve("live").apply { mkdirs() }
        val image = base.resolve("restart")
        val original = IOException("filesystem unavailable before move")
        var denied = true
        val catalog = PluginDataCatalog(live, 1, publicationBoundary = {
            if (it == PluginPublicationBoundary.PARENT_READY && denied) {
                snapshot(live, image)
                throw original
            }
        })
        assertSame(original, assertThrows(IOException::class.java) { install(catalog, 1) })
        assertFalse(live.resolve("plugins/$id").exists())
        denied = false
        assertEquals(1L, install(catalog, 1).manifest.versionCode) // same-process retry
        val cold = PluginDataCatalog(image, 1)
        assertTrue(cold.restoreInventory { fail(it) }.isEmpty())
        assertFalse(image.resolve("plugins/$id").exists())
        assertEquals(1L, install(cold, 1).manifest.versionCode) // lost-ticket restart retry
    }

    @Test fun ownershipReservationDoesNotAdoptArbitraryEmptyDirectory() = root { dir ->
        PluginDataStateStore(dir).claim(id)
        dir.resolve("plugins/$id").mkdirs()
        assertThrows(PluginPackageException::class.java) { install(PluginDataCatalog(dir, 1), 1) }
        assertTrue(dir.resolve("plugins/$id").isDirectory)
    }

    @Test fun failedColdRemovalRetainsRetryTicketAndDurableIntent() = root { base ->
        val live = base.resolve("live").apply { mkdirs() }
        val original = PluginDataCatalog(live, 1)
        val selected = install(original, 1)
        val image = base.resolve("restart")
        install(PluginDataCatalog(live, 1, publicationBoundary = {
            if (it == PluginPublicationBoundary.PARENT_READY) snapshot(live, image)
        }), 2)
        var denied = true
        val cleanup = PluginStageCleanup()
        val cold = PluginDataCatalog(image, 1, cleanup = cleanup, deleteStage = { parent, name ->
            if (parent.name == "plugin-data-publication" && denied) throw IOException("busy")
            deletePluginTree(parent, name)
        })
        val errors = mutableListOf<String>()
        assertEquals(selected, cold.restoreInventory(errors::add).single().selection)
        assertTrue(errors.isNotEmpty())
        assertTrue(cleanup.hasPending)
        assertTrue(image.resolve("plugin-data-publication/intent.properties").isFile)
        assertThrows(Exception::class.java) { cold.stage(bundle(3).inputStream()) }
        assertThrows(IOException::class.java) { cold.retryCleanup() }
        denied = false
        cold.retryCleanup()
        assertFalse(cleanup.hasPending)
        assertFalse(image.resolve("plugin-data-publication/intent.properties").exists())
        assertEquals(2L, install(cold, 2).manifest.versionCode)
    }

    @Test fun initialMovedDataWithoutSelectionNeverActivatesOnRestart() = root { base ->
        val live = base.resolve("live").apply { mkdirs() }
        val image = base.resolve("restart")
        install(PluginDataCatalog(live, 1, publicationBoundary = {
            if (it == PluginPublicationBoundary.MOVED) snapshot(live, image)
        }), 1)
        assertTrue(PluginDataCatalog(image, 1).restoreInventory { fail(it) }.isEmpty())
        assertNull(PluginDataStateStore(image).read(id))
        assertTrue(image.resolve("plugins/$id/1/plugin.zip").isFile)
    }

    @Test fun mixedAndUnjournaledTreesAreNeverDeletedByRecovery() = root { base ->
        val live = base.resolve("live").apply { mkdirs() }
        val image = base.resolve("restart")
        install(PluginDataCatalog(live, 1, publicationBoundary = {
            if (it == PluginPublicationBoundary.PARENT_READY) snapshot(live, image)
        }), 1)
        val code = image.resolve("plugins/$id/7/plugin.apk").apply { parentFile.mkdirs(); writeText("code") }
        val intent = image.resolve("plugin-data-publication/intent.properties")
        val cold = PluginDataCatalog(image, 1)
        val failures = mutableListOf<String>()
        cold.restoreInventory(failures::add)
        assertTrue(failures.isNotEmpty())
        assertTrue(intent.isFile)
        assertEquals("code", code.readText())
        assertThrows(Exception::class.java) { cold.retryCleanup() }
        val arbitrary = base.resolve("unowned").apply { mkdirs() }
        val suspicious = arbitrary.resolve("plugins/$id/.data-not-owned/value").apply { parentFile.mkdirs(); writeText("keep") }
        PluginDataCatalog(arbitrary, 1).restoreInventory { fail(it) }
        assertEquals("keep", suspicious.readText())
    }

    @Test fun codeCrashImagesRecoverOnlyUnpublishedVersions() = root { base ->
        for (point in PluginPublicationBoundary.values()) {
            val live = base.resolve("live-$point").apply { mkdirs() }
            val image = base.resolve("restart-$point")
            val first = fixturePluginStore(live, live.resolve("cache"), verifier)
            first.install(first.stage(bundle(1, false).inputStream()))
            val store = fixturePluginStore(live, live.resolve("cache"), verifier, publicationBoundary = {
                if (it == point) snapshot(live, image)
            })
            store.install(store.stage(bundle(2, false).inputStream()))
            val cold = fixturePluginStore(image, image.resolve("cache"), verifier)
            cold.cleanupColdStages()
            assertEquals(if (point == PluginPublicationBoundary.MOVED) listOf(1L, 2L) else listOf(1L), cold.versions(id))
            assertFalse(image.resolve("plugin-code-publication/intent.properties").exists())
            assertTrue(image.resolve("plugins/$id/1/plugin.apk").isFile)
        }
    }

    @Test fun committedCodePublicationReturnsDespiteStageDeleteFailureAndRetryPreservesApk() = root { dir ->
        val cleanup = PluginStageCleanup()
        var denied = true
        val store = fixturePluginStore(dir, dir.resolve("cache"), verifier, cleanup, deleteStage = {
            if (it.parentFile.name == "plugin-staging" && denied) throw IOException("stage busy")
            deleteFixtureFile(dir, it)
        })
        // Actual store.install uses the shared committed-cleanup helper, not a fake install result.
        val installed = store.install(store.stage(bundle(1, false).inputStream()))
        assertEquals(1L, installed.manifest.versionCode)
        assertTrue(installed.apk.isFile)
        val published = installed.apk.readBytes()
        assertTrue(cleanup.hasPending)
        assertThrows(IOException::class.java) { store.retryCleanup() }
        denied = false
        store.retryCleanup()
        assertFalse(cleanup.hasPending)
        assertArrayEquals(published, installed.apk.readBytes())
    }

    @Test fun committedJournalCleanupFailureReturnsBothKindsAndSurvivesRestart() = root { base ->
        for (kind in PluginPublicationKind.values()) {
            val dir = base.resolve(kind.name).apply { mkdirs() }
            if (kind == PluginPublicationKind.DATA) PluginDataStateStore(dir).claim(id)
            else PluginTrustStore(dir).bindConfirmed(id, verifier.verify(File(dir, "unused")))
            val cleanup = PluginStageCleanup()
            val publication = PluginPackagePublication(dir, kind, cleanup,
                deleteIntent = { throw IOException("intent removal denied") })
            val target = publication.publish(id, 1,
                write = { it.writeBytes(bundle(1, kind == PluginPublicationKind.DATA)) }, beforeMove = {})
            assertTrue(target.resolve(kind.archive).isFile)
            val bytes = target.resolve(kind.archive).readBytes()
            assertTrue(cleanup.hasPending)
            assertThrows(IOException::class.java) { cleanup.retry() }
            val cold = PluginPackagePublication(dir, kind, PluginStageCleanup())
            cold.recoverOnce()
            assertFalse(dir.resolve("${kind.directory}/intent.properties").exists())
            assertArrayEquals(bytes, target.resolve(kind.archive).readBytes())
            if (kind == PluginPublicationKind.DATA) assertNull(PluginDataStateStore(dir).read(id))
        }
    }

    @Test fun precommitFailureKeepsOriginalAndDataCleanupCannotCommitPrematurely() = root { dir ->
        val cleanup = PluginStageCleanup()
        val original = IOException("write failed")
        val code = fixturePluginStore(dir, dir.resolve("cache"), verifier, cleanup,
            deleteStage = { throw IOException("delete denied") }, publicationBoundary = {
                if (it == PluginPublicationBoundary.PARENT_READY) throw original
            })
        val error = assertThrows(IOException::class.java) { code.install(code.stage(bundle(1, false).inputStream())) }
        assertSame(original, error)
        assertTrue(error.suppressed.isNotEmpty())
        assertTrue(cleanup.hasPending)
        assertFalse(dir.resolve("plugins/$id/1/plugin.apk").exists())
        val dataDir = dir.resolve("data-test").apply { mkdirs() }
        val catalog = PluginDataCatalog(dataDir, 1, deleteStage = { parent, name ->
            if (parent.name == "plugin-data-staging") throw IOException("stage denied")
            deletePluginTree(parent, name)
        })
        assertThrows(IOException::class.java) { install(catalog, 1) }
        assertFalse(dataDir.resolve("plugins/$id/1/plugin.zip").exists())
        assertNull(PluginDataStateStore(dataDir).read(id))
    }
}
