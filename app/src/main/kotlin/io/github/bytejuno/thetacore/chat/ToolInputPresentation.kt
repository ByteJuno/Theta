package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import kotlinx.serialization.json.*

/** Plain scalar form; unsupported schema shapes remain explicitly declinable. */
internal fun toolInputFields(schema: JsonObject): Map<String, JsonObject>? = runCatching {
    require(schema["type"] == JsonPrimitive("object"))
    val properties = (schema["properties"] as? JsonObject).orEmpty()
    require(properties.size <= 32)
    properties.mapValues { (_, value) -> value.jsonObject.also {
        require(it["type"]?.jsonPrimitive?.content in setOf("string", "number", "integer", "boolean"))
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

internal fun toolInputLabel(key: String, field: JsonObject) = field["title"]?.jsonPrimitive?.content ?: key
internal fun toolInputChoices(field: JsonObject) = (field["enum"] as? JsonArray)?.map { it.jsonPrimitive.content }
internal fun toolInputBoolean(field: JsonObject) = field["type"] == JsonPrimitive("boolean")
internal fun toolInputChecked(value: String?) = value == "true"
