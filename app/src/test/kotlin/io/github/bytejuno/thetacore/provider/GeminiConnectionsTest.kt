package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.gemini.GeminiProvider
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.chat.ChatState
import io.github.bytejuno.thetacore.chat.chatReasoningRecovery
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class GeminiConnectionsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cipher() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> AesGcmSecretCipher("gemini") { key } }
    private val connection = ProviderConnection("gemini", "Gemini", "https://example.invalid/v1beta", "gemini")
    private fun model() = EnabledModel("gemini-model", parameters = OpenAiParameters(reasoningLevel = "high"))

    @Test fun nativeTemplateColdReadRouteAndSnapshotRemainIndependent() = runBlocking {
        val raw = """{"id":"gemini","name":"Gemini","protocol":"gemini","baseUrl":"https://example.invalid/v1beta","models":[{"id":"gemini-model","reasoningLevels":["low","high"],"defaultReasoningLevel":"high"}]}"""
        val template = parseProviderTemplate("pack", "Pack", "1", "providers/gemini.json", raw.toByteArray())
        assertTrue(template.supported)
        val selected = anthropicTemplateModel(template, "gemini-model")
        assertEquals(listOf("low", "high"), selected.reasoningLevels)
        val root = temp.newFolder(); val cipher = cipher(); val store = OpenAiSettingsStore(root, cipher)
        store.saveProvider(connection, listOf(selected), "old-key")
        val cold = OpenAiSettingsStore(root, cipher); val snapshot = cold.snapshotForNewRun()
        assertEquals("gemini", snapshot.protocol); assertTrue(snapshot.target().provider is GeminiProvider)
        assertEquals("high", snapshot.request().reasoningLevel)
        cold.saveProvider(connection.copy(protocol = "openai-chat"), listOf(selected), "new-key")
        assertTrue(cold.snapshotForNewRun().target().provider is OpenAiChatProvider)
        assertEquals("old-key", snapshot.target().credentials.accessToken())
    }

    @Test fun unsupportedCapabilityPersistsAcrossColdReadAndEmitsGentleNotice() {
        val root = temp.newFolder(); val cipher = cipher(); val store = OpenAiSettingsStore(root, cipher)
        store.saveProvider(connection, listOf(model()), "key")
        val snapshot = store.snapshotForNewRun(); val state = MutableStateFlow(ChatState())
        chatReasoningRecovery(snapshot, state).unsupported(snapshot.target(), snapshot.request())
        assertEquals(ReasoningLevelAdjustment("high", null), state.value.reasoningAdjustment)
        val next = OpenAiSettingsStore(root, cipher).snapshotForNewRun()
        assertEquals(emptyList<String>(), next.settings.reasoningLevels); assertNull(next.request().reasoningLevel)
        assertEquals("high", snapshot.request().reasoningLevel)
        assertEquals("high", store.readProviderCatalog().providers.single().models.single().parameters.reasoningLevel)
    }

    @Test fun observationDoesNotOverwriteConcurrentEndpointOrModelEdits() {
        for (endpoint in listOf(false, true)) {
            val store = OpenAiSettingsStore(temp.newFolder(), cipher())
            store.saveProvider(connection, listOf(model()), "key")
            val old = store.snapshotForNewRun()
            val c = if (endpoint) connection.copy(baseUrl = "https://other.invalid/v1beta") else connection
            val m = if (endpoint) model() else model().copy(parameters = OpenAiParameters(reasoningLevel = "low"))
            store.saveProvider(c, listOf(m), "new-key")
            old.recordUnsupportedReasoning()
            assertNull(store.readProviderCatalog().providers.single().models.single().reasoningLevels)
        }
    }

    @Test fun explicitThinkingSelectionCanOverridePersistedRejectionForFutureRuns() {
        for (protocol in listOf("gemini", "anthropic")) {
            val root = temp.newFolder(); val cipher = cipher(); val store = OpenAiSettingsStore(root, cipher)
            val native = connection.copy(protocol = protocol)
            store.saveProvider(native, listOf(model()), "key")
            store.snapshotForNewRun().recordUnsupportedReasoning()
            val defaultRun = store.snapshotForNewRun()
            val rejected = store.readProviderCatalog().providers.single().models.single()
            val edited = rejected.withSelectedReasoningLevel("low")
            store.saveProvider(native, listOf(edited), "")
            val cold = OpenAiSettingsStore(root, cipher).snapshotForNewRun()
            assertEquals("low", cold.request().reasoningLevel)
            assertNull(cold.settings.reasoningLevels)
            assertNull(defaultRun.request().reasoningLevel)
            assertEquals(emptyList<String>(), rejected.withSelectedReasoningLevel(null).reasoningLevels)
        }
        val known = model().copy(reasoningLevels = listOf("low", "high")).withSelectedReasoningLevel("medium")
        val store = OpenAiSettingsStore(temp.newFolder(), cipher())
        store.saveProvider(connection, listOf(known), "key")
        assertEquals("low", store.snapshotForNewRun().request().reasoningLevel)
    }

    @Test fun nativeConfigurationRejectsUnauthenticatedAndImageGenerationAndKeyReuse() {
        val store = OpenAiSettingsStore(temp.newFolder(), cipher())
        assertThrows(IllegalArgumentException::class.java) { connection.copy(noAuth = true) }
        assertThrows(IllegalArgumentException::class.java) { store.saveProvider(connection, listOf(model().copy(image = true)), "key") }
        store.saveProvider(connection, listOf(model()), "key")
        assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED,
            assertThrows(ProviderSettingsException::class.java) {
                store.saveProvider(connection.copy(baseUrl = "https://other.invalid/v1beta"), listOf(model()), "")
            }.failure)
    }
}
