package io.github.bytejuno.thetacore.sdk

/**
 * Host-managed encrypted String settings. Key/value, null, size, atomicity and close
 * contracts are identical to [PluginPrefs], including its per-store limits. Persisted
 * size includes encryption overhead. get returns null only for an absent key; failed
 * authentication, missing keys and malformed storage throw, never return plaintext.
 *
 * Ciphertext lives under dataDir/.theta-settings and rolls back with dataDir. The
 * host's plugin-ID-scoped AndroidKeyStore key survives same-ID data rollback. Clear
 * data removes the records, not the key; this is not cryptographic secure erasure.
 * Device transfer/backup without that key cannot decrypt the records. Plugins are
 * full-trust, same-UID native code: this API does not promise secrecy from plugins.
 */
interface PluginSecrets {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun remove(key: String)
}
