package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** 用假的签名校验，只验证更新事务：快照、回滚、观察期和中断后的重入。 */
class PluginUpdatesTest {
    private val id = "test.update"
    private val signers = PluginSigners(listOf("a".repeat(64)))

    private inner class Fixture(val root: File) {
        var clock = 1_000L
        var copyFails = false
        val badFiles = mutableSetOf<String>()
        val reports = mutableListOf<String>()
        val store = PluginStore(root, root.resolve("cache"), object : PluginSignatureVerifier {
            override fun verify(file: File): PluginSigners {
                if (file.parentFile.name in badFiles) throw PluginPackageException("Bad package")
                return signers
            }
        })
        val updates = PluginUpdates(root, store, { clock }) { source, target ->
            if (copyFails) throw IOException("No space left")
            copyPluginTree(source, target)
        }
        val data = root.resolve("plugin-data/$id")
        val snapshot = root.resolve("plugin-snapshots/$id/1")

        fun publish(version: Long) {
            store.install(store.stage(ByteArrayInputStream(apk(version))))
        }

        fun save(text: String) {
            data.mkdirs()
            data.resolve("value.txt").writeText(text)
        }

        fun saved() = data.resolve("value.txt").readText()

        fun recover(interrupted: Boolean = false, safeMode: Boolean = false) =
            updates.recover(id, interrupted, safeMode, reports::add)

        /** v1 存了 "old"，v2 已激活并改了数据。 */
        fun activated() {
            publish(1)
            save("old")
            publish(2)
            recover()
            save("new")
        }
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("theta-update").toFile()
        try { Fixture(root).block() } finally { root.deleteRecursively() }
    }

