package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** Deliberately bounded S1 import subset, not a general-purpose ZIP implementation. */
internal data class PluginDataLimits(
    val compressedBytes: Int = 16 * 1024 * 1024,
    val expandedBytes: Long = 32L * 1024 * 1024,
    val entryBytes: Int = 1024 * 1024,
    val entries: Int = 512,
    val pathDepth: Int = 4,
) {
    init {
        require(compressedBytes in 1..16 * 1024 * 1024)
        require(expandedBytes in 1..32L * 1024 * 1024)
        require(entryBytes in 1..1024 * 1024)
        require(entries in 1..512 && pathDepth in 1..4)
    }
}

internal data class ValidatedPluginData(
    val manifest: PluginDataManifest,
    val destinations: List<PluginDataDestination>,
)

/**
 * Inspect central AND local headers (java.util.zip does not expose Unix entry modes),
 * then stream every entry to EOF to check actual expanded counts and CRCs.
 * Nothing is extracted; only the original validated ZIP is ever published.
 */
internal fun validatePluginDataZip(
    bytes: ByteArray,
    hostApiLevel: Int,
    limits: PluginDataLimits,
    // Internal host reader only. Results must not escape until the ENTIRE archive validates.
    onContent: (String, ByteArray) -> Unit = { _, _ -> },
): ValidatedPluginData {
    dataRequire(bytes.size <= limits.compressedBytes, "Compressed data ZIP exceeds limit")
    val zip = DataZipHeaders(bytes, limits)
    val records = zip.records()
    var total = 0L
    var manifest: PluginDataManifest? = null
    val destinations = mutableListOf<PluginDataDestination>()
    ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { input ->
        for (record in records) {
            val entry = input.nextEntry ?: throw PluginPackageException("Missing local ZIP entry")
            dataRequire(entry.name == record.name && entry.method == record.method, "ZIP header disagreement")
            val out = ByteArrayOutputStream()
            val crc = CRC32()
            var expanded = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                expanded += read
                total += read
                dataRequire(expanded <= limits.entryBytes && total <= limits.expandedBytes, "Expanded data ZIP exceeds limit")
                crc.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            input.closeEntry() // ZipInputStream verifies local CRC / data descriptor as well.
            dataRequire(expanded == record.size && crc.value == record.crc, "Data ZIP size or CRC mismatch")
            dataRequire(entry.compressedSize == record.compressed, "Compressed ZIP size mismatch")
            if (record.name.endsWith('/')) {
                dataRequire(expanded == 0L, "Directory has data")
            } else if (record.name == "theta-plugin.json") {
                manifest = readPluginDataManifest(out.toByteArray(), hostApiLevel)
            } else {
                readDataDestination(record.name, out.toByteArray())?.let(destinations::add)
            }
            if (!record.name.endsWith('/')) onContent(record.name, out.toByteArray())
        }
        dataRequire(input.nextEntry == null, "Unlisted local ZIP entry")
    }
    return ValidatedPluginData(manifest ?: throw PluginPackageException("Missing root theta-plugin.json"), destinations.toList())
}

private data class DataZipRecord(
    val name: String, val flags: Int, val method: Int,
    val crc: Long, val compressed: Long, val size: Long, val offset: Int,
)

private class DataZipHeaders(private val bytes: ByteArray, private val limits: PluginDataLimits) {
    private fun u16(at: Int): Int {
        dataRequire(at >= 0 && at <= bytes.size - 2, "Truncated ZIP header")
        return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    }
    private fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    private fun name(at: Int, length: Int): String {
        dataRequire(length > 0 && length <= 1024 && at >= 0 && at <= bytes.size - length, "Invalid ZIP name")
        return dataUtf8(bytes.copyOfRange(at, at + length))
    }
    private fun extra(at: Int, length: Int) {
        dataRequire(at >= 0 && at <= bytes.size - length, "Truncated ZIP extra")
        var cursor = at
        while (cursor < at + length) {
            dataRequire(cursor + 4 <= at + length, "Truncated ZIP extra")
            val tag = u16(cursor)
            val size = u16(cursor + 2)
            // Reject ZIP64, Unicode alternate names, Unix link metadata and unknown extras.
            dataRequire(tag == 0x5455 || tag == 0x000a, "Unsupported ZIP extra field")
            cursor += 4 + size
            dataRequire(cursor <= at + length, "Truncated ZIP extra")
        }
    }

