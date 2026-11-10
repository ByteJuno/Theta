package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.json.Json
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.*

class InvocationRecordCodecTest {
    private val codec = InvocationRecordCodec(Json { encodeDefaults = true; explicitNulls = true })

    @Test fun isolatedSurrogatesRoundTripThroughCodecAndUtf8() {
        for ((value, escaped) in listOf(
            "\uD800" to "\\ud800",
            "\uDC00" to "\\udc00",
            "\uD800\uD800\uDC00\uDC00" to "\\ud800\uD800\uDC00\\udc00",
            "\uDC00\uD83D\uDE00\uD800" to "\\udc00\uD83D\uDE00\\ud800",
        )) Fixture().use { f ->
            val record = f.record(f.input(args = "{\"value\":\"$value\"}"))
            val encoded = codec.encode(record)
            assertTrue(encoded.contains(escaped))
            assertEquals(record, codec.decode(encoded))
            // REPORT on malformed UTF-16/UTF-8: the actual disk boundary must be lossless.
            val bytes = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(encoded))
            val diskText = Charsets.UTF_8.newDecoder().decode(bytes).toString()
            assertEquals(encoded, diskText)
            assertEquals(record, codec.decode(diskText))
            assertEquals(encoded, codec.encode(codec.decode(diskText)))
        }
    }

    @Test fun validPairsLiteralEscapesAndControlsKeepExistingEncoding() = Fixture().use { f ->
        val value = "emoji \uD83D\uDE00 literal \\ud800 \\udc00 quote \" slash \\ controls \u0000\b\t\n\r"
        val input = f.input().let { it.copy(binding = it.binding.copy(requestId = value)) }
        val record = f.record(input)
        val encoded = codec.encode(record)
        assertTrue(encoded.contains("emoji \uD83D\uDE00 literal \\\\ud800 \\\\udc00"))
        assertTrue(encoded.contains("\\u0000\\b\\t\\n\\r"))
        assertEquals(record, codec.decode(encoded))
        assertEquals(encoded, Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded.toByteArray(Charsets.UTF_8))).toString())
        assertEquals(encoded, codec.encode(codec.decode(encoded)))
    }
}
