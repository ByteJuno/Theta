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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import kotlinx.serialization.json.*
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath

internal enum class ThemeMode { SYSTEM, LIGHT, DARK }
internal data class ThemePreference(val mode: ThemeMode = ThemeMode.SYSTEM, val dynamic: Boolean = true) {
    fun isDark(systemDark: Boolean) = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

/** Same commit ordering as ChatApprovalSettingsStore: serialize, fsync a temporary file,
 * atomically replace, then let the caller publish the durable preference. No speculative cache. */
internal class ThemePreferenceStore(directory: File, private val legacyDynamic: Boolean = true,
    private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val file = root.resolve(FILE_NAME)
    private val lock = synchronized(locks) { locks.getOrPut(file.toString()) { Mutex() } }
    private val serializer = ThemePreferenceSerializer()

    suspend fun current(): ThemePreference = lock.withLock { read() }
    suspend fun setMode(mode: ThemeMode) { lock.withLock { persist(read().copy(mode = mode)) } }
    suspend fun setDynamic(dynamic: Boolean) { lock.withLock { persist(read().copy(dynamic = dynamic)) } }

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
    }
    companion object {
        internal const val FILE_NAME = "theme-preferences.json"
        private val locks = mutableMapOf<String, Mutex>()
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
            require(value.keys == setOf("mode", "dynamic"))
            val mode = value.getValue("mode").jsonPrimitive
            val dynamic = value.getValue("dynamic").jsonPrimitive
            require(mode.isString && !dynamic.isString)
            return ThemePreference(ThemeMode.valueOf(mode.content), requireNotNull(dynamic.booleanOrNull))
        } catch (error: Exception) {
            throw CorruptionException("Invalid theme preference", error)
        }
    }
    override suspend fun writeTo(t: ThemePreference, sink: BufferedSink) {
        sink.writeUtf8(buildJsonObject { put("mode", t.mode.name); put("dynamic", t.dynamic) }.toString())
    }
}
