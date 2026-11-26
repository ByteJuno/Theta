package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class McpPresetsTest {
    @Test fun realDataArchivePresetsRespectDisableAndDeletionAndNeverCarrySecrets() {
        val dir = Files.createTempDirectory("theta-mcp-presets").toFile()
        try {
            val catalog = PluginDataCatalog(dir, 1)
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                mapOf("theta-plugin.json" to """{"schema":1,"type":"data","id":"test.mcp","name":"Connections","version":"1","versionCode":1,"apiLevel":1,"provides":["mcp"]}""",
                    "mcp/local.json" to """{"id":"local","name":"Local","url":"http://127.0.0.1:8000/mcp","auth":"none"}""",
                    "mcp/oauth.json" to """{"id":"oauth","name":"OAuth","url":"https://example.com/mcp","auth":"oauth"}""").forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            val stage = catalog.stage(bytes.toByteArray().inputStream())
            val selection = catalog.install(stage, stage.sha256, true)
            val presets = catalog.readMcpPresets(selection)
            assertEquals(listOf("none", "oauth"), presets.map { it.auth })
            assertEquals("test.mcp/mcp/local.json", presets.first().key)
            catalog.setEnabled("test.mcp", false, selection)
            assertThrows(Exception::class.java) { catalog.readMcpPresets(selection) }
            val restored = catalog.setEnabled("test.mcp", true)!!
            assertEquals(2, catalog.readMcpPresets(restored).size)
            // A removed/damaged archive is revalidated at read, never served from old UI state.
            dir.resolve("plugins/test.mcp/1/plugin.zip").writeText("invalid archive")
            assertThrows(Exception::class.java) { catalog.readMcpPresets(restored) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedOrCredentialBearingPresetsAreRejected() {
        for (body in listOf(
            """{"id":"x","name":"X","url":"http://public.example/mcp","auth":"none"}""",
            """{"id":"x","name":"X","url":"https://example.com","auth":"bearer","token":"secret"}""",
            """{"id":"x","name":"X","url":"https://example.com?token=secret","auth":"bearer"}""",
            """{"id":"x","name":"X","url":"https://example.com","auth":"unknown"}""")) {
            assertThrows(Exception::class.java) { parseMcpPreset("test.mcp", "mcp/x.json", body.toByteArray()) }
        }
    }
}
