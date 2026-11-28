package io.github.bytejuno.thetacore.agent

import io.github.bytejuno.thetacore.agent.provider.openai.*
import org.junit.Test
import kotlin.test.*

class ReasoningSelectionTest {
    private fun snapshot(requested: String?, levels: List<String>?) = OpenAiRunSnapshot(
        OpenAiProviderSettings("test", "https://example.invalid/v1", "model",
            OpenAiParameters(1234, 0.5, 0.8, requested, false, listOf("end")), reasoningLevels = levels),
        ApiKeyCredentials("test-key"), protocol = "anthropic")

    @Test fun choosesNearestLowerKnownLevelRegardlessOfMetadataOrder() {
        val s = snapshot("medium", listOf("high", "low", "minimal"))
        assertEquals("low", s.request().reasoningLevel)
        assertEquals(ReasoningLevelAdjustment("medium", "low"), s.reasoningAdjustment)
        assertEquals("high", snapshot("max", listOf("high", "low")).request().reasoningLevel)
        assertEquals("minimal", snapshot("low", listOf("minimal", "high")).request().reasoningLevel)
        assertEquals(1234, s.request().maxOutputTokens)
        assertEquals(0.5, s.request().temperature)
        assertEquals(0.8, s.request().topP)
        assertEquals(false, s.request().parallelToolCalls)
        assertEquals(listOf("end"), s.request().stopSequences)
    }

    @Test fun unknownCapabilitiesSupportedLevelsAndDefaultStayUnchanged() {
        for ((requested, levels) in listOf("medium" to null, "high" to listOf("low", "high"),
            "custom" to listOf("custom"), null to listOf("low", "high"))) {
            val s = snapshot(requested, levels)
            assertEquals(requested, s.request().reasoningLevel)
            assertNull(s.reasoningAdjustment)
        }
    }

    @Test fun noKnownLowerLevelUsesDefaultWithoutInventingOrderOrIncreasingEffort() {
        for ((requested, levels) in listOf("low" to listOf("high"), "medium" to emptyList(),
            "custom" to listOf("low", "high"), "medium" to listOf("custom"))) {
            val s = snapshot(requested, levels)
            assertNull(s.request().reasoningLevel)
            assertEquals(ReasoningLevelAdjustment(requested, null), s.reasoningAdjustment)
        }
    }

    @Test fun capabilitiesAndAdjustmentAreFrozenIncludingImageAttachment() {
        val levels = mutableListOf("low", "high")
        val s = snapshot("medium", levels)
        levels.clear(); levels += "medium"
        assertEquals(listOf("low", "high"), s.settings.reasoningLevels)
        val attached = s.withImageSnapshot(snapshot(null, null))
        assertEquals(s.reasoningAdjustment, attached.reasoningAdjustment)
        assertEquals("low", attached.request { reasoningLevel = "high" }.reasoningLevel)
        assertFailsWith<UnsupportedOperationException> { (s.settings.reasoningLevels as MutableList).clear() }
    }
}
