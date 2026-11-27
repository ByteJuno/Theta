package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ResponsesConnectionsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cipher() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key ->
        AesGcmSecretCipher("responses-tests") { key }
    }
    private val compat = OpenAiChatConfig.Compat(maxTokensField = "max_tokens", reasoningField = "reasoning_content",
        replayReasoning = true, schemaProfile = SchemaProfile.BASIC, supportsImageInput = false)

    @Test fun nativeProtocolPersistsBindsSwitchesAndFreezesWhileChatOnlyCompatNeverApplies() = runBlocking {
        val root = temp.newFolder(); val c = cipher(); val store = OpenAiSettingsStore(root, c)
        val connection = ProviderConnection("native", "Responses", "https://example.invalid/v1", "openai-responses", compat = compat)
        store.saveProvider(connection, listOf(EnabledModel("m1", compat = compat), EnabledModel("m2")), "old-key")
        val selection = ChatModelSelection(this, store::selectChatModel)
        selection.loaded(store.readProviderCatalog())
        requireNotNull(selection.select(ModelRef("native", "m2"))).join()
        val cold = OpenAiSettingsStore(root, c)
        val frozen = cold.snapshotForNewRun()
        assertEquals("openai-responses", frozen.protocol); assertEquals("m2", frozen.settings.model)
        assertTrue(frozen.target().provider is OpenAiResponsesProvider)
        assertTrue(frozen.target().provider.supportsMediaInput(MediaKind.IMAGE, "m2"))
        assertEquals(SchemaProfile.FULL, frozen.settings.compat.schemaProfile)
        var requestUrl = ""; var requestBody = ""; var requestKey = ""
        val t = ModelTransport { url, body, token -> flow {
            requestUrl = url; requestBody = body; requestKey = token
            emit(TransportEvent.Headers(401, "application/json", null)); emit(TransportEvent.Bytes("{}".toByteArray()))
        } }
        frozen.target(t).provider.chat(frozen.request(), frozen.target().credentials).toList()
        assertEquals("https://example.invalid/v1/responses", requestUrl); assertEquals("old-key", requestKey)
        val body = Json.parseToJsonElement(requestBody).jsonObject
        assertTrue("input" in body); assertFalse("messages" in body); assertFalse("max_tokens" in body)
        cold.saveProvider(connection.copy(protocol = "openai-chat"), listOf(EnabledModel("m1"), EnabledModel("m2")), "new-key")
        assertTrue(cold.snapshotForNewRun().target().provider is OpenAiChatProvider)
        assertEquals(SchemaProfile.BASIC, cold.snapshotForNewRun().settings.compat.schemaProfile)
        assertEquals("old-key", frozen.target().credentials.accessToken())
        assertEquals("new-key", cold.snapshotForNewRun().target().credentials.accessToken())
        assertEquals("openai-responses", frozen.protocol)
    }

    @Test fun nativeConnectionCannotReuseAKeyAfterEndpointChangeOrFetchModelsOnAnotherHost() = runBlocking {
        val store = OpenAiSettingsStore(temp.newFolder(), cipher())
        val c = ProviderConnection("native", "Native", "https://example.invalid/v1", "openai-responses")
        store.saveProvider(c, listOf(EnabledModel("m")), "synthetic-key")
        val error = assertThrows(ProviderSettingsException::class.java) {
            store.saveProvider(c.copy(baseUrl = "https://changed.invalid/v1"), listOf(EnabledModel("m")), "")
        }
        assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, error.failure)
        try {
            store.fetchModels("https://changed.invalid/v1", "", "native", false) { _, _ -> error("Must not fetch") }
            fail("Changed endpoint reused a key")
        } catch (e: ProviderSettingsException) { assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, e.failure) }
        val models = store.fetchModels(c.baseUrl, "", "native", false) { endpoint, auth ->
            assertEquals(c.baseUrl, endpoint); assertEquals("synthetic-key", auth.accessToken()); listOf("m", "m2")
        }
        assertEquals(listOf("m", "m2"), models)
        assertEquals("synthetic-key", store.snapshotForNewRun().target().credentials.accessToken())
    }

    @Test fun responsesPresetImportRetainsProtocolAndRejectsChatCompatWhileChatSchemaReachesWire() = runBlocking {
        val raw = """{"id":"native","name":"Native","protocol":"openai-responses","baseUrl":"https://example.invalid/v1","models":[{"id":"m"}]}"""
        val preset = parseProviderTemplate("pack", "Pack", "1", "providers/native.json", raw.toByteArray())
        assertTrue(preset.supported); assertEquals("openai-responses", preset.protocol.wire)
        val store = OpenAiSettingsStore(temp.newFolder(), cipher())
        store.saveProvider(ProviderConnection("native", preset.name, preset.baseUrl, preset.protocol.wire),
            preset.models.map { EnabledModel(it.id) }, "key")
        assertEquals("openai-responses", store.snapshotForNewRun().protocol)
        assertThrows(PluginPackageException::class.java) {
            parseProviderTemplate("pack", "Pack", "1", "providers/native.json",
                raw.dropLast(1).plus(",\"compat\":{\"schemaProfile\":\"BASIC\"}}").toByteArray())
        }
        val chat = raw.replace("openai-responses", "openai-chat").dropLast(1) + ",\"compat\":{\"schemaProfile\":\"BASIC\"}}"
        val cp = parseProviderTemplate("pack", "Pack", "1", "providers/chat.json", chat.toByteArray())
        store.saveProvider(ProviderConnection("chat", cp.name, cp.baseUrl, cp.protocol.wire, compat = cp.compat!!),
            cp.models.map { EnabledModel(it.id) }, "chat-key")
        store.selectChatModel(ModelRef("chat", "m"))
        val snapshot = store.snapshotForNewRun()
        assertEquals(SchemaProfile.BASIC, snapshot.settings.adapterConfig().schemaProfile)
        val request = snapshot.request {
            tools = listOf(modelTool("read", "Read", Json.parseToJsonElement(
                """{"type":"object","properties":{"q":{"type":"string","minLength":2}}}""").jsonObject))
        }
        val encoded = Json.parseToJsonElement(OpenAiChatCodec(snapshot.settings.adapterConfig()).encode(request)).jsonObject
        val parameters = encoded["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertFalse(parameters["properties"]!!.jsonObject["q"]!!.jsonObject.containsKey("minLength"))
    }
}
