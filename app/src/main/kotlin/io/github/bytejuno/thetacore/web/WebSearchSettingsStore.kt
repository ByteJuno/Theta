package io.github.bytejuno.thetacore.web

import io.github.bytejuno.thetacore.storage.SecretCipher
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toOkioPath

internal enum class SearchBackend { SEARXNG, BRAVE, TAVILY }
internal class WebSearchSettings(val backend: SearchBackend, val endpoint: String, val key: String) {
    init {
        webUrl(endpoint)
        require(key.length <= 4096 && key.none { it.isISOControl() })
        require(backend == SearchBackend.SEARXNG || key.isNotBlank())
        require(backend != SearchBackend.SEARXNG || key.isEmpty())
        require(backend != SearchBackend.BRAVE || endpoint == BRAVE_ENDPOINT)
        require(backend != SearchBackend.TAVILY || endpoint == TAVILY_ENDPOINT)
    }
    override fun toString() = "WebSearchSettings(redacted)"
    companion object {
        const val BRAVE_ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
        const val TAVILY_ENDPOINT = "https://api.tavily.com/search"
    }
}
internal class WebSettingsRead(val value: WebSearchSettings? = null, val unreadable: Boolean = false)

/** DataStore owns atomic writes and serializes updates. Its cache contains only ciphertext;
 * every read checks the key again. Existing v1 bytes are read in place without migration or reset. */
internal class WebSearchSettingsStore(directory: File, private val cipher: SecretCipher,
    scope: CoroutineScope? = null) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val file = root.resolve("web-search.bin")
    // Production wrappers share one DataStore per file. Tests may own a scope and close it
    // before reopening the same file, exercising an actual cold read instead of the cache.
    private val store: DataStore<WebSettingsRecord> = if (scope != null) createStore(scope) else synchronized(stores) {
        stores.getOrPut(file.toString()) { createStore() }
    }
    private fun createStore(scope: CoroutineScope? = null): DataStore<WebSettingsRecord> {
        val storage = OkioStorage(FileSystem.SYSTEM, WebSettingsSerializer,
            producePath = { file.toFile().toOkioPath() })
        return if (scope == null) DataStoreFactory.create(storage = storage)
        else DataStoreFactory.create(storage = storage, scope = scope)
    }
    private fun checkPath() {
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
    }
    suspend fun read(): WebSettingsRead = try {
        checkPath()
        decode(store.data.first())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { WebSettingsRead(unreadable = true) }

    private fun decode(record: WebSettingsRecord): WebSettingsRead {
        try {
            val encrypted = checkNotNull(record.bytes)
            if (encrypted.size == 0) return WebSettingsRead()
            val raw = cipher.decrypt(encrypted.toByteArray())
            try {
                val obj = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
                check(obj.getValue("version").jsonPrimitive.int == 1)
                return WebSettingsRead(WebSearchSettings(SearchBackend.valueOf(obj.getValue("backend").jsonPrimitive.content),
                    obj.getValue("endpoint").jsonPrimitive.content, obj.getValue("key").jsonPrimitive.content))
            } finally { raw.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return WebSettingsRead(unreadable = true) }
    }
    suspend fun save(value: WebSearchSettings?, replaceUnreadable: Boolean = false) {
        try {
            checkPath()
            store.updateData { current ->
                val loaded = decode(current)
                check(!loaded.unreadable || replaceUnreadable)
                // An empty record survives a cold start and never touches the key on reads or clear.
                if (value == null) return@updateData WebSettingsRecord()
                val raw = buildJsonObject {
                    put("version", 1); put("backend", value.backend.name); put("endpoint", value.endpoint); put("key", value.key)
                }.toString().toByteArray(Charsets.UTF_8)
                val encrypted = try {
                    if (loaded.value == null) cipher.encryptForReplacement(raw) else cipher.encrypt(raw)
                } finally { raw.fill(0) }
                check(encrypted.size <= WEB_SETTINGS_LIMIT)
                WebSettingsRecord(encrypted.toByteString())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw WebFailure(WebError.SETTINGS) }
    }
    override fun toString() = "WebSearchSettingsStore(private)"
    private companion object { val stores = mutableMapOf<String, DataStore<WebSettingsRecord>>() }
}

private const val WEB_SETTINGS_LIMIT = 32 * 1024
/** Null represents an oversized unreadable record, preserved until explicit replacement. */
private data class WebSettingsRecord(val bytes: ByteString? = ByteString.EMPTY)
private object WebSettingsSerializer : OkioSerializer<WebSettingsRecord> {
    private val cleared = "THETA-WEB-CLEARED-1".encodeUtf8()
    override val defaultValue = WebSettingsRecord()
    override suspend fun readFrom(source: BufferedSource): WebSettingsRecord {
        if (source.request(WEB_SETTINGS_LIMIT + 1L)) return WebSettingsRecord(null)
        val bytes = source.readByteString()
        // A truncated zero-byte file is corrupt, not an explicit user clear.
        return when {
            bytes == cleared -> WebSettingsRecord()
            bytes.size == 0 -> WebSettingsRecord(null)
            else -> WebSettingsRecord(bytes)
        }
    }
    override suspend fun writeTo(t: WebSettingsRecord, sink: BufferedSink) {
        val bytes = checkNotNull(t.bytes)
        check(bytes.size <= WEB_SETTINGS_LIMIT)
        sink.write(if (bytes.size == 0) cleared else bytes)
    }
}
