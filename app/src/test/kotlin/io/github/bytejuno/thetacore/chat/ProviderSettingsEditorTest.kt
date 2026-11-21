package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import io.github.bytejuno.thetacore.storage.SecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ProviderSettingsEditorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun settings(model: String = "original") =
        OpenAiProviderSettings("chat", "https://example.invalid/v1", model)

    @Test fun explicitReentryRequiresNewKeyAndPreservesRecordUntilSuccessfulSave() = runBlocking {
        for (recordName in listOf("active.bin", "images.bin")) {
            for (damage in listOf("truncated", "invalid-payload", "missing-key")) {
                val root = temp.newFolder()
                var currentKey = key()
                var available = true
                var failWrite = false
                var keyLookups = 0
                val aes = AesGcmSecretCipher("provider-editor-test") { create ->
                    keyLookups++
                    if (!available) {
                        check(create)
                        currentKey = key(); available = true
                    }
                    currentKey
                }
                val cipher = object : SecretCipher {
                    override fun encrypt(plaintext: ByteArray): ByteArray {
                        check(!failWrite)
                        return aes.encrypt(plaintext)
                    }
                    override fun decrypt(ciphertext: ByteArray) = aes.decrypt(ciphertext)
                }
                val store = OpenAiSettingsStore(root, cipher, recordName)
                store.replace(settings(), "synthetic-old-key")
                val record = File(root, "${OpenAiSettingsStore.DIRECTORY}/$recordName")
                when (damage) {
                    "truncated" -> record.writeBytes(byteArrayOf(1, 2, 3))
                    "invalid-payload" -> record.writeBytes(aes.encrypt("{}".toByteArray()))
                    "missing-key" -> available = false
                }
                val before = record.readBytes()
                val editor = ProviderSettingsEditor()
                repeat(2) {
                    editor.load(store)
                    assertFalse(editor.loading)
                    assertNotNull(editor.loadError)
                    assertFalse(editor.editable)
                    assertFalse(editor.replacingUnreadable)
                    assertArrayEquals(before, record.readBytes())
                }
                val lookupsBeforeReentry = keyLookups
                editor.beginReplacement()
                assertTrue(editor.editable)
                assertTrue(editor.replacingUnreadable)
                assertNull(editor.loadError)
                assertEquals(ProviderSettingsForm(), editor.form)
                assertEquals(lookupsBeforeReentry, keyLookups)
                assertArrayEquals(before, record.readBytes())
                editor.form = editor.form.copy(endpoint = settings().baseUrl, model = "replacement")
                assertFalse(editor.form.canReuseKey)
                val replacement = editor.form.settings("chat", recordName == "images.bin")
                assertThrows(IllegalArgumentException::class.java) { store.replace(replacement, "") }
                assertArrayEquals(before, record.readBytes())
                failWrite = true
                val failure = assertThrows(ProviderSettingsException::class.java) {
                    store.replace(replacement, "synthetic-new-key")
                }
                assertEquals(ProviderSettingsException.Failure.WRITE_FAILED, failure.failure)
                assertArrayEquals(before, record.readBytes())
                failWrite = false
                store.replace(replacement, "synthetic-new-key")
                val reopened = OpenAiSettingsStore(root, cipher, recordName)
                val loaded = ProviderSettingsEditor()
                loaded.load(reopened)
                assertTrue(loaded.editable)
                assertFalse(loaded.replacingUnreadable)
                assertEquals("replacement", loaded.form.model)
                reopened.fetchModels(settings().baseUrl, "") { _, credentials ->
                    assertEquals("synthetic-new-key", credentials.accessToken())
                    emptyList()
                }
            }
        }
    }

    @Test fun retryAfterTemporaryReadFailureRestoresSavedFieldsWithoutReplacement() = runBlocking {
        val root = temp.newFolder()
        val currentKey = key()
        val aes = AesGcmSecretCipher("provider-editor-test") { currentKey }
        var unavailable = false
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = aes.encrypt(plaintext)
            override fun decrypt(ciphertext: ByteArray): ByteArray {
                check(!unavailable)
                return aes.decrypt(ciphertext)
            }
        }
        val store = OpenAiSettingsStore(root, cipher)
        store.replace(settings(), "synthetic-old-key")
        val record = File(root, "${OpenAiSettingsStore.DIRECTORY}/active.bin")
        val before = record.readBytes()
        val editor = ProviderSettingsEditor()
        unavailable = true
        editor.load(store)
        assertFalse(editor.editable)
        unavailable = false
        editor.load(store)
        assertTrue(editor.editable)
        assertTrue(editor.form.canReuseKey)
        assertEquals("original", editor.form.model)
        assertFalse(editor.replacingUnreadable)
        assertArrayEquals(before, record.readBytes())
    }
}
