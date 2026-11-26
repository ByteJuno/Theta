package io.github.bytejuno.thetacore.chat

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolInputPresentationTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{
        "name":{"type":"string","minLength":2},"count":{"type":"integer","minimum":1},
        "enabled":{"type":"boolean"},"color":{"type":"string","enum":["red","blue"]}},
        "required":["name","count","enabled","color"]}""").jsonObject

    @Test fun scalarFormPreservesTypesAndValidatesBeforeSubmission() {
        val fields = mapOf("name" to "中文", "count" to "2", "enabled" to "false", "color" to "blue")
        val answer = requireNotNull(toolInputAnswer(schema, fields))
        assertEquals(JsonPrimitive(false), answer["enabled"])
        assertEquals(JsonPrimitive(2), answer["count"])
        assertNull(toolInputAnswer(schema, fields + ("count" to "2.5")))
        assertNull(toolInputAnswer(schema, fields + ("name" to "x")))
        assertNull(toolInputAnswer(schema, fields + ("color" to "green")))
        assertNull(toolInputAnswer(schema, fields - "enabled"))
        assertEquals(listOf("red", "blue"), toolInputChoices(toolInputFields(schema)!!.getValue("color")))
    }

    @Test fun unsupportedFormsRemainUnavailableAndOptionalBlankFieldsAreOmitted() {
        assertNull(toolInputFields(Json.parseToJsonElement("""{"type":"object","properties":{"nested":{"type":"object"}}}""").jsonObject))
        val optional = Json.parseToJsonElement("""{"type":"object","properties":{"note":{"type":"string"}}}""").jsonObject
        assertEquals(buildJsonObject {}, toolInputAnswer(optional, emptyMap()))
    }
}
