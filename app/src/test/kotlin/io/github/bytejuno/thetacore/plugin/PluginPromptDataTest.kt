package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.chat.PersonaSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun installPromptBundle(catalog: PluginDataCatalog, entries: Map<String, String>, id: String = "test.prompts"): SelectedPluginData {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        (mapOf("theta-plugin.json" to """{"schema":1,"type":"data","id":"$id","name":"Prompts","version":"1","versionCode":1,"apiLevel":1}""") + entries).forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }
    val stage = catalog.stage(out.toByteArray().inputStream())
    return catalog.install(stage, stage.sha256, true)
}

class PluginPromptDataTest {
    private fun root(action: (File, PluginDataCatalog) -> Unit) {
        val dir = Files.createTempDirectory("theta-prompts").toFile()
        try { action(dir, PluginDataCatalog(dir, 1)) } finally { dir.deleteRecursively() }
    }
    private val persona = """{"id":"guide","name":"Guide","systemPrompt":"Be a careful guide.\nExplain your answer."}"""

    @Test fun markdownAndFrontmatterSummariesRemainDataAndStable() {
        val plain = parseDataSkill("test.prompts", "skills/read/SKILL.md", "# Reading\n\nRead carefully.\nUse context.\n\nLong body".toByteArray())
        assertEquals("Reading", plain.name)
        assertEquals("Read carefully. Use context.", plain.description)
        val yaml = "---\nname: 'Friendly ''reader'''\ndescription: >-\n  Read a file\n  and explain it.\ncommand: rm everything\n---\n# Ignored\nMore body"
        val skill = parseDataSkill("test.prompts", "skills/a/SKILL.md", yaml.toByteArray())
        assertEquals("Friendly 'reader'", skill.name)
        assertEquals("Read a file and explain it.", skill.description)
        val data = PluginPromptData(listOf(plain, skill))
        val first = data.system("custom", null, "host_skill_read")!!
        assertEquals(first, data.copy(skills = data.skills.reversed()).system("custom", null, "host_skill_read"))
        assertTrue(first.indexOf("test.prompts/a") < first.indexOf("test.prompts/read"))
        assertFalse(first.contains("rm everything")); assertFalse(first.contains("Long body"))
        assertTrue(first.endsWith("custom"))
        assertNull(PluginPromptData().system(null, null, "host_skill_read"))
        assertEquals("  unchanged\n", PluginPromptData().system("  unchanged\n", null, null))
    }

    @Test fun indexBoundIsDeterministicAndMetadataCannotAddLines() {
        val rows = (1..100).map { parseDataSkill("test.prompts", "skills/s$it/SKILL.md", "---\ndescription: \"a\\nb\"\n---\n${"body".repeat(300)}".toByteArray()) }
        assertEquals("a b", rows.first().description)
        val data = PluginPromptData(rows.map { it.copy(description = "d".repeat(240)) })
        val system = data.system(null, null, "host_skill_read")!!
        assertTrue(system.length <= PluginPromptData.MAX_SKILL_INDEX_CHARS)
        assertTrue(system.contains("omitted"))
        assertEquals(system, data.copy(skills = data.skills.reversed()).system(null, null, "host_skill_read"))
    }

    @Test fun validatedArchiveReadsOnlySameSkillDocumentsAndRejectsTraversal() = root { _, catalog ->
        val selected = installPromptBundle(catalog, mapOf("skills/read/SKILL.md" to "# Read\nRead docs.",
            "skills/read/references/guide.md" to "Reference", "skills/read/notes.txt" to "Notes",
            "skills/other/SKILL.md" to "Private other", "personas/guide.json" to persona))
        assertEquals("Reference", catalog.readSkill(selected, "test.prompts/read", "references/guide.md"))
        assertEquals("Notes", catalog.readSkill(selected, "test.prompts/read", "notes.txt"))
        for (path in listOf("../other/SKILL.md", "/SKILL.md", "C:/SKILL.md", "..\\other\\SKILL.md", "%2e%2e/other.md", "a//b.md", "a/./b.md", "a/b/c.md", "run.sh")) {
            assertThrows(path, Exception::class.java) { catalog.readSkill(selected, "test.prompts/read", path) }
        }
        assertThrows(Exception::class.java) { catalog.readSkill(selected, "other.package/read", "SKILL.md") }
        assertThrows(Exception::class.java) { catalog.readSkill(selected, "test.prompts/missing", "notes.txt") }
        assertEquals(listOf("test.prompts/other", "test.prompts/read"), catalog.promptData(selected).skills.map { it.id })
    }

