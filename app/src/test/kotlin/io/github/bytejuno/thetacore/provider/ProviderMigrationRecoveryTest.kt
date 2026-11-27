package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import io.github.bytejuno.thetacore.storage.SecretCipher
import io.github.bytejuno.thetacore.storage.KeyStoreSecretCipher
import io.github.bytejuno.thetacore.storage.SecretKeySource
import io.github.bytejuno.thetacore.storage.UnusableSecretKeyException
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderMigrationRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    private class Fixture(val root: File) {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val aes = AesGcmSecretCipher("migration-chat") { key }
        var unreadable = false
        var failWrite = false
        var imageReads = 0
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray { check(!failWrite); return aes.encrypt(plaintext) }
            override fun decrypt(ciphertext: ByteArray): ByteArray { check(!unreadable); return aes.decrypt(ciphertext) }
        }
        private val imageKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val imageAes = AesGcmSecretCipher("migration-image") { imageKey }
        private val imageCipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = imageAes.encrypt(plaintext)
            override fun decrypt(ciphertext: ByteArray): ByteArray { imageReads++; return imageAes.decrypt(ciphertext) }
        }
        val images = OpenAiSettingsStore(root, imageCipher, "images.bin")
        init {
            OpenAiSettingsStore(root, cipher).replace(
                OpenAiProviderSettings("chat", "https://chat.invalid/v1", "chat"), "old-chat-key")
            images.replace(OpenAiProviderSettings("images", "https://retired.invalid/v1", "old-image"), "old-image-key")
        }
        fun open() = OpenAiSettingsStore(root, cipher, legacyImages = OpenAiSettingsStore(root, imageCipher, "images.bin"))
        fun migrate(): OpenAiSettingsStore = open().also { it.bindModels(it.readProviderCatalog().bindings) }
        fun legacyBytes() = listOf("active.bin", "images.bin").associateWith {
            File(root, "${OpenAiSettingsStore.DIRECTORY}/$it").readBytes()
        }
    }

    private fun assertRecoveryDoesNotImportLegacy(edit: (OpenAiSettingsStore) -> Unit) = runBlocking {
        val f = Fixture(temp.newFolder())
        edit(f.migrate())
        val legacy = f.legacyBytes()
        val imageReads = f.imageReads
        f.unreadable = true
        val recovering = f.open()
        assertThrows(ProviderSettingsException::class.java) { recovering.readProviderCatalog() }
        recovering.saveProvider(ProviderConnection("replacement", "Replacement", "https://replacement.invalid/v1"),
            listOf(EnabledModel("new-chat")), "new-key", replaceUnreadable = true)
        f.unreadable = false
        val cold = f.open()
        val catalog = cold.readProviderCatalog()
        assertEquals(listOf("replacement"), catalog.providers.map { it.connection.providerId })
        assertEquals(ModelRef("replacement", "new-chat"), catalog.bindings.activeChatModelRef)
        assertNull(catalog.bindings.activeImageModelRef)
        assertNull(cold.snapshotForNewRun().imageSnapshot)
        assertEquals("new-key", cold.snapshotForNewRun().target().credentials.accessToken())
        assertThrows(ProviderSettingsException::class.java) { cold.snapshotForImage() }
        // The old key cannot be reused by re-entering its provider ID and leaving the key blank.
        val error = assertThrows(ProviderSettingsException::class.java) {
            cold.saveProvider(ProviderConnection("legacy-images", "Old images", "https://retired.invalid/v1"),
                listOf(EnabledModel("old-image", chat = false, image = true)), "")
        }
        assertEquals(ProviderSettingsException.Failure.NOT_CONFIGURED, error.failure)
        assertEquals(imageReads, f.imageReads)
        legacy.forEach { (name, bytes) -> assertArrayEquals(bytes,
            File(f.root, "${OpenAiSettingsStore.DIRECTORY}/$name").readBytes()) }
    }

    @Test fun deletedImageProviderStaysRetiredAfterUnreadableV3Recovery() =
        assertRecoveryDoesNotImportLegacy { it.deleteProvider("legacy-images") }

    @Test fun unboundImageStaysUnboundAfterUnreadableV3Recovery() =
        assertRecoveryDoesNotImportLegacy { it.selectImageModel(null) }

    @Test fun replacedImageCredentialsNeverFallBackToLegacyAfterUnreadableV3Recovery() =
        assertRecoveryDoesNotImportLegacy {
            it.saveProvider(ProviderConnection("legacy-images", "New images", "https://new-images.invalid/v1"),
                listOf(EnabledModel("new-image", chat = false, image = true)), "new-image-key")
            it.selectImageModel(ModelRef("legacy-images", "new-image"))
        }

    @Test fun deletingAllProvidersDoesNotReenableLegacyImagesOnRecovery() =
        assertRecoveryDoesNotImportLegacy {
            it.readProviderCatalog().providers.forEach { p -> it.deleteProvider(p.connection.providerId) }
        }

    @Test fun failedFirstMigrationLeavesBothLegacyRecordsAvailableForRetry() = runBlocking {
        val f = Fixture(temp.newFolder())
        val before = f.legacyBytes()
        val store = f.open()
        f.failWrite = true
        assertThrows(ProviderSettingsException::class.java) { store.bindModels(store.readProviderCatalog().bindings) }
        f.failWrite = false
        before.forEach { (name, bytes) -> assertArrayEquals(bytes,
            File(f.root, "${OpenAiSettingsStore.DIRECTORY}/$name").readBytes()) }
        val cold = f.open()
        assertEquals("old-chat-key", cold.snapshotForNewRun().target().credentials.accessToken())
        assertEquals("old-image-key", cold.snapshotForImage().target().credentials.accessToken())
        cold.bindModels(cold.readProviderCatalog().bindings)
        assertEquals("old-image-key", f.open().snapshotForImage().target().credentials.accessToken())
    }

    @Test fun explicitClearKeepsMigrationBoundaryEvenWithUnavailableKey() {
        val f = Fixture(temp.newFolder())
        val store = f.migrate()
        f.unreadable = true
        f.failWrite = true
        store.clear()
        val imageReads = f.imageReads
        assertTrue(f.open().readProviderCatalog().providers.isEmpty())
        assertThrows(ProviderSettingsException::class.java) { f.open().snapshotForImage() }
        assertEquals(imageReads, f.imageReads)
    }

    @Test fun corruptV3PayloadNeverReimportsReadableLegacyRecords() {
        for (bytes in listOf(byteArrayOf(0), "{\"version\":3}".toByteArray())) {
            val f = Fixture(temp.newFolder())
            f.migrate().selectImageModel(null)
            val record = File(f.root, "${OpenAiSettingsStore.DIRECTORY}/active.bin.v3")
            record.writeBytes(if (bytes.size == 1) bytes else f.cipher.encrypt(bytes))
            val imageReads = f.imageReads
            assertThrows(ProviderSettingsException::class.java) { f.open().readProviderCatalog() }
            f.open().saveProvider(ProviderConnection("new", "New", "https://new.invalid/v1"),
                listOf(EnabledModel("chat")), "new-key", replaceUnreadable = true)
            assertEquals(listOf("new"), f.open().readProviderCatalog().providers.map { it.connection.providerId })
            assertNull(f.open().snapshotForNewRun().imageSnapshot)
            assertEquals(imageReads, f.imageReads)
        }
    }

    @Test fun clearThenRefillDoesNotRestoreLegacyAndStaleRecoveryConsentIsRejected() = runBlocking {
        val f = Fixture(temp.newFolder())
        f.migrate().clear()
        val imageReads = f.imageReads
        val cold = f.open()
        val provider = ProviderConnection("new", "New", "https://new.invalid/v1")
        val failure = assertThrows(ProviderSettingsException::class.java) {
            cold.saveProvider(provider, listOf(EnabledModel("chat")), "new-key", replaceUnreadable = true)
        }
        assertEquals(ProviderSettingsException.Failure.RECOVERED, failure.failure)
        assertTrue(cold.readProviderCatalog().providers.isEmpty())
        cold.saveProvider(provider, listOf(EnabledModel("chat")), "new-key")
        assertNull(f.open().snapshotForNewRun().imageSnapshot)
        assertEquals("new-key", f.open().snapshotForNewRun().target().credentials.accessToken())
        assertEquals(imageReads, f.imageReads)
    }

    @Test fun noAuthRefillAfterUnavailableKeyClearRebuildsOnlyOnExplicitSave() = runBlocking {
        assertNoAuthInitialization(legacyImageOnly = false)
    }

    @Test fun noAuthFirstChatRecoversUnusedKeyAndKeepsReadableLegacyImages() = runBlocking {
        assertNoAuthInitialization(legacyImageOnly = true)
    }

    private suspend fun assertNoAuthInitialization(legacyImageOnly: Boolean) {
        var unusable = false
        var rebuilds = 0
        var key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val source = object : SecretKeySource {
            override fun load(create: Boolean): SecretKey {
                if (unusable) throw UnusableSecretKeyException("synthetic invalidation")
                return key
            }
            override fun delete() {
                rebuilds++
                unusable = false
                key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }
        val cipher = KeyStoreSecretCipher(AesGcmSecretCipher("clear-refill", source::load), source, allowKeyRecovery = true)
        val root = temp.newFolder()
        val store = OpenAiSettingsStore(root, cipher)
        val imageKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val images = OpenAiSettingsStore(root, AesGcmSecretCipher("initial-image") { imageKey }, "images.bin")
        images.replace(OpenAiProviderSettings("image", "https://image.invalid/v1", "paint"), "image-key")
        val imageFile = File(root, "${OpenAiSettingsStore.DIRECTORY}/images.bin")
        val imageBefore = imageFile.readBytes()
        if (legacyImageOnly) {
            store.replace(OpenAiProviderSettings("old", "https://old.invalid/v1", "old-model"), "old-key")
        } else {
            store.saveProvider(ProviderConnection("old", "Old", "https://old.invalid/v1"),
                listOf(EnabledModel("old-model")), "old-key")
        }
        unusable = true
        store.clear()
        val cold = OpenAiSettingsStore(root, cipher, legacyImages = images)
        assertEquals(if (legacyImageOnly) listOf("legacy-images") else emptyList<String>(),
            cold.readProviderCatalog().providers.map { it.connection.providerId })
        assertEquals(0, rebuilds)
        cold.saveProvider(ProviderConnection("local", "Local", "http://127.0.0.1/v1", noAuth = true),
            listOf(EnabledModel("local-model")), "")
        assertEquals(1, rebuilds)
        val reopened = OpenAiSettingsStore(root, cipher, legacyImages = images)
        assertEquals(if (legacyImageOnly) listOf("legacy-images", "local") else listOf("local"),
            reopened.readProviderCatalog().providers.map { it.connection.providerId })
        assertEquals("local-model", reopened.snapshotForNewRun().settings.model)
        assertEquals("", reopened.snapshotForNewRun().target().credentials.accessToken())
        if (legacyImageOnly) {
            val image = requireNotNull(reopened.snapshotForNewRun().imageSnapshot)
            assertEquals("paint", image.settings.model)
            assertEquals("image-key", image.target().credentials.accessToken())
        } else {
            assertNull(reopened.snapshotForNewRun().imageSnapshot)
        }
        assertArrayEquals(imageBefore, imageFile.readBytes())
    }
}
