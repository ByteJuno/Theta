package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.anthropic.AnthropicProvider
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiChatProvider
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiParameters
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class AnthropicConnectionsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cipher() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> AesGcmSecretCipher("anthropic") { key } }

    @Test fun thinkingOptionsAndTemplateDefaultsPreserveAllOtherParameters() {
        val original = OpenAiParameters(123, 0.5, 0.8, "low", false, listOf("stop"))
        val updated = anthropicParameters(original, maxTokens = 456, effort = "high")
        assertEquals(456, updated.maxOutputTokens); assertEquals("high", updated.reasoningLevel)
        assertEquals(original.temperature, updated.temperature); assertEquals(original.topP, updated.topP)
        assertEquals(original.parallelToolCalls, updated.parallelToolCalls); assertEquals(original.stopSequences, updated.stopSequences)
        val raw = """{"id":"claude","name":"Claude","protocol":"anthropic","baseUrl":"https://example.invalid/v1","models":[{"id":"m","displayName":"Model","maxOutputTokens":8192,"reasoningLevels":["high"],"defaultReasoningLevel":"high"}]}"""
        val p = parseProviderTemplate("pack", "Pack", "1", "providers/claude.json", raw.toByteArray())
        val model = anthropicTemplateModel(p, "m")
        assertEquals("Model", model.alias); assertEquals(8192, model.parameters.maxOutputTokens)
        assertEquals("high", model.parameters.reasoningLevel)
    }

    @Test fun presetPersistsBindsAndFreezesNativeProtocolAcrossColdReadAndSwitch() = runBlocking {
        val raw = """{"id":"claude","name":"Claude","protocol":"anthropic","baseUrl":"https://api.example.invalid/v1","models":[{"id":"m1"},{"id":"m2"}]}"""
        val p = parseProviderTemplate("pack", "Pack", "1", "providers/claude.json", raw.toByteArray())
        assertTrue(p.supported); assertNull(p.compat)
        val dir = temp.newFolder(); val c = cipher(); val store = OpenAiSettingsStore(dir, c)
        val connection = ProviderConnection(p.id, p.name, p.baseUrl, p.protocol.wire)
        store.saveProvider(connection, p.models.map { EnabledModel(it.id) }, "old-key")
        store.selectChatModel(ModelRef(p.id, "m2"))
        val cold = OpenAiSettingsStore(dir, c); val frozen = cold.snapshotForNewRun()
        assertEquals("anthropic", frozen.protocol); assertEquals("m2", frozen.settings.model)
        assertTrue(frozen.target().provider is AnthropicProvider)
        cold.saveProvider(connection.copy(protocol = "openai-chat"), p.models.map { EnabledModel(it.id) }, "new-key")
        assertTrue(cold.snapshotForNewRun().target().provider is OpenAiChatProvider)
        assertEquals("old-key", frozen.target().credentials.accessToken())
        assertEquals("new-key", cold.snapshotForNewRun().target().credentials.accessToken())
    }

    @Test fun invalidPresetParametersFailDuringParsingBeforeModelSelection() {
        for (metadata in listOf(
            """"reasoningLevels":["very high"],"defaultReasoningLevel":"very high"""",
            """"defaultReasoningLevel":"very high"""",
            """"reasoningLevels":["high","bad level"]""",
        )) {
            val raw = """{"id":"preset","name":"Preset","protocol":"anthropic","baseUrl":"https://example.invalid/v1","models":[{"id":"m",$metadata}]}"""
            val error = assertThrows(PluginPackageException::class.java) {
                parseProviderTemplate("pack", "Pack", "1", "providers/preset.json", raw.toByteArray())
            }
            assertEquals("Invalid provider model parameters", error.message)
        }
        for (effort in listOf("low", "medium", "high", "max")) {
            val raw = """{"id":"preset","name":"Preset","protocol":"anthropic","baseUrl":"https://example.invalid/v1","models":[{"id":"m","maxOutputTokens":8192,"reasoningLevels":["$effort"],"defaultReasoningLevel":"$effort"}]}"""
            val template = parseProviderTemplate("pack", "Pack", "1", "providers/preset.json", raw.toByteArray())
            val selected = anthropicTemplateModel(template, "m")
            assertEquals(effort, selected.parameters.reasoningLevel)
            assertEquals(8192, selected.parameters.maxOutputTokens)
        }
    }

    @Test fun nativeConfigurationRejectsMissingAuthImageGenerationAndCrossHostKeyReuse() = runBlocking {
        val store = OpenAiSettingsStore(temp.newFolder(), cipher())
        val c = ProviderConnection("native", "Claude", "https://example.invalid/v1", "anthropic")
        assertThrows(IllegalArgumentException::class.java) { c.copy(noAuth = true) }
        assertThrows(IllegalArgumentException::class.java) { store.saveProvider(c, listOf(EnabledModel("m", image = true)), "key") }
        store.saveProvider(c, listOf(EnabledModel("m")), "key")
        val failure = assertThrows(ProviderSettingsException::class.java) { store.saveProvider(c.copy(baseUrl = "https://other.invalid/v1"), listOf(EnabledModel("m")), "") }
        assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, failure.failure)
        try { store.fetchModels("https://other.invalid/v1", "", c.providerId, false, "anthropic"); fail("Key reused") }
        catch (e: ProviderSettingsException) { assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, e.failure) }
        assertEquals("anthropic", store.snapshotForNewRun().protocol)
    }
}
