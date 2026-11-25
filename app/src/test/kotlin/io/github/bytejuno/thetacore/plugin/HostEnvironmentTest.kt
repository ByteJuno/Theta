package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostEnvironmentTest {
    private fun dexApkBytes(manifestJson: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun dexApk(manifestJson: String): File {
        val file = Files.createTempFile("theta-manifest-test-", ".apk").toFile()
        file.writeBytes(dexApkBytes(manifestJson))
        return file
    }

    @Test
    fun legacySixFieldDexManifestAndInstalledPackageRecoveryDoNotImposeDataLengthCap() {
        val root = Files.createTempDirectory("theta-legacy-dex-restore").toFile()
        try {
            val longLegacyName = "LegacyPlugin-" + "N".repeat(3000)
            val longLegacyVersion = "1.0.0+" + "v".repeat(2500)
            val longLegacyEntry = "io.example." + "sub.".repeat(550) + "LegacyEntry"
            val legacyJson = """{"schema":1,"type":"dex","id":"io.example.legacy","name":"$longLegacyName","version":"$longLegacyVersion","versionCode":3,"apiLevel":1,"entry":"$longLegacyEntry","author":"Legacy Author"}"""

            val verifier = PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) }
            val store = fixturePluginStore(root, root.resolve("cache"), verifier)
            val staged = store.stage(dexApkBytes(legacyJson).inputStream())
            store.install(staged)

            // Cold store restore must load previously installed legacy dex packages without rejecting >2048-char fields.
            val coldStore = fixturePluginStore(root, root.resolve("cache"), verifier)
            val rejected = mutableListOf<String>()
            val installed = coldStore.installed(onFailure = { fail("Unexpected restore failure: $it") }, onRejected = rejected::add)
            assertTrue(rejected.isEmpty())
            assertEquals(1, installed.size)
            val manifest = installed.single().manifest
            assertEquals("io.example.legacy", manifest.id)
            assertEquals(longLegacyName, manifest.name)
            assertEquals(longLegacyVersion, manifest.version)
            assertEquals(3L, manifest.versionCode)
            assertEquals(1, manifest.apiLevel)
            assertEquals(longLegacyEntry, manifest.entry)
            assertTrue(manifest.provides.isEmpty())
            assertTrue(manifest.declares.isEmpty())
            assertEquals(PluginRequirements(), manifest.requires)
            assertFalse(manifest.requires.requiresAny)

            // Total 64 KiB manifest size limit is still strictly enforced.
            val oversizedJson = """{"schema":1,"type":"dex","id":"io.example.huge","name":"${"H".repeat(66_000)}","version":"1","versionCode":1,"apiLevel":1,"entry":"io.example.Huge"}"""
            val hugeApk = dexApk(oversizedJson)
            try {
                assertThrows(PluginPackageException::class.java) { readPluginManifest(hugeApk) }
            } finally {
                hugeApk.delete()
            }
        } finally {
            deleteFixtureRoot(root)
        }
    }

    @Test
    fun dexManifestParsesProvidesDeclaresEmptyHooksAndAllFourRequirementFlags() {
        val json = """
            {
              "schema": 1,
              "type": "dex",
              "id": "io.example.automation",
              "name": "Screen Helper",
              "version": "2.1.0",
              "versionCode": 12,
              "apiLevel": 1,
              "entry": "io.example.ScreenHelper",
              "provides": ["tool", "settings"],
              "declares": ["network", "clipboard"],
              "requires": {
                "root": false,
                "lsposed": true,
                "accessibility": true,
                "shizuku": false
              },
              "hooks": []
            }
        """.trimIndent()
        val manifest = parsePluginManifest(json.toByteArray(Charsets.UTF_8))
        assertEquals(listOf("tool", "settings"), manifest.provides)
        assertEquals(listOf("network", "clipboard"), manifest.declares)
        assertEquals(
            PluginRequirements(root = false, lsposed = true, accessibility = true, shizuku = false),
            manifest.requires,
        )
        assertTrue(manifest.requires.requiresAny)

        val allTrue = parsePluginManifest(
            """{"schema":1,"type":"dex","id":"io.example.full","name":"Full","version":"1.0","versionCode":1,"apiLevel":1,"entry":"io.example.Full","requires":{"root":true,"lsposed":true,"accessibility":true,"shizuku":true}}"""
                .toByteArray(Charsets.UTF_8),
        )
        assertEquals(
            PluginRequirements(root = true, lsposed = true, accessibility = true, shizuku = true),
            allTrue.requires,
        )
    }

    @Test
    fun dexManifestFailsClosedOnInvalidNewFieldsUnknownRequirementKeysDuplicatesControlCharsAndBounds() {
        val base = """{"schema":1,"type":"dex","id":"io.example.bad","name":"Bad","version":"1.0","versionCode":1,"apiLevel":1,"entry":"io.example.Bad""""
        val tooManyItems = (1..65).joinToString(",") { "\"cap$it\"" }
        val overlongItem = "a".repeat(129)
        val invalidPayloads = listOf(
            "$base,\"requires\":[]}",
            "$base,\"requires\":null}",
            "$base,\"requires\":{\"adb\":false}}",
            "$base,\"requires\":{\"root\":\"false\"}}",
            "$base,\"requires\":{\"lsposed\":1}}",
            "$base,\"requires\":{\"accessibility\":null}}",
            "$base,\"requires\":{\"shizuku\":{}}}",
            "$base,\"requires\":{\"root\":false,\"root\":true}}",
            "$base,\"requires\":{\"root\":false,\"ro\\u006ft\":true}}",
            "$base,\"provides\":\"tool\"}",
            "$base,\"provides\":[\"tool\",\"tool\"]}",
            "$base,\"declares\":[\"\"]}",
            "$base,\"declares\":[\"   \"]}",
            "$base,\"provides\":[\"bad\\u0000char\"]}",
            "$base,\"provides\":[\"bad\\u007fchar\"]}",
            "$base,\"provides\":[\"$overlongItem\"]}",
            "$base,\"provides\":[$tooManyItems]}",
            "$base,\"hooks\":[{}]}",
        )
        for (payload in invalidPayloads) {
            assertThrows(payload, PluginPackageException::class.java) {
                parsePluginManifest(payload.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun dataManifestAllowsFourFalseRequirementKeysAndRejectsAnyTrueRequirement() {
        val valid = """{"schema":1,"type":"data","id":"io.example.data","name":"Data","version":"1.0","versionCode":1,"apiLevel":1,"requires":{"root":false,"lsposed":false,"accessibility":false,"shizuku":false},"provides":["skill"],"declares":["network"]}"""
        val parsed = readPluginDataManifest(valid.toByteArray(Charsets.UTF_8), hostApiLevel = 1)
        assertEquals(listOf("skill"), parsed.provides)
        assertEquals(listOf("network"), parsed.declares)

        for (key in listOf("root", "lsposed", "accessibility", "shizuku")) {
            val requiring = """{"schema":1,"type":"data","id":"io.example.data","name":"Data","version":"1.0","versionCode":1,"apiLevel":1,"requires":{"$key":true}}"""
            assertThrows("Data ZIP must reject $key=true", PluginPackageException::class.java) {
                readPluginDataManifest(requiring.toByteArray(Charsets.UTF_8), hostApiLevel = 1)
            }
            val stringBool = """{"schema":1,"type":"data","id":"io.example.data","name":"Data","version":"1.0","versionCode":1,"apiLevel":1,"requires":{"$key":"false"}}"""
            assertThrows("Data ZIP must reject string boolean for $key", PluginPackageException::class.java) {
                readPluginDataManifest(stringBool.toByteArray(Charsets.UTF_8), hostApiLevel = 1)
            }
        }

        val unknownReq = """{"schema":1,"type":"data","id":"io.example.data","name":"Data","version":"1.0","versionCode":1,"apiLevel":1,"requires":{"bluetooth":false}}"""
        assertThrows(PluginPackageException::class.java) {
            readPluginDataManifest(unknownReq.toByteArray(Charsets.UTF_8), hostApiLevel = 1)
        }
    }

    @Test
    fun otherAppAccessibilityServicesDoNotSatisfyHostAndShizukuManagerOnlyReportsDetectedUnverified() {
        val hostPkg = "io.github.bytejuno.thetacore"

        // Case 1: Other apps have Accessibility enabled, Theta does NOT; Shizuku Manager is installed,
        // su binary exists on PATH, LSPosed is disconnected.
        val passiveStatus = evaluatePassiveHostEnvironment(
            hostPackageName = hostPkg,
            accessibilityManagerEnabled = true,
            enabledAccessibilityPackages = listOf("com.google.android.marvin.talkback", "com.example.other"),
            shizukuManagerInstalled = true,
            shizukuServiceConfirmed = false,
            suBinaryDetected = true,
            rootGrantConfirmed = false,
            lsposedConnected = false,
        )
        assertFalse("Other apps' accessibility services must never mark Theta ready", passiveStatus.accessibilityEnabled)
        assertEquals(EnvironmentReadiness.UNAVAILABLE, passiveStatus.readiness(HostRequirementKey.ACCESSIBILITY))

        assertTrue(passiveStatus.shizukuAvailable)
        assertFalse(passiveStatus.shizukuReady)
        assertEquals(EnvironmentReadiness.DETECTED_UNVERIFIED, passiveStatus.readiness(HostRequirementKey.SHIZUKU))

        assertTrue(passiveStatus.rootAvailable)
        assertFalse(passiveStatus.rootReady)
        assertEquals(EnvironmentReadiness.DETECTED_UNVERIFIED, passiveStatus.readiness(HostRequirementKey.ROOT))
        assertEquals(EnvironmentReadiness.UNAVAILABLE, passiveStatus.readiness(HostRequirementKey.LSPOSED))

        val eval = PluginRequirements(accessibility = true, shizuku = true, root = true, lsposed = true).evaluate(passiveStatus)
        assertFalse(eval.allSatisfied)
        assertEquals(listOf(HostRequirementKey.ACCESSIBILITY, HostRequirementKey.LSPOSED), eval.missing)
        assertEquals(listOf(HostRequirementKey.SHIZUKU, HostRequirementKey.ROOT), eval.unverified)
        assertTrue(eval.missingAccessibility)
        assertEquals(EnvironmentReadiness.DETECTED_UNVERIFIED, eval.shizukuReadiness)
        assertEquals(EnvironmentReadiness.DETECTED_UNVERIFIED, eval.rootReadiness)

        // Case 2: Theta's own Accessibility service enabled + Shizuku service & Root explicitly confirmed + LSPosed connected.
        val confirmedStatus = evaluatePassiveHostEnvironment(
            hostPackageName = hostPkg,
            accessibilityManagerEnabled = true,
            enabledAccessibilityPackages = listOf("com.example.other", hostPkg),
            shizukuManagerInstalled = true,
            shizukuServiceConfirmed = true,
            suBinaryDetected = true,
            rootGrantConfirmed = true,
            lsposedConnected = true,
        )
        assertTrue(confirmedStatus.accessibilityEnabled)
        assertTrue(confirmedStatus.shizukuReady)
        assertTrue(confirmedStatus.rootReady)
        assertTrue(confirmedStatus.lsposedActive)
        val readyEval = PluginRequirements(accessibility = true, shizuku = true, root = true, lsposed = true).evaluate(confirmedStatus)
        assertTrue(readyEval.allSatisfied)
        assertTrue(readyEval.missing.isEmpty())
        assertTrue(readyEval.unverified.isEmpty())

        // Case 3: Root-free plugin with no requirements.
        val noneEval = PluginRequirements().evaluate(HostEnvironmentStatus())
        assertFalse(noneEval.hasRequirements)
        assertTrue(noneEval.isRootFree)
        assertTrue(noneEval.allSatisfied)
    }

    @Test
    fun passiveSuPathDetectionAndSafeDetectorHandleMissingPathsAndFailuresGracefully(): Unit = runBlocking {
        val dir = Files.createTempDirectory("theta-env-su-test").toFile()
        try {
            val emptyBin = File(dir, "empty-bin").apply { mkdirs() }
            val dirNamedSu = File(dir, "dir-bin").apply {
                mkdirs()
                File(this, "su").mkdirs()
            }
            assertFalse(hasSuBinaryInPaths(listOf(File(dir, "nonexistent"), emptyBin, dirNamedSu)))

            val realBin = File(dir, "real-bin").apply {
                mkdirs()
                File(this, "su").writeText("#!/system/bin/sh\n")
            }
            assertTrue(hasSuBinaryInPaths(listOf(emptyBin, realBin)))

            val fallback = detectHostEnvironmentSafely { throw IllegalStateException("binder failure") }
            assertEquals(HostEnvironmentStatus(), fallback)

            assertThrows(CancellationException::class.java) {
                runBlocking {
                    detectHostEnvironmentSafely { throw CancellationException("cancelled") }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
