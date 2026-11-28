package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginThemeRevocationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "sample.theme"

    private fun withAlias(block: (File, File) -> Unit) {
        val real = temporary.newFolder("real")
        val files = real.resolve("app/files").apply { mkdirs() }
        val alias = temporary.root.resolve("system-user-alias").toPath()
        Files.createSymbolicLink(alias, real.toPath())
        try { block(alias.resolve("app/files").toFile(), files) }
        finally { Files.delete(alias) }
    }

    @Test fun trustedApplicationAncestorAliasSharesDurableRevocations() = withAlias { alias, files ->
        val journal = PluginThemeRevocations(alias)
        assertTrue(journal.pending().isEmpty())
        journal.begin(id)
        assertTrue(files.resolve("plugin-theme-revocations/$id.properties").isFile)
        assertEquals(listOf(id), PluginThemeRevocations(files).pending())
        PluginThemeRevocations(alias).finish(id)
        assertTrue(journal.pending().isEmpty())
        journal.beginAll()
        assertEquals(listOf(PluginThemeRevocations.ALL_THEMES), PluginThemeRevocations(files).pending())
        PluginThemeRevocations(alias).finish(PluginThemeRevocations.ALL_THEMES)
        assertTrue(PluginThemeRevocations(files).pending().isEmpty())
    }

    @Test fun linkedJournalBelowTrustedAliasIsRejected() = withAlias { alias, files ->
        val outside = temporary.newFolder("outside")
        val retained = outside.resolve("keep").apply { writeText("retained") }
        val link = files.resolve("plugin-theme-revocations").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        try {
            assertThrows(IOException::class.java) { PluginThemeRevocations(alias).begin(id) }
            assertThrows(IOException::class.java) { PluginThemeRevocations(alias).pending() }
            assertEquals("retained", retained.readText())
            assertEquals(listOf("keep"), outside.list()!!.toList())
        } finally { Files.delete(link) }
    }

    @Test fun journalDirectoryIsRecheckedAfterConstruction() {
        val files = temporary.newFolder("files")
        val journal = PluginThemeRevocations(files)
        val outside = temporary.newFolder("outside")
        outside.resolve("$id.properties").writeText("plugin=$id\n")
        val link = files.resolve("plugin-theme-revocations").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        try {
            assertThrows(IOException::class.java) { journal.pending() }
            assertThrows(IOException::class.java) { journal.beginAll() }
            assertThrows(IOException::class.java) { journal.finish(id) }
            assertEquals("plugin=$id\n", outside.resolve("$id.properties").readText())
            assertEquals(listOf("$id.properties"), outside.list()!!.toList())
        } finally { Files.delete(link) }
    }

    @Test fun linkedIntentIsNeverReadOverwrittenOrDeleted() = withAlias { alias, files ->
        val journal = PluginThemeRevocations(alias)
        journal.begin(id)
        val outside = temporary.newFile("outside.properties").apply { writeText("plugin=$id\n") }
        val intent = files.resolve("plugin-theme-revocations/$id.properties").toPath()
        Files.delete(intent)
        Files.createSymbolicLink(intent, outside.toPath())
        try {
            assertThrows(IOException::class.java) { journal.pending() }
            assertThrows(IOException::class.java) { journal.begin(id) }
            assertThrows(IOException::class.java) { journal.finish(id) }
            assertTrue(Files.isSymbolicLink(intent))
            assertEquals("plugin=$id\n", outside.readText())
        } finally { Files.delete(intent) }
    }
}
