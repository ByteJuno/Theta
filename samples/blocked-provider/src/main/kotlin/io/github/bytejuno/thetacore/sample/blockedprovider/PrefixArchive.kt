package io.github.bytejuno.thetacore.sample.blockedprovider

import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One immutable-by-convention archive shared by the successful and interrupted sources. */
internal object PrefixArchive {
    const val PLUGIN_ID = "io.github.bytejuno.thetacore.fixture.source.prefixcopy"
    const val PADDING_BYTES = 512 * 1024
    val bytes: ByteArray by lazy {
        val manifest = """{"schema":1,"type":"data","id":"$PLUGIN_ID","name":"Source fixture prefix copy","version":"1","versionCode":1,"apiLevel":1,"provides":["skills"],"declares":[]}"""
        // Deterministic printable padding: valid UTF-8 Markdown, no executable payload.
        // STORED ensures archive length > the host's 8192-byte source-copy buffer even
        // if this padding is compressible. Each entry remains below the 1 MiB limit.
        val random = Random(0x534F55524345L)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val padding = ByteArray(PADDING_BYTES) { index ->
            if (index % 80 == 79) '\n'.code.toByte()
            else alphabet[random.nextInt(alphabet.length)].code.toByte()
        }
        val entries = linkedMapOf(
            "theta-plugin.json" to manifest.toByteArray(Charsets.UTF_8),
            "skills/source-marker/SKILL.md" to
                "# Source fixture prefix copy\n\nSOURCE_PREFIX_COPY_V1\n".toByteArray(Charsets.UTF_8),
            "skills/source-padding/SKILL.md" to padding,
        )
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(data) }.value
                        time = 1704067200000L
                    })
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
