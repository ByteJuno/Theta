package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.protocol.ModelTool
import io.github.bytejuno.thetacore.agent.protocol.modelTool
import kotlinx.serialization.json.*

enum class SchemaExclusionReason { UNSUPPORTED_SCHEMA, UNREPRESENTABLE_SCHEMA, DESCRIPTION_LIMIT }
data class ToolSchemaExclusion(val name: String, val profile: SchemaProfile, val reason: SchemaExclusionReason)
data class ToolSchemaPreparation(val tools: List<ModelTool>, val excluded: List<ToolSchemaExclusion>)

/** A request-local view, never a replacement for catalog definitions, digests or argument validation. */
class ToolSchemaProfiles {
    private val validator = Schema202012Validator()
    fun prepare(tools: List<ModelTool>, profile: SchemaProfile): ToolSchemaPreparation {
        val accepted = mutableListOf<ModelTool>()
        val excluded = mutableListOf<ToolSchemaExclusion>()
        for (tool in tools.sortedBy { it.name }) {
            val expanded = try { validator.expanded(tool.inputSchema.toString()) } catch (_: IllegalArgumentException) {
                excluded += ToolSchemaExclusion(tool.name, profile, SchemaExclusionReason.UNSUPPORTED_SCHEMA); continue
            } catch (_: com.networknt.schema.JsonSchemaException) {
                excluded += ToolSchemaExclusion(tool.name, profile, SchemaExclusionReason.UNSUPPORTED_SCHEMA); continue
            }
            try {
                val projected = when (profile) {
                    SchemaProfile.FULL -> expanded
                    SchemaProfile.NO_TOP_LEVEL_COMBINATORS -> project(expanded, basic = false)
                    SchemaProfile.BASIC -> project(expanded, basic = true)
                }
                if (projected !is JsonObject) throw CannotProject(SchemaExclusionReason.UNREPRESENTABLE_SCHEMA)
                if (projected.toString().toByteArray(Charsets.UTF_8).size > 131_072)
                    throw CannotProject(SchemaExclusionReason.DESCRIPTION_LIMIT)
                accepted += modelTool(tool.name, tool.description, projected)
            } catch (e: CannotProject) {
                excluded += ToolSchemaExclusion(tool.name, profile, e.reason)
            }
        }
        return ToolSchemaPreparation(accepted.toList(), excluded.toList())
    }

    private class CannotProject(val reason: SchemaExclusionReason) : RuntimeException()
    private val combinators = setOf("allOf", "anyOf", "oneOf")
    private val basicKeys = setOf("type", "properties", "items", "enum", "required", "description")

    private fun project(node: JsonElement, basic: Boolean): JsonObject {
        if (node == JsonPrimitive(true)) return JsonObject(emptyMap())
        if (node !is JsonObject) throw CannotProject(SchemaExclusionReason.UNREPRESENTABLE_SCHEMA)
        val kept = node.filterKeys { if (basic) it in basicKeys else it !in combinators }.toMutableMap()
        if (basic && "prefixItems" in node) {
            // items applies only AFTER prefixItems in 2020-12. Without the prefix it
            // would incorrectly constrain every element, so describe both together.
            kept.remove("items")
        }
        if (!basic && node.keys.any { it in combinators }) {
            // Removed branches may have evaluated fields/items. Keeping these keywords
            // would turn a relaxation into a new restriction on otherwise valid arguments.
            kept.remove("unevaluatedProperties")
            kept.remove("unevaluatedItems")
        }
        val lost = node.filterKeys { it !in kept }
        // Expose branch fields as hints without introducing branch-specific restrictions.
        // Retained root properties remain authoritative; branch-only fields are relaxed to
        // descriptions. This is a superset, including when an alternative omits a field.
        val properties = (kept["properties"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        fun branchHints(branch: JsonElement) {
            if (branch !is JsonObject) return
            (branch["properties"] as? JsonObject)?.forEach { (name, _) ->
                if (name !in properties) properties[name] = JsonObject(emptyMap())
            }
            for (key in combinators) (branch[key] as? JsonArray)?.forEach(::branchHints)
        }
        for (key in combinators) (node[key] as? JsonArray)?.forEach(::branchHints)
        if (properties.isNotEmpty() || "properties" in kept) kept["properties"] = JsonObject(properties)
        if (basic) {
            kept["properties"]?.let { value -> kept["properties"] = JsonObject(value.jsonObject.mapValues { project(it.value, true) }) }
            kept["items"]?.let { kept["items"] = project(it, true) }
        }
        if (lost.isNotEmpty()) {
            // Keep exact values and relationships, not a truncated or ambiguous paraphrase.
            val description = listOfNotNull((kept["description"] as? JsonPrimitive)?.content,
                "Additional constraints (validated by the host): ${JsonObject(lost)}").joinToString("\n")
            if (description.toByteArray(Charsets.UTF_8).size > 8192) throw CannotProject(SchemaExclusionReason.DESCRIPTION_LIMIT)
            kept["description"] = JsonPrimitive(description)
        }
        return JsonObject(kept)
    }
}
