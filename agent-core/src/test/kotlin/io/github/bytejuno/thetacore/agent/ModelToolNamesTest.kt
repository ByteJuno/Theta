package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.execution.ModelToolNames
import org.junit.Test
import kotlin.random.Random
import kotlin.test.*

class ModelToolNamesTest {
    private fun assertValid(names: Map<String, String>) {
        assertEquals(names.size, names.values.toSet().size)
        names.values.forEach { assertTrue(it.matches(Regex("[A-Za-z0-9_-]{1,64}")), it) }
    }

    @Test fun preservesSimpleNamesAndSanitizesMcpAndUnicodeNames() {
        val keys = listOf("host/clock", "host/Read_v2-OK", "mcp/connection/tool.with.dots", "host/工具: café!?", "")
        val names = ModelToolNames.allocate(keys)
        assertEquals("host_clock", names["host/clock"])
        assertEquals("host_Read_v2-OK", names["host/Read_v2-OK"])
        assertEquals("mcp_connection_tool_with_dots", names["mcp/connection/tool.with.dots"])
        assertValid(names)
    }

    @Test fun hashesEveryCollidingNameUsingOriginalKey() {
        val names = ModelToolNames.allocate(listOf("host/a/b", "host/a_b"))
        assertEquals("host_a_b_02c2b0e6aca1", names["host/a/b"])
        assertEquals("host_a_b_301bc47e80ad", names["host/a_b"])
        assertValid(names)
    }

    @Test fun respectsExactLengthBoundaryAndDistinguishesTruncatedPrefixes() {
        val boundary = "host/" + "x".repeat(59)
        val keys = listOf(boundary, boundary + "x", boundary + "y", "mcp/" + "long.".repeat(100))
        val names = ModelToolNames.allocate(keys)
        assertEquals(boundary.replace('/', '_'), names[boundary])
        keys.drop(1).forEach {
            assertEquals(64, names.getValue(it).length)
            assertTrue(names.getValue(it).matches(Regex(".*_[0-9a-f]{12}")))
        }
        assertValid(names)
    }

    @Test fun reservesLiteralAliasesAndResolvesSecondaryCollisionsDeterministically() {
        val keys = listOf("host/a/b", "host/a_b", "host/a_b_02c2b0e6aca1", "host/a_b_02c2b0e6aca1_1")
        val names = ModelToolNames.allocate(keys)
        assertEquals("host_a_b_02c2b0e6aca1", names[keys[2]])
        assertEquals("host_a_b_02c2b0e6aca1_1", names[keys[3]])
        assertEquals("host_a_b_02c2b0e6aca1_2", names[keys[0]])
        assertEquals(names, ModelToolNames.allocate(keys.reversed()))
        assertValid(names)
    }

    @Test fun longHashCollisionSuffixRetruncatesWithoutDroppingLiteralNames() {
        val key = "host/" + "x".repeat(90)
        val original = ModelToolNames.allocate(listOf(key)).getValue(key)
        val hash = original.takeLast(12)
        fun literal(alias: String) = "host/" + alias.removePrefix("host_")
        val firstRetry = original.take(49) + "_${hash}_1"
        val keys = listOf(key, literal(original), literal(firstRetry))
        val names = ModelToolNames.allocate(keys)
        assertEquals(original, names.getValue(keys[1]))
        assertEquals(firstRetry, names.getValue(keys[2]))
        assertEquals(original.take(49) + "_${hash}_2", names.getValue(key))
        assertEquals(keys.toSet(), names.keys)
        assertEquals(names.toList(), ModelToolNames.allocate(keys.reversed()).toList())
        assertValid(names)
    }

    @Test fun mixedDirectoryIsStableAcrossRegistrationOrder() {
        val keys = (0..100).flatMap { listOf("mcp/server/tool.$it", "mcp/server/tool_$it",
            "host/" + "x".repeat(90) + it) } + listOf("host/clock", "host/a/b", "host/a_b")
        val names = ModelToolNames.allocate(keys)
        repeat(10) { assertEquals(names.toList(), ModelToolNames.allocate(keys.shuffled(Random(it))).toList()) }
        assertValid(names)
    }

    @Test fun emptyDirectoryAndDuplicateKeys() {
        assertTrue(ModelToolNames.allocate(emptyList()).isEmpty())
        assertFailsWith<IllegalArgumentException> { ModelToolNames.allocate(listOf("host/a", "host/a")) }
    }
}