    private fun apk(version: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write(
                """{"schema":1,"type":"dex","id":"$id","name":"Update","version":"$version","versionCode":$version,"apiLevel":1,"entry":"test.Entry"}"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun firstInstallNeedsNoUpdateRecord() = fixture {
        publish(1)
        assertFalse(recover())
        assertNull(updates.read(id))
    }

    @Test fun coldStartSnapshotsDataBeforeActivating() = fixture {
        publish(1)
        save("old")
        publish(2)
        assertFalse(recover())
        assertEquals(PluginUpdate(1, 2), updates.read(id))
        assertEquals("old", snapshot.resolve("value.txt").readText())
        assertEquals(listOf(1L, 2L), store.versions(id))
    }

    @Test fun missingDataDirectoryStillGetsASnapshot() = fixture {
        publish(1)
        publish(2)
        recover()
        assertTrue(snapshot.isDirectory)
    }

    @Test fun snapshotKeepsExecutableBitAndDoesNotFollowLinks() = fixture {
        publish(1)
        save("old")
        val outside = root.resolve("outside.txt").apply { writeText("secret") }
        Files.createSymbolicLink(data.resolve("link").toPath(), outside.toPath())
        val tool = data.resolve("tool").apply { writeText("#!") }
        assertTrue(tool.setExecutable(true))
        publish(2)
        recover()
        assertTrue(Files.isSymbolicLink(snapshot.resolve("link").toPath()))
        assertTrue(OWNER_EXECUTE in Files.getPosixFilePermissions(snapshot.resolve("tool").toPath()))
    }

    @Test fun snapshotFailureCancelsUpdateAndKeepsOldVersion() = fixture {
        publish(1)
        save("old")
        publish(2)
        copyFails = true
        recover()
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
        assertEquals("old", saved())
        assertTrue(reports.single().contains("cancelled"))
        assertTrue(root.resolve("plugin-snapshots/$id").list().orEmpty().isEmpty())
    }

    @Test fun brokenNewPackageCancelsUpdate() = fixture {
        publish(1)
        publish(2)
        badFiles += "2"
        recover()
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
    }

    @Test fun failedFirstStartRollsBackOnNextColdStartOnly() = fixture {
        activated()
        assertTrue(updates.failed(id, 2))
        assertEquals("new", saved()) // 这个进程里旧代码可能还在写，不动数据。
        assertEquals(listOf(1L, 2L), store.versions(id))
        assertTrue(recover())
        assertEquals("old", saved())
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
        assertFalse(root.resolve("plugin-snapshots/$id").exists())
    }

    @Test fun interruptedFirstStartRollsBack() = fixture {
        activated()
        assertTrue(recover(interrupted = true))
        assertEquals("old", saved())
    }

    @Test fun failureAfterASuccessfulStartIsTheUsersCall() = fixture {
        activated()
        updates.started(id, 2)
        assertFalse(updates.failed(id, 2))
        assertFalse(recover(interrupted = true))
        assertEquals("new", saved())
        assertEquals(UpdatePhase.OBSERVING, updates.read(id)?.phase)
    }

    @Test fun manualRollbackRestoresSnapshot() = fixture {
        activated()
        updates.started(id, 2)
        updates.requestRollback(id)
        assertTrue(recover())
        assertEquals("old", saved())
    }

    @Test fun rollbackRequiresAnObservedUpdate() = fixture {
        publish(1)
        assertThrows(PluginPackageException::class.java) { updates.requestRollback(id) }
        publish(2)
        recover()
        updates.requestRollback(id)
        assertThrows(PluginPackageException::class.java) { updates.requestRollback(id) }
    }

    @Test fun rollbackResumesAfterDataWasPartlyDeleted() = fixture {
        activated()
        updates.requestRollback(id)
        data.deleteRecursively()
        assertTrue(recover())
        assertEquals("old", saved())
    }

    @Test fun rollbackResumeDoesNotDeleteRestoredData() = fixture {
        activated()
        updates.requestRollback(id)
        // 中断点：快照已经改名成数据目录，新版本和记录还在。
        data.deleteRecursively()
        Files.move(snapshot.toPath(), data.toPath())
        assertTrue(recover())
        assertEquals("old", saved())
        assertEquals(listOf(1L), store.versions(id))
        assertNull(updates.read(id))
    }

    @Test fun rollbackWithABrokenOldPackageKeepsEverythingForRetry() = fixture {
        activated()
        updates.requestRollback(id)
        badFiles += "1"
        assertThrows(PluginPackageException::class.java) { recover() }
        assertEquals("new", saved())
        assertEquals(listOf(1L, 2L), store.versions(id))
        badFiles.clear()
        assertTrue(recover())
        assertEquals("old", saved())
    }

    @Test fun observationEndsTwentyFourHoursAfterTheFirstStart() = fixture {
        activated()
        updates.started(id, 2)
        clock += UPDATE_OBSERVATION_MS - 1
        recover()
        assertEquals(listOf(1L, 2L), store.versions(id))
        clock += 1
        recover()
        assertEquals(listOf(2L), store.versions(id))
        assertNull(updates.read(id))
        assertFalse(root.resolve("plugin-snapshots/$id").exists())
        assertEquals("new", saved())
    }

    @Test fun observationDoesNotRunOutBeforeTheFirstStart() = fixture {
        activated()
        clock += UPDATE_OBSERVATION_MS * 2
        recover()
        assertEquals(listOf(1L, 2L), store.versions(id))
        assertNotNull(updates.read(id))
    }

    @Test fun safeModeKeepsTheRollbackAvailable() = fixture {
        activated()
        updates.started(id, 2)
        clock += UPDATE_OBSERVATION_MS
        recover(safeMode = true)
        assertEquals(listOf(1L, 2L), store.versions(id))
    }

    @Test fun updateDuringObservationStartsANewRound() = fixture {
        activated()
        updates.started(id, 2)
        publish(3)
        recover()
        assertEquals(PluginUpdate(2, 3), updates.read(id))
        assertEquals(listOf(2L, 3L), store.versions(id))
        assertFalse(snapshot.exists())
        assertEquals("new", root.resolve("plugin-snapshots/$id/2/value.txt").readText())
    }

    @Test fun cancelledUpdateDuringObservationKeepsTheFirstRound() = fixture {
        activated()
        updates.started(id, 2)
        publish(3)
        copyFails = true
        recover()
        assertEquals(1L, updates.read(id)?.from)
        assertEquals(listOf(1L, 2L), store.versions(id))
        assertTrue(snapshot.isDirectory)
    }

    @Test fun rollbackAlsoDropsAnUnactivatedNewerPackage() = fixture {
        activated()
        publish(3)
        updates.requestRollback(id)
        assertTrue(recover())
        assertEquals(listOf(1L), store.versions(id))
    }

    @Test fun forgetRemovesSnapshotAndRecord() = fixture {
        activated()
        updates.forget(id)
        assertNull(updates.read(id))
        assertFalse(root.resolve("plugin-snapshots/$id").exists())
    }

    @Test fun corruptRecordIsAnError() = fixture {
        activated()
        root.resolve("plugin-updates/$id.properties").writeText("from=1\n")
        assertThrows(IOException::class.java) { updates.read(id) }
        root.resolve("plugin-updates/$id.properties").writeText("from=x\nto=2\nphase=OBSERVING\n")
        assertThrows(IOException::class.java) { updates.read(id) }
    }
}
