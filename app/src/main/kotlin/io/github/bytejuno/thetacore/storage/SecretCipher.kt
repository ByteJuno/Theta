package io.github.bytejuno.thetacore.storage

import android.security.keystore.BackendBusyException
import android.security.keystore.KeyExpiredException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyNotYetValidException
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.security.keystore.UserPresenceUnavailableException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Collections
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Explicit marker for a definitively unusable key alias/material; never wraps transient errors. */
internal class UnusableSecretKeyException(message: String, cause: Throwable? = null) :
    UnrecoverableKeyException(message) {
    init { if (cause != null) initCause(cause) }
}

internal enum class KeyFailureKind {
    DEFINITIVE_INVALIDATION,
    NON_RECOVERABLE,
    UNKNOWN,
}

internal fun classifyKeyStoreError(
    numericErrorCode: Int,
    transientFailure: Boolean,
    requiresUserAuthentication: Boolean,
    systemError: Boolean,
): KeyFailureKind {
    if (transientFailure || requiresUserAuthentication || systemError) {
        return KeyFailureKind.NON_RECOVERABLE
    }
    return when (numericErrorCode) {
        android.security.KeyStoreException.ERROR_KEY_CORRUPTED,
        android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST,
        -> KeyFailureKind.DEFINITIVE_INVALIDATION
        else -> KeyFailureKind.NON_RECOVERABLE
    }
}

internal fun classifyAndroidKeyFailure(error: Throwable): KeyFailureKind = when (error) {
    is IOException,
    is UserNotAuthenticatedException,
    is UserPresenceUnavailableException,
    is KeyNotYetValidException,
    is KeyExpiredException,
    is BackendBusyException,
    is StrongBoxUnavailableException,
    -> KeyFailureKind.NON_RECOVERABLE
    is android.security.KeyStoreException -> classifyKeyStoreError(
        numericErrorCode = error.numericErrorCode,
        transientFailure = error.isTransientFailure,
        requiresUserAuthentication = error.requiresUserAuthentication(),
        systemError = error.isSystemError,
    )
    is UnusableSecretKeyException,
    is KeyPermanentlyInvalidatedException,
    -> KeyFailureKind.DEFINITIVE_INVALIDATION
    else -> KeyFailureKind.UNKNOWN
}

/** Fail-closed cause-chain classification: any transient/auth/system veto blocks key deletion,
 * and unknown wrappers/exceptions never authorize deletion on their own. */
internal fun isDefinitiveKeyInvalidation(
    failure: Throwable,
    classifyNode: (Throwable) -> KeyFailureKind = ::classifyAndroidKeyFailure,
): Boolean {
    var definitive = false
    val causeVisited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    while (current != null && causeVisited.add(current)) {
        when (classifyNode(current)) {
            KeyFailureKind.NON_RECOVERABLE -> return false
            KeyFailureKind.DEFINITIVE_INVALIDATION -> definitive = true
            KeyFailureKind.UNKNOWN -> Unit
        }
        if (hasNonRecoverable(current.suppressed, classifyNode)) return false
        current = current.cause
    }
    return definitive
}

private fun hasNonRecoverable(
    roots: Array<Throwable>,
    classifyNode: (Throwable) -> KeyFailureKind,
): Boolean {
    if (roots.isEmpty()) return false
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val stack = ArrayDeque<Throwable>()
    roots.forEach { stack.addLast(it) }
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (!visited.add(node)) continue
        if (classifyNode(node) == KeyFailureKind.NON_RECOVERABLE) return true
        node.cause?.let { stack.addLast(it) }
        node.suppressed.forEach { stack.addLast(it) }
    }
    return false
}

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
 * Only definitive key invalidation justifies replacement; transient, auth, and unknown failures
 * preserve the existing key and encrypted record. */
internal class KeyStoreSecretCipher internal constructor(
    private val aes: SecretCipher,
    private val keys: SecretKeySource,
    private val allowKeyRecovery: Boolean = false,
    private val classifyNode: (Throwable) -> KeyFailureKind = ::classifyAndroidKeyFailure,
) : SecretCipher {
    constructor(
        associatedData: String,
        keys: SecretKeySource,
        allowKeyRecovery: Boolean = false,
        classifyNode: (Throwable) -> KeyFailureKind = ::classifyAndroidKeyFailure,
    ) : this(AesGcmSecretCipher(associatedData, keys::load), keys, allowKeyRecovery, classifyNode)

    override fun encrypt(plaintext: ByteArray): ByteArray = synchronized(keyLock) { aes.encrypt(plaintext) }
    override fun decrypt(ciphertext: ByteArray): ByteArray = synchronized(keyLock) { aes.decrypt(ciphertext) }
    override fun encryptForReplacement(plaintext: ByteArray): ByteArray = synchronized(keyLock) {
        try { return@synchronized aes.encrypt(plaintext) }
        catch (failure: Exception) {
            if (!allowKeyRecovery || !isDefinitiveKeyInvalidation(failure, classifyNode)) {
                throw failure
            }
        }
        // The old key is definitively unusable. Never retry ordinary writes or rotate a usable key.
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
                val secret = existing as? SecretKey
                    ?: throw UnusableSecretKeyException("Invalid settings key type")
                if (!secret.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
                    throw UnusableSecretKeyException("Incompatible settings key algorithm")
                }
                secret
            } else {
                if (store.containsAlias(alias)) throw UnusableSecretKeyException("Settings key unavailable")
                if (!create) throw UnrecoverableKeyException("Settings key missing")
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
