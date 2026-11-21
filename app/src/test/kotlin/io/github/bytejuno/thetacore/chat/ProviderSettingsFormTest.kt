package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.*
import org.junit.Assert.*
import org.junit.Test

class ProviderSettingsFormTest {
    private fun saved() = OpenAiProviderSettings("chat", "https://example.invalid/v1", "old",
        OpenAiParameters(123, 0.4, 0.9, "high", false, listOf("stop")),
        OpenAiChatConfig.Compat("max_tokens", "reasoning_content", true, false, SchemaProfile.BASIC, true))

    @Test fun loadedFormPreservesParametersAndCompatWhenEditingModel() {
        val form = ProviderSettingsForm.loaded(saved())
        assertTrue(form.canReuseKey)
        assertTrue(form.copy(endpoint = "  ${form.endpoint}  ").canReuseKey)
        assertFalse(form.copy(endpoint = "https://other.invalid/v1").canReuseKey)
        assertFalse(ProviderSettingsForm(endpoint = form.endpoint).canReuseKey)
        val changed = form.copy(model = "new").settings("chat", false)
        assertEquals("new", changed.model)
        assertEquals(123, changed.parameters.maxOutputTokens)
        assertEquals(listOf("stop"), changed.parameters.stopSequences)
        assertEquals(SchemaProfile.BASIC, changed.compat.schemaProfile)
        assertEquals("max_tokens", changed.compat.maxTokensField)
        assertFalse(changed.compat.streamUsage)
        assertTrue(changed.compat.explicitCacheControl)
        assertEquals("reasoning_content", changed.compat.reasoningField)
        assertTrue(changed.compat.replayReasoning)
    }

    @Test fun blankReasoningDisablesReplayAndInvalidOrReservedFieldsCannotSave() {
        val cleared = ProviderSettingsForm.loaded(saved()).withReasoningField("  ")
        assertFalse(cleared.replayReasoning)
        assertNull(cleared.settings("chat", false).compat.reasoningField)
        assertFalse(cleared.withReasoningField("reasoning").replayReasoning)
        for (field in listOf("0field", "bad-name", "role", "content", "tool_calls", "refusal", "function_call")) {
            try { cleared.withReasoningField(field).settings("chat", false); fail("Invalid reasoning field") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("reasoning_content", cleared.withReasoningField(" reasoning_content ")
            .copy(replayReasoning = true).settings("chat", false).compat.reasoningField)
    }

    @Test fun defaultsAreConservativeAndImageEditsKeepOriginalChatIndependent() {
        val form = ProviderSettingsForm(endpoint = saved().baseUrl, model = "image")
        val defaults = form.settings("image", true).compat
        assertFalse(defaults.explicitCacheControl)
        assertEquals("max_completion_tokens", defaults.maxTokensField)
        assertTrue(defaults.streamUsage); assertNull(defaults.reasoningField); assertFalse(defaults.replayReasoning)
        val loaded = ProviderSettingsForm.loaded(saved())
        val image = loaded.copy(reasoningField = "invalid-field", explicitCacheControl = false).settings("image", true)
        assertSame(loaded.saved!!.compat, image.compat)
        assertSame(loaded.saved.parameters, image.parameters)
    }
}
