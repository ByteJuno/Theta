package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.plugin.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import kotlinx.serialization.json.*

internal data class PersonaSelection(val id: String? = null, val unavailable: Boolean = false)
internal data class ChatPromptSnapshot(
    val data: PluginPromptData = PluginPromptData(),
    val persona: DataPersona? = null,
    val unavailable: Boolean = false,
)

/** Non-secret global chat preference. It affects new runs, including new runs in saved sessions. */
internal class PersonaSettings(directory: File) {
    private val root = directory.toPath().toAbsolutePath().normalize()
    private val file = root.resolve("chat-persona.json")
    private val lock = locks[(file.hashCode() and Int.MAX_VALUE) % locks.size]

    fun current(): PersonaSelection = synchronized(lock) { read() }
    fun select(id: String?) = synchronized(lock) {
        if (id != null) skillLocation(id)
        write(PersonaSelection(id))
    }
    fun resolve(data: PluginPromptData): ChatPromptSnapshot = synchronized(lock) {
        val selection = read()
        val persona = data.personas.singleOrNull { it.id == selection.id }
        val unavailable = selection.unavailable || selection.id != null && persona == null
        if (selection.id != null && persona == null) write(PersonaSelection(unavailable = true))
        ChatPromptSnapshot(data, persona, unavailable)
    }
    private fun checkRoot() {
        check(Files.notExists(root, NOFOLLOW_LINKS) || Files.isDirectory(root, NOFOLLOW_LINKS))
        check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
    }
    private fun read(): PersonaSelection {
        checkRoot()
        if (Files.notExists(file, NOFOLLOW_LINKS)) return PersonaSelection()
        check(Files.size(file) <= 4096)
        val data = Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { dataJson(it.readBytes()) }
        val id = data["id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
        if (id != null) skillLocation(id)
        return PersonaSelection(id, data["unavailable"]?.jsonPrimitive?.booleanOrNull ?: false)
    }
    private fun write(value: PersonaSelection) {
        checkRoot()
        Files.createDirectories(root)
        val bytes = buildJsonObject {
            put("id", value.id?.let(::JsonPrimitive) ?: JsonNull)
            put("unavailable", value.unavailable)
        }.toString().toByteArray(Charsets.UTF_8)
        val temp = root.resolve("chat-persona-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    companion object { private val locks = Array(16) { Any() } }
}