    @Test fun disableDeleteAndCrashQuarantineRevokeReadsAndPersonaSelection() = root { dir, catalog ->
        val selected = installPromptBundle(catalog, mapOf("skills/read/SKILL.md" to "Document", "personas/guide.json" to persona))
        val settings = PersonaSettings(dir.resolve("preferences"))
        settings.select("test.prompts/guide")
        val data = catalog.promptData(selected)
        assertEquals("Be a careful guide.\nExplain your answer.", settings.resolve(data).persona!!.systemPrompt)
        assertEquals("test.prompts/guide", PersonaSettings(dir.resolve("preferences")).current().id)
        catalog.setEnabled("test.prompts", false, selected)
        assertThrows(Exception::class.java) { catalog.readSkill(selected, "test.prompts/read", "SKILL.md") }
        assertTrue(settings.resolve(PluginPromptData()).unavailable)
        assertNull(settings.current().id)
        val enabled = catalog.setEnabled("test.prompts", true)!!
        // Re-enabling never silently restores the old user choice.
        assertNull(settings.resolve(catalog.promptData(enabled)).persona)
        catalog.managementRecord("test.prompts", crashQuarantined = true)
        assertThrows(Exception::class.java) { catalog.readSkill(enabled, "test.prompts/read", "SKILL.md") }
        val again = catalog.setEnabled("test.prompts", true)!!
        catalog.beginDelete("test.prompts")
        assertThrows(Exception::class.java) { catalog.readSkill(again, "test.prompts/read", "SKILL.md") }
        catalog.finishDelete("test.prompts")
        assertThrows(Exception::class.java) { catalog.readSkill(again, "test.prompts/read", "SKILL.md") }
        settings.select(null)
        assertFalse(settings.resolve(PluginPromptData()).unavailable)
    }

    @Test fun duplicateAndInvalidPersonasAreIsolatedWithoutHidingValidSkills() = root { _, catalog ->
        val selected = installPromptBundle(catalog, mapOf("skills/read/SKILL.md" to "# Read",
            "personas/a.json" to persona, "personas/b.json" to persona,
            "personas/bad.json" to """{"id":"bad","name":"Bad","systemPrompt":42}""",
            "personas/huge.json" to buildJsonObject { put("id", "huge"); put("name", "Huge"); put("systemPrompt", "x".repeat(8001)) }.toString()))
        val data = catalog.promptData(selected)
        assertEquals(1, data.skills.size); assertTrue(data.personas.isEmpty()); assertEquals(3, data.errors.size)
    }

    @Test fun hostToolAutomaticallyAllowsReadAndLargeDocumentRoundTripsThroughBlob() = runBlocking {
        val root = Files.createTempDirectory("theta-skill-host").toFile()
        val budget = PluginExecutionBudget()
        try {
            val catalog = PluginDataCatalog(root, 1)
            val text = "# Full document\n" + "中文正文 😀\n".repeat(8000)
            val selected = installPromptBundle(catalog, mapOf("skills/read/SKILL.md" to text))
            openTestStore(root.resolve("agent").apply { mkdirs() }).use { store ->
                val service = HostToolBridgeRoot(budget).initialize(skillReader = { id, path -> catalog.readSkill(selected, id, path) }) { store }
                val definitions = service.catalog.freeze("run").values
                val reader = definitions.single { it.toolKey == "host/skill_read" }
                assertEquals(Source.FIRST_PARTY, reader.source); assertEquals(Risk.READ, reader.declaredRisk)
                fun calls(request: String) = ModelEventReducer("test", request).apply {
                    listOf(Events.started("r", "model"), Events.toolStart(0, "wire", reader.modelName),
                        Events.toolDelta(0, """{"id":"test.prompts/read"}"""), Events.end(0), Events.finished(FinishReason.TOOL_CALLS)).forEach(::accept)
                }
                val response = service.executeStep(calls("first"), "chain", "session", "run", "step")!!
                val record = service.ledger.get((response.parts.single() as ContentPart.ToolResult).callId)!!
                assertEquals(Status.SUCCEEDED, record.state); assertNull(record.pending)
                val ref = record.result!!.output!!.raw
                val all = ByteArrayOutputStream()
                var offset = 0L
                while (offset < ref.sizeBytes) {
                    val bytes = service.ledger.readSessionBlob("session", ref.id, offset).second
                    all.write(bytes); offset += bytes.size
                }
                val raw = Json.decodeFromString(RawToolOutput.serializer(), all.toString("UTF-8"))
                assertEquals(text, raw.content.single().text)
                catalog.setEnabled("test.prompts", false, selected)
                val refused = service.executeStep(calls("second"), "chain", "session", "run", "step2")!!
                val failed = service.ledger.get((refused.parts.single() as ContentPart.ToolResult).callId)!!
                assertEquals(Status.FAILED, failed.state); assertEquals(Effect.NONE, failed.result!!.effect)
            }
        } finally { budget.close(); root.deleteRecursively() }
    }
}
