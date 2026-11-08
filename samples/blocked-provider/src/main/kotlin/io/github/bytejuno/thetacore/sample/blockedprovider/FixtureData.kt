package io.github.bytejuno.thetacore.sample.blockedprovider

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class ReadMode { IMMEDIATE, DELAY, BLOCK, PREFIX_ABORT }

internal enum class FixtureDocument(val key: String, val mode: ReadMode) {
    CONTROL("control", ReadMode.IMMEDIATE),
    DELAYED("delayed", ReadMode.DELAY),
    BLOCKED("blocked", ReadMode.BLOCK),
    PREFIX_CONTROL("prefix-control", ReadMode.IMMEDIATE),
    PREFIX_INTERRUPTED("prefix-interrupted", ReadMode.PREFIX_ABORT);

    val isPrefixFixture get() = this == PREFIX_CONTROL || this == PREFIX_INTERRUPTED
    val pluginId get() = if (isPrefixFixture) PrefixArchive.PLUGIN_ID
        else "io.github.bytejuno.thetacore.fixture.source.$key"
    val displayName get() = "$key-data.zip"
    // Only generated, inert text; no network addresses or executable payloads.
    val bytes: ByteArray by lazy {
        if (isPrefixFixture) return@lazy PrefixArchive.bytes
        val manifest = """{"schema":1,"type":"data","id":"$pluginId","name":"Source fixture $key","version":"1","versionCode":1,"apiLevel":1,"provides":["skills"],"declares":[]}"""
        val entries = linkedMapOf(
            "theta-plugin.json" to manifest,
            "skills/source-marker/SKILL.md" to "# Source fixture $key\n\nInert local import marker: SOURCE_${key.uppercase(java.util.Locale.ROOT)}_V1\n",
        )
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, text) ->
                    val data = text.toByteArray(Charsets.UTF_8)
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                        // Inside DOS date range, no extended timestamp/ZIP64 metadata required.
                        time = 1704067200000L
                    })
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.key == id }
    }
}
