package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.ui.theme.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginThemesTest {
    private val minimal = """{"id":"ocean","displayName":"Ocean 海洋"}"""
    private fun withCatalog(block: (java.io.File, PluginDataCatalog) -> Unit) {
        val root = Files.createTempDirectory("theta-themes").toFile()
        try { block(root, PluginDataCatalog(root, 1)) } finally { root.deleteRecursively() }
    }

    @Test fun legalThemeUsesRealArchiveAndAppliesBothModesWithoutChangingSafetyColors() = withCatalog { _, catalog ->
        val selection = installPromptBundle(catalog, mapOf("themes/ocean.json" to """{
            "id":"ocean","displayName":"Ocean 海洋","useDynamicColor":false,"cornerScale":1.5,"messageStyle":"bubble",
            "light":{"background":"#FFFFFF","surface":"#FFFFFF","userBubbleContainer":"#EEEEFF","onUserBubble":"#151550",
                "assistantText":"#101030","capsuleContainer":"#EEEEFF","onCapsule":"#202040"},
            "dark":{"assistantText":"#FFFFFF","capsuleContainer":"#202030","onCapsule":"#FFFFFF"}}
        """))
        val result = catalog.readThemes(selection)
        assertEquals(emptyList<String>(), result.errors)
        val theme = result.themes.single()
        assertEquals("test.prompts/ocean", theme.key)
        assertEquals(MessageStyle.BUBBLE, theme.messageStyle)
        for (dark in listOf(false, true)) {
            val base = thetaBrandScheme(dark)
            val resolved = requireNotNull(resolvePluginTheme(theme, base, dark))
            assertEquals(base.error, resolved.colors.error)
            assertEquals(base.onError, resolved.colors.onError)
            assertEquals(base.errorContainer, resolved.colors.errorContainer)
            assertEquals(base.onErrorContainer, resolved.colors.onErrorContainer)
            assertEquals(1.5f, resolved.tokens.cornerScale)
            assertEquals(MessageStyle.BUBBLE, resolved.tokens.messageStyle)
            assertEquals(brandColor(if (dark) "#FFFFFF" else "#101030"), resolved.tokens.assistantText)
        }
    }

    @Test fun invalidThemesAreIndividuallyIsolatedWhileOtherThemesAndSkillsSurvive() = withCatalog { _, catalog ->
        val badFields = listOf(
            "\"light\":{\"primary\":\"red\"}", "\"light\":{\"primary\":\"#AAFF0000\"}",
            "\"cornerScale\":0.49", "\"cornerScale\":1.51", "\"cornerScale\":\"1\"", "\"cornerScale\":1e200",
            "\"messageStyle\":\"html\"", "\"useDynamicColor\":\"true\"", "\"light\":[]",
            "\"light\":{\"onSurface\":\"#FFFFFF\"}", "\"dark\":{\"onUserBubble\":\"#23488C\"}",
            "\"light\":{\"error\":\"#000000\"}", "\"dark\":{\"errorContainer\":\"#000000\"}",
            "\"light\":{\"fullAutoRisk\":\"#000000\"}", "\"approvalDanger\":\"#000000\"",
            "\"light\":{\"error\":\"#FFFFFF\",\"onError\":\"#FFFFFF\"}",
            "\"displayName\":\"\\uD800\"",
            "\"light\":{\"primary\":\"#315DCE\",\"primary\":\"#000000\"}",
        )
        val entries = badFields.mapIndexed { index, fields -> "themes/bad$index.json" to
            """{"id":"bad$index","displayName":"Invalid",$fields}""" }.toMap() + mapOf(
            "themes/ocean.json" to minimal, "skills/read/SKILL.md" to "# Read\n\nRead the document.",
            "themes/mismatch.json" to minimal, "themes/large.json" to " ".repeat(16 * 1024 + 1),
            "themes/malformed.json" to "{not-json", "themes/binary.json" to "\u0000")
        val selection = installPromptBundle(catalog, entries)
        val result = catalog.readThemes(selection)
        assertEquals(badFields.size + 4, result.errors.size)
        assertEquals(listOf("ocean"), result.themes.map { it.id })
        assertEquals(1, catalog.promptData(selection).skills.size)
        assertEquals("\uFFFD", catalog.content(selection, "themes/binary.json").text)
    }

    @Test fun dynamicPaletteIsRevalidatedAndContrastMatchesWcag() = withCatalog { _, catalog ->
        val theme = catalog.readThemes(installPromptBundle(catalog, mapOf("themes/ocean.json" to minimal))).themes.single()
        assertEquals(21.0, themeContrast(brandColor("#000000"), brandColor("#FFFFFF")), 0.0001)
        assertNull(resolvePluginTheme(theme, thetaBrandScheme(false).copy(onSurface = brandColor("#FFFFFF")), false))
        for (style in MessageStyle.entries) assertEquals(style,
            resolvePluginTheme(theme.copy(messageStyle = style), thetaBrandScheme(false), false)!!.tokens.messageStyle)
    }

    @Test fun disableUninstallQuarantineAndReplacementRevokeExactSelectionAndRestoreBrandPreference() = withCatalog { root, catalog ->
        runBlocking {
            val selected = installPromptBundle(catalog, mapOf("themes/ocean.json" to minimal))
            val themes = catalog.readThemes(selected).themes
            val store = ThemePreferenceStore(root)
            store.setMode(ThemeMode.DARK); store.setTheme(themes.single().key)
            assertEquals(themes.single().key, ThemePreferenceStore(root).current().themeKey)
            val disabled = catalog.setEnabled(selected.manifest.id, false, selected)!!
            assertTrue(activeThemeChoices(themes, listOf(disabled).filter { it.active(false) }).isEmpty())
            assertThrows(PluginPackageException::class.java) { catalog.readThemes(selected) }
            store.clearUnavailableTheme(themes.single().key)
            assertEquals(ThemePreference(ThemeMode.DARK, false), store.current())
            catalog.setEnabled(selected.manifest.id, true)
            val updated = installPromptBundle(catalog, mapOf("themes/ocean.json" to minimal), version = 2)
            assertTrue(activeThemeChoices(themes, listOf(updated)).isEmpty())
            val fresh = catalog.readThemes(updated).themes
            assertEquals(1, activeThemeChoices(fresh, listOf(updated)).size)
            val quarantine = catalog.managementRecord(selected.manifest.id, crashQuarantined = true)
            assertNull(quarantine.selection)
            assertTrue(activeThemeChoices(fresh, listOfNotNull(quarantine.selection)).isEmpty())
            catalog.setEnabled(selected.manifest.id, true)
            catalog.beginDelete(selected.manifest.id)
            assertThrows(PluginPackageException::class.java) { catalog.readThemes(updated) }
            catalog.finishDelete(selected.manifest.id)
            assertTrue(catalog.restore {}.isEmpty())
            // A delayed fallback must not overwrite a more recent user choice.
            store.setTheme("other.plugin/new")
            store.clearUnavailableTheme(themes.single().key)
            assertEquals("other.plugin/new", store.current().themeKey)
            store.setDynamic(true)
            assertEquals(ThemePreference(ThemeMode.DARK, true), store.current())
        }
    }

    @Test fun tamperedArchiveFailsClosedAndSafeModeExposesNoThemes() = withCatalog { root, catalog ->
        val selected = installPromptBundle(catalog, mapOf("themes/ocean.json" to minimal))
        val themes = catalog.readThemes(selected).themes
        assertTrue(activeThemeChoices(themes, listOf(selected).filter { it.active(true) }).isEmpty())
        root.resolve("plugins/${selected.manifest.id}/1/plugin.zip").writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(PluginPackageException::class.java) { catalog.readThemes(selected) }
        assertNull(catalog.managementRecord(selected.manifest.id).selection)
    }
}
