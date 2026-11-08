package io.github.bytejuno.thetacore.sdk

/**
 * Host-managed String settings, restored together with dataDir on rollback.
 *
 * Keys are nonempty, well-formed Unicode strings (not paths), at most [MAX_KEY_BYTES]
 * UTF-8 bytes. Values are well-formed Unicode, at most [MAX_VALUE_BYTES] UTF-8 bytes;
 * an empty value is valid. Invalid input throws IllegalArgumentException. Each store
 * holds at most [MAX_ENTRIES] entries and [MAX_STORED_BYTES] persisted bytes including
 * framing. Exceeding a store limit or unreadable/corrupt storage throws IOException,
 * not an absent value. Failed writes preserve the previous committed file.
 *
 * Operations are synchronous; use the plugin's IO scope for disk work. Each operation
 * is atomic, not a get/put transaction. After the host closes the owner, all operations
 * throw [PluginClosedException]. dataDir/.theta-settings is reserved for the host.
 */
interface PluginPrefs {
    /** Returns null only when the key is absent. */
    fun get(key: String): String?

    /** Null means remove; null is never stored. */
    fun put(key: String, value: String?)

    /** Removing an absent key succeeds without changing the store. */
    fun remove(key: String)

    companion object {
        const val MAX_KEY_BYTES = 256
        const val MAX_VALUE_BYTES = 64 * 1024
        const val MAX_ENTRIES = 256
        const val MAX_STORED_BYTES = 1024 * 1024
    }
}
