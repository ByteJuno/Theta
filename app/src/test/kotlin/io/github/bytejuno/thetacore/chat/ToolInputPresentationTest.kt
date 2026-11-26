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
    @Test fun malformedDisplayDataStaysDeclinableWithoutThrowing() {
        for (field in listOf(
            """{"type":"string","enum":["red",{"legacy":true}]}""",
            """{"type":"string","enum":[["red"]]}""",
            """{"type":"string","enum":[null]}""",
            """{"type":"string","enum":"red"}""",
            """{"type":"string","enum":[42]}""",
            """{"type":"boolean","enum":["true"]}""",
            """{"type":"integer","enum":[1.5]}""",
            """{"type":"number","enum":[false]}""",
            """{"type":"string","title":{"legacy":true}}""",
            """{"type":"string","title":["Title"]}""",
            """{"type":"string","title":null}""",
            """{"type":"string","description":42}""",
        )) {
            val definition = Json.parseToJsonElement(field).jsonObject
            val malformed = buildJsonObject { put("type", "object"); putJsonObject("properties") { put("choice", definition) } }
            assertNull(field, toolInputFields(malformed))
            assertNull(toolInputAnswer(malformed, mapOf("choice" to "red")))
            // Defense at the rendering boundary, even for an unvalidated direct caller.
            toolInputLabel("choice", definition); toolInputChoices(definition)
        }
        for (extra in listOf(""""title":{}""", """"required":[{}]""")) {
            assertNull(toolInputFields(Json.parseToJsonElement("""{"type":"object",$extra}""").jsonObject))
        }
    }

    @Test fun scalarEnumsKeepTheirJsonTypesAndLocalizedTitles() {
        val supported = Json.parseToJsonElement("""{"type":"object","properties":{
            "i":{"type":"integer","title":"数量 / Count","enum":[1,2]},
            "n":{"type":"number","enum":[1.5,2]},
            "b":{"type":"boolean","enum":[true,false]}}}""").jsonObject
        val fields = requireNotNull(toolInputFields(supported))
        assertEquals("数量 / Count", toolInputLabel("i", fields.getValue("i")))
        assertEquals(listOf("1.5", "2"), toolInputChoices(fields.getValue("n")))
        val answer = requireNotNull(toolInputAnswer(supported, mapOf("i" to "2", "n" to "1.5", "b" to "false")))
        assertEquals(JsonPrimitive(2), answer["i"]); assertEquals(JsonPrimitive(1.5), answer["n"])
        assertEquals(JsonPrimitive(false), answer["b"])
    }

}
