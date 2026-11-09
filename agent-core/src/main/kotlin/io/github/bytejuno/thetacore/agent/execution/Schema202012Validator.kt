package io.github.bytejuno.thetacore.agent.execution

import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bytejuno.thetacore.agent.ledger.ArgumentsValidator
import kotlinx.serialization.json.*

/** Offline, bounded 2020-12 profile. References/dynamic vocabularies are deliberately not admitted.
 * No default insertion or type coercion. Unsupported schemas fail closed at catalog admission.
 * Networknt 1.5.3 targets Java 8; Android packaging is checked by the host build, not device execution.
 */
class Schema202012Validator : ArgumentsValidator {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val mapper = ObjectMapper()
    private val keywords = setOf("\$schema", "title", "description", "type", "properties", "required",
        "additionalProperties", "items", "prefixItems", "minItems", "maxItems", "uniqueItems", "contains",
        "minContains", "maxContains", "enum", "const", "allOf", "anyOf", "oneOf", "not", "if", "then", "else",
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength",
        "minProperties", "maxProperties", "dependentRequired", "dependentSchemas", "unevaluatedProperties", "unevaluatedItems")
    fun supported(schema: String): Boolean = runCatching {
        require(schema.length <= 32_768)
        checkSchema(Json.parseToJsonElement(schema), 0)
        factory.getSchema(schema)
        true
    }.getOrDefault(false)
    private fun checkSchema(s: JsonElement, depth: Int) {
        require(depth <= 24)
        if (s is JsonPrimitive) { require(s.booleanOrNull != null); return }
        require(s is JsonObject && s.keys.all { it in keywords })
        s["\$schema"]?.let { require(it.jsonPrimitive.content == "https://json-schema.org/draft/2020-12/schema") }
        for ((key, value) in s) when (key) {
            "properties", "dependentSchemas" -> value.jsonObject.values.forEach { checkSchema(it, depth + 1) }
            "allOf", "anyOf", "oneOf", "prefixItems" -> value.jsonArray.forEach { checkSchema(it, depth + 1) }
            "additionalProperties", "items", "contains", "not", "if", "then", "else", "unevaluatedProperties", "unevaluatedItems" -> checkSchema(value, depth + 1)
        }
    }
    override fun valid(originalSchema: String, objectJson: String): Boolean =
        objectJson.length <= 65_536 && validOutput(originalSchema, objectJson)

    /** Output is already returned in memory; the argument admission cap must not reject a valid large result. */
    fun validOutput(originalSchema: String, objectJson: String): Boolean = runCatching {
        require(supported(originalSchema))
        factory.getSchema(originalSchema).validate(mapper.readTree(objectJson)).isEmpty()
    }.getOrDefault(false)
}
