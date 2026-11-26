package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.plugin.*
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath

internal data class PersonaSelection(val id: String? = null, val unavailable: Boolean = false,
    val recovered: Boolean = false)
internal data class ChatPromptSnapshot(
    val data: PluginPromptData = PluginPromptData(),
    val persona: DataPersona? = null,
    val unavailable: Boolean = false,
    val recovered: Boolean = false,
)

/** DataStore owns serialization of updates, fsync and atomic replacement. Application supplies
 * noBackupFilesDir. The existing bounded JSON file is read in place; no lossy migration. */
internal class PersonaSettings(directory: File) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val file = root.resolve("chat-persona.json")
    private val store: DataStore<PersonaSelection>

    init {
        checkRoot()
        // One DataStore per file for this process, even when a settings wrapper is recreated.
        store = synchronized(stores) {
            stores.getOrPut(file.toString()) {
                DataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, PersonaOkioSerializer,
                    producePath = { file.toFile().toOkioPath() }),
                    corruptionHandler = ReplaceFileCorruptionHandler { PersonaSelection(recovered = true) },
                )
            }
        }
    }

    suspend fun current(): PersonaSelection { checkRoot(); return store.data.first() }
    suspend fun select(id: String?) {
        if (id != null) skillLocation(id)
        checkRoot()
        store.updateData { PersonaSelection(id) }
    }
    suspend fun resolve(data: PluginPromptData): ChatPromptSnapshot {
        checkRoot()
        val selection = store.updateData { current ->
            if (current.id != null && data.personas.none { it.id == current.id })
                PersonaSelection(unavailable = true, recovered = current.recovered)
            else current
        }
        return ChatPromptSnapshot(data, data.personas.singleOrNull { it.id == selection.id },
            selection.unavailable, selection.recovered)
    }
    private fun checkRoot() {
        check(Files.notExists(root, NOFOLLOW_LINKS) || Files.isDirectory(root, NOFOLLOW_LINKS))
        check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
    }
    companion object { private val stores = mutableMapOf<String, DataStore<PersonaSelection>>() }
}

private object PersonaOkioSerializer : OkioSerializer<PersonaSelection> {
    override val defaultValue = PersonaSelection()
    override suspend fun readFrom(source: BufferedSource) = PersonaSerializer.readFrom(source.inputStream())
    override suspend fun writeTo(t: PersonaSelection, sink: BufferedSink) = PersonaSerializer.writeTo(t, sink.outputStream())
}

internal object PersonaSerializer : Serializer<PersonaSelection> {
    override val defaultValue = PersonaSelection()
    override suspend fun readFrom(input: InputStream): PersonaSelection {
        // IO failures propagate and never reset a possibly valid preference.
        val bytes = input.readNBytes(4097)
        try {
            require(bytes.size <= 4096)
            val data = dataJson(bytes)
            val id = data["id"]?.takeIf { it != JsonNull }?.let {
                require(it is JsonPrimitive && it.isString)
                it.content.also { value -> skillLocation(value) }
            }
            fun flag(key: String): Boolean = data[key]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
            return PersonaSelection(id, flag("unavailable"), flag("recovered"))
        } catch (error: Exception) {
            throw CorruptionException("Invalid persona preference", error)
        }
    }
    override suspend fun writeTo(t: PersonaSelection, output: OutputStream) {
        t.id?.let { skillLocation(it) }
        val bytes = buildJsonObject {
            put("id", t.id?.let(::JsonPrimitive) ?: JsonNull)
            put("unavailable", t.unavailable)
            put("recovered", t.recovered)
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096)
        output.write(bytes)
    }
}
