package io.github.bytejuno.thetacore.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Host-only seam shared by plugin settings and provider credentials. */
internal interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
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

/** Missing keys may be created only for writes, never when decrypting existing data. */
internal fun androidSecretCipher(alias: String, associatedData: String): SecretCipher =
    AesGcmSecretCipher(associatedData) { create ->
        synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(alias, null)
            if (existing != null) {
                existing as? SecretKey ?: throw IOException("Invalid settings key type")
            } else {
                if (!create || store.containsAlias(alias)) throw IOException("Settings key unavailable")
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
    }
