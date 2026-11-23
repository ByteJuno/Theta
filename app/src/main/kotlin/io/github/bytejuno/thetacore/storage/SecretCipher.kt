package io.github.bytejuno.thetacore.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Host-only seam shared by plugin settings and provider credentials. */
internal interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
    /** Only an explicit credential replacement may repair an unusable key. */
    fun encryptForReplacement(plaintext: ByteArray): ByteArray = encrypt(plaintext)
}

/** Fresh provider-generated IV; callers supply a distinct authenticated namespace. */
internal class AesGcmSecretCipher(
    associatedData: String,
    private val key: (create: Boolean) -> SecretKey,
) : SecretCipher {
    private val aad = associatedData.toByteArray(Charsets.UTF_8)
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        val iv = cipher.iv
        if (iv.size != 12) throw IOException("Unexpected GCM IV size")
        return ByteBuffer.allocate(4 + iv.size + plaintext.size + 16)
            .putInt(MAGIC).put(iv).put(cipher.doFinal(plaintext)).array()
    }
    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 4 + 12 + 16 || ByteBuffer.wrap(ciphertext).int != MAGIC) {
            throw IOException("Invalid encrypted settings envelope")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, ciphertext.copyOfRange(4, 16)))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, 16, ciphertext.size - 16)
    }
    private companion object { const val MAGIC = 0x54535331 }
}

private val keyLock = Any()

internal interface SecretKeySource {
    fun load(create: Boolean): SecretKey
    fun delete()
}

/** Serializes key use and recovery across factory instances. A failed read never mutates keys.
 * Only key lookup/init failures justify replacement, not damaged ciphertext or IO failures. */
internal class KeyStoreSecretCipher(associatedData: String, private val keys: SecretKeySource,
    private val allowKeyRecovery: Boolean = false) : SecretCipher {
    private val aes = AesGcmSecretCipher(associatedData, keys::load)
    override fun encrypt(plaintext: ByteArray): ByteArray = synchronized(keyLock) { aes.encrypt(plaintext) }
    override fun decrypt(ciphertext: ByteArray): ByteArray = synchronized(keyLock) { aes.decrypt(ciphertext) }
    override fun encryptForReplacement(plaintext: ByteArray): ByteArray = synchronized(keyLock) {
        try { return@synchronized aes.encrypt(plaintext) }
        catch (failure: Exception) {
            if (!allowKeyRecovery || (failure !is InvalidKeyException && failure !is UnrecoverableKeyException)) {
                throw failure
            }
        }
        // The old key is already unusable. Never retry ordinary writes or rotate a usable key.
        keys.delete()
        try { aes.encrypt(plaintext) }
        catch (failure: Exception) {
            // Key generation may have installed an entry before failing. Leave no half-created
            // replacement where possible; propagate failure so explicit saving can be retried.
            try { keys.delete() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

/** Missing keys may be created only for writes. Recovery is opt-in for provider credentials;
 * plugin settings retain their existing fail-closed behavior. Factory creation performs no IO. */
internal fun androidSecretCipher(alias: String, associatedData: String,
    allowKeyRecovery: Boolean = false): SecretCipher = KeyStoreSecretCipher(associatedData,
    object : SecretKeySource {
        override fun load(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null)
            return if (existing != null) {
                existing as? SecretKey ?: throw UnrecoverableKeyException("Invalid settings key type")
            } else {
                if (!create || store.containsAlias(alias)) throw UnrecoverableKeyException("Settings key unavailable")
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build())
                }.generateKey()
            }
        }
        override fun delete() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }, allowKeyRecovery)
