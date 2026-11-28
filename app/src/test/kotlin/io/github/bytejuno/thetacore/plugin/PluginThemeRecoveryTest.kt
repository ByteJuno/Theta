package io.github.bytejuno.thetacore.plugin

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.bytejuno.thetacore.ui.theme.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PluginThemeRecoveryTest {
    private fun fixture(aliasedFiles: Boolean = false,
        block: suspend (Context, PluginDataCatalog, ThemePreferenceStore) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("theta-theme-recovery").toFile()
        val alias = root.resolve("system-user-alias").toPath()
        val application = root.resolve("real-app").apply { mkdirs() }
        if (aliasedFiles) Files.createSymbolicLink(alias, application.toPath())
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = (if (aliasedFiles) alias.toFile() else application)
                .resolve("files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = root.resolve("preferences").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        try {
            val catalog = PluginDataCatalog(context.filesDir, 1)
            installPromptBundle(catalog, mapOf("themes/ocean.json" to
                """{"id":"ocean","displayName":"Ocean"}"""))
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            preferences.setTheme("test.prompts/ocean")
            block(context, catalog, preferences)
        } finally {
            Files.deleteIfExists(alias)
            root.deleteRecursively()
        }
    }

    @Test fun aliasedApplicationStorageRestoresThemesAndRecoversPendingRevocations() = fixture(aliasedFiles = true) {
        context, catalog, preferences ->
        // Match the Activity startup construction boundary before any Compose frame.
        PluginManager(context)
        val lifecycle = PluginThemeLifecycle(context)
        assertTrue(lifecycle.restore(catalog, {}, emptySet(), false, false).single().selection!!.active(false))
        assertEquals("test.prompts/ocean", preferences.current().themeKey)

        PluginThemeRevocations(context.filesDir).begin("test.prompts")
        val recreated = PluginThemeLifecycle(context)
        assertTrue(recreated.restore(catalog, {}, emptySet(), false, false).single().selection!!.active(false))
        assertEquals(ThemePreference(dynamic = false), ThemePreferenceStore(context.noBackupFilesDir).current())
        assertTrue(PluginThemeRevocations(context.filesDir.canonicalFile).pending().isEmpty())

        preferences.setTheme("test.prompts/ocean")
        assertTrue(recreated.restore(catalog, {}, emptySet(), true, true).isEmpty())
        assertEquals(ThemePreference(dynamic = false), preferences.current())
        assertTrue(PluginThemeRevocations(context.filesDir).pending().isEmpty())
    }

    @Test fun skippedInventoryReadFailureRetainsRevocationUntilNormalRestore() = failedRecovery(unreadable = true)

    @Test fun skippedInventoryCommitFailureRetainsRevocationUntilNormalRestore() = failedRecovery(unreadable = false)

    private fun failedRecovery(unreadable: Boolean) = fixture { context, catalog, preferences ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val live = ThemePreferenceState(preferences, scope)
            assertEquals("test.prompts/ocean", live.preference.themeKey)
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val original = file.readBytes()
            val failingPreferences = if (unreadable) preferences else ThemePreferenceStore(
                context.noBackupFilesDir, fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun atomicMove(source: Path, target: Path) {
                        throw IOException("Recovery preference commit unavailable")
                    }
                })
            if (unreadable) { check(file.delete()); check(file.mkdir()) }
            try {
                try {
                    PluginThemeLifecycle(context, failingPreferences).restore(catalog, {}, emptySet(),
                        skipPlugins = true, skipInventory = true)
                    fail("Recovery must report the preference IO failure")
                } catch (error: Exception) {
                    if (unreadable) assertTrue(error is IOException)
                    else assertTrue(error is IOException)
                }
                assertEquals(listOf(PluginThemeRevocations.ALL_THEMES), PluginThemeRevocations(context.filesDir).pending())
                assertEquals("test.prompts/ocean", live.preference.themeKey)
                if (!unreadable) assertArrayEquals(original, file.readBytes())
            } finally {
                if (unreadable) { check(file.delete()); file.writeBytes(original) }
            }

            val restored = PluginThemeLifecycle(context).restore(catalog, {}, emptySet(), false, false)
            assertTrue(restored.single().selection!!.active(false))
            val brand = ThemePreference(dynamic = false)
            assertEquals(brand, ThemePreferenceSerializer().readFrom(Buffer().write(file.readBytes())))
            assertEquals(brand, ThemePreferenceStore(context.noBackupFilesDir).current())
            assertEquals(brand, live.preference)
            assertTrue(live.themeUnavailable)
            val recreated = ThemePreferenceState(ThemePreferenceStore(context.noBackupFilesDir), scope)
            assertEquals(brand, recreated.preference)
            assertTrue(PluginThemeRevocations(context.filesDir).pending().isEmpty())

            // Once recovery completed, a new explicit choice must survive another restore.
            preferences.setTheme("test.prompts/ocean")
            PluginThemeLifecycle(context).restore(catalog, {}, emptySet(), false, false)
            assertEquals("test.prompts/ocean", preferences.current().themeKey)
            assertEquals("test.prompts/ocean", live.preference.themeKey)
            assertEquals("test.prompts/ocean", recreated.preference.themeKey)
            assertTrue(PluginThemeRevocations(context.filesDir).pending().isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun recoveryRevokesThemesWithoutReadingBrokenInventory() = fixture { context, catalog, preferences ->
        val ownership = context.filesDir.resolve("plugin-data-catalog")
        check(ownership.renameTo(context.filesDir.resolve("saved-catalog")))
        ownership.writeText("unreadable catalog")
        val records = PluginThemeLifecycle(context).restore(catalog, {}, emptySet(),
            skipPlugins = true, skipInventory = true)
        assertTrue(records.isEmpty())
        assertEquals(ThemePreference(dynamic = false), preferences.current())
        assertTrue(PluginThemeRevocations(context.filesDir).pending().isEmpty())
    }
}
