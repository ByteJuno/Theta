package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real ZIP streams and real filesystem operations; no fake APK verifier in this suite. */
class PluginDataStoreTest {
    private val id = "test.data"
    private fun manifest(version: Long = 1) = """{"schema":1,"type":"data","id":"$id","name":"Data","version":"v$version","versionCode":$version,"apiLevel":1}"""
    private fun archive(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private fun bundle(version: Long = 1, vararg payload: Pair<String, ByteArray>) =
        archive("theta-plugin.json" to manifest(version).toByteArray(), *payload)
    private fun text(path: String, value: String) = path to value.toByteArray()
    private fun rejected(action: () -> Unit) {
        assertThrows(Exception::class.java) { action() }
    }
    private fun withRoot(action: (File, PluginDataStore) -> Unit) {
        val root = Files.createTempDirectory("theta-data-test").toFile()
        try { action(root, PluginDataStore(root, 1)) }
        finally { root.deleteRecursively() }
    }
    private fun publish(store: PluginDataStore, bytes: ByteArray): PublishedPluginData {
        val staged = store.stageData(bytes.inputStream())
        return store.publishConfirmedData(staged, staged.sha256, true)
    }
    private fun stageFile(root: File): File = root.resolve("plugin-data-staging").listFiles()!!.single().resolve("plugin.zip")
    private fun assertNoStages(root: File) {
        assertTrue(root.resolve("plugin-data-staging").listFiles().orEmpty().isEmpty())
    }
    private fun signatures(bytes: ByteArray, signature: Int): List<Int> = (0..bytes.size - 4).filter { u32(bytes, it) == signature.toLong() }
    private fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, at: Int) = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
    private fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte()
    }
    private fun put32(bytes: ByteArray, at: Int, value: Long) {
        put16(bytes, at, value.toInt()); put16(bytes, at + 2, (value ushr 16).toInt())
    }

    @Test fun explicitUnsignedImportPublishesExactBytesWithoutActivation() = withRoot { root, store ->
        val bytes = bundle(1,
            text("skills/hello/SKILL.md", "# Hello\nA declarative skill."),
            text("personas/friendly.json", "{}"), text("lang/en.json", "{\"hello\":\"Hello\"}"),
            text("providers/example.json", """{"id":"example","name":"Example","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"example-large"}]}"""),
            text("mcp/home.json", """{"id":"home","name":"Home","url":"https://mcp.example.com/mcp","auth":"oauth"}"""))
        val staged = store.stageData(ByteArrayInputStream(bytes))
        assertEquals(DataNotice.AUTHOR_UNVERIFIED, staged.authorNotice)
        assertEquals(DataNotice.DIGEST_ONLY, staged.digestNotice)
        assertEquals(setOf("api.example.com", "mcp.example.com"), staged.destinations.map { it.host }.toSet())
        assertFalse(root.resolve("plugins/$id/1").exists())
        val result = store.publishConfirmedData(staged, staged.sha256, true)
        assertEquals(root.resolve("plugins/$id/1/plugin.zip"), result.zip)
        assertArrayEquals(bytes, result.zip.readBytes())
        assertFalse(root.resolve("plugin-trust").exists())
        assertFalse(root.resolve("plugin-state").exists())
        assertFalse(root.resolve("plugin-data").exists())
        assertNoStages(root)
        rejected { store.publishConfirmedData(staged, staged.sha256, true) }
    }

    @Test fun dataUpdateRetainsOldBytesAndExistingSelection() = withRoot { root, store ->
        val first = publish(store, bundle(1))
        val old = first.zip.readBytes()
        val selection = root.resolve("test-caller-selection").apply { writeText("1") }
        publish(store, bundle(2))
        assertArrayEquals(old, first.zip.readBytes())
        assertEquals("1", selection.readText()) // Store deliberately has no activation API.
        for (version in listOf(1L, 2L)) rejected { store.stageData(bundle(version).inputStream()) }
        assertTrue(root.resolve("plugins/$id/2/plugin.zip").isFile)
        assertNoStages(root)
    }

    @Test fun freshStoreRecognizesPublishedDataWithoutAdoptingCode() = withRoot { root, store ->
        publish(store, bundle(1))
        publish(PluginDataStore(root, 1), bundle(2))
        assertTrue(root.resolve("plugins/$id/2/plugin.zip").isFile)
    }

    @Test fun confirmationAndIndexDigestAreMandatoryConsistencyChecks() = withRoot { root, store ->
        rejected { store.stageData(bundle().inputStream(), "a".repeat(64)) }
        rejected { store.stageData(bundle().inputStream(), "not-a-digest") }
        var staged = store.stageData(bundle().inputStream())
        rejected { store.publishConfirmedData(staged, "b".repeat(64), true) }
        staged = store.stageData(bundle().inputStream())
        rejected { store.publishConfirmedData(staged, staged.sha256, false) }
        assertFalse(root.resolve("plugins/$id").exists())
        assertNoStages(root)
    }

    @Test fun tamperedStageCannotPublishAndExternalFileCannotBeForgedAsStage() = withRoot { root, store ->
        val staged = store.stageData(bundle().inputStream())
        stageFile(root).writeBytes(bundle(2))
        rejected { store.publishConfirmedData(staged, staged.sha256, true) }
        val forged = StagedPluginData(staged.manifest, staged.sha256, staged.destinations)
        rejected { store.publishConfirmedData(forged, forged.sha256, true) }
        assertFalse(root.resolve("plugins/$id/1").exists())
        assertNoStages(root)
    }

    @Test fun failedUpdateKeepsPreviousVersionAndNoNewVisibility() = withRoot { root, store ->
        val first = publish(store, bundle(1))
        val old = first.zip.readBytes()
        val staged = store.stageData(bundle(2).inputStream())
        stageFile(root).writeBytes(byteArrayOf(1, 2, 3))
        rejected { store.publishConfirmedData(staged, staged.sha256, true) }
        assertArrayEquals(old, first.zip.readBytes())
        assertFalse(root.resolve("plugins/$id/2").exists())
        assertNoStages(root)
    }

    @Test fun encryptionZip64AndSymlinkMetadataExtrasAreRejected() = withRoot { root, store ->
        for (mutate in listOf<(ByteArray, Int) -> Unit>(
            { bytes, at -> put16(bytes, at + 8, u16(bytes, at + 8) or 1) },
            { bytes, at -> put16(bytes, at + 6, 45) },
            { bytes, at -> put32(bytes, at + 42, 0xffffffffL) },
        )) {
            val bytes = bundle()
            mutate(bytes, signatures(bytes, 0x02014b50).single())
            rejected { store.stageData(bytes.inputStream()) }
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry("theta-plugin.json")
            entry.extra = byteArrayOf(0x0d, 0x00, 0x00, 0x00) // PKWARE Unix metadata.
            zip.putNextEntry(entry)
            zip.write(manifest().toByteArray())
            zip.closeEntry()
        }
        rejected { store.stageData(output.toByteArray().inputStream()) }
        assertNoStages(root)
    }

    @Test fun stagesBelongToTheirStoreAndCanBeCancelled() = withRoot { root, store ->
        val staged = store.stageData(bundle().inputStream())
        rejected { PluginDataStore(root, 1).publishConfirmedData(staged, staged.sha256, true) }
        assertTrue(stageFile(root).exists())
        store.discardData(staged)
        assertNoStages(root)
    }

    @Test fun strictManifestRejectsCoercionsDuplicateKeysAndCodeEntry() = withRoot { root, store ->
        val original = manifest()
        val invalid = listOf(
            original.replace("\"schema\":1", "\"schema\":\"1\""),
            original.replace("\"schema\":1", "\"schema\":2"),
            original.replace("\"type\":\"data\"", "\"type\":\"dex\""),
            original.replace("\"name\":\"Data\"", "\"name\":5"),
            original.replace("\"versionCode\":1", "\"versionCode\":1.0"),
            original.replace("\"versionCode\":1", "\"versionCode\":0"),
            original.replace("\"versionCode\":1", "\"versionCode\":9223372036854775808"),
            original.replace("\"apiLevel\":1", "\"apiLevel\":2"),
            original.replace(id, "../escape"),
            original.dropLast(1) + ",\"entry\":\"Code\"}",
            original.dropLast(1) + ",\"schema\":1}",
            original.dropLast(1) + ",\"sche\\u006da\":1}",
            original.dropLast(1) + ",\"requires\":{\"root\":true}}",
            original.dropLast(1) + ",\"requires\":{\"root\":\"false\"}}",
            original.dropLast(1) + ",\"hooks\":[{}]}",
            original.dropLast(1) + ",\"unknown\":true}",
            original.dropLast(1) + ",\"provides\":[3]}",
            original.replace("\"schema\"", "'schema'"),
            original + " {}",
        )
        invalid.forEach { value -> rejected { store.stageData(archive(text("theta-plugin.json", value)).inputStream()) } }
        rejected { store.stageData(archive(text("assets/theta-plugin.json", original)).inputStream()) }
        rejected { store.stageData(archive(text("skills/hello/SKILL.md", "no manifest")).inputStream()) }
        assertNoStages(root)
    }

    /** Each payload goes through a real ZIP and stageData, not just a parser unit call. */
    private fun jsonPayload(path: String, member: String): String = when {
        path.startsWith("providers/") -> """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com",$member}"""
        path.startsWith("mcp/") -> """{"id":"a","name":"A","auth":"oauth","url":"https://mcp.example.com",$member}"""
        else -> "{$member}"
    }
    private val jsonPaths = listOf("providers/a.json", "mcp/a.json", "personas/a.json", "lang/a.json")

    @Test fun bareAndNonstandardPrimitivesAreRejectedAtEveryJsonRoute() = withRoot { root, store ->
        val invalid = listOf("data", "Name", "undefined", "TRUE", "True", "FALSE", "False", "NULL", "Null",
            "+1", ".1", "01", "-01", "1.", "NaN", "Infinity", "-Infinity", "0x10", "1e", "1e+", "1e-", "1e++2", "1e2.3")
        for (token in invalid) {
            for (value in listOf(token, "[$token]", "[{\"nested\":[$token]}]")) {
                // hooks would fail schema too, so assert the lexical rejection specifically.
                val manifestValue = manifest().dropLast(1) + ",\"hooks\":$value}"
                val error = assertThrows("manifest token: $value", PluginPackageException::class.java) {
                    store.stageData(archive(text("theta-plugin.json", manifestValue)).inputStream())
                }
                assertEquals("Invalid JSON primitive", error.message)
                for (path in jsonPaths) {
                    val payload = jsonPayload(path, "\"extra\":$value")
                    assertThrows("$path token: $value", PluginPackageException::class.java) {
                        store.stageData(bundle(1, text(path, payload)).inputStream())
                    }
                }
            }
        }
        for ((quoted, bare) in listOf("\"data\"" to "data", "\"Data\"" to "Name", "\"v1\"" to "v1")) {
            assertThrows(PluginPackageException::class.java) {
                store.stageData(archive(text("theta-plugin.json", manifest().replace(quoted, bare))).inputStream())
            }
        }
        assertNoStages(root)
    }

    @Test fun validPrimitiveLexemesRemainNumbersAndQuotedLookalikesRemainStrings() = withRoot { root, store ->
        val values = """[0,-0,1,-42,0.1,-0.125,1e2,1E+2,-1.25e-2,9223372036854775808,1e9999,1e-9999,true,false,null,"data","Name","01","NaN"]"""
        for (path in jsonPaths) {
            val payload = jsonPayload(path, "\"extra\":$values")
            val staged = store.stageData(bundle(1, text(path, payload)).inputStream())
            store.discardData(staged)
            // Assert exact lexemes as well: accepting them must not round, overflow or coerce.
            val array = dataJson(payload.toByteArray())["extra"] as kotlinx.serialization.json.JsonArray
            assertEquals("-0", (array[1] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("9223372036854775808", (array[9] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals("1e9999", (array[10] as kotlinx.serialization.json.JsonPrimitive).content)
            assertFalse((array[10] as kotlinx.serialization.json.JsonPrimitive).isString)
            assertTrue((array[17] as kotlinx.serialization.json.JsonPrimitive).isString)
        }
        val maximum = manifest().replace("\"versionCode\":1", "\"versionCode\":9223372036854775807")
        val staged = store.stageData(archive(text("theta-plugin.json", maximum)).inputStream())
        assertEquals(Long.MAX_VALUE, staged.manifest.versionCode)
        store.discardData(staged)
        // Valid JSON numbers are still not integer-form schema fields.
        for (token in listOf("1e0", "1.0", "-1", "0", "9223372036854775808")) {
            assertThrows(PluginPackageException::class.java) {
                store.stageData(archive(text("theta-plugin.json", manifest().replace("\"versionCode\":1", "\"versionCode\":$token"))).inputStream())
            }
        }
        assertNoStages(root)
    }

    @Test fun quotedKeysEscapesAndOnlyRfcWhitespaceAreAccepted() = withRoot { root, store ->
        val escaped = """"escapedKey":"\"\\\/\b\f\n\r\t\u0000A𝄞","punctuation":"{}[]:,","nested":[{"same":1},{"same":2}]"""
        for (path in jsonPaths) {
            val payload = " \t\r\n" + jsonPayload(path, escaped) + "\n\r\t "
            store.discardData(store.stageData(bundle(1, text(path, payload)).inputStream()))
            val tree = dataJson(payload.toByteArray())
            assertEquals("\"\\/\b\u000c\n\r\t\u0000A𝄞",
                (tree["escapedKey"] as kotlinx.serialization.json.JsonPrimitive).content)
        }
        val escapedManifest = manifest().replace("\"schema\"", "\"sche\\u006da\"")
            .replace("\"data\"", "\"d\\u0061ta\"").replace("\"Data\"", "\"Da\\u0074a\"")
        val staged = store.stageData(archive(text("theta-plugin.json", " \r\n\t$escapedManifest\t ")).inputStream())
        assertEquals("Data", staged.manifest.name)
        store.discardData(staged)
        assertNoStages(root)
    }

    @Test fun strictStructureStringsEscapesAndWhitespaceRejectLenientExtensions() = withRoot { root, store ->
        val invalidMembers = listOf(
            """bare:1""", """'single':1""", """"x":'single'""", "\"x\":\"\\x41\"", "\"x\":\"\\v\"",
            "\"x\":\"\\'\"", "\"x\":\"\\u123\"", "\"x\":\"\\u12x4\"", "\"x\":\"unterminated",
            "\"x\":\"trailing\\", "\"x\":\"raw\nline\"", "\"x\":\"raw\ttab\"", "\"x\":\"raw\rreturn\"",
            "\"x\":\"raw\u0000nul\"", "\"bad\nkey\":1", "\"x\" :1", "\"x\": 1",
            "\"x\":\u000b1", "\"x\":\u000c1", "\"x\": 1", "\"x\":﻿1",
            """"x":1,""", """"x":[1,]""", """"x":[,1]""", """"x":[1,,2]""", """"x":[1 2]""",
            """"x":{"a":1,}""", """"x":1 "y":2""", """"x"=1""", """"x":1;"y":2""",
            """"x":/*comment*/1""", "\"x\":1//comment\n", """"x":[1}""", """"x":{"a":1]""",
            """"x":true false""", """"x":1:2""", """"x":01""",
        )
        for (path in jsonPaths) {
            for (member in invalidMembers) {
                assertThrows("$path member: $member", PluginPackageException::class.java) {
                    store.stageData(bundle(1, text(path, jsonPayload(path, member))).inputStream())
                }
            }
            for (payload in listOf("[]", "null", "﻿{}", "{} {}", "{}garbage", "{} ", "{}\u0000")) {
                assertThrows("$path document: $payload", PluginPackageException::class.java) {
                    store.stageData(bundle(1, text(path, payload)).inputStream())
                }
            }
        }
        assertNoStages(root)
    }

    @Test fun duplicateDecodedKeysAndDepthAreCheckedBeforeTreeParsing() = withRoot { root, store ->
        for (path in jsonPaths) {
            val escapedA = "\\" + "u0061"
            val escapedSymbol = "\\" + "uD834" + "\\" + "uDD1E"
            for (member in listOf(""""a":1,"a":2""", """"a":1,"$escapedA":2""",
                """"extra":[{"a":1,"$escapedA":2}]""", """"extra":{"/":1,"\/":2}""",
                """"extra":{"𝄞":1,"$escapedSymbol":2}""")) {
                val error = assertThrows(PluginPackageException::class.java) {
                    store.stageData(bundle(1, text(path, jsonPayload(path, member))).inputStream())
                }
                assertEquals("Duplicate JSON key", error.message)
            }
            // Root object counts as level 1; array/object mixtures share one limit.
            for (depth in listOf(31, 32, 2000)) {
                val payload = jsonPayload(path, "\"extra\":" + "[".repeat(depth) + "0" + "]".repeat(depth))
                if (depth == 31) store.discardData(store.stageData(bundle(1, text(path, payload)).inputStream()))
                else {
                    val error = assertThrows(PluginPackageException::class.java) {
                        store.stageData(bundle(1, text(path, payload)).inputStream())
                    }
                    assertEquals("Data JSON nesting exceeds limit", error.message)
                }
            }
        }
        // Object-only boundary and braces inside strings must not change the count.
        val atLimit = "{\"x\":".repeat(32) + "\"{[\\\"\"" + "}".repeat(32)
        store.discardData(store.stageData(bundle(1, text("personas/a.json", atLimit)).inputStream()))
        val tooDeep = "{\"x\":".repeat(33) + "0" + "}".repeat(33)
        val error = assertThrows(PluginPackageException::class.java) {
            store.stageData(bundle(1, text("personas/a.json", tooDeep)).inputStream())
        }
        assertEquals("Data JSON nesting exceeds limit", error.message)
        assertNoStages(root)
    }

    @Test fun documentedEmptyNativeMetadataIsAcceptedWithoutEntry() = withRoot { _, store ->
        val value = manifest().dropLast(1) + ",\"requires\":{\"root\":false,\"lsposed\":false},\"hooks\":[],\"provides\":[\"skill\"],\"declares\":[]}"
        publish(store, archive(text("theta-plugin.json", value)))
    }

    @Test fun traversalAbsoluteBackslashDepthAndCodePayloadsAreRejected() = withRoot { root, store ->
        val paths = listOf("../outside", "/absolute", "C:/absolute", "skills\\bad\\SKILL.md", "skills//SKILL.md",
            "skills/../SKILL.md", "skills/./SKILL.md", "skills/a/b/c/SKILL.md", "skills/name /SKILL.md",
            "skills/CON/SKILL.md", "classes.dex", "plugin.apk", "lib/a.so", "AndroidManifest.xml",
            "skills/a/run.sh", "skills/a/nested.zip", "prompts/prompt.md")
        paths.forEach { path -> rejected { store.stageData(bundle(1, text(path, "payload")).inputStream()) } }
        assertNoStages(root)
        assertFalse(root.resolve("outside").exists())
    }

    @Test fun duplicateAndCaseUnicodeAndDirectoryCollisionsAreRejected() = withRoot { root, store ->
        // ZipOutputStream disallows duplicate names; mutate an equal-length second name in BOTH headers.
        val duplicate = bundle(1, text("lang/aa.json", "{}"), text("lang/bb.json", "{}"))
        for (at in signatures(duplicate, 0x04034b50) + signatures(duplicate, 0x02014b50)) {
            val local = u32(duplicate, at) == 0x04034b50L
            val nameAt = at + if (local) 30 else 46
            val length = u16(duplicate, at + if (local) 26 else 28)
            if (String(duplicate, nameAt, length, Charsets.UTF_8) == "lang/bb.json") {
                "lang/aa.json".toByteArray().copyInto(duplicate, nameAt)
            }
        }
        rejected { store.stageData(duplicate.inputStream()) }
        rejected { store.stageData(bundle(1, text("lang/aa.json", "{}"), text("lang/AA.json", "{}")).inputStream()) }
        rejected { store.stageData(bundle(1, text("skills/A/SKILL.md", "a"), text("skills/a/SKILL.md", "b")).inputStream()) }
        rejected { store.stageData(bundle(1, text("lang/e\u0301.json", "{}")).inputStream()) }
        rejected { store.stageData(bundle(1, text("lang/a.json", "{}"), text("lang/a.json/", "")).inputStream()) }
        assertNoStages(root)
    }

    @Test fun ordinaryExplicitDirectoriesAndStoredEntriesWork() = withRoot { _, store ->
        publish(store, archive(text("theta-plugin.json", manifest()), text("skills/", ""),
            text("skills/hello/", ""), text("skills/hello/SKILL.md", "# Hello"), stored = true))
    }

    @Test fun symlinkDeviceAndExecutableZipModesAreRejected() = withRoot { root, store ->
        for (mode in listOf(0xa1ff, 0x21a4, 0x11a4, 0x81ed)) {
            val bytes = bundle(1, text("skills/hello/SKILL.md", "text"))
            val central = signatures(bytes, 0x02014b50).last()
            put16(bytes, central + 4, 0x0314)
            put32(bytes, central + 38, mode.toLong() shl 16)
            rejected { store.stageData(bytes.inputStream()) }
        }
        assertNoStages(root)
    }

    @Test fun crcCorruptionAndHiddenLocalEntriesAndTruncationAreRejected() = withRoot { root, store ->
        val crc = archive(text("theta-plugin.json", manifest()), stored = true)
        val local = signatures(crc, 0x04034b50).single()
        val start = local + 30 + u16(crc, local + 26) + u16(crc, local + 28)
        crc[start + 1] = 'X'.code.toByte()
        rejected { store.stageData(crc.inputStream()) }
        val centralCrc = bundle()
        val central = signatures(centralCrc, 0x02014b50).single()
        put32(centralCrc, central + 16, u32(centralCrc, central + 16) xor 1)
        rejected { store.stageData(centralCrc.inputStream()) }
        val names = bundle()
        names[30] = 'X'.code.toByte()
        rejected { store.stageData(names.inputStream()) }
        val bytes = bundle()
        rejected { store.stageData(bytes.copyOf(bytes.size - 5).inputStream()) }
        rejected { store.stageData((byteArrayOf(1, 2, 3) + bytes).inputStream()) }
        rejected { store.stageData((bytes + byteArrayOf(1)).inputStream()) }
        assertNoStages(root)
    }

    @Test fun expandedLimitUsesActualBytesNotForgedHeaderSize() = withRoot { root, _ ->
        val bytes = bundle(1, text("skills/large/SKILL.md", "A".repeat(8192)))
        val local = signatures(bytes, 0x04034b50).last()
        val central = signatures(bytes, 0x02014b50).last()
        val descriptor = signatures(bytes, 0x08074b50).last()
        put32(bytes, central + 24, 1)
        put32(bytes, descriptor + 12, 1)
        put32(bytes, local + 22, 1)
        val store = PluginDataStore(root, 1, PluginDataLimits(entryBytes = 1024))
        val error = assertThrows(PluginPackageException::class.java) { store.stageData(bytes.inputStream()) }
        assertTrue(error.message!!.contains("Expanded"))
        assertNoStages(root)
    }

    @Test fun compressedExpandedEntryCountAndManifestLimitsAreEnforced() = withRoot { root, _ ->
        rejected { PluginDataStore(root, 1, PluginDataLimits(compressedBytes = 32)).stageData(bundle().inputStream()) }
        rejected { PluginDataStore(root, 1, PluginDataLimits(expandedBytes = 200)).stageData(bundle(1, text("skills/a/SKILL.md", "x".repeat(200))).inputStream()) }
        rejected { PluginDataStore(root, 1, PluginDataLimits(entries = 1)).stageData(bundle(1, text("lang/a.json", "{}")).inputStream()) }
        rejected { PluginDataStore(root, 1).stageData(archive(text("theta-plugin.json", manifest() + " ".repeat(65536))).inputStream()) }
        assertNoStages(root)
    }

    @Test fun invalidUtf8NestedJsonAndInvalidEndpointsAreRejected() = withRoot { root, store ->
        rejected { store.stageData(bundle(1, "skills/a/SKILL.md" to byteArrayOf(0xc3.toByte(), 0x28)).inputStream()) }
        rejected { store.stageData(bundle(1, text("personas/a.json", "{\"a\":1,\"a\":2}")).inputStream()) }
        rejected { store.stageData(bundle(1, text("personas/a.json", "{\"x\":".repeat(40) + "0" + "}".repeat(40))).inputStream()) }
        for (url in listOf("file:///tmp/a", "https://user:pass@example.com", "https://", "https://example.com/#hidden")) {
            rejected { store.stageData(bundle(1, text("mcp/a.json", """{"id":"a","name":"A","auth":"oauth","url":"$url"}""")).inputStream()) }
        }
        assertNoStages(root)
    }

    @Test fun codeTrustDataAndLifecycleResidueReserveIdentity() {
        for (path in listOf("plugin-trust/$id.signers", "plugin-data/$id/keep", "plugin-state/$id.properties",
            "plugin-updates/$id.properties", "plugin-snapshots/$id/keep", "plugins/$id/1/plugin.apk")) {
            withRoot { root, store ->
                val keep = root.resolve(path).apply { parentFile.mkdirs(); writeText("retained code") }
                rejected { store.stageData(bundle(2).inputStream()) }
                assertEquals("retained code", keep.readText())
                assertNoStages(root)
            }
        }
    }

    @Test fun namespaceIsRecheckedAfterConfirmationWithoutOverwritingCode() = withRoot { root, store ->
        val staged = store.stageData(bundle().inputStream())
        val code = root.resolve("plugins/$id/1/plugin.apk").apply { parentFile.mkdirs(); writeText("trusted") }
        rejected { store.publishConfirmedData(staged, staged.sha256, true) }
        assertEquals("trusted", code.readText())
        assertFalse(code.parentFile.resolve("plugin.zip").exists())
        assertNoStages(root)
    }

    @Test fun symlinkedStorageAndTamperedStageNeverTouchOutside() = withRoot { root, store ->
        val outside = Files.createTempDirectory("theta-data-outside").toFile()
        try {
            val keep = outside.resolve("keep").apply { writeText("keep") }
            Files.createSymbolicLink(root.resolve("plugins").toPath(), outside.toPath())
            rejected { store.stageData(bundle().inputStream()) }
            Files.delete(root.resolve("plugins").toPath())
            val staged = store.stageData(bundle().inputStream())
            val file = stageFile(root)
            Files.delete(file.toPath())
            Files.createSymbolicLink(file.toPath(), keep.toPath())
            rejected { store.publishConfirmedData(staged, staged.sha256, true) }
            assertEquals("keep", keep.readText())
            assertNoStages(root)
        } finally { outside.deleteRecursively() }
    }

    @Test fun interruptedInputAndCleanupFailurePreserveOriginalException() = withRoot { root, store ->
        val original = IOException("source interrupted")
        val source = object : InputStream() {
            override fun read(): Int = throw original
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                // A linked managed parent forces cleanup to fail its no-follow check.
                // This is failure injection, not a claim to defend hostile same-UID writers.
                root.resolve("plugin-data-staging").deleteRecursively()
                val target = root.resolve("cleanup-target").apply { mkdirs() }
                Files.createSymbolicLink(root.resolve("plugin-data-staging").toPath(), target.toPath())
                throw original
            }
        }
        val error = assertThrows(IOException::class.java) { store.stageData(source) }
        assertSame(original, error)
        assertTrue(error.suppressed.isNotEmpty())
        assertFalse(Files.exists(root.resolve("plugins/$id/1").toPath(), NOFOLLOW_LINKS))
    }
}
