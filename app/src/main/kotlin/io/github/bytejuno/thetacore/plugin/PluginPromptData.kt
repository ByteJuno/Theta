package io.github.bytejuno.thetacore.plugin

import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

internal data class DataSkill(val id: String, val name: String, val description: String)
internal data class DataPersona(val id: String, val name: String, val systemPrompt: String)
internal data class PluginPromptData(
    val skills: List<DataSkill> = emptyList(),
    val personas: List<DataPersona> = emptyList(),
    val errors: List<String> = emptyList(),
    val selections: Map<String, SelectedPluginData> = emptyMap(),
) {
    /** Only metadata enters the stable prefix; full documents stay behind the read tool. */
    fun system(base: String?, persona: DataPersona?, readerName: String?): String? {
        val prefix = if (skills.isEmpty() || readerName == null) "" else buildString {
            append("Available skills (data documents). Use ").append(readerName)
                .append(" with id to read SKILL.md, or path for a relative .md/.txt document.\n")
            var omitted = false
            for (skill in skills.sortedBy { it.id }) {
                // JSON quoting keeps untrusted metadata on one line, separate from instructions.
                val line = "- ${JsonPrimitive(skill.id)}: ${JsonPrimitive(skill.description)}\n"
                if (length + line.length > MAX_SKILL_INDEX_CHARS - 80) { omitted = true; break }
                append(line)
            }
            if (omitted) append("Additional skills omitted due to the catalog size limit.\n")
        }
        if (prefix.isEmpty() && persona == null) return base
        return listOfNotNull(prefix.takeIf { it.isNotEmpty() }, persona?.systemPrompt, base)
            .joinToString("\n\n")
    }

    companion object { const val MAX_SKILL_INDEX_CHARS = 8_000 }
}

internal fun validPromptUnicode(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

private fun promptSegment(value: String): Boolean = value.isNotEmpty() && value.length <= 128 &&
    validPromptUnicode(value) &&
    value !in setOf(".", "..") && !value.endsWith('.') && !value.endsWith(' ') &&
    value.none { it.isISOControl() || it in "/\\:*?\"<>|%" } &&
    Normalizer.isNormalized(value, Normalizer.Form.NFC)

internal fun skillLocation(id: String, relative: String = "SKILL.md"): Pair<String, String> {
    val parts = id.split('/')
    dataRequire(parts.size == 2 && validPluginId(parts[0]) && promptSegment(parts[1]), "Invalid skill id")
    val path = relative.split('/')
    dataRequire(path.size in 1..2 && path.all(::promptSegment) &&
        (path.last().endsWith(".md") || path.last().endsWith(".txt")), "Invalid skill document path")
    return parts[0] to "skills/${parts[1]}/$relative"
}

private fun shortLine(text: String, max: Int): String = text.replace(Regex("\\s+"), " ")
    .filterNot { it.isISOControl() }.trim().let {
        // Never split a UTF-16 surrogate pair at the bound.
        it.take(if (it.length > max && it[max - 1].isHighSurrogate()) max - 1 else max)
    }

/** SafeConstructor accepts YAML data only. No arbitrary class tags or object construction. */
internal fun parseDataSkill(pluginId: String, path: String, bytes: ByteArray): DataSkill {
    dataRequire(bytes.size <= 1024 * 1024, "Skill exceeds size limit")
    val folder = path.removePrefix("skills/").removeSuffix("/SKILL.md")
    val id = "$pluginId/$folder"
    skillLocation(id)
    val lines = dataUtf8(bytes).removePrefix("\uFEFF").replace("\r\n", "\n").lines()
    val fields = mutableMapOf<String, String>()
    var body = 0
    if (lines.firstOrNull()?.trimEnd() == "---") {
        // Document markers must start at column zero. Indented markers are scalar data.
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() in setOf("---", "...") }
        dataRequire(end != null, "Unterminated skill frontmatter")
        if (end != null) {
            body = end + 1
            val yaml = lines.subList(1, end).joinToString("\n")
            dataRequire(yaml.toByteArray(Charsets.UTF_8).size <= 16 * 1024, "Skill frontmatter exceeds limit")
            val options = LoaderOptions().apply {
                codePointLimit = 16 * 1024
                nestingDepthLimit = 16
                maxAliasesForCollections = 0
                isAllowDuplicateKeys = false
                setAllowRecursiveKeys(false)
                tagInspector = org.yaml.snakeyaml.inspector.TagInspector { false }
            }
            val metadata = Yaml(SafeConstructor(options)).load<Any?>(yaml)
            dataRequire(metadata == null || metadata is Map<*, *>, "Skill frontmatter must be a mapping")
            for (key in listOf("name", "description")) {
                val value = (metadata as? Map<*, *>)?.get(key) ?: continue
                dataRequire(value is String && validPromptUnicode(value), "Invalid skill metadata string")
                fields[key] = value as String
            }
        }
    }
    val content = lines.drop(body)
    val heading = content.firstOrNull { Regex("^#{1,6}\\s+.+").matches(it) }
        ?.replace(Regex("^#{1,6}\\s+"), "")?.trimEnd('#', ' ')
    val paragraph = content.dropWhile { it.isBlank() || it.startsWith('#') }
        .takeWhile { it.isNotBlank() }.joinToString(" ")
    val name = shortLine(fields["name"].orEmpty().ifBlank { heading ?: folder }, 128)
    val description = shortLine(fields["description"].orEmpty().ifBlank { paragraph.ifBlank { name } }, 240)
    return DataSkill(id, name, description)
}

