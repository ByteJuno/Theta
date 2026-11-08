package io.github.bytejuno.thetacore.sample.blockedprovider

import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class FixtureDataTest {
    @Test fun generatedArchivesHaveOnlyManifestAndInertText() {
        FixtureDocument.entries.filterNot { it.isPrefixFixture }.forEach { document ->
            assertTrue(document.bytes.size < 4096)
            val contents = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(document.bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    assertEquals(ZipEntry.STORED, entry.method)
                    val bytes = zip.readBytes()
                    assertEquals(entry.size, bytes.size.toLong())
                    assertEquals(entry.crc, CRC32().apply { update(bytes) }.value)
                    assertNull(contents.put(entry.name, bytes.toString(Charsets.UTF_8)))
                    zip.closeEntry()
                }
            }
            assertEquals(setOf("theta-plugin.json", "skills/source-marker/SKILL.md"), contents.keys)
            val manifest = contents.getValue("theta-plugin.json")
            assertTrue(manifest.contains("\"type\":\"data\""))
            assertTrue(manifest.contains("\"id\":\"${document.pluginId}\""))
            assertTrue(manifest.contains("\"versionCode\":1"))
            assertFalse(manifest.contains("entry"))
            assertTrue(contents.getValue("skills/source-marker/SKILL.md").contains(
                "SOURCE_${document.key.uppercase(java.util.Locale.ROOT)}_V1"))
        }
    }

    @Test fun documentsHaveStableIdentitiesWithOneSharedControlPair() {
        assertEquals(5, FixtureDocument.entries.map { it.key }.toSet().size)
        assertEquals(4, FixtureDocument.entries.map { it.pluginId }.toSet().size)
        FixtureDocument.entries.forEach { assertSame(it, FixtureDocument.fromId(it.key)) }
        assertNull(FixtureDocument.fromId("../control"))
    }
}
