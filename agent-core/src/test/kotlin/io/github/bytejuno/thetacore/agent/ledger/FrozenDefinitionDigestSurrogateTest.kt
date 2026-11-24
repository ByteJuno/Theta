package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import kotlin.test.*

class FrozenDefinitionDigestSurrogateTest {
    private fun def(
        name: String = "tool",
        description: String = "desc",
        inputSchema: String = """{"type":"object","properties":{}}""",
        risk: Risk = Risk.WRITE,
    ) = FrozenDefinition(
        toolKey = "plugin/tool",
        modelName = "plugin_tool",
        name = name,
        description = description,
        inputSchema = inputSchema,
        declaredRisk = risk,
        source = Source.THIRD_PARTY,
        owner = "plugin",
        version = "1",
        generation = 1,
        parallelSafe = false,
        exclusiveResource = null,
        idempotent = false,
        outputSchema = null,
    )

    @Test fun unpairedSurrogatesInNameDescriptionAndSchemaProduceDistinctDigests() {
        val nameDigests = listOf(
            def(name = "tool\uD800").digest,
            def(name = "tool\uD801").digest,
            def(name = "tool\uDC00").digest,
            def(name = "tool?").digest,
            def(name = "tool\\ud800").digest,
        )
        assertEquals(nameDigests.size, nameDigests.distinct().size, "Name surrogate digests collided: $nameDigests")

        val descDigests = listOf(
            def(description = "desc\uD800").digest,
            def(description = "desc\uD801").digest,
            def(description = "desc\uDC00").digest,
            def(description = "desc?").digest,
            def(description = "desc\\ud800").digest,
            def(description = "\uD800\uD800\uDC00").digest,
            def(description = "\uD800\uDC00\uD800").digest,
        )
        assertEquals(descDigests.size, descDigests.distinct().size, "Description surrogate digests collided: $descDigests")

        val schemaValueDigests = listOf(
            def(inputSchema = """{"type":"object","description":"v\uD800"}""").digest,
            def(inputSchema = """{"type":"object","description":"v\uD801"}""").digest,
            def(inputSchema = """{"type":"object","description":"v\uDC00"}""").digest,
            def(inputSchema = """{"type":"object","description":"v?"}""").digest,
            def(inputSchema = """{"type":"object","description":"v\\ud800"}""").digest,
        )
        assertEquals(schemaValueDigests.size, schemaValueDigests.distinct().size, "Schema value surrogate digests collided")

        val schemaKeyDigests = listOf(
            def(inputSchema = """{"type":"object","properties":{"k\uD800":{"type":"string"}}}""").digest,
            def(inputSchema = """{"type":"object","properties":{"k\uD801":{"type":"string"}}}""").digest,
            def(inputSchema = """{"type":"object","properties":{"k?":{"type":"string"}}}""").digest,
        )
        assertEquals(schemaKeyDigests.size, schemaKeyDigests.distinct().size, "Schema key surrogate digests collided")
    }

    @Test fun validUnicodeAndEmojiDigestsRemainBackwardCompatibleWithOriginalUtf8Sha256() {
        val valid = def(
            name = "search_界_😀",
            description = "Valid UTF-8 with emoji 😀, CJK 界, quotes \" and literal \\ud800",
            inputSchema = """{"type":"object","properties":{"q":{"type":"string","description":"🔍"}}}""",
        )
        // Exact pre-fix digest calculation for valid Unicode (no unpaired surrogates).
        val expectedCanonical = "definition-v1:" + kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive(valid.name))
            put("description", kotlinx.serialization.json.JsonPrimitive(valid.description))
            put("inputSchema", kotlinx.serialization.json.Json.parseToJsonElement(valid.inputSchema))
            put("risk", kotlinx.serialization.json.JsonPrimitive(valid.declaredRisk.name))
        }.let(::canonical)
        val expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(expectedCanonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedDigest, valid.digest)
    }

    @Test fun approvalReceiptForOneSurrogateDefinitionDoesNotGrantAnotherSurrogateOrQuestionMark() = Fixture().use { f ->
        val d1 = def(description = "act-\uD800")
        val d2 = def(description = "act-\uD801")
        val dQuestion = def(description = "act-?")
        assertNotEquals(d1.digest, d2.digest)
        assertNotEquals(d1.digest, dQuestion.digest)

        val approved = f.approve(f.prepare(f.input(d1)), Decision.ALLOW_ALWAYS)
        val claimed = f.claim(approved)
        f.ledger.complete(approved.callId, claimed.ticket!!.attemptId, Status.SUCCEEDED, "ok")
        f.ledger.recordActualExit(approved.callId, claimed.ticket.attemptId, "exit")

        // Replacing registration with d2 or dQuestion must NOT inherit d1's ALLOW_ALWAYS receipt.
        for (other in listOf(d2, dQuestion)) {
            val next = f.prepare(f.input(other, run = "run-${other.digest.take(8)}"))
            assertEquals(Status.WAITING_APPROVAL, next.state)
            assertNotNull(next.pending)
        }
    }
}
