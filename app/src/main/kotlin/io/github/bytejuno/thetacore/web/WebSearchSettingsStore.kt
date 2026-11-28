package io.github.bytejuno.thetacore.web

import io.github.bytejuno.thetacore.storage.SecretCipher
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID

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

/** Encrypted no-backup record; corrupt reads preserve bytes and disable search until explicit replacement. */
internal class WebSearchSettingsStore(directory: File, private val cipher: SecretCipher) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val file = root.resolve("web-search.bin")
    private val lock = locks[(file.toString().hashCode() and Int.MAX_VALUE) % locks.size]
    fun read(): WebSettingsRead = synchronized(lock) {
        try {
            check(Files.isDirectory(root, NOFOLLOW_LINKS))
            if (Files.notExists(file, NOFOLLOW_LINKS)) return@synchronized WebSettingsRead()
            check(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 32 * 1024)
            val raw = cipher.decrypt(Files.readAllBytes(file))
            try {
                val obj = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
                check(obj.getValue("version").jsonPrimitive.int == 1)
                WebSettingsRead(WebSearchSettings(SearchBackend.valueOf(obj.getValue("backend").jsonPrimitive.content),
                    obj.getValue("endpoint").jsonPrimitive.content, obj.getValue("key").jsonPrimitive.content))
            } finally { raw.fill(0) }
        } catch (_: Exception) { WebSettingsRead(unreadable = true) }
    }
    fun save(value: WebSearchSettings?, replaceUnreadable: Boolean = false) = synchronized(lock) {
        try {
            check(Files.isDirectory(root, NOFOLLOW_LINKS))
            check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
            val unreadable = read().unreadable
            check(!unreadable || replaceUnreadable)
            if (value == null) { Files.deleteIfExists(file); return@synchronized }
            val raw = buildJsonObject {
                put("version", 1); put("backend", value.backend.name); put("endpoint", value.endpoint); put("key", value.key)
            }.toString().toByteArray(Charsets.UTF_8)
            val encrypted = try {
                if (unreadable && replaceUnreadable) cipher.encryptForReplacement(raw) else cipher.encrypt(raw)
            } finally { raw.fill(0) }
            check(encrypted.size <= 32 * 1024)
            val temp = root.resolve("web-search-${UUID.randomUUID()}.tmp")
            try {
                FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(encrypted)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        } catch (_: Exception) { throw WebFailure(WebError.SETTINGS) }
    }
    override fun toString() = "WebSearchSettingsStore(private)"
    private companion object { val locks = Array(32) { Any() } }
}
