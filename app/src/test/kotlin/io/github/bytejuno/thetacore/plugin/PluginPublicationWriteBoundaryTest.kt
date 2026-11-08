package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Production publication/storage helpers, not device or power-loss proof.
 * CODE uses the existing policy-only verifier and Windows unlink fixture.
 * Preparing-intent residue is seeded: writeIntent has no mid-write injection seam.
 * Payload crash images are copied INSIDE write(), before exception/finally cleanup.
 */
class PluginPublicationWriteBoundaryTest {
    private val id = "test.write_boundary"
    private val verifier = PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) }

    private fun roots(action: (File, PluginPublicationKind) -> Unit) {
        val root = Files.createTempDirectory("theta-publication-write").toFile()
        try {
            for (kind in PluginPublicationKind.values()) action(root.resolve(kind.name).apply { mkdirs() }, kind)
        } finally { deleteFixtureRoot(root) }
    }

    private fun bundle(kind: PluginPublicationKind, version: Long): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val data = kind == PluginPublicationKind.DATA
            zip.putNextEntry(ZipEntry(if (data) "theta-plugin.json" else "assets/theta-plugin.json"))
            val entry = if (data) "" else ",\"entry\":\"test.Entry\""
            zip.write("""{"schema":1,"type":"${if (data) "data" else "dex"}","id":"$id","name":"Write boundary","version":"$version","versionCode":$version,"apiLevel":1$entry}""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun install(root: File, kind: PluginPublicationKind, version: Long) {
        if (kind == PluginPublicationKind.DATA) {
            val catalog = PluginDataCatalog(root, 1)
            val stage = catalog.stage(bundle(kind, version).inputStream())
            assertEquals(version, catalog.install(stage, stage.sha256, true).manifest.versionCode)
        } else {
            val store = fixturePluginStore(root, root.resolve("cache"), verifier)
            assertEquals(version, store.install(store.stage(bundle(kind, version).inputStream())).manifest.versionCode)
        }
    }

    private fun seed(root: File, kind: PluginPublicationKind): ByteArray {
        install(root, kind, 1)
        if (kind == PluginPublicationKind.CODE) {
            root.resolve("plugin-data/$id/value").apply { parentFile.mkdirs(); writeText("committed data") }
        }
        return root.resolve("plugins/$id/1/${kind.archive}").readBytes()
    }

    /** Fresh production objects own recovery: no live publisher or in-memory ticket is reused. */
    private fun assertColdOld(root: File, kind: PluginPublicationKind, original: ByteArray) {
        if (kind == PluginPublicationKind.DATA) {
            val catalog = PluginDataCatalog(root, 1)
            val selected = catalog.restoreInventory { fail(it) }.single().selection!!
            assertEquals(1L, selected.manifest.versionCode)
            assertTrue(selected.state.enabled)
            assertEquals(1L, PluginDataStateStore(root).read(id)!!.version)
            assertEquals(listOf("theta-plugin.json"), catalog.content(selected, null).paths)
        } else {
            val store = fixturePluginStore(root, root.resolve("cache"), verifier)
            store.cleanupColdStages()
            assertEquals(listOf(1L), store.versions(id))
            assertEquals(1L, store.installed { fail(it) }.single().manifest.versionCode)
            assertEquals("committed data", root.resolve("plugin-data/$id/value").readText())
        }
        assertArrayEquals(original, root.resolve("plugins/$id/1/${kind.archive}").readBytes())
        assertFalse(root.resolve("plugins/$id/2").exists())
        assertTrue(root.resolve(kind.directory).listFiles().orEmpty().isEmpty())
    }

    @Test fun partialPreparingIntentColdRecoveryPreservesCommittedVersionAndAllowsRetry() = roots { root, kind ->
        val original = seed(root, kind)
        val preparing = root.resolve("${kind.directory}/intent.preparing")
        // Seed the only on-disk residue allowed before the atomic intent rename.
        // This validates recovery, not interruption of an actual FileChannel write.
        preparing.parentFile.mkdirs()
        preparing.writeText("schema=1\nkind=${kind.name}\nid=$id\nversion=")
        assertColdOld(root, kind, original)
        assertFalse(preparing.exists())
        install(root, kind, 2)
        assertTrue(root.resolve("plugins/$id/2/${kind.archive}").isFile)
        assertArrayEquals(original, root.resolve("plugins/$id/1/${kind.archive}").readBytes())
    }

    @Test fun partialPayloadWriteExceptionCleansBeforeFreshStoreRetry() = roots { root, kind ->
        val original = seed(root, kind)
        val cleanup = PluginStageCleanup()
        val publication = PluginPackagePublication(root, kind, cleanup)
        val failure = IOException("payload write interrupted")
        var beforeMove = false
        val thrown = assertThrows(IOException::class.java) {
            publication.publish(id, 2, write = { target ->
                target.writeBytes(bundle(kind, 2).copyOf(19))
                throw failure // Ordinary failure: production finally IS expected to run.
            }, beforeMove = { beforeMove = true })
        }
        assertSame(failure, thrown)
        assertFalse(beforeMove)
        assertFalse(cleanup.hasPending)
        assertTrue(root.resolve(kind.directory).listFiles().orEmpty().isEmpty())
        assertColdOld(root, kind, original)
        install(root, kind, 2)
        assertArrayEquals(original, root.resolve("plugins/$id/1/${kind.archive}").readBytes())
    }

    @Test fun partialPayloadPreFinallyCrashImageRecoversWithoutSelectingPartialVersion() = roots { base, kind ->
        val live = base.resolve("live").apply { mkdirs() }
        val image = base.resolve("restart")
        val original = seed(live, kind)
        val failure = IOException("stop after capturing pre-finally disk image")
        var partialRelativePath: String? = null
        val publication = PluginPackagePublication(live, kind, PluginStageCleanup())
        assertSame(failure, assertThrows(IOException::class.java) {
            publication.publish(id, 2, write = { target ->
                target.writeBytes(bundle(kind, 2).copyOf(19))
                partialRelativePath = target.relativeTo(live).path
                check(live.copyRecursively(image))
                throw failure
            }, beforeMove = { fail("incomplete payload must never reach move") })
        })
        // Only the captured image models process interruption. The live tree ran finally.
        assertTrue(live.resolve(kind.directory).listFiles().orEmpty().isEmpty())
        val partial = image.resolve(checkNotNull(partialRelativePath))
        assertEquals(19L, partial.length())
        assertTrue(image.resolve("${kind.directory}/intent.properties").isFile)
        assertFalse(image.resolve("plugins/$id/2").exists())
        assertColdOld(image, kind, original)
        assertFalse(partial.exists())
        assertColdOld(image, kind, original) // Recovery is idempotent across fresh objects.
        install(image, kind, 2) // Exact failed version is reusable, not consumed by residue.
        assertTrue(image.resolve("plugins/$id/2/${kind.archive}").isFile)
        assertArrayEquals(original, image.resolve("plugins/$id/1/${kind.archive}").readBytes())
    }
}
