package io.github.bytejuno.thetacore.agent.execution

import kotlinx.serialization.json.*

/** No URI resolver is involved. Both admission and model projection use these limits. */
internal object LocalSchemaReferences {
    const val MAX_SOURCE_BYTES = 32_768
    const val MAX_EXPANDED_BYTES = 131_072
    private const val MAX_DEPTH = 24
    private const val MAX_REFS = 16
    private val maps = setOf("properties", "dependentSchemas", "\$defs", "definitions")
    private val arrays = setOf("allOf", "anyOf", "oneOf", "prefixItems")
    private val singles = setOf("additionalProperties", "items", "contains", "not", "if", "then", "else",
        "unevaluatedProperties", "unevaluatedItems")
    private val keywords = maps + arrays + singles + setOf("\$schema", "\$ref", "title", "description", "type",
        "required", "minItems", "maxItems", "uniqueItems", "minContains", "maxContains", "enum", "const",
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength",
        "minProperties", "maxProperties", "dependentRequired")

    fun expand(source: String): JsonElement {
        require(source.length <= MAX_SOURCE_BYTES && source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES)
        // Bound nesting before calling the JSON parser (including literal enum/const data).
        var nesting = 0; var quoted = false; var escaped = false
        for (c in source) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { nesting++; require(nesting <= 64) }
                '}', ']' -> nesting--
            }
        }
        val root = Json.parseToJsonElement(source)
        val nodes = linkedMapOf<String, JsonElement>()
        fun index(node: JsonElement, path: String, depth: Int) {
            require(depth <= MAX_DEPTH && nodes.size < 4096)
            nodes[path] = node
            if (node is JsonPrimitive) { require(node == JsonPrimitive(true) || node == JsonPrimitive(false)); return }
            require(node is JsonObject && node.keys.all { it in keywords })
            node["\$schema"]?.let { require(it == JsonPrimitive("https://json-schema.org/draft/2020-12/schema")) }
            for ((key, value) in node) when (key) {
                in maps -> value.jsonObject.forEach { (name, child) -> index(child, "$path/$key/${escape(name)}", depth + 1) }
                in arrays -> value.jsonArray.forEachIndexed { i, child -> index(child, "$path/$key/$i", depth + 1) }
                in singles -> index(value, "$path/$key", depth + 1)
                "\$ref", "title", "description" -> require(value is JsonPrimitive && value.isString)
            }
        }
        index(root, "#", 0)
        val budget = Budget()
        fun visit(node: JsonElement, depth: Int, refs: Set<String>): JsonElement {
            require(depth <= MAX_DEPTH)
            budget.add(2)
            if (node is JsonPrimitive) { budget.add(5); return node }
            val obj = node.jsonObject
            val result = linkedMapOf<String, JsonElement>()
            for ((key, value) in obj) {
                if (key in setOf("\$defs", "definitions", "\$ref")) continue
                budget.add(key.toByteArray(Charsets.UTF_8).size + 4)
                result[key] = when (key) {
                    in maps -> JsonObject(value.jsonObject.mapValues { (name, child) ->
                        budget.add(JsonPrimitive(name).toString().toByteArray(Charsets.UTF_8).size + 2)
                        visit(child, depth + 1, refs)
                    })
                    in arrays -> JsonArray(value.jsonArray.map { visit(it, depth + 1, refs) })
                    in singles -> visit(value, depth + 1, refs)
                    else -> value.also { budget.add(it.toString().toByteArray(Charsets.UTF_8).size) }
                }
            }
            val ref = obj["\$ref"] ?: return JsonObject(result)
            val path = pointer(ref.jsonPrimitive.content)
            require(path !in refs && refs.size < MAX_REFS)
            val target = nodes[path] ?: throw IllegalArgumentException("Reference is not a local schema node")
            val expanded = visit(target, depth + 1, refs + path)
            if (result.isEmpty()) return expanded
            // 2020-12 evaluates ref siblings too. Keep siblings in their original evaluation
            // scope, especially unevaluatedProperties; do not overwrite colliding keywords.
            val conjunction = (result["allOf"] as? JsonArray)?.toList().orEmpty()
            result["allOf"] = JsonArray(conjunction + expanded)
            budget.add(12)
            return JsonObject(result)
        }
        // Even unreachable definitions must not hide external/dynamic/cyclic references.
        for ((path, node) in nodes) if (node is JsonObject && "\$ref" in node) visit(node, 0, setOf(path))
        return visit(root, 0, emptySet()).also {
            require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_EXPANDED_BYTES)
        }
    }

    private fun escape(value: String) = value.replace("~", "~0").replace("/", "~1")
    private fun pointer(value: String): String {
        require(value.startsWith("#/\$defs/") || value.startsWith("#/definitions/"))
        // Accept canonical JSON Pointer escapes only, never URI/percent decoding or anchors.
        require('%' !in value)
        val segments = value.removePrefix("#/").split('/')
        return "#/" + segments.joinToString("/") { part ->
            require(!Regex("~(?![01])").containsMatchIn(part))
            escape(part.replace("~1", "/").replace("~0", "~"))
        }
    }

    private class Budget {
        private var bytes = 0
        private var nodes = 0
        fun add(size: Int) {
            bytes += size; nodes++
            require(bytes <= MAX_EXPANDED_BYTES && nodes <= 16_384)
        }
    }
}
