package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.chat.ProviderSettingsEditor
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import io.github.bytejuno.thetacore.storage.KeyFailureKind
import io.github.bytejuno.thetacore.storage.KeyStoreSecretCipher
import io.github.bytejuno.thetacore.storage.SecretCipher
import io.github.bytejuno.thetacore.storage.SecretKeySource
import io.github.bytejuno.thetacore.storage.UnusableSecretKeyException
import io.github.bytejuno.thetacore.storage.classifyKeyStoreError
import io.github.bytejuno.thetacore.storage.isDefinitiveKeyInvalidation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
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
        var lookupFailure: Exception? = null
        var clearLookupFailureOnDelete = false
        var deletes = 0
        var creates = 0
        override fun load(create: Boolean): SecretKey {
            lookupFailure?.let { throw it }
            if (serviceFailure) throw IOException("synthetic provider error")
            if (unreadable || (create && failWrites)) throw UnusableSecretKeyException("synthetic unusable alias")
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
            if (clearLookupFailureOnDelete) lookupFailure = null
        }
    }

    // Corrupt/check the authoritative record after settings-only updates migrate to v3.
    private fun record(root: File, name: String = "active.bin"): File {
        val current = File(root, "${OpenAiSettingsStore.DIRECTORY}/$name.v3")
        return if (current.exists()) current else File(root, "${OpenAiSettingsStore.DIRECTORY}/$name")
    }
    private fun failure(kind: ProviderSettingsException.Failure, action: () -> Unit) {
        val error = assertThrows(ProviderSettingsException::class.java, action)
        assertEquals(kind, error.failure)
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
        assertFalse(error.stackTraceToString().contains("synthetic"))
    }

    @Test fun explicitReentryRecoversLookupAndCipherInitFailuresAndPreservesAdmittedSnapshot() = runBlocking {
        for (name in listOf("active.bin", "images.bin")) {
            for (mode in listOf("lookup", "wrapped-lookup", "cipher-init")) {
                val root = temp.newFolder()
                val keys = Keys()
                var invalidatedKey: SecretKey? = null
                val aes = AesGcmSecretCipher("recovery-$name", keys::load)
                val innerCipher = object : SecretCipher {
                    override fun encrypt(plaintext: ByteArray): ByteArray {
                        if (invalidatedKey != null && keys.current === invalidatedKey) {
                            throw InvalidKeyException(
                                "synthetic cipher init failure",
                                UnusableSecretKeyException("synthetic invalidated key"),
                            )
                        }
                        return aes.encrypt(plaintext)
                    }
                    override fun decrypt(ciphertext: ByteArray): ByteArray {
                        if (invalidatedKey != null && keys.current === invalidatedKey) {
                            throw InvalidKeyException(
                                "synthetic cipher init failure",
                                UnusableSecretKeyException("synthetic invalidated key"),
                            )
                        }
                        return aes.decrypt(ciphertext)
                    }
                }
                val cipher = if (mode == "cipher-init") {
                    KeyStoreSecretCipher(innerCipher, keys, allowKeyRecovery = true)
                } else {
                    KeyStoreSecretCipher("recovery-$name", keys, allowKeyRecovery = true)
                }
                val store = OpenAiSettingsStore(root, cipher, name)
                store.replace(settings(), "synthetic-old-key")
                val admitted = store.snapshotForNewRun()
                val original = record(root, name).readBytes()
                when (mode) {
                    "lookup" -> keys.unreadable = true
                    "wrapped-lookup" -> {
                        keys.clearLookupFailureOnDelete = true
                        keys.lookupFailure = UnrecoverableKeyException("synthetic wrapper").apply {
                            initCause(UnusableSecretKeyException("synthetic corrupted alias"))
                        }
                    }
                    "cipher-init" -> invalidatedKey = keys.current
                }
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

    @Test fun wrappedTemporaryAndUnknownFailuresPreserveKeyAndReadableRecordEvenIfGenerationWouldFail() = runBlocking {
        class SyntheticAuthError : Exception("synthetic auth required")
        val failures = listOf(
            IOException("synthetic temporary service failure"),
            UnrecoverableKeyException("synthetic wrapper").apply {
                initCause(IOException("synthetic temporary service failure"))
            },
            InvalidKeyException("synthetic wrapper", IOException("synthetic temporary service failure")),
            ProviderException("synthetic wrapper", IOException("synthetic temporary service failure")),
            UnusableSecretKeyException("synthetic wrapper", IOException("synthetic temporary service failure")),
            UnusableSecretKeyException("synthetic suppressed").apply {
                addSuppressed(IOException("synthetic suppressed transient"))
            },
            UnrecoverableKeyException("synthetic unknown failure"),
            InvalidKeyException("synthetic unknown failure"),
            KeyStoreException("synthetic uninitialized keystore"),
            IllegalStateException("synthetic wrapper", InvalidKeyException("synthetic unknown failure")),
            InvalidKeyException("synthetic auth wrapper", SyntheticAuthError()),
            UnusableSecretKeyException("synthetic auth veto", SyntheticAuthError()),
        )
        val classifier: (Throwable) -> KeyFailureKind = { node ->
            if (node is SyntheticAuthError) KeyFailureKind.NON_RECOVERABLE
            else io.github.bytejuno.thetacore.storage.classifyAndroidKeyFailure(node)
        }
        for (injected in failures) for (generationFails in listOf(false, true)) {
            val root = temp.newFolder()
            val keys = Keys()
            val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true, classifier))
            store.replace(settings(), "old")
            val originalKey = keys.current
            val original = record(root).readBytes()
            keys.lookupFailure = injected
            keys.failGeneration = generationFails
            failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
            assertSame(originalKey, keys.current)
            assertEquals(0, keys.deletes)
            assertEquals(0, keys.creates)
            assertArrayEquals(original, record(root).readBytes())
            keys.lookupFailure = null
            val cold = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true, classifier))
            assertEquals("old", token(cold.snapshotForNewRun()))
            cold.replace(settings("retry"), "new")
            assertEquals("new", token(cold.snapshotForNewRun()))
            assertSame(originalKey, keys.current)
            assertEquals(0, keys.deletes)
            assertEquals(0, keys.creates)
        }
    }

    @Test fun cipherInitTransientAuthAndUnknownFailuresPreserveKeyEvenIfGenerationWouldFail() = runBlocking {
        class SyntheticPlatformTransient : Exception("synthetic transient keystore error")
        class SyntheticPlatformInvalidated : Exception("synthetic permanently invalidated")
        val classifier: (Throwable) -> KeyFailureKind = { node ->
            when (node) {
                is SyntheticPlatformTransient -> KeyFailureKind.NON_RECOVERABLE
                is SyntheticPlatformInvalidated -> KeyFailureKind.DEFINITIVE_INVALIDATION
                else -> io.github.bytejuno.thetacore.storage.classifyAndroidKeyFailure(node)
            }
        }
        val nonRecoverable = listOf<() -> Exception>(
            { InvalidKeyException("synthetic unknown cipher init") },
            { InvalidKeyException("synthetic wrapped transient", IOException("synthetic io")) },
            { ProviderException("synthetic wrapped transient", SyntheticPlatformTransient()) },
            {
                InvalidKeyException("synthetic invalidated with transient cause", SyntheticPlatformInvalidated()).apply {
                    cause!!.initCause(SyntheticPlatformTransient())
                }
            },
        )
        for (makeFailure in nonRecoverable) for (generationFails in listOf(false, true)) {
            val root = temp.newFolder()
            val keys = Keys()
            val aes = AesGcmSecretCipher("recovery", keys::load)
            var encryptFailure: Exception? = null
            val inner = object : SecretCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray {
                    encryptFailure?.let { throw it }
                    return aes.encrypt(plaintext)
                }
                override fun decrypt(ciphertext: ByteArray): ByteArray = aes.decrypt(ciphertext)
            }
            val store = OpenAiSettingsStore(root, KeyStoreSecretCipher(inner, keys, true, classifier))
            store.replace(settings(), "old")
            val originalKey = keys.current
            val original = record(root).readBytes()
            encryptFailure = makeFailure()
            keys.failGeneration = generationFails
            failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
            assertSame(originalKey, keys.current)
            assertEquals(0, keys.deletes)
            assertEquals(0, keys.creates)
            assertArrayEquals(original, record(root).readBytes())
            encryptFailure = null
            val cold = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true, classifier))
            assertEquals("old", token(cold.snapshotForNewRun()))
        }

        // Real Cipher.init rejecting an unknown invalid key without definitive invalidation cause does not delete.
        val root = temp.newFolder()
        val keys = Keys()
        val store = OpenAiSettingsStore(root, KeyStoreSecretCipher("recovery", keys, true))
        store.replace(settings(), "old")
        val original = record(root).readBytes()
        keys.current = SecretKeySpec(ByteArray(1), "AES")
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), "new") }
        assertEquals(0, keys.deletes)
        assertEquals(0, keys.creates)
        assertArrayEquals(original, record(root).readBytes())
    }

    @Test fun keyStoreErrorCodeClassificationAndCauseChainRulesAreFailClosed() {
        val definitiveCodes = setOf(
            android.security.KeyStoreException.ERROR_KEY_CORRUPTED,
            android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST,
        )
        val nonRecoverableCodes = listOf(
            android.security.KeyStoreException.ERROR_OTHER,
            android.security.KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED,
            android.security.KeyStoreException.ERROR_KEYSTORE_UNINITIALIZED,
            android.security.KeyStoreException.ERROR_INTERNAL_SYSTEM_ERROR,
            android.security.KeyStoreException.ERROR_PERMISSION_DENIED,
            android.security.KeyStoreException.ERROR_ID_ATTESTATION_FAILURE,
            android.security.KeyStoreException.ERROR_ATTESTATION_CHALLENGE_TOO_LARGE,
            android.security.KeyStoreException.ERROR_KEYMINT_FAILURE,
            android.security.KeyStoreException.ERROR_KEYSTORE_FAILURE,
            android.security.KeyStoreException.ERROR_UNIMPLEMENTED,
            android.security.KeyStoreException.ERROR_INCORRECT_USAGE,
            android.security.KeyStoreException.ERROR_KEY_NOT_TEMPORALLY_VALID,
            android.security.KeyStoreException.ERROR_KEY_OPERATION_EXPIRED,
            android.security.KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE,
            android.security.KeyStoreException.ERROR_TOO_MANY_KEYS,
        )
        for (code in definitiveCodes) {
            assertEquals(KeyFailureKind.DEFINITIVE_INVALIDATION, classifyKeyStoreError(code, false, false, false))
            assertEquals(KeyFailureKind.NON_RECOVERABLE, classifyKeyStoreError(code, true, false, false))
            assertEquals(KeyFailureKind.NON_RECOVERABLE, classifyKeyStoreError(code, false, true, false))
            assertEquals(KeyFailureKind.NON_RECOVERABLE, classifyKeyStoreError(code, false, false, true))
        }
        for (code in nonRecoverableCodes) {
            assertEquals(KeyFailureKind.NON_RECOVERABLE, classifyKeyStoreError(code, false, false, false))
        }

        // Deeply wrapped definitive invalidation recovers when no veto exists in the chain.
        val deepDefinitive = IllegalStateException(
            "outer",
            ProviderException("mid", UnusableSecretKeyException("corrupted")),
        )
        assertTrue(isDefinitiveKeyInvalidation(deepDefinitive))

        // Veto anywhere in the cause chain blocks deletion even if another node is definitive.
        val vetoBefore = IOException("outer transient", UnusableSecretKeyException("inner"))
        val vetoAfter = UnusableSecretKeyException("outer", IOException("inner transient"))
        assertFalse(isDefinitiveKeyInvalidation(vetoBefore))
        assertFalse(isDefinitiveKeyInvalidation(vetoAfter))

        // Suppressed definitive invalidation must not authorize deletion; suppressed transient vetoes it.
        val suppressedOnly = InvalidKeyException("unknown").apply {
            addSuppressed(UnusableSecretKeyException("suppressed"))
        }
        assertFalse(isDefinitiveKeyInvalidation(suppressedOnly))
        val suppressedVeto = UnusableSecretKeyException("outer").apply {
            addSuppressed(IllegalStateException("wrapper", IOException("suppressed transient")))
        }
        assertFalse(isDefinitiveKeyInvalidation(suppressedVeto))
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
        assertThrows(UnusableSecretKeyException::class.java) { noRecovery.encryptForReplacement(byteArrayOf(1)) }
        assertEquals(0, imageKeys.deletes)
    }
}
