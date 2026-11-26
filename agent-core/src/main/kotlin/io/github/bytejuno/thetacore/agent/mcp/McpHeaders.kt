package io.github.bytejuno.thetacore.agent.mcp

import kotlinx.serialization.json.*
import java.util.Base64
import java.util.Locale

data class McpHeaderParameter(val path: List<String>, val name: String, val type: String)

/** Only literal properties chains can be mirrored; annotations in composition/ref/arrays reject the tool. */
fun mcpHeaderParameters(schema: JsonObject): List<McpHeaderParameter>? = try {
    val result = mutableListOf<McpHeaderParameter>()
    val names = hashSetOf<String>()
    fun walk(value: JsonElement, path: List<String>, reachable: Boolean) {
        when (value) {
            is JsonObject -> {
                value["x-mcp-header"]?.let { raw ->
                    require(reachable && path.isNotEmpty())
                    val name = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid header")
                    require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")))
                    require(names.add(name.lowercase(Locale.ROOT)))
                    val type = value["type"]?.jsonPrimitive?.content
                    require(type in setOf("string", "integer", "boolean"))
                    result += McpHeaderParameter(path, name, type!!)
                }
                // Only schema-bearing keywords contain annotations. const/enum/default/examples
                // and unknown extension values are literal data, even when keys look like schemas.
                value.forEach { (key, child) -> when (key) {
                    "properties" -> child.jsonObject.forEach { (property, definition) ->
                        walk(definition, path + property, reachable)
                    }
                    "patternProperties", "dependentSchemas", "\$defs", "definitions" ->
                        child.jsonObject.values.forEach { walk(it, path, false) }
                    "allOf", "anyOf", "oneOf", "prefixItems" ->
                        child.jsonArray.forEach { walk(it, path, false) }
                    "additionalProperties", "items", "contains", "not", "if", "then", "else",
                    "unevaluatedProperties", "unevaluatedItems", "propertyNames", "additionalItems",
                    "contentSchema" -> walk(child, path, false)
                } }
            }
            else -> Unit
        }
    }
    walk(schema, emptyList(), true)
    result
} catch (_: Exception) { null }

fun mcpParameterHeaders(parameters: List<McpHeaderParameter>, args: JsonObject): Map<String, String> = buildMap {
    for (p in parameters) {
        var current: JsonElement? = args
        for (key in p.path) current = (current as? JsonObject)?.get(key)
        if (current == null) continue
        val value = current as? JsonPrimitive ?: throw McpException()
        val text = when (p.type) {
            "string" -> { if (!value.isString) throw McpException(); value.content }
            "boolean" -> { if (value.isString || value.booleanOrNull == null) throw McpException(); value.content }
            else -> {
                val n = if (value.isString) null else value.longOrNull
                if (n == null || n !in -9007199254740991L..9007199254740991L) throw McpException()
                n.toString()
            }
        }
        put("Mcp-Param-${p.name}", mcpHeaderValue(text))
    }
}

internal fun mcpHeaderValue(text: String): String =
    if (text == text.trim() && text.all { it in ' '..'~' || it == '\t' } && !text.startsWith("=?base64?")) text
    else "=?base64?${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}?="