internal fun parseDataPersona(pluginId: String, bytes: ByteArray): DataPersona {
    dataRequire(bytes.size <= 48 * 1024, "Persona exceeds size limit")
    val json = dataJson(bytes)
    val id = dataString(json, "id")
    dataRequire(promptSegment(id), "Invalid persona id")
    val name = dataString(json, "name")
    dataRequire(name.length <= 128 && validPromptUnicode(name) && name.none { it.isISOControl() }, "Invalid persona name")
    val prompt = (json["systemPrompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    dataRequire(prompt != null && prompt.isNotBlank() && prompt.length <= 8_000 && validPromptUnicode(prompt), "Invalid persona prompt")
    return DataPersona("$pluginId/$id", name, prompt!!)
}

internal fun PluginDataCatalog.promptData(selection: SelectedPluginData): PluginPromptData {
    val skills = mutableListOf<DataSkill>()
    val personas = mutableListOf<DataPersona>()
    val errors = mutableListOf<String>()
    val id = selection.manifest.id
    for ((path, bytes) in readEntries(selection) {
        it.matches(Regex("skills/[^/]+/SKILL\\.md")) || it.matches(Regex("personas/[^/]+\\.json"))
    }) {
        try {
            if (path.startsWith("skills/")) skills += parseDataSkill(id, path, bytes)
            else personas += parseDataPersona(id, bytes)
        } catch (_: Exception) { errors += "$id/$path: invalid prompt metadata" }
    }
    val duplicates = personas.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
    duplicates.forEach { errors += "$it: duplicate persona id" }
    return PluginPromptData(skills.sortedBy { it.id }, personas.filter { it.id !in duplicates }.sortedBy { it.id }, errors,
        mapOf(id to selection))
}

internal fun PluginDataCatalog.readSkill(selection: SelectedPluginData, id: String, path: String): String {
    val (pluginId, fullPath) = skillLocation(id, path)
    dataRequire(pluginId == selection.manifest.id, "Skill belongs to another package")
    val main = "skills/${id.substringAfter('/')}/SKILL.md"
    val entries = readEntries(selection, allowRetainedVersion = true) { it == main || it == fullPath }
    dataRequire(main in entries && fullPath in entries, "Skill document unavailable")
    return dataUtf8(entries.getValue(fullPath))
}
