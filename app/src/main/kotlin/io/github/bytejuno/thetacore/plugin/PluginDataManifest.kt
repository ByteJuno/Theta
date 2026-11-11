package io.github.bytejuno.thetacore.plugin

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Data metadata deliberately has no ThetaPlugin entry point or signer identity. */
internal data class PluginDataManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiLevel: Int,
    val provides: List<String>,
    val declares: List<String>,
)

/** Display-only destination, never an instruction to connect or supply credentials. */
internal data class PluginDataDestination(val path: String, val url: String, val host: String)

internal fun readPluginDataManifest(bytes: ByteArray, hostApiLevel: Int): PluginDataManifest {
    dataRequire(bytes.size <= 64 * 1024, "Data manifest is too large")
    val json = dataJson(bytes)
    val allowed = setOf("schema", "type", "id", "name", "version", "versionCode", "apiLevel",
        "provides", "declares", "requires", "hooks")
    dataRequire(json.keys.all { it in allowed }, "Unsupported data manifest field")
    dataRequire(dataInteger(json, "schema") == 1L, "Unsupported data schema; expected 1")
    dataRequire(dataString(json, "type") == "data", "Expected an explicit data ZIP")
    val id = dataString(json, "id")
    checkId(id)
    dataRequire(id.length <= 200, "Data plugin id is too long")
    val versionCode = dataInteger(json, "versionCode")
    val api = dataInteger(json, "apiLevel")
    dataRequire(versionCode >= 1, "Invalid data versionCode")
    dataRequire(api in 1L..hostApiLevel.toLong(), "Unsupported data API level")
    if ("requires" in json) {
        val requires = json["requires"] as? JsonObject
            ?: throw PluginPackageException("requires must be an object")
        dataRequire(requires.keys.all { it == "root" || it == "lsposed" }, "Unknown requirement")
        requires.values.forEach { value ->
            dataRequire(value == JsonPrimitive(false), "Data ZIP cannot require native execution")
        }
    }
    if ("hooks" in json) {
        val hooks = json["hooks"] as? JsonArray
            ?: throw PluginPackageException("hooks must be an array")
        dataRequire(hooks.isEmpty(), "Hook data is not supported by this slice")
    }
    return PluginDataManifest(id, dataString(json, "name"), dataString(json, "version"),
        versionCode, api.toInt(), dataStrings(json, "provides"), dataStrings(json, "declares"))
}

internal fun dataUtf8(bytes: ByteArray): String = try {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    dataRequire(text.none { it < ' ' && it != '\n' && it != '\r' && it != '\t' }, "Binary data is not supported")
    text
} catch (error: java.nio.charset.CharacterCodingException) {
    throw PluginPackageException("Invalid UTF-8 data")
}

internal fun dataJson(bytes: ByteArray): JsonObject {
    val text = dataUtf8(bytes)
    try {
        // Nonrecursive lexical/structural gate runs BEFORE the tree parser. In
        // particular, Json's default mode still accepts some bare primitives.
        checkDataJsonSyntax(text)
        val result = Json.parseToJsonElement(text)
        checkDataJsonPrimitives(result)
        return result as? JsonObject ?: throw PluginPackageException("Data JSON must be an object")
    } catch (error: kotlinx.serialization.SerializationException) {
        throw PluginPackageException("Invalid data JSON: ${error.message}")
    }
}

private val dataJsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
private val dataJsonInteger = Regex("-?(0|[1-9][0-9]*)")

/** Keep exact number lexemes, with no Double rounding/overflow or string coercion. */
private fun checkDataJsonPrimitives(value: JsonElement) {
    when (value) {
        is JsonObject -> value.values.forEach(::checkDataJsonPrimitives)
        is JsonArray -> value.forEach(::checkDataJsonPrimitives)
        is JsonPrimitive -> dataRequire(value.isString || value.content in setOf("true", "false", "null") ||
            dataJsonNumber.matches(value.content), "Invalid JSON primitive")
    }
}

/** States are next-token expectations; only the initial states allow an empty container. */
private enum class DataJsonNext { FIRST_KEY, KEY, COLON, FIRST_VALUE, VALUE, COMMA }
private class DataJsonScope(val objectScope: Boolean) {
    val keys = mutableSetOf<String>()
    var next = if (objectScope) DataJsonNext.FIRST_KEY else DataJsonNext.FIRST_VALUE
}

/**
 * Bounded iterative syntax gate, not a second value parser. Only string keys
 * are decoded (by the SAME Json implementation) to detect escaped duplicates.
 * All schema reads subsequently use the single validated JsonElement tree.
 */
