package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.chat.ProviderSettingsEditor
import io.github.bytejuno.thetacore.storage.KeyStoreSecretCipher
import io.github.bytejuno.thetacore.storage.SecretKeySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Real AES-GCM, atomic files and editor/store paths; key-provider failures are injected.
 * These host tests do not certify AndroidKeyStore or hardware invalidation on a device. */
class CredentialKeyRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun settings(model: String = "original") =
        OpenAiProviderSettings("chat", "https://example.invalid/v1", model)
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private suspend fun token(snapshot: OpenAiRunSnapshot) = snapshot.target(ModelTransport { _, _, _ ->
        throw AssertionError("Reading a snapshot must not make a request")
    }).credentials.accessToken()

    private inner class Keys : SecretKeySource {
        var current: SecretKey? = key()
        var unreadable = false
        var serviceFailure = false
        var failGeneration = false
        var failDeletion = false
        var failWrites = false
        var deletes = 0
        var creates = 0
        override fun load(create: Boolean): SecretKey {
            if (serviceFailure) throw IOException("synthetic provider error")
            if (unreadable || (create && failWrites)) throw UnrecoverableKeyException("synthetic unusable alias")
            if (current == null) {
                if (!create) throw UnrecoverableKeyException("synthetic missing alias")
                creates++
                current = key()
                if (failGeneration) throw IOException("synthetic partial key generation")
            }
            return current!!
        }
        override fun delete() {
            deletes++
            if (failDeletion) throw IOException("synthetic delete failure")
            current = null; unreadable = false; failWrites = false
        }
    }

    private fun record(root: File, name: String = "active.bin") =
        File(root, "${OpenAiSettingsStore.DIRECTORY}/$name")
    private fun failure(kind: ProviderSettingsException.Failure, action: () -> Unit) {
        val error = assertThrows(ProviderSettingsException::class.java, action)
        assertEquals(kind, error.failure)
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
        assertFalse(error.stackTraceToString().contains("synthetic"))
    }

    @Test fun explicitReentryRecoversLookupAndCipherInitFailuresAndPreservesAdmittedSnapshot() = runBlocking {
        for (name in listOf("active.bin", "images.bin")) {
            for (mode in listOf("lookup", "cipher-init")) {
                val root = temp.newFolder()
                val keys = Keys()
                val cipher = KeyStoreSecretCipher("recovery-$name", keys, allowKeyRecovery = true)
                val store = OpenAiSettingsStore(root, cipher, name)
                store.replace(settings(), "synthetic-old-key")
                val admitted = store.snapshotForNewRun()
                val original = record(root, name).readBytes()
                if (mode == "lookup") keys.unreadable = true
                else keys.current = SecretKeySpec(ByteArray(1), "AES") // real Cipher.init rejects this key
                val editor = ProviderSettingsEditor()
                repeat(2) {
                    editor.load(store)
                    assertEquals(R.string.chat_credentials_unavailable, editor.loadError)
                    assertFalse(editor.editable)
                    failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { store.snapshotForNewRun() }
                    failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { store.updateSettings(settings("edited")) }
                    assertEquals(0, keys.deletes)
                    assertEquals(0, keys.creates)
                    assertArrayEquals(original, record(root, name).readBytes())
                }
                editor.beginReplacement()
                assertTrue(editor.editable)
                assertTrue(editor.replacingUnreadable)
                assertFalse(editor.form.canReuseKey)
                assertEquals(0, keys.deletes)
                assertThrows(IllegalArgumentException::class.java) { store.replace(settings(), "") }
                assertEquals(0, keys.deletes)
                store.replace(settings("replacement"), "synthetic-new-key")
                assertEquals(1, keys.deletes)
                assertEquals(1, keys.creates)
                val cold = OpenAiSettingsStore(root,
                    KeyStoreSecretCipher("recovery-$name", keys, allowKeyRecovery = true), name)
                editor.load(cold)
                assertEquals("replacement", editor.form.model)
                assertTrue(editor.form.canReuseKey)
                assertFalse(editor.replacingUnreadable)
                assertEquals("synthetic-new-key", token(cold.snapshotForNewRun()))
                assertEquals("synthetic-old-key", token(admitted))
                assertEquals("original", admitted.settings.model)
                assertEquals(listOf(name), record(root, name).parentFile.list()!!.toList())
            }
        }
    }

    @Test fun usableKeysAndCorruptCiphertextNeverTriggerRotation() {
        val root = temp.newFolder()
        val keys = Keys()
        val originalKey = keys.current
        val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true))
        store.replace(settings(), "first")
        val original = record(root).readBytes()
        store.replace(settings(), "second")
        assertFalse(original.contentEquals(record(root).readBytes()))
        store.updateSettings(settings("updated"))
        record(root).writeBytes(byteArrayOf(1, 2, 3))
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { store.readSettings() }
        store.replace(settings(), "third")
        assertEquals(settings().model, store.readSettings().model)
        assertSame(originalKey, keys.current)
        assertEquals(0, keys.deletes)
        assertEquals(0, keys.creates)
    }

    @Test fun failedRebuildCleansPartialKeyAndKeepsOriginalRecordForExplicitRetry() = runBlocking {
        val root = temp.newFolder()
        val keys = Keys()
        val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true))
        store.replace(settings(), "old")
        val admitted = store.snapshotForNewRun()
        val original = record(root).readBytes()
        keys.unreadable = true; keys.failGeneration = true
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
        assertNull(keys.current)
        assertEquals(2, keys.deletes)
        assertArrayEquals(original, record(root).readBytes())
        assertEquals(listOf("active.bin"), record(root).parentFile.list()!!.toList())
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { store.snapshotForNewRun() }
        assertNull(keys.current) // Read must not generate the missing key.
        keys.failGeneration = false
        store.replace(settings(), "new")
        assertEquals("new", token(store.snapshotForNewRun()))
        assertEquals("old", token(admitted))
    }

    @Test fun failedDeleteIsSanitizedAndRetryableWithoutPublishingNewRecord() {
        val root = temp.newFolder()
        val keys = Keys()
        val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true))
        store.replace(settings(), "old")
        val original = record(root).readBytes()
        val originalKey = keys.current
        keys.unreadable = true; keys.failDeletion = true
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
        assertSame(originalKey, keys.current)
        assertArrayEquals(original, record(root).readBytes())
        keys.failDeletion = false
        store.replace(settings("new"), "new")
        assertEquals("new", store.readSettings().model)
    }

    @Test fun ordinaryServiceFailureAndSettingsOnlyWriteDoNotAuthorizeRecovery() {
        val root = temp.newFolder()
        val keys = Keys()
        val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true))
        store.replace(settings(), "old")
        val original = record(root).readBytes()
        keys.serviceFailure = true
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
        keys.serviceFailure = false; keys.failWrites = true
        // Decrypt succeeds, but the key fails when encrypting the settings-only update.
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.updateSettings(settings("new")) }
        assertEquals(0, keys.deletes)
        assertArrayEquals(original, record(root).readBytes())
        store.replace(settings("new"), "new")
        assertEquals(1, keys.deletes)
        assertEquals("new", store.readSettings().model)
    }

    @Test fun persistenceFailureAfterRecoveryLeavesNoPartialRecordAndNextSaveReusesNewKey() {
        val root = temp.newFolder()
        val keys = Keys()
        val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true))
        store.replace(settings(), "old")
        keys.unreadable = true
        val path = record(root)
        assertTrue(path.delete()); assertTrue(path.mkdir()) // Reject non-regular destination.
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
        assertTrue(path.isDirectory)
        assertEquals(listOf("active.bin"), path.parentFile.list()!!.toList())
        val rebuilt = keys.current
        assertTrue(path.delete())
        store.replace(settings(), "new")
        assertSame(rebuilt, keys.current)
        assertEquals(1, keys.deletes)
        assertEquals(1, keys.creates)
    }

    @Test fun recoveryIsOptInAndDoesNotAffectAnotherProfileAlias() = runBlocking {
        val root = temp.newFolder()
        val chatKeys = Keys(); val imageKeys = Keys()
        val chat = OpenAiSettingsStore(root, KeyStoreSecretCipher("chat", chatKeys, true))
        val images = OpenAiSettingsStore(root, KeyStoreSecretCipher("images", imageKeys, true), "images.bin")
        chat.replace(settings(), "chat-key"); images.replace(settings(), "image-key")
        val imageRecord = record(root, "images.bin").readBytes()
        chatKeys.unreadable = true
        chat.replace(settings(), "new-chat-key")
        assertArrayEquals(imageRecord, record(root, "images.bin").readBytes())
        assertEquals("image-key", token(images.snapshotForNewRun()))
        assertEquals(0, imageKeys.deletes)
        val noRecovery = KeyStoreSecretCipher("plugin", imageKeys)
        imageKeys.unreadable = true
        assertThrows(UnrecoverableKeyException::class.java) { noRecovery.encryptForReplacement(byteArrayOf(1)) }
        assertEquals(0, imageKeys.deletes)
    }
}
