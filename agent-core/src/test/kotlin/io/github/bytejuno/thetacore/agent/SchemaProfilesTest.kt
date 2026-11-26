package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class SchemaProfilesTest {
    private val validator = Schema202012Validator()
    private fun schema(text: String) = Json.parseToJsonElement(text.replace('@', '$')).jsonObject
    private fun tool(text: String, name: String = "read") = modelTool(name, "Read", schema(text))
    private val original = """{"type":"object","@defs":{"positive":{"type":"integer","minimum":2}},"properties":{"n":{"@ref":"#/@defs/positive","maximum":4}},"required":["n"],"additionalProperties":false}""".replace('@', '$')
    private fun project(text: String, profile: SchemaProfile) = ToolSchemaProfiles().prepare(listOf(tool(text)), profile).tools.single().inputSchema

    @Test fun localDefinitionsExpandAndOriginalValidationKeepsTypesSiblingsAndClosedObjects() {
        for (source in listOf(original, original.replace("\$defs", "definitions"))) {
            assertTrue(validator.supported(source))
            assertTrue(validator.valid(source, "{\"n\":3}"))
            for (args in listOf("{}", "{\"n\":1}", "{\"n\":5}", "{\"n\":\"3\"}", "{\"n\":3,\"extra\":0}"))
                assertFalse(validator.valid(source, args), args)
            val expanded = project(source, SchemaProfile.FULL)
            assertFalse(expanded.toString().contains("\$ref"))
            assertFalse(expanded.toString().contains("\$defs"))
            assertTrue(validator.valid(expanded.toString(), "{\"n\":3}"))
            assertFalse(validator.valid(expanded.toString(), "{\"n\":5}"))
        }
    }

    @Test fun pointersDecodeEscapesAndOnlyResolveActualSchemaNodes() {
        val source = """{"@defs":{"a/b~c":{"type":"integer","minimum":2}},"type":"object","properties":{"n":{"@ref":"#/@defs/a~1b~0c"}}}"""
        assertTrue(validator.valid(schema(source).toString(), "{\"n\":3}"))
        assertFalse(validator.valid(schema(source).toString(), "{\"n\":1}"))
        listOf("#/@defs/a~2b", "#/@defs/a%2Fb", "#/@defs/missing", "#/@defs/x/const").forEach { ref ->
            assertFalse(validator.supported(schema("""{"@defs":{"x":{"const":{"type":"string"}}},"@ref":"$ref"}""").toString()))
        }
    }

    @Test fun externalDynamicCyclesAndUnusedDangerousDefinitionsAreRejected() {
        val bad = listOf(
            """{"@ref":"https://example.invalid/schema"}""",
            """{"@dynamicRef":"#node"}""",
            """{"@defs":{"a":{"@ref":"#/@defs/a"}},"type":"object"}""",
            """{"@defs":{"a":{"@ref":"#/@defs/b"},"b":{"@ref":"#/@defs/a"}},"@ref":"#/@defs/a"}""",
            """{"@defs":{"a":{"@ref":"file:///etc/passwd"}},"type":"object"}""",
            """{"@defs":{"a":{"@dynamicRef":"#x"}},"type":"object"}""",
            """{"@defs":{"a":{"@id":"https://example.invalid","type":"string"}},"type":"object"}"""
        )
        bad.forEach { assertFalse(validator.supported(schema(it).toString()), it) }
    }

    @Test fun depthReferenceCountAndExponentialExpansionAreBounded() {
        var nested: JsonElement = buildJsonObject { put("type", "string") }
        repeat(26) { val child = nested; nested = buildJsonObject { put("items", child) } }
        assertFalse(validator.supported(nested.toString()))
        val chain = buildJsonObject {
            putJsonObject("\$defs") {
                repeat(18) { i -> putJsonObject("n$i") {
                    if (i == 17) put("type", "string") else put("\$ref", "#/\$defs/n${i + 1}")
                } }
            }
            put("\$ref", "#/\$defs/n0")
        }
        assertFalse(validator.supported(chain.toString()))
        val large = buildJsonObject {
            putJsonObject("\$defs") { putJsonObject("s") { put("type", "string"); put("description", "a".repeat(4000)) } }
            put("type", "object")
            putJsonObject("properties") { repeat(40) { putJsonObject("p$it") { put("\$ref", "#/\$defs/s") } } }
        }
        assertTrue(large.toString().length < 32_768)
        assertFalse(validator.supported(large.toString()))
        assertFalse(validator.supported("[".repeat(10000) + "0" + "]".repeat(10000)))
    }

    @Test fun literalsAreNotSchemasAndRefSiblingsKeepUnevaluatedScope() {
        val literal = """{"type":"object","properties":{"data":{"const":{"@ref":"https://literal.invalid","@dynamicRef":"literal"}}}}"""
        assertTrue(validator.supported(schema(literal).toString()))
        assertEquals(schema(literal), project(literal, SchemaProfile.FULL))
        val ref = """{"@defs":{"base":{"type":"object","properties":{"n":{"type":"integer"}}}},"@ref":"#/@defs/base","unevaluatedProperties":false}"""
        for (candidate in listOf(schema(ref).toString(), project(ref, SchemaProfile.FULL).toString())) {
            assertTrue(validator.valid(candidate, "{\"n\":1}"))
            assertFalse(validator.valid(candidate, "{\"n\":1,\"x\":2}"))
        }
    }

    @Test fun noTopLevelCombinatorsExposeFieldsAndPreserveAllLostRelationships() {
        val source = """{"type":"object","oneOf":[{"properties":{"a":{"type":"string"}},"required":["a"]},{"properties":{"b":{"type":"integer"}},"required":["b"]}],"allOf":[{"minProperties":1}],"anyOf":[{"maxProperties":3}]}"""
        val projected = project(source, SchemaProfile.NO_TOP_LEVEL_COMBINATORS)
        assertTrue(projected.keys.none { it in setOf("oneOf", "anyOf", "allOf") })
        assertEquals(setOf("a", "b"), projected["properties"]!!.jsonObject.keys)
        val description = projected["description"]!!.jsonPrimitive.content
        for (key in listOf("oneOf", "anyOf", "allOf", "required", "maxProperties")) assertTrue(description.contains(key))
        assertTrue(validator.valid(projected.toString(), "{\"a\":\"ok\"}"))
        assertTrue(validator.valid(projected.toString(), "{\"b\":2}"))
        assertFalse(validator.valid(schema(source).toString(), "{\"a\":\"ok\",\"b\":2}"))
    }

    @Test fun basicRecursivelyRetainsOnlyWhitelistAndExplainsNumericLengthConstAndClosedObjectConstraints() {
        val source = """{"title":"Numbers","type":"object","properties":{"n":{"type":"integer","minimum":2,"maximum":4,"const":3},"s":{"type":"array","items":{"type":"string","minLength":2},"maxItems":4}},"required":["n"],"additionalProperties":false}"""
        val projected = project(source, SchemaProfile.BASIC)
        fun check(node: JsonObject) {
            assertTrue(node.keys.all { it in setOf("type", "properties", "items", "enum", "required", "description") })
            (node["properties"] as? JsonObject)?.values?.forEach { check(it.jsonObject) }
            node["items"]?.let { check(it.jsonObject) }
        }
        check(projected)
        for (key in listOf("minimum", "maximum", "const", "minLength", "maxItems", "additionalProperties"))
            assertTrue(projected.toString().contains(key), key)
        assertEquals(JsonArray(listOf(JsonPrimitive("n"))), projected["required"])
        assertFalse(validator.valid(schema(source).toString(), "{\"n\":2}"))
        assertTrue(validator.valid(projected.toString(), "{\"n\":2}"))
    }

    @Test fun removingCombinatorsNeverIntroducesUnevaluatedFieldOrItemRejections() {
        val source = """{"type":"object","allOf":[{"additionalProperties":{"type":"integer"}}],"unevaluatedProperties":false}"""
        val projected = project(source, SchemaProfile.NO_TOP_LEVEL_COMBINATORS)
        assertTrue(validator.valid(source, "{\"x\":1}"))
        assertTrue(validator.valid(projected.toString(), "{\"x\":1}"))
        assertFalse("unevaluatedProperties" in projected)
        assertTrue(projected["description"]!!.jsonPrimitive.content.contains("unevaluatedProperties"))
        val nested = """{"type":"object","properties":{"a":{"type":"array","allOf":[{"items":{"type":"integer"}}],"unevaluatedItems":false}}}"""
        assertEquals(schema(nested), project(nested, SchemaProfile.NO_TOP_LEVEL_COMBINATORS))
    }

    @Test fun repeatedAcyclicReferencesAndBooleanLiteralsRemainSupported() {
        val source = """{"@defs":{"s":{"type":"string","enum":["a","b"]}},"type":"object","properties":{"a":{"@ref":"#/@defs/s"},"b":{"@ref":"#/@defs/s"},"data":{"enum":[{"additionalProperties":false,"@ref":"literal"}]}},"required":["a","b"]}"""
        assertTrue(validator.valid(schema(source).toString(), "{\"a\":\"a\",\"b\":\"b\"}"))
        val projected = project(source, SchemaProfile.BASIC)
        assertEquals(schema(source)["properties"]!!.jsonObject["data"], projected["properties"]!!.jsonObject["data"])
        assertFalse(validator.supported("\"true\""))
        assertFalse(validator.supported("""{"properties":{"x":"false"}}"""))
    }

    @Test fun basicTupleProjectionDoesNotApplyTailConstraintsToPrefixItems() {
        val source = """{"type":"object","properties":{"tuple":{"type":"array","prefixItems":[{"type":"string"}],"items":{"type":"integer"}}}}"""
        val projected = project(source, SchemaProfile.BASIC)
        val args = "{\"tuple\":[\"first\",2]}"
        assertTrue(validator.valid(source, args))
        assertTrue(validator.valid(projected.toString(), args))
        val tuple = projected["properties"]!!.jsonObject["tuple"]!!.jsonObject
        assertFalse("items" in tuple)
        assertTrue(tuple["description"]!!.jsonPrimitive.content.contains("prefixItems"))
        assertTrue(tuple["description"]!!.jsonPrimitive.content.contains("items"))
        assertFalse(validator.valid(source, "{\"tuple\":[\"first\",\"invalid tail\"]}"))
    }

    @Test fun incompatibleToolsHaveStructuredReasonsWithoutPreventingOtherTools() {
        val input = listOf(tool("""{"type":"object","properties":{"x":false}}""", "boolean"),
            tool("""{"@ref":"https://example.invalid"}""", "external"), tool("""{"type":"object"}""", "ok"),
            tool("""{"type":"object","oneOf":[{"description":"${"a".repeat(9000)}"}]}""", "verbose"))
        val result = ToolSchemaProfiles().prepare(input, SchemaProfile.BASIC)
        assertEquals(listOf("ok"), result.tools.map { it.name })
        assertEquals(listOf(SchemaExclusionReason.UNREPRESENTABLE_SCHEMA, SchemaExclusionReason.UNSUPPORTED_SCHEMA,
            SchemaExclusionReason.DESCRIPTION_LIMIT), result.excluded.map { it.reason })
        assertTrue(result.excluded.all { it.profile == SchemaProfile.BASIC })
    }

    @Test fun providerConfigProjectsActualWireAndReportsRequestScopedExclusions() = runTest {
        var body = ""; var diagnostics: ToolSchemaPreparation? = null
        val transport = ModelTransport { _, wire, _ ->
            body = wire
            flowOf(TransportEvent.Headers(200, "text/event-stream", null), TransportEvent.Bytes(
                "data: {\"id\":\"r\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray()))
        }
        val config = OpenAiChatConfig("test", "https://example.invalid", schemaProfile = SchemaProfile.BASIC)
        val provider = OpenAiChatProvider(config, transport, onSchemaDiagnostics = { diagnostics = it })
        val request = chatRequest("m") { tools = listOf(tool(original), tool("""{"@ref":"https://example.invalid"}""", "bad")) }
        val events = provider.chat(request, Credentials { "" }).toList()
        assertIs<ModelEvent.Finished>(events.last())
        assertEquals(listOf("bad"), diagnostics!!.excluded.map { it.name })
        val functions = Json.parseToJsonElement(body).jsonObject["tools"]!!.jsonArray
        assertEquals(1, functions.size)
        val parameters = functions.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertFalse(parameters.containsKey("additionalProperties"))
        assertTrue(parameters["description"]!!.jsonPrimitive.content.contains("additionalProperties"))
        assertEquals(body.toByteArray().size.toLong(), OpenAiChatCodec(config).estimateRequestBytes(request))
        assertEquals("BASIC", Json.parseToJsonElement(provider.contextConfigurationKey()).jsonObject["schema"]!!.jsonPrimitive.content)
    }

    @Test fun excludedForcedToolFailsLocallyAndEmptyAutoCatalogOmitsToolFields() {
        val codec = OpenAiChatCodec(OpenAiChatConfig("test", "https://example.invalid"))
        val bad = tool("""{"@ref":"https://example.invalid"}""")
        for (choice in listOf(namedTool("read"), ToolChoice.Required)) {
            var exclusions = emptyList<ToolSchemaExclusion>()
            assertFailsWith<IllegalArgumentException> { codec.encode(chatRequest("m") { tools = listOf(bad); toolChoice = choice }) {
                exclusions = it.excluded
            } }
            assertEquals("read", exclusions.single().name)
        }
        val wire = Json.parseToJsonElement(codec.encode(chatRequest("m") { tools = listOf(bad); parallelToolCalls = true })).jsonObject
        assertTrue(wire.keys.none { it in setOf("tools", "tool_choice", "parallel_tool_calls") })
    }

    @Test fun projectionsNeverMutateOriginalAuthorizationIdentityAndCompatRemainsSupported() {
        val frozen = FrozenDefinition("host/read", "read", "read", "Read", original, Risk.READ, Source.FIRST_PARTY,
            "host", "1", 1, false, null, false, null)
        val digest = frozen.digest
        for (profile in SchemaProfile.entries) {
            val model = modelTool(frozen.modelName, frozen.description, schema(original))
            val before = model.inputSchema.toString()
            val config = OpenAiChatConfig("test", "https://example.invalid", compat = OpenAiChatConfig.Compat(schemaProfile = profile))
            OpenAiChatCodec(config).encode(chatRequest("m") { tools = listOf(model) })
            assertEquals(profile, config.schemaProfile)
            assertEquals(before, model.inputSchema.toString())
            assertEquals(original, frozen.inputSchema)
            assertEquals(digest, frozen.digest)
            assertFalse(validator.valid(frozen.inputSchema, "{\"n\":1}"))
        }
    }
}
