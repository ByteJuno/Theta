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
            updates.recover(id, interrupted, safeMode, reports::add).also {
                if (it) updates.acknowledgeRecovery(id)
            }

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

    @Test fun expiredRequestIsRejectedWithoutRestartOrSnapshotDeletion() = fixture {
        activated()
        updates.started(id, 2)
        clock += UPDATE_OBSERVATION_MS - 1
        assertTrue(updates.canRollback(id)) // UI cached true.
        clock += 1
        assertFalse(updates.canRollback(id))
        assertThrows(PluginPackageException::class.java) { updates.requestRollback(id) }
        assertEquals(UpdatePhase.OBSERVING, updates.read(id)!!.phase)
        assertTrue(snapshot.isDirectory)
        assertEquals(listOf(1L, 2L), store.versions(id))
    }

    @Test fun acceptedRequestStillRecoversAfterDeadlineInSafeMode() = fixture {
        activated()
        updates.started(id, 2)
        updates.requestRollback(id)
        clock += UPDATE_OBSERVATION_MS * 2
        assertFalse(updates.canRollback(id))
        assertTrue(recover(safeMode = true))
        assertEquals("old", saved())
    }

    @Test fun safeModeRetainsExpiredSnapshotButDoesNotExtendEligibility() = fixture {
        activated()
        updates.started(id, 2)
        clock += UPDATE_OBSERVATION_MS
        recover(safeMode = true)
        assertTrue(snapshot.isDirectory)
        assertFalse(updates.canRollback(id))
        assertThrows(PluginPackageException::class.java) { updates.requestRollback(id) }
    }

    @Test fun corruptRecoveryDoesNotRereadBlockedIdOrPreventOtherIdsAndRemoval() = fixture {
        activated()
        root.resolve("plugin-updates/$id.properties").writeText("from=broken\n")
        val blocked = mutableSetOf<String>()
        kotlinx.coroutines.runBlocking {
            assertNull(isolatePluginRecovery(id, blocked, reports::add) { recover() })
            assertNull(isolatePluginRecovery(id, blocked, reports::add) {
                fail("blocked record must not be read for UI eligibility")
                updates.canRollback(id)
            })
            assertEquals(false, isolatePluginRecovery("test.other", blocked, reports::add) {
                updates.canRollback("test.other")
            })
        }
        assertEquals(setOf(id), blocked)
        // Same deletion primitives as manager uninstall: corrupt update need not parse.
        store.delete(id, false)
        updates.forget(id)
        assertNull(updates.read(id))
        assertEquals("new", saved())
    }

    @Test fun initScopeFaultDisablesBeforeCleanupAndPreservesFirstStartRollback() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState(initializing = true))
        val boundary = PluginFaultBoundary({}, {})
        boundary.report(IllegalStateException("init child"))
        assertFalse(boundary.beginStart())
        val result = persistPluginFault(states, updates, id, 2, boundary.takeFault(boundary)!!.duringInitialization)
        assertTrue(result.errors.isEmpty())
        assertFalse(states.read(id).enabled)
        assertTrue(states.read(id).resumeAfterRollback)
        assertNull(updates.read(id)!!.startedAt)
        assertTrue(result.rollback)
        assertTrue(recover(interrupted = states.read(id).initializing))
        assertEquals("old", saved())
    }

    @Test fun runtimeScopeFaultNeverAutomaticallyRollsBackUserData() = fixture {
        activated()
        updates.started(id, 2)
        val states = PluginStateStore(root)
        states.write(id, PluginState())
        val result = persistPluginFault(states, updates, id, 2, false)
        assertFalse(result.rollback)
        assertFalse(PluginStateStore(root).read(id).enabled)
        assertFalse(states.read(id).resumeAfterRollback)
        assertEquals(UpdatePhase.OBSERVING, updates.read(id)!!.phase)
        assertEquals("new", saved())
    }

    @Test fun failedPersistenceReturnsDisabledStateAndDoesNotMarkUpdateStarted() = fixture {
        activated()
        val states = PluginStateStore(root)
        root.resolve("plugin-state").writeText("blocks state directory")
        val result = persistPluginFault(states, updates, id, 2, true)
        assertFalse(result.state.enabled)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.rollback)
        assertNull(updates.read(id)!!.startedAt)
    }

    @Test fun unreadableStateIsStillReplacedByDisabledFault() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState())
        root.resolve("plugin-state").resolve("$id.properties").writeText("enabled=maybe\n")
        val result = persistPluginFault(states, updates, id, 2, false)
        assertTrue(result.errors.isNotEmpty())
        assertFalse(states.read(id).enabled)
    }

    @Test fun uninstallReplacesCorruptStateWithoutLosingTheRequest() = fixture {
        val states = PluginStateStore(root)
        states.write(id, PluginState())
        root.resolve("plugin-state").resolve("$id.properties").writeText("enabled=maybe\n")
        val state = persistPluginUninstall(states, id, true)
        assertTrue(state.pendingDelete)
        assertTrue(state.clearData)
        assertEquals(state, states.read(id))
    }

    @Test fun durableStartStorageIsCompleteBeforePublicationAndWriteFailurePreventsIt() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState(initializing = true))
        kotlinx.coroutines.runBlocking {
            assertTrue(PluginFaultBoundary({}, {}).completeStart(
                persist = {
                    updates.started(id, 2)
                    states.write(id, states.read(id).copy(initializing = false))
                },
                publish = {
                    assertNotNull(updates.read(id)!!.startedAt)
                    assertFalse(states.read(id).initializing)
                },
            ))
            // Actual filesystem obstruction at the second durable write.
            states.remove(id)
            root.resolve("plugin-state").deleteRecursively()
            root.resolve("plugin-state").writeText("blocks state directory")
            var published = false
            try {
                PluginFaultBoundary({}, {}).completeStart(
                    { updates.started(id, 2); states.write(id, PluginState()) },
                    { published = true },
                )
                fail("state write should fail")
            } catch (_: IOException) { }
            assertFalse(published)
        }
    }

    @Test fun acceptedDurableRollbackCannotBeInterruptedByEligibilityRefreshFailure() = fixture {
        activated()
        var active = true
        var deferred = false
        var affordance = true
        kotlinx.coroutines.runBlocking {
            requestPluginRollback(
                request = { updates.requestRollback(id) },
                refreshRejected = { throw IOException("optional refresh failed") },
                accepted = { deferred = true; affordance = false },
                close = { active = false },
            )
        }
        assertEquals(UpdatePhase.ROLLBACK, updates.read(id)!!.phase)
        assertFalse(active)
        assertTrue(deferred)
        assertFalse(affordance)
    }

    @Test fun corruptRecordIsAnError() = fixture {
        activated()
        root.resolve("plugin-updates/$id.properties").writeText("from=1\n")
        assertThrows(IOException::class.java) { updates.read(id) }
        root.resolve("plugin-updates/$id.properties").writeText("from=x\nto=2\nphase=OBSERVING\n")
        assertThrows(IOException::class.java) { updates.read(id) }
    }

    @Test fun recoveryReceiptSurvivesStateWriteFailureAndColdStartRestoresEnabledIntent() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState(initializing = true))
        persistPluginFault(states, updates, id, 2, true)
        val disabled = states.read(id)
        val failure = IOException("state disk write failed")
        assertThrows(IOException::class.java) {
            recoverPluginUpdate(states, updates, id, disabled, false, reports::add) { _, _ -> throw failure }
        }
        assertEquals(UpdatePhase.RECOVERED, updates.read(id)!!.phase)
        assertFalse(states.read(id).enabled)
        assertTrue(states.read(id).resumeAfterRollback)
        assertEquals("old", saved())
        val coldUpdates = PluginUpdates(root, store, { clock })
        assertTrue(recoverPluginUpdate(states, coldUpdates, id, states.read(id), false, reports::add))
        assertEquals(PluginState(enabled = true), states.read(id))
        assertNull(coldUpdates.read(id))
        // Once old code runs, a subsequent cold start must not overwrite its data.
        save("old code wrote after recovery")
        assertFalse(recoverPluginUpdate(states, coldUpdates, id, states.read(id), false, reports::add))
        assertEquals("old code wrote after recovery", saved())
    }

    @Test fun receiptCutpointsBeforeAndAfterStateWriteNeverRestoreDataTwice() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState(enabled = false, initializing = true, resumeAfterRollback = true))
        updates.failed(id, 2)
        // Crash after package/data recovery but before desired state restoration.
        assertTrue(updates.recover(id, true, false, reports::add))
        assertEquals(UpdatePhase.RECOVERED, updates.read(id)!!.phase)
        // Crash after state restoration but before receipt acknowledgement.
        states.write(id, PluginState())
        save("data after completed restore")
        assertTrue(recoverPluginUpdate(states, PluginUpdates(root, store), id, states.read(id), false, reports::add))
        assertEquals("data after completed restore", saved())
        assertNull(updates.read(id))
    }

    @Test fun ordinaryAndManualRollbackPreserveExistingEnabledIntent() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState(initializing = true))
        updates.failed(id, 2) // ordinary onEnable exception, not managed scope
        assertTrue(recoverPluginUpdate(states, updates, id, states.read(id), false, reports::add))
        assertEquals(PluginState(), states.read(id))
        publish(2)
        recover()
        updates.started(id, 2)
        states.write(id, PluginState(enabled = false))
        updates.requestRollback(id)
        assertTrue(recoverPluginUpdate(states, updates, id, states.read(id), true, reports::add))
        assertEquals(PluginState(enabled = false), states.read(id))
    }

    @Test fun pendingDeleteAndCorruptUpdateCanDeleteThenFreshInstallInSameProcess() = fixture {
        val states = PluginStateStore(root)
        val gate = PluginLoadGate()
        repeat(2) { scenario ->
            publish(1)
            states.write(id, PluginState(enabled = false, pendingDelete = scenario == 0))
            val blocked = mutableSetOf(id)
            if (scenario == 1) {
                root.resolve("plugin-updates").mkdirs()
                root.resolve("plugin-updates/$id.properties").writeText("from=corrupt")
            }
            kotlinx.coroutines.runBlocking {
                try {
                    deletePluginRecovery(id, blocked) { throw IOException("delete failed") }
                    fail("missing failure")
                } catch (_: IOException) { }
                assertTrue(id in blocked)
                deletePluginRecovery(id, blocked) {
                    store.delete(id, true)
                    updates.forget(id)
                    states.remove(id)
                }
            }
            assertFalse(id in blocked)
            publish(1)
            states.write(id, PluginState())
            assertTrue(states.read(id).enabled)
            assertFalse(gate.wasAttempted(id))
            assertEquals(listOf(1L), store.versions(id))
            store.delete(id, true)
            states.remove(id)
        }
        // Clearing recovery quarantine must never reset the independent dex-attempt gate.
        gate.begin(id)
        val blocked = mutableSetOf(id)
        kotlinx.coroutines.runBlocking { deletePluginRecovery(id, blocked) {} }
        assertFalse(id in blocked)
        assertTrue(gate.wasAttempted(id))
    }

    @Test fun faultDuringActualStartWritesNeverPublishesOrSchedulesAutomaticRollback() = fixture {
        activated()
        val states = PluginStateStore(root)
        states.write(id, PluginState(initializing = true))
        val boundary = PluginFaultBoundary({}, {})
        kotlinx.coroutines.runBlocking {
            assertFalse(boundary.completeStart(
                persist = {
                    updates.started(id, 2)
                    boundary.report(IllegalStateException("between durable writes"))
                    states.write(id, states.read(id).copy(initializing = false))
                },
                publish = { fail("faulted runtime must never expose pages") },
            ))
        }
        val fault = boundary.takeFault(boundary)!!
        assertFalse(fault.duringInitialization)
        val result = persistPluginFault(states, updates, id, 2, fault.duringInitialization)
        assertFalse(result.rollback)
        assertFalse(states.read(id).enabled)
        assertNotNull(updates.read(id)!!.startedAt)
        assertEquals(UpdatePhase.OBSERVING, updates.read(id)!!.phase)
    }
}
