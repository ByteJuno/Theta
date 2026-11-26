package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import kotlinx.serialization.json.*

/** Plain scalar form; unsupported schema shapes remain explicitly declinable. */
internal fun toolInputFields(schema: JsonObject): Map<String, JsonObject>? = runCatching {
    require(schema["type"] == JsonPrimitive("object"))
    require(Schema202012Validator().supported(schema.toString()))
    fun stringAnnotation(field: JsonObject, key: String) {
        field[key]?.let { require(it is JsonPrimitive && it.isString) }
    }
    stringAnnotation(schema, "title"); stringAnnotation(schema, "description")
    schema["required"]?.let { raw ->
        require(raw is JsonArray && raw.all { it is JsonPrimitive && it.isString })
    }
    val properties = (schema["properties"] as? JsonObject).orEmpty()
    require(properties.size <= 32)
    properties.mapValues { (_, value) -> value.jsonObject.also { field ->
        val type = field["type"]
        require(type is JsonPrimitive && type.isString && type.content in setOf("string", "number", "integer", "boolean"))
        stringAnnotation(field, "title"); stringAnnotation(field, "description")
        field["enum"]?.let { choices ->
            require(choices is JsonArray && choices.isNotEmpty())
            require(choices.all { choice -> choice is JsonPrimitive && choice != JsonNull && when (type.content) {
                "string" -> choice.isString
                "boolean" -> !choice.isString && choice.booleanOrNull != null
                "integer" -> !choice.isString && choice.content.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
                else -> !choice.isString && choice.content.toBigDecimalOrNull() != null
            } })
        }
    } }
}.getOrNull()

internal fun toolInputAnswer(schema: JsonObject, fields: Map<String, String>): JsonObject? = runCatching {
    val definitions = toolInputFields(schema) ?: error("Unsupported form")
    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    buildJsonObject {
        for ((key, field) in definitions) {
            val text = fields[key].orEmpty()
            if (text.isEmpty() && key !in required) continue
            val value = when (field["type"]!!.jsonPrimitive.content) {
                "string" -> JsonPrimitive(text)
                "boolean" -> JsonPrimitive(text.toBooleanStrict())
                else -> Json.parseToJsonElement(text).also { require(it is JsonPrimitive && !it.isString) }
            }
            put(key, value)
        }
    }.also { require(Schema202012Validator().valid(schema.toString(), it.toString())) }
}.getOrNull()

internal fun toolInputLabel(key: String, field: JsonObject) = (field["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: key
internal fun toolInputChoices(field: JsonObject): List<String>? {
    val choices = field["enum"] as? JsonArray ?: return null
    return choices.map { (it as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content ?: return null }
}
internal fun toolInputBoolean(field: JsonObject) = field["type"] == JsonPrimitive("boolean")
internal fun toolInputChecked(value: String?) = value == "true"
