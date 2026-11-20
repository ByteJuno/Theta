package io.github.bytejuno.thetacore.chat

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import io.github.bytejuno.thetacore.agent.provider.openai.ImageLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

internal fun decodeImage(bytes: ByteArray): Bitmap {
    require(bytes.size in 1..ImageLimits.BYTES)
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(ImageLimits.validDimensions(info.size.width, info.size.height))
        require(info.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setOnPartialImageListener { false }
    }
}

internal fun normalizeImage(bytes: ByteArray): ByteArray {
    val bitmap = decodeImage(bytes)
    try {
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                require(count <= ImageLimits.BYTES - length)
                super.write(bytes, offset, length)
            }
            override fun write(value: Int) { require(count < ImageLimits.BYTES); super.write(value) }
        }
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return output.toByteArray().also { require(it.size in 1..ImageLimits.BYTES) }
    } finally { bitmap.recycle() }
}

internal class ImageAttachments(directory: File) {
    private val root = directory.toPath().toAbsolutePath().normalize().resolve("chat-images-v1")
    private val lock = locks[(root.toString().hashCode() and Int.MAX_VALUE) % locks.size]
    private fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun session(id: String): java.nio.file.Path {
        require(id.isNotBlank())
        check(Files.notExists(root, NOFOLLOW_LINKS) || Files.isDirectory(root, NOFOLLOW_LINKS))
        val path = root.resolve(key(id))
        check(Files.notExists(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS))
        return path
    }
    private fun write(path: java.nio.file.Path, bytes: ByteArray) {
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    fun claim(sessionId: String, callId: String): Boolean = synchronized(lock) {
        val folder = session(sessionId)
        Files.createDirectories(folder)
        check(Files.notExists(folder.resolve("deleted"), NOFOLLOW_LINKS))
        val marker = folder.resolve(key(callId) + ".started")
        if (Files.exists(marker, NOFOLLOW_LINKS)) return@synchronized false
        write(marker, byteArrayOf(1))
        true
    }
    fun save(sessionId: String, callId: String, bytes: ByteArray) = synchronized(lock) {
        require(bytes.size in 1..ImageLimits.BYTES)
        val folder = session(sessionId)
        check(Files.isRegularFile(folder.resolve(key(callId) + ".started"), NOFOLLOW_LINKS))
        check(Files.notExists(folder.resolve("deleted"), NOFOLLOW_LINKS))
        val temp = folder.resolve(key(callId) + ".tmp")
        write(temp, bytes)
        Files.move(temp, folder.resolve(key(callId) + ".png"), ATOMIC_MOVE)
    }
    fun read(sessionId: String, callId: String): ByteArray? = synchronized(lock) {
        val folder = session(sessionId)
        if (Files.exists(folder.resolve("deleted"), NOFOLLOW_LINKS)) return@synchronized null
        val path = folder.resolve(key(callId) + ".png")
        if (Files.notExists(path, NOFOLLOW_LINKS)) return@synchronized null
        check(Files.isRegularFile(path, NOFOLLOW_LINKS))
        require(Files.size(path) in 1..ImageLimits.BYTES.toLong())
        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() <= ImageLimits.BYTES - count)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    fun deleteSession(sessionId: String) = synchronized(lock) {
        val folder = session(sessionId)
        if (Files.notExists(folder, NOFOLLOW_LINKS)) return@synchronized
        val tombstone = folder.resolve("deleted")
        if (Files.notExists(tombstone, NOFOLLOW_LINKS)) write(tombstone, byteArrayOf(1))
        Files.newDirectoryStream(folder).use { entries ->
            entries.filter { it.fileName.toString() != "deleted" }.forEach { entry ->
                check(Files.isRegularFile(entry, NOFOLLOW_LINKS))
                Files.delete(entry)
            }
        }
    }
    fun prepareDelete(sessionId: String) = synchronized(lock) {
        val folder = session(sessionId)
        if (Files.notExists(folder, NOFOLLOW_LINKS)) return@synchronized
        val marker = folder.resolve("deleting")
        if (Files.notExists(marker, NOFOLLOW_LINKS)) write(marker, byteArrayOf(1))
    }
    fun removeDeletedSessions(sessionIds: Set<String>) = synchronized(lock) {
        if (Files.notExists(root, NOFOLLOW_LINKS)) return@synchronized
        check(Files.isDirectory(root, NOFOLLOW_LINKS))
        val retained = sessionIds.map(::key).toSet()
        Files.newDirectoryStream(root).use { folders ->
            for (folder in folders) {
                check(folder.fileName.toString().matches(Regex("[0-9a-f]{64}")))
                check(Files.isDirectory(folder, NOFOLLOW_LINKS))
                if (folder.fileName.toString() in retained) continue
                if (Files.notExists(folder.resolve("deleting"), NOFOLLOW_LINKS) &&
                    Files.notExists(folder.resolve("deleted"), NOFOLLOW_LINKS)) continue
                Files.newDirectoryStream(folder).use { files ->
                    for (file in files) {
                        check(Files.isRegularFile(file, NOFOLLOW_LINKS))
                        Files.delete(file)
                    }
                }
                Files.delete(folder)
            }
        }
    }
    companion object { private val locks = Array(64) { Any() } }
}