private fun checkDataJsonSyntax(text: String) {
    val scopes = mutableListOf<DataJsonScope>()
    var rootSeen = false
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char in " \t\r\n") { index++; continue }
        val scope = scopes.lastOrNull()
        if (char == '}' || char == ']') {
            dataRequire(scope != null && scope.objectScope == (char == '}'), "Unbalanced data JSON")
            dataRequire(scope!!.next == DataJsonNext.COMMA ||
                scope.next == DataJsonNext.FIRST_KEY || scope.next == DataJsonNext.FIRST_VALUE,
                "Incomplete JSON container or trailing comma")
            scopes.removeAt(scopes.lastIndex)
            index++
            continue
        }
        if (scope?.next == DataJsonNext.COLON) {
            dataRequire(char == ':', "Expected JSON colon")
            scope.next = DataJsonNext.VALUE
            index++
            continue
        }
        if (scope?.next == DataJsonNext.COMMA) {
            dataRequire(char == ',', "Expected JSON comma")
            scope.next = if (scope.objectScope) DataJsonNext.KEY else DataJsonNext.VALUE
            index++
            continue
        }
        val key = scope?.next == DataJsonNext.FIRST_KEY || scope?.next == DataJsonNext.KEY
        dataRequire(!key || char == '"', "JSON keys must be quoted")
        if (scope == null) {
            dataRequire(!rootSeen, "Trailing JSON content")
            rootSeen = true
        }
        if (char == '"') {
            val start = index++
            while (index < text.length && text[index] != '"') {
                dataRequire(text[index] >= ' ', "Unescaped JSON control character")
                if (text[index++] == '\\') {
                    dataRequire(index < text.length, "Unterminated JSON escape")
                    val escape = text[index++]
                    if (escape == 'u') {
                        dataRequire(index + 4 <= text.length && (index until index + 4).all {
                            text[it] in '0'..'9' || text[it] in 'a'..'f' || text[it] in 'A'..'F'
                        }, "Invalid JSON Unicode escape")
                        index += 4
                    } else dataRequire(escape in "\"\\/bfnrt", "Invalid JSON escape")
                }
            }
            dataRequire(index < text.length, "Unterminated JSON string")
            index++
            if (key) {
                val decoded = (Json.parseToJsonElement(text.substring(start, index)) as JsonPrimitive).content
                dataRequire(scope!!.keys.add(decoded), "Duplicate JSON key")
                scope.next = DataJsonNext.COLON
                continue
            }
        } else if (char == '{' || char == '[') {
            dataRequire(scopes.size < 32, "Data JSON nesting exceeds limit")
            scope?.next = DataJsonNext.COMMA
            scopes.add(DataJsonScope(char == '{'))
            index++
            continue
        } else {
            val start = index
            while (index < text.length && text[index] !in " \t\r\n,]}:{[\"") index++
            dataRequire(index > start, "Expected JSON value")
            val token = text.substring(start, index)
            dataRequire(token in setOf("true", "false", "null") || dataJsonNumber.matches(token), "Invalid JSON primitive")
        }
        scope?.next = DataJsonNext.COMMA
    }
    dataRequire(rootSeen && scopes.isEmpty(), "Incomplete data JSON")
}

private fun dataQuotedString(value: JsonElement?, key: String): String =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw PluginPackageException("$key must be a string")

internal fun dataString(json: JsonObject, key: String): String {
    val value = dataQuotedString(json[key], key)
    dataRequire(value.isNotBlank() && value.length <= 2048 && value.none { it < ' ' }, "Invalid $key")
    return value
}

private fun dataInteger(json: JsonObject, key: String): Long {
    val value = json[key] as? JsonPrimitive
    dataRequire(value != null && !value.isString && dataJsonInteger.matches(value.content), "$key must be an integer")
    return value!!.content.toLongOrNull() ?: throw PluginPackageException("$key exceeds integer range")
}

private fun dataStrings(json: JsonObject, key: String): List<String> {
    if (key !in json) return emptyList()
    val values = json[key] as? JsonArray ?: throw PluginPackageException("$key must be an array")
    dataRequire(values.size <= 64, "Too many $key")
    return values.map { element ->
        val value = dataQuotedString(element, key)
        dataRequire(value.isNotBlank() && value.length <= 128 && value.none { it < ' ' }, "Invalid $key")
        value
    }.also { dataRequire(it.distinct().size == it.size, "Duplicate $key") }
}

internal fun readDataDestination(path: String, bytes: ByteArray): PluginDataDestination? {
    if (path.startsWith("skills/")) {
        dataUtf8(bytes) // Markdown only, no shell/script execution or SKILL frontmatter semantics.
        return null
    }
    val json = dataJson(bytes)
    val key = when {
        path.startsWith("providers/") -> {
            dataString(json, "id")
            dataString(json, "name")
            dataRequire(dataString(json, "protocol") in setOf("openai-chat", "openai-responses", "anthropic", "gemini"),
                "Unsupported provider protocol")
            if ("models" in json) {
                val models = json["models"] as? JsonArray ?: throw PluginPackageException("models must be an array")
                dataRequire(models.all { it is JsonObject }, "models must contain objects")
            }
            if ("compat" in json) dataRequire(json["compat"] is JsonObject, "compat must be an object")
            "baseUrl"
        }
        path.startsWith("mcp/") -> {
            dataString(json, "id")
            dataString(json, "name")
            dataString(json, "auth") // Values beyond the documented example are not assigned semantics.
            "url"
        }
        else -> return null // Persona and language object schemas are not yet specified.
    }
    val value = dataString(json, key)
    val uri = try { URI(value) } catch (error: java.net.URISyntaxException) {
        throw PluginPackageException("Invalid $key")
    }
    dataRequire(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535, "Invalid $key destination")
    return PluginDataDestination(path, value, uri.host.lowercase(java.util.Locale.ROOT))
}

internal fun dataRequire(condition: Boolean, message: String, reason: PluginFailureReason = PluginFailureReason.UNKNOWN) {
    if (!condition) throw PluginPackageException(message, reason)
}
