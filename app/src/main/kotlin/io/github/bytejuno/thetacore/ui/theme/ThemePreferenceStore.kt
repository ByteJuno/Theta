package io.github.bytejuno.thetacore.ui.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import kotlinx.serialization.json.*
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath

internal enum class ThemeMode { SYSTEM, LIGHT, DARK }
internal data class ThemePreference(val mode: ThemeMode = ThemeMode.SYSTEM, val dynamic: Boolean = true, val themeKey: String? = null) {
    fun isDark(systemDark: Boolean) = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/** Same commit ordering as ChatApprovalSettingsStore: serialize, fsync a temporary file,
 * atomically replace, then publish the durable preference. No speculative cache.
 * DataStore 1.1.7 with OkioStorage publishes its cache before atomicMove completes:
 * a failed move leaves its observed value ahead of disk, violating rollback on failure. */
internal class ThemePreferenceStore(directory: File, private val legacyDynamic: Boolean = true,
    private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val file = root.resolve(FILE_NAME)
    private val shared = synchronized(states) { states.getOrPut(file.toString()) { SharedState() } }
    private val lock = shared.lock
    private val serializer = ThemePreferenceSerializer()
    val updates = shared.committed.filterNotNull()

    suspend fun current(): ThemePreference = lock.withLock { read().also { shared.committed.value = it } }
    suspend fun setMode(mode: ThemeMode) { lock.withLock { persist(read().copy(mode = mode)) } }
    suspend fun setDynamic(dynamic: Boolean) { lock.withLock { persist(read().copy(dynamic = dynamic, themeKey = null)) } }
    suspend fun setTheme(key: String) { lock.withLock { persist(read().copy(themeKey = key, dynamic = false)) } }
    suspend fun clearUnavailableTheme(expected: String) { lock.withLock {
        val current = read()
        if (current.themeKey == expected) persist(current.copy(themeKey = null, dynamic = false))
    } }

    private fun checkPaths() {
        check(Files.notExists(root, NOFOLLOW_LINKS) || Files.isDirectory(root, NOFOLLOW_LINKS))
        check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
    }
    private suspend fun read(): ThemePreference {
        checkPaths()
        if (Files.notExists(file, NOFOLLOW_LINKS)) return ThemePreference(dynamic = legacyDynamic)
        return try {
            val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(4097) }
            serializer.readFrom(Buffer().write(bytes))
        } catch (_: CorruptionException) {
            ThemePreference().also { persist(it) }
        }
    }
    private suspend fun persist(preference: ThemePreference) {
        checkPaths()
        Files.createDirectories(root)
        val buffer = Buffer()
        serializer.writeTo(preference, buffer)
        val bytes = buffer.readByteArray()
        val temp = root.resolve("$FILE_NAME-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp, WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
                val data = ByteBuffer.wrap(bytes)
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            fileSystem.atomicMove(temp.toFile().toOkioPath(), file.toFile().toOkioPath())
        } finally { Files.deleteIfExists(temp) }
        shared.committed.value = preference
    }
    private class SharedState {
        val lock = Mutex()
        val committed = MutableStateFlow<ThemePreference?>(null)
    }
    companion object {
        internal const val FILE_NAME = "theme-preferences.json"
        private val states = mutableMapOf<String, SharedState>()
    }
}

internal class ThemePreferenceSerializer(legacyDynamic: Boolean = true) : OkioSerializer<ThemePreference> {
    override val defaultValue = ThemePreference(dynamic = legacyDynamic)
    override suspend fun readFrom(source: BufferedSource): ThemePreference {
        // IO errors propagate; only invalid bytes are treated as corruption.
        val bytes = source.inputStream().readNBytes(4097)
        try {
            require(bytes.size <= 4096)
            val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(value.keys == setOf("mode", "dynamic") || value.keys == setOf("mode", "dynamic", "themeKey"))
            val mode = value.getValue("mode").jsonPrimitive
            val dynamic = value.getValue("dynamic").jsonPrimitive
            require(mode.isString && !dynamic.isString)
            val themeKey = value["themeKey"]?.let {
                if (it == JsonNull) null else it.jsonPrimitive.also { key -> require(key.isString) }.content.also { key ->
                    require(key.length <= 265 && Regex("[a-zA-Z0-9_.-]+/[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(key))
                }
            }
            return ThemePreference(ThemeMode.valueOf(mode.content), requireNotNull(dynamic.booleanOrNull), themeKey)
        } catch (error: Exception) {
            throw CorruptionException("Invalid theme preference", error)
        }
    }
    override suspend fun writeTo(t: ThemePreference, sink: BufferedSink) {
        sink.writeUtf8(buildJsonObject { put("mode", t.mode.name); put("dynamic", t.dynamic); put("themeKey", t.themeKey) }.toString())
    }
}
