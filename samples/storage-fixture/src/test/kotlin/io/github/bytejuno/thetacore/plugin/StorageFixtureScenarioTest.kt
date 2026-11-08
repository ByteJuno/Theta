package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** JVM orchestration checks only. Crash images are NOT evidence of Android process death. */
class StorageFixtureScenarioTest {
    private class SimulatedDeath : Error()
    private fun root(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("s1-fixture-unit").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun boundedArchiveIsValidAndRepeatable() {
        for (version in 1L..2L) {
            val bytes = StorageFixtureScenario.archive(version)
            assertTrue(bytes.size < 4096)
            assertArrayEquals(bytes, StorageFixtureScenario.archive(version))
            assertEquals(version, validatePluginDataZip(bytes, 1, PluginDataLimits()).manifest.versionCode)
        }
        assertFalse(StorageFixtureScenario.archive(1).contentEquals(StorageFixtureScenario.archive(2)))
    }

    @Test fun expectedShaRejectsValidUpdateWithoutChangingEitherEnabledState() = root { dir ->
        for (enabled in listOf(false, true)) {
            val scenario = StorageFixtureScenario(dir, "process-a", 10)
            scenario.execute("PREPARE", PluginPublicationBoundary.INTENT, enabled)
            val result = scenario.execute("MISMATCH", PluginPublicationBoundary.INTENT, enabled)
            assertEquals("PASS", result["status"])
            assertEquals("Data ZIP index digest mismatch", result["rejection"])
            assertEquals(true, result["matchingDigestAccepted"])
            assertEquals(1L, result["selectedVersion"])
            assertEquals(enabled, result["enabled"])
            assertNotEquals(result["actualSha256"], result["expectedSha256"])
        }
    }

    @Test fun snapshotsAtAllRealHooksModelMovedVersusUnpublishedAndRequireFreshProcess() = root { dir ->
        for (enabled in listOf(false, true)) for (point in PluginPublicationBoundary.values()) {
            val live = File(dir, "live-${point.name}-$enabled").apply { mkdirs() }
            val image = File(dir, "image-${point.name}-$enabled")
            val first = StorageFixtureScenario(live, "process-a", 10)
            first.execute("PREPARE", point, enabled)
            assertThrows(SimulatedDeath::class.java) {
                first.execute("CRASH", point, enabled,
                    beforeKill = { evidence ->
                        assertEquals("CUTPOINT_REACHED", evidence["status"])
                        assertEquals(point == PluginPublicationBoundary.MOVED, evidence["updatePublished"])
                        assertEquals(1L, evidence["selectedVersion"])
                        assertTrue(live.copyRecursively(image))
                    }, killSelf = { throw SimulatedDeath() })
            }
            assertThrows(IllegalStateException::class.java) {
                StorageFixtureScenario(image, "process-a", 10).execute("RECOVER", point, enabled)
            }
            val result = StorageFixtureScenario(image, "process-b", 11).execute("RECOVER", point, enabled)
            assertEquals("PASS", result["status"])
            assertEquals(true, result["freshProcess"])
            assertEquals(1L, result["selectedVersion"])
            assertEquals(enabled, result["enabled"])
            assertEquals(point == PluginPublicationBoundary.MOVED, result["updatePublished"])
            assertEquals(false, result["updateSelected"])
            assertEquals(true, result["baselineBytesIntact"])
            assertEquals(true, result["publicationJournalEmpty"])
            assertEquals(true, result["stagingRemoved"])
        }
    }

    @Test fun recoveryWithoutCutpointFailsInsteadOfClaimingPass() = root { dir ->
        val fixture = StorageFixtureScenario(dir, "process-a", 10)
        fixture.execute("PREPARE", PluginPublicationBoundary.MOVED, false)
        assertThrows(IllegalStateException::class.java) {
            StorageFixtureScenario(dir, "process-b", 11).execute("RECOVER", PluginPublicationBoundary.MOVED, false)
        }
    }
}
