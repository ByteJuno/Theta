package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.storage.*
import io.github.bytejuno.thetacore.R
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ProviderConnectionsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cipher(): SecretCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmSecretCipher("v3-tests") { key }
    }
    private fun connection(id: String = "gateway") = ProviderConnection(id, id, "https://example.invalid/v1")
    private suspend fun token(snapshot: OpenAiRunSnapshot) = snapshot.target().credentials.accessToken()

    @Test fun legacyWhitespaceModelMigratesWithoutChangingModelOrKey() = runBlocking {
        val root = temp.newFolder(); val cipher = cipher()
        val store = OpenAiSettingsStore(root, cipher)
        val legacy = OpenAiProviderSettings("chat", "https://example.invalid/v1", "model\told",
            OpenAiParameters(maxOutputTokens = 123, stopSequences = listOf("stop")),
            OpenAiChatConfig.Compat(schemaProfile = SchemaProfile.BASIC))
        store.replace(legacy, "old-key")
        val before = File(root, "${OpenAiSettingsStore.DIRECTORY}/active.bin").readBytes()
        val catalog = store.readProviderCatalog()
        assertEquals("model old", catalog.providers.single().connection.displayName)
        assertEquals("model\told", catalog.providers.single().models.single().modelId)
        assertEquals("old-key", token(store.snapshotForNewRun()))
        assertArrayEquals(before, File(root, "${OpenAiSettingsStore.DIRECTORY}/active.bin").readBytes())
        store.bindModels(catalog.bindings)
        val cold = OpenAiSettingsStore(root, cipher).snapshotForNewRun()
        assertEquals("model\told", cold.settings.model)
        assertEquals(123, cold.settings.parameters.maxOutputTokens)
        assertEquals(SchemaProfile.BASIC, cold.settings.compat.schemaProfile)
        assertEquals("old-key", token(cold))
    }

    @Test fun v2IndependentCredentialSlotsAndPromptsSurviveSharedAndSeparateImageMigration() = runBlocking {
        for (shared in listOf(false, true)) {
            val root = temp.newFolder(); val c = cipher(); val ic = cipher()
            val profiles = listOf("a", "b").map { id -> ProviderSettingsCodec.ProfileRecord(id, id,
                OpenAiProviderSettings("chat", "https://example.invalid/v1", "model-$id",
                    OpenAiParameters(temperature = 0.2), systemPrompt = "System $id"), "key-$id") }
            val bytes = ProviderSettingsCodec.encodeCatalog(ProviderSettingsCodec.CatalogRecord("b", profiles))
            val directory = File(root, OpenAiSettingsStore.DIRECTORY).apply { mkdirs() }
            File(directory, "active.bin").writeBytes(c.encrypt(bytes))
            val images = OpenAiSettingsStore(root, ic, "images.bin")
            images.replace(OpenAiProviderSettings("images", if (shared) "https://example.invalid/v1" else "https://images.invalid/v1", "paint"),
                if (shared) "key-a" else "image-key")
            val oldImage = File(directory, "images.bin").readBytes()
            val store = OpenAiSettingsStore(root, c, legacyImages = images)
            val catalog = store.readProviderCatalog()
            assertEquals(if (shared) 2 else 3, catalog.providers.size)
            assertEquals("b", catalog.bindings.activeChatModelRef?.providerId)
            assertEquals("System b", store.snapshotForNewRun().settings.systemPrompt)
            assertEquals("key-b", token(store.snapshotForNewRun()))
            assertEquals(if (shared) "a" else "legacy-images", catalog.bindings.activeImageModelRef?.providerId)
            store.bindModels(catalog.bindings)
            val cold = OpenAiSettingsStore(root, c)
            assertEquals("paint", cold.snapshotForImage().settings.model)
            assertEquals(if (shared) "key-a" else "image-key", token(cold.snapshotForImage()))
            cold.selectChatModel(ModelRef("a", "model-a"))
            assertEquals("System a", cold.snapshotForNewRun().settings.systemPrompt)
            assertEquals("key-a", token(cold.snapshotForNewRun()))
            assertArrayEquals(oldImage, File(directory, "images.bin").readBytes())
        }
    }

    @Test fun multipleModelsInheritOrOverrideCompatAndBothSlotsFreezeBeforeNextEdit() = runBlocking {
        val root = temp.newFolder(); val c = cipher(); val store = OpenAiSettingsStore(root, c)
        val provider = connection().copy(compat = OpenAiChatConfig.Compat(schemaProfile = SchemaProfile.BASIC))
        val models = listOf(EnabledModel("fast", alias = "Fast", systemPrompt = "Be brief"),
            EnabledModel("strong", compat = OpenAiChatConfig.Compat(schemaProfile = SchemaProfile.NO_TOP_LEVEL_COMBINATORS)),
            EnabledModel("draw", chat = false, image = true))
        store.saveProvider(provider, models, "first-key")
        store.bindModels(ModelBindings(ModelRef("gateway", "fast"), ModelRef("gateway", "draw")))
        val frozen = store.snapshotForNewRun()
        assertEquals(SchemaProfile.BASIC, frozen.settings.compat.schemaProfile)
        assertEquals("Be brief", frozen.request().system)
        store.selectChatModel(ModelRef("gateway", "strong"))
        assertEquals("draw", store.snapshotForImage().settings.model)
        store.saveProvider(provider, models, "new-key")
        val next = store.snapshotForNewRun()
        assertEquals("strong", next.settings.model)
        assertEquals(SchemaProfile.NO_TOP_LEVEL_COMBINATORS, next.settings.compat.schemaProfile)
        assertEquals("new-key", token(next))
        assertEquals("first-key", token(frozen))
        assertEquals("first-key", token(requireNotNull(frozen.imageSnapshot)))
        assertEquals("fast", frozen.settings.model)
        val runs = RunModelSnapshots()
        runs.bind("run", frozen)
        assertSame(frozen.imageSnapshot, runs.image("run", store::snapshotForImage))
        runs.release("run")
        assertEquals("new-key", token(runs.image("run", store::snapshotForImage)))
    }

    @Test fun recoverySaveRereadsUnderLockAndNeverDeletesReadableProviders() {
        val root = temp.newFolder(); val aes = cipher(); var unreadable = false
        val flaky = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = aes.encrypt(plaintext)
            override fun decrypt(ciphertext: ByteArray): ByteArray { check(!unreadable); return aes.decrypt(ciphertext) }
        }
        val store = OpenAiSettingsStore(root, flaky)
        store.saveProvider(connection("one"), listOf(EnabledModel("first")), "old-key")
        val file = File(root, "${OpenAiSettingsStore.DIRECTORY}/active.bin"); val before = file.readBytes()
        unreadable = true
        assertThrows(ProviderSettingsException::class.java) { store.readProviderCatalog() }
        unreadable = false
        val failure = assertThrows(ProviderSettingsException::class.java) {
            store.saveProvider(connection("two"), listOf(EnabledModel("second")), "new-key", replaceUnreadable = true)
        }
        assertEquals(ProviderSettingsException.Failure.RECOVERED, failure.failure)
        assertArrayEquals(before, file.readBytes())
        assertEquals(listOf("one"), store.readProviderCatalog().providers.map { it.connection.providerId })
        unreadable = true
        store.saveProvider(connection("two"), listOf(EnabledModel("second")), "new-key", replaceUnreadable = true)
        unreadable = false
        assertEquals(listOf("two"), store.readProviderCatalog().providers.map { it.connection.providerId })
    }

    @Test fun selectionBlocksUiAdmissionUntilPersistenceAndSurfacesRetryableFailure() = runBlocking {
        val root = temp.newFolder(); val aes = cipher(); var failWrite = false
        val c = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray { check(!failWrite); return aes.encrypt(plaintext) }
            override fun decrypt(ciphertext: ByteArray) = aes.decrypt(ciphertext)
        }
        val store = OpenAiSettingsStore(root, c)
        val initial = store.saveProvider(connection(), listOf(EnabledModel("a"), EnabledModel("b")), "key")
        val gate = CompletableDeferred<Unit>()
        val selection = ChatModelSelection(this) { ref -> gate.await(); store.selectChatModel(ref) }
        selection.loaded(initial)
        failWrite = true
        val write = requireNotNull(selection.select(ModelRef("gateway", "b")))
        assertTrue(selection.state.value.saving)
        assertNull(selection.select(ModelRef("gateway", "a")))
        assertEquals("a", store.snapshotForNewRun().settings.model)
        gate.complete(Unit); write.join()
        assertFalse(selection.state.value.saving)
        assertEquals(R.string.providers_switch_failed, selection.state.value.error)
        assertEquals("a", selection.state.value.catalog.bindings.activeChatModelRef?.modelId)
        assertEquals("a", OpenAiSettingsStore(root, c).snapshotForNewRun().settings.model)
        failWrite = false
        requireNotNull(selection.select(ModelRef("gateway", "b"))).join()
        assertNull(selection.state.value.error)
        assertEquals("b", store.snapshotForNewRun().settings.model)
    }

    @Test fun providerProtocolAndEndpointIsolationFailClosed() {
        val store = OpenAiSettingsStore(temp.newFolder(), cipher())
        val c = connection(); val models = listOf(EnabledModel("model"))
        store.saveProvider(c, models, "original-key")
        val failure = assertThrows(ProviderSettingsException::class.java) {
            store.saveProvider(c.copy(baseUrl = "https://changed.invalid/v1"), models, "")
        }
        assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, failure.failure)
        assertThrows(IllegalArgumentException::class.java) { c.copy(protocol = "openai-responses") }
        assertThrows(IllegalArgumentException::class.java) { c.copy(protocol = "anthropic") }
        assertEquals("openai-chat", store.snapshotForNewRun().protocol)
    }

    @Test fun sharedModelKeepsDistinctLegacyImageParametersAndImageOnlyInstallsRemainRecoverable() = runBlocking {
        for (chatPresent in listOf(false, true)) {
            val root = temp.newFolder(); val c = cipher(); val ic = cipher()
            val images = OpenAiSettingsStore(root, ic, "images.bin")
            images.replace(OpenAiProviderSettings("images", "https://example.invalid/v1", "same",
                OpenAiParameters(temperature = 0.7), OpenAiChatConfig.Compat(schemaProfile = SchemaProfile.BASIC)), "key")
            if (chatPresent) OpenAiSettingsStore(root, c).replace(OpenAiProviderSettings("chat", "https://example.invalid/v1", "same",
                OpenAiParameters(temperature = 0.1)), "key")
            val store = OpenAiSettingsStore(root, c, legacyImages = images)
            val catalog = store.readProviderCatalog()
            assertEquals(1, catalog.providers.size)
            assertEquals(0.7, store.snapshotForImage().settings.parameters.temperature!!, 0.0)
            if (chatPresent) assertEquals(0.1, store.snapshotForNewRun().settings.parameters.temperature!!, 0.0)
            store.bindModels(catalog.bindings)
            val cold = OpenAiSettingsStore(root, c)
            assertEquals(0.7, cold.snapshotForImage().settings.parameters.temperature!!, 0.0)
            assertEquals(SchemaProfile.BASIC, cold.snapshotForImage().settings.compat.schemaProfile)
            assertEquals("key", token(cold.snapshotForImage()))
            store.deleteProvider(catalog.providers.single().connection.providerId)
            assertTrue(OpenAiSettingsStore(root, c, legacyImages = images).readProviderCatalog().providers.isEmpty())
        }
    }
}
