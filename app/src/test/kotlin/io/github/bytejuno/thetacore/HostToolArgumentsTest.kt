package io.github.bytejuno.thetacore

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostToolArgumentsTest {
    @Test fun acceptsObjectAndNestedValues() {
        val parsed = parseHostToolArguments(""" {"name":"test","nested":{"items":[1,true,null]}} """)
        assertEquals("test", parsed.getValue("name").jsonPrimitive.content)
        assertTrue("nested" in parsed)
        assertEquals(0, parseHostToolArguments("{}").size)
    }

    @Test fun rejectsMalformedAndNonObjectJson() {
        listOf("", "{", "}", "{]", "{\"a\":}", "{\"a\":1,}", "{\"a\":\"unterminated}",
            "{} trailing", "{}{}", "[]", "null", "42", "\"text\"").forEach(::assertRejected)
    }

    @Test fun quotedBracesEscapedQuotesAndBackslashesDoNotCountAsNesting() {
        val value = "[{}]".repeat(100) + " quote: \" slash: \\ end"
        val parsed = parseHostToolArguments("{\"text\":${JsonPrimitive(value)}}")
        assertEquals(value, parsed.getValue("text").jsonPrimitive.content)
    }

    @Test fun acceptsExactlyMaximumDepthAndRejectsOneMore() {
        // Root object counts as one level, followed by nested arrays.
        val atLimit = "{\"value\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}"
        assertTrue("value" in parseHostToolArguments(atLimit))
        val tooDeep = "{\"value\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}"
        assertRejected(tooDeep)
        assertRejected("{".repeat(16_384))
    }

    @Test fun acceptsExactlySizeLimitAndRejectsOversizedInput() {
        val emptyEnvelope = "{\"text\":\"\"}"
        val value = "x".repeat(16_384 - emptyEnvelope.length)
        val atLimit = "{\"text\":\"$value\"}"
        assertEquals(16_384, atLimit.length)
        assertEquals(value, parseHostToolArguments(atLimit).getValue("text").jsonPrimitive.content)
        assertRejected(atLimit + " ")
    }

    private fun assertRejected(text: String) {
        try {
            parseHostToolArguments(text)
            fail("Expected JSON to be rejected: ${text.take(80)}")
        } catch (_: IllegalArgumentException) {
            // Includes parser SerializationException for malformed JSON.
        }
    }
}