    fun records(): List<DataZipRecord> {
        dataRequire(bytes.size >= 22, "Truncated ZIP")
        var end = bytes.size - 22
        val lowest = maxOf(0, end - 65535)
        while (end >= lowest && !(u32(end) == 0x06054b50L && end + 22 + u16(end + 20) == bytes.size)) end--
        dataRequire(end >= lowest, "Missing ZIP end record")
        dataRequire(u16(end + 4) == 0 && u16(end + 6) == 0, "Multi-disk ZIP not supported")
        val count = u16(end + 10)
        dataRequire(count in 1..limits.entries && u16(end + 8) == count, "Invalid ZIP entry count")
        val central = u32(end + 16)
        dataRequire(central <= end && central + u32(end + 12) == end.toLong(), "Invalid ZIP central directory")
        var cursor = central.toInt()
        val records = mutableListOf<DataZipRecord>()
        val explicit = mutableSetOf<String>()
        val spelling = mutableMapOf<String, String>()
        val kinds = mutableMapOf<String, Boolean>()
        repeat(count) {
            dataRequire(cursor <= end - 46 && u32(cursor) == 0x02014b50L, "Invalid central ZIP entry")
            val system = u16(cursor + 4) shr 8
            dataRequire(system == 0 || system == 3, "Unsupported ZIP creator system")
            dataRequire(u16(cursor + 6) <= 20, "Unsupported ZIP version")
            val flags = u16(cursor + 8)
            dataRequire(flags and 0x080e.inv() == 0, "Encrypted or unsupported ZIP flags")
            val method = u16(cursor + 10)
            dataRequire(method == 0 || method == 8, "Unsupported ZIP compression")
            dataRequire(method != 0 || flags and 14 == 0, "Unsupported stored ZIP flags")
            val nameLength = u16(cursor + 28)
            val extraLength = u16(cursor + 30)
            val commentLength = u16(cursor + 32)
            val path = name(cursor + 46, nameLength)
            dataRequire(flags and 0x800 != 0 || path.all { it.code < 128 }, "Non-UTF8 ZIP name")
            val directory = path.endsWith('/')
            val mode = (u32(cursor + 38) shr 16).toInt()
            val kind = mode and 0xf000
            dataRequire(kind == 0 || kind == (if (directory) 0x4000 else 0x8000), "Links and special ZIP entries are forbidden")
            dataRequire(mode and 0x0e00 == 0 && (directory || mode and 0x49 == 0), "Executable or privileged ZIP mode")
            val dos = u32(cursor + 38).toInt() and 255
            dataRequire(dos and 0x48 == 0 && (dos and 0x10 == 0 || directory), "Invalid ZIP attributes")
            dataRequire(u16(cursor + 34) == 0, "Multi-disk ZIP entry")
            checkPath(path, explicit, spelling, kinds)
            extra(cursor + 46 + nameLength, extraLength)
            val offset = u32(cursor + 42)
            dataRequire(offset < central, "Invalid local ZIP offset")
            records += DataZipRecord(path, flags, method, u32(cursor + 16), u32(cursor + 20), u32(cursor + 24), offset.toInt())
            cursor += 46 + nameLength + extraLength + commentLength
            dataRequire(cursor <= end, "Truncated central directory")
        }
        dataRequire(cursor == end, "Unexpected central directory data")
        val sorted = records.sortedBy { it.offset }
        var next = 0
        for (record in sorted) {
            val at = record.offset
            dataRequire(at == next && u32(at) == 0x04034b50L, "Overlapping ZIP entries or hidden prefix")
            dataRequire(u16(at + 4) <= 20 && u16(at + 6) == record.flags && u16(at + 8) == record.method, "Local ZIP header mismatch")
            val length = u16(at + 26)
            val extraLength = u16(at + 28)
            dataRequire(name(at + 30, length) == record.name, "Local ZIP name mismatch")
            extra(at + 30 + length, extraLength)
            val dataEnd = at.toLong() + 30 + length + extraLength + record.compressed
            dataRequire(dataEnd <= central, "Truncated compressed entry")
            next = dataEnd.toInt()
            if (record.flags and 8 != 0) {
                // Signed 32-bit descriptors only; reject ambiguous/ZIP64 descriptor forms.
                dataRequire(next.toLong() + 16 <= central && u32(next) == 0x08074b50L, "Unsupported ZIP descriptor")
                dataRequire(u32(next + 4) == record.crc && u32(next + 8) == record.compressed && u32(next + 12) == record.size,
                    "ZIP descriptor mismatch")
                next += 16
            } else {
                dataRequire(u32(at + 14) == record.crc && u32(at + 18) == record.compressed && u32(at + 22) == record.size,
                    "Local ZIP sizes or CRC mismatch")
            }
        }
        dataRequire(next.toLong() == central, "Hidden ZIP data or APK signing block")
        return sorted
    }

    private fun checkPath(path: String, explicit: MutableSet<String>, spelling: MutableMap<String, String>, kinds: MutableMap<String, Boolean>) {
        val directory = path.endsWith('/')
        val clean = if (directory) path.dropLast(1) else path
        val parts = clean.split('/')
        dataRequire(parts.size <= limits.pathDepth && clean.length <= 512 && parts.all { part ->
            part.isNotEmpty() && part != "." && part != ".." && part.length <= 128 &&
                part.none { it < ' ' || it == '\u007f' || it in "\\:*?\"<>|" } &&
                !part.endsWith('.') && !part.endsWith(' ') &&
                !Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(part)
        }, "Unsafe ZIP path")
        dataRequire(Normalizer.isNormalized(clean, Normalizer.Form.NFC), "ZIP path must be NFC")
        val canonical = clean.lowercase(Locale.ROOT)
        dataRequire(explicit.add(canonical), "Duplicate ZIP path")
        parts.indices.forEach { index ->
            val actual = parts.take(index + 1).joinToString("/")
            val key = actual.lowercase(Locale.ROOT)
            dataRequire(spelling.putIfAbsent(key, actual)?.let { it == actual } != false, "ZIP filename normalization collision")
            val isDirectory = index < parts.lastIndex || directory
            dataRequire(kinds.putIfAbsent(key, isDirectory)?.let { it == isDirectory } != false, "ZIP file-directory conflict")
        }
        val allowed = if (directory) {
            (parts.size == 1 && parts[0] in setOf("skills", "personas", "lang", "providers", "mcp")) ||
                (parts.size == 2 && parts[0] == "skills")
        } else {
            clean == "theta-plugin.json" ||
                (parts.size == 3 && parts[0] == "skills" && parts[2] == "SKILL.md") ||
                (parts.size == 2 && parts[0] in setOf("personas", "lang", "providers", "mcp") &&
                    parts[1].endsWith(".json") && parts[1].length > 5)
        }
        dataRequire(allowed, "Unsupported data ZIP payload: $path")
    }
}
