package io.github.bytejuno.thetacore.sample.blockedprovider

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class PrefixArchiveTest {
    @Test fun controlAndInterruptedExposeIdenticalValidStoredZip() {
        val control = FixtureDocument.PREFIX_CONTROL
        val interrupted = FixtureDocument.PREFIX_INTERRUPTED
        assertSame(control.bytes, interrupted.bytes)
        assertArrayEquals(control.bytes, interrupted.bytes)
        assertEquals(PrefixArchive.PLUGIN_ID, control.pluginId)
        assertEquals(control.pluginId, interrupted.pluginId)
        assertFalse(FixtureDocument.entries.filterNot { it.isPrefixFixture }.any { it.pluginId == control.pluginId })
        assertTrue(control.bytes.size > 8192)
        assertTrue(control.bytes.size > 128 * 1024) // also exceeds typical proxy/FUSE callback buffer
        assertTrue(control.bytes.size < 1024 * 1024)
        assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), control.bytes.copyOfRange(0, 4))
        val path = Files.createTempFile("prefix-fixture-", ".zip")
        try {
            Files.write(path, control.bytes)
            // ZipFile requires a real central directory, not just readable local entry headers.
            ZipFile(path.toFile()).use { zip ->
                val contents = linkedMapOf<String, String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    assertEquals(ZipEntry.STORED, entry.method)
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    assertEquals(entry.size, bytes.size.toLong())
                    assertEquals(entry.size, entry.compressedSize)
                    assertEquals(entry.crc, CRC32().apply { update(bytes) }.value)
                    val text = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                    assertNull(contents.put(entry.name, text))
                }
                assertEquals(setOf("theta-plugin.json", "skills/source-marker/SKILL.md",
                    "skills/source-padding/SKILL.md"), contents.keys)
                val manifest = contents.getValue("theta-plugin.json")
                assertTrue(manifest.contains("\"id\":\"${PrefixArchive.PLUGIN_ID}\""))
                assertTrue(manifest.contains("\"type\":\"data\""))
                assertFalse(manifest.contains("\"entry\""))
                assertTrue(contents.getValue("skills/source-marker/SKILL.md").contains("SOURCE_PREFIX_COPY_V1"))
                assertEquals(PrefixArchive.PADDING_BYTES, contents.getValue("skills/source-padding/SKILL.md").length)
            }
        } finally { Files.deleteIfExists(path) }
    }
}
