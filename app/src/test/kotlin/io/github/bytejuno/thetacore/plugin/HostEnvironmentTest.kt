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
import org.junit.Test

class HostEnvironmentTest {
    private fun dexApk(manifestJson: String): File {
        val file = Files.createTempFile("theta-manifest-test-", ".apk").toFile()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        file.writeBytes(out.toByteArray())
        return file
    }

    @Test
    fun legacySixFieldDexManifestRemainsBackwardCompatible() {
        val json = """{"schema":1,"type":"dex","id":"io.example.legacy","name":"Legacy","version":"1.0.0","versionCode":3,"apiLevel":1,"entry":"io.example.LegacyPlugin"}"""
        val apk = dexApk(json)
        try {
            val manifest = readPluginManifest(apk)
            assertEquals("io.example.legacy", manifest.id)
            assertEquals("Legacy", manifest.name)
            assertEquals("1.0.0", manifest.version)
            assertEquals(3L, manifest.versionCode)
            assertEquals(1, manifest.apiLevel)
            assertEquals("io.example.LegacyPlugin", manifest.entry)
            assertTrue(manifest.provides.isEmpty())
            assertTrue(manifest.declares.isEmpty())
            assertEquals(PluginRequirements(), manifest.requires)
            assertFalse(manifest.requires.requiresAny)
        } finally {
            apk.delete()
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
    fun dexManifestFailsClosedOnInvalidTypesUnknownKeysDuplicatesControlCharsAndBounds() {
        val base = """{"schema":1,"type":"dex","id":"io.example.bad","name":"Bad","version":"1.0","versionCode":1,"apiLevel":1,"entry":"io.example.Bad""""
        val tooManyItems = (1..65).joinToString(",") { "\"cap$it\"" }
        val overlongItem = "a".repeat(129)
        val invalidPayloads = listOf(
            "$base,\"unknownField\":true}",
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
    fun environmentEvaluationAccuratelyComputesRootFreeReadyAndMissingItems() {
        val noneEval = PluginRequirements().evaluate(HostEnvironmentStatus())
        assertFalse(noneEval.hasRequirements)
        assertTrue(noneEval.isRootFree)
        assertTrue(noneEval.allSatisfied)
        assertTrue(noneEval.missing.isEmpty())

        val rootFreeReqs = PluginRequirements(accessibility = true, shizuku = true)
        val partialStatus = HostEnvironmentStatus(accessibilityEnabled = false, shizukuAvailable = true)
        val partialEval = rootFreeReqs.evaluate(partialStatus)
        assertTrue(partialEval.hasRequirements)
        assertTrue(partialEval.isRootFree)
        assertFalse(partialEval.allSatisfied)
        assertEquals(listOf(HostRequirementKey.ACCESSIBILITY), partialEval.missing)
        assertTrue(partialEval.missingAccessibility)
        assertFalse(partialEval.missingShizuku)
        assertFalse(partialEval.missingPrivileged)

        val readyEval = rootFreeReqs.evaluate(HostEnvironmentStatus(accessibilityEnabled = true, shizukuAvailable = true))
        assertTrue(readyEval.allSatisfied)
        assertTrue(readyEval.missing.isEmpty())

        val privilegedReqs = PluginRequirements(root = true, lsposed = true)
        val missingPrivEval = privilegedReqs.evaluate(HostEnvironmentStatus(rootAvailable = true, lsposedActive = false))
        assertFalse(missingPrivEval.isRootFree)
        assertFalse(missingPrivEval.allSatisfied)
        assertEquals(listOf(HostRequirementKey.LSPOSED), missingPrivEval.missing)
        assertTrue(missingPrivEval.missingPrivileged)
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
