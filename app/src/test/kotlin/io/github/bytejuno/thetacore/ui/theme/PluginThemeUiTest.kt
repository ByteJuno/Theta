package io.github.bytejuno.thetacore.ui.theme

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.settings.AppearanceSettingsScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import android.net.Uri
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Files

/** Real Compose layout/semantics and persistence, not a device pixel-rendering claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en-w360dp-h640dp-mdpi")
class PluginThemeUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun screen(fontScale: Float = 1f, dark: Boolean = false, skipPlugins: Boolean = false,
        beforeThemeCommit: (Context) -> Unit = {}, block: Harness.() -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val root = Files.createTempDirectory("theta-theme-ui").toFile()
        try {
            val activity = controller.get()
            // Each case owns its real preference file as well as plugin storage; otherwise
            // the process-wide committed StateFlow can retain a previous sandbox's store.
            val preferenceContext = object : ContextWrapper(activity) {
                override fun getNoBackupFilesDir(): File = root.resolve("preferences").apply { mkdirs() }
                override fun getApplicationContext(): Context = this
            }
            val context = object : ContextWrapper(activity.applicationContext) {
                override fun getFilesDir(): File = root.resolve("files").apply { mkdirs() }
                override fun getCacheDir(): File = root.resolve("cache").apply { mkdirs() }
                override fun getNoBackupFilesDir(): File = preferenceContext.noBackupFilesDir
                override fun getApplicationContext(): Context = this
            }
            val catalog = PluginDataCatalog(context.filesDir, 1)
            installPromptBundle(catalog, mapOf("themes/ocean.json" to """{"id":"ocean","displayName":"Ocean 海洋",
                "light":{"assistantText":"#101030"},"dark":{"assistantText":"#FFFFFF"},"cornerScale":1.5,"messageStyle":"compact"}"""))
            val fileSystem = object : okio.ForwardingFileSystem(okio.FileSystem.SYSTEM) {
                override fun atomicMove(source: okio.Path, target: okio.Path) {
                    beforeThemeCommit(context)
                    super.atomicMove(source, target)
                }
            }
            val manager = PluginManager(context, PluginThemeLifecycle(context,
                ThemePreferenceStore(context.noBackupFilesDir, fileSystem = fileSystem)))
            val harness = Harness(manager, context)
            val view = ComposeView(activity)
            activity.setContentView(view)
            view.setContent {
                LaunchedEffect(manager) {
                    assertTrue(manager.activeThemes().isEmpty())
                    assertFalse(manager.dataThemesReady)
                    manager.restore(skipPlugins = skipPlugins)
                }
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale),
                    androidx.compose.ui.platform.LocalContext provides preferenceContext) {
                    key(harness.appearanceGeneration) { ThetaTheme(systemDark = dark, manager = manager) {
                        harness.state = LocalThemePreferenceState.current
                        harness.choices = LocalThemeChoices.current
                        harness.colors = MaterialTheme.colorScheme
                        harness.tokens = LocalThetaThemeTokens.current
                        harness.scope = rememberCoroutineScope()
                        AppearanceSettingsScreen {}
                    } }
                }
            }
            harness.await { !harness.state.loading && !harness.choices.loading && harness.choices.themes.size == if (skipPlugins) 0 else 1 }
            harness.block()
        } finally { controller.pause().stop().destroy(); root.deleteRecursively() }
    }

    private inner class Harness(val manager: PluginManager, val context: Context) {
        val files get() = context.filesDir
        var appearanceGeneration by mutableIntStateOf(0)
        lateinit var state: ThemePreferenceState
        lateinit var choices: ThemeChoices
        lateinit var colors: ColorScheme
        lateinit var tokens: ThetaThemeTokens
        lateinit var scope: CoroutineScope
        fun await(condition: () -> Boolean) {
            try {
                rule.waitUntil(10_000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    rule.mainClock.advanceTimeByFrame()
                    condition()
                }
                rule.waitForIdle()
            } catch (error: ComposeTimeoutException) {
                throw AssertionError("state=${if (::state.isInitialized) state.preference else null}; " +
                    "saving=${if (::state.isInitialized) state.saving else null}; failed=${if (::state.isInitialized) state.failed else null}; " +
                    "choices=${if (::choices.isInitialized) choices else null}; pages=${manager.dataPlugins.map { it.active }}; " +
                    "failures=${manager.failures}; ready=${manager.dataThemesReady}", error)
            }
        }
        fun select() {
            rule.onNodeWithText("Ocean 海洋", substring = true).performScrollTo().performClick()
            await { !state.saving && state.preference.themeKey == "test.prompts/ocean" }
        }
        suspend fun installVersion(version: Int, theme: Boolean = true, id: String = "test.prompts",
            themeIds: List<String> = if (theme) listOf("ocean") else emptyList()) {
            val archive = context.cacheDir.resolve("$id-$version.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                val entries = linkedMapOf("theta-plugin.json" to
                    """{"schema":1,"type":"data","id":"$id","name":"Themes","version":"$version","versionCode":$version,"apiLevel":1}""")
                themeIds.forEach { themeId ->
                    val name = if (themeId == "ocean") "Ocean 海洋" else "Forest 森林"
                    entries["themes/$themeId.json"] = """{"id":"$themeId","displayName":"$name","messageStyle":"compact"}"""
                }
                if (themeIds.isEmpty()) entries["skills/read/SKILL.md"] = "# Read"
                entries.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            val session = PluginImportSession()
            try {
                val staged = requireNotNull(manager.stageData(Uri.fromFile(archive), session))
                manager.installData(session, staged, staged.sha256, true)
            } finally { manager.discardImport(session) }
        }
        suspend fun assertDiskBrand() {
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            assertEquals(ThemePreference(dynamic = false), ThemePreferenceSerializer().readFrom(okio.Buffer().write(file.readBytes())))
        }
        fun assertLiveAndRecreatedBrand() {
            await { !state.loading && !state.saving && !choices.loading }
            assertEquals(ThemePreference(dynamic = false), state.preference)
            assertEquals(thetaBrandScheme(false).primary, colors.primary)
            assertEquals(MessageStyle.EDITORIAL, tokens.messageStyle)
            val previous = state
            rule.runOnIdle { appearanceGeneration++ }
            await { state !== previous && !state.loading && !choices.loading }
            assertEquals(ThemePreference(dynamic = false), state.preference)
            assertEquals(thetaBrandScheme(false).primary, colors.primary)
            assertEquals(MessageStyle.EDITORIAL, tokens.messageStyle)
        }
        fun revoke(action: suspend () -> Unit) {
            lateinit var work: Deferred<Unit>
            rule.runOnIdle { work = scope.async { action() } }
            // Preference revocation now precedes the package mutation; observe its actual
            // completion before asserting inventory, and propagate any operation failure.
            await { work.isCompleted && !state.saving && !choices.loading && state.preference.themeKey == null }
            runBlocking { work.await() }
        }
        fun beforeNextFrame(action: suspend () -> Unit) {
            rule.mainClock.autoAdvance = false
            val frameTime = rule.mainClock.currentTime
            lateinit var work: Deferred<Unit>
            try {
                rule.runOnIdle { work = scope.async { action() } }
                rule.waitUntil(10_000) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    work.isCompleted
                }
                runBlocking { work.await() }
                assertEquals("Lifecycle operations must finish without a Compose frame", frameTime, rule.mainClock.currentTime)
            } finally { rule.mainClock.autoAdvance = true }
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        @Volatile var paused = false
        private val waiting = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            synchronized(waiting) {
                if (paused) { waiting.add(block); return }
            }
            block.run()
        }
        fun resume() {
            synchronized(waiting) { paused = false }
            while (true) (waiting.poll() ?: break).run()
        }
    }

    @Test fun delayedActivityFallbackPreservesNewChoiceOfReintroducedTheme() {
        val failCommit = java.util.concurrent.atomic.AtomicBoolean(false)
        screen(beforeThemeCommit = { if (failCommit.get()) throw java.io.IOException("Theme preference replacement unavailable") }) {
            select()
            beforeNextFrame {
                val dispatcher = PausedDispatcher()
                val oldScope = CoroutineScope(SupervisorJob() + dispatcher)
                try {
                    val oldState = ThemePreferenceState(ThemePreferenceStore(context.noBackupFilesDir), oldScope, manager)
                    oldState.load()
                    withContext(Dispatchers.Default) { withTimeout(5000) { while (oldState.loading) delay(1) } }
                    assertEquals("test.prompts/ocean", oldState.preference.themeKey)
                    failCommit.set(true)
                    try { installVersion(2, theme = false); fail("Expected failed fallback write") }
                    catch (_: java.io.IOException) { }
                    assertTrue(manager.activeThemes().isEmpty())
                    assertEquals("test.prompts/ocean", oldState.preference.themeKey)
                    // This is the same callback ThetaTheme schedules when the key is absent.
                    // Hold an older Activity's coroutine before it reaches the IO dispatcher.
                    dispatcher.paused = true
                    oldState.clearUnavailableTheme("test.prompts/ocean")
                    assertTrue(oldState.saving)
                    failCommit.set(false)
                    installVersion(3)
                    manager.selectTheme("test.prompts/ocean")
                    val before = ThemePreferenceStore(context.noBackupFilesDir).current().themeKey
                    assertEquals("test.prompts/ocean", before)
                    assertTrue(PluginThemeRevocations(files).pending().isEmpty())
                    dispatcher.resume()
                    withContext(Dispatchers.Default) { withTimeout(5000) { while (oldState.saving) delay(1) } }
                    val after = ThemePreferenceStore(context.noBackupFilesDir).current().themeKey
                    assertEquals("Delayed fallback must preserve the later successful explicit selection", before, after)
                    PluginManager(context).restore()
                    assertEquals(before, ThemePreferenceStore(context.noBackupFilesDir).current().themeKey)
                    val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
                    assertEquals(before, ThemePreferenceSerializer().readFrom(okio.Buffer().write(file.readBytes())).themeKey)
                } finally { dispatcher.resume(); oldScope.cancel() }
            }
        }
    }

    @Test fun invalidPreferencePathReportsStorageFailureAndAllowsRestoreRetry() = screen {
        select()
        beforeNextFrame {
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val original = file.readBytes()
            val restored = PluginManager(context)
            check(file.delete()); check(file.mkdir())
            try {
                try { restored.restore(); fail("Invalid preference path must fail restore") }
                catch (_: java.io.IOException) { } // Handled by MainActivity's startup boundary.
                assertFalse(restored.dataThemesReady)
                assertTrue(restored.dataPlugins.isEmpty())
            } finally { check(file.delete()); file.writeBytes(original) }
            restored.restore()
            assertTrue(restored.dataThemesReady)
            assertEquals(listOf("test.prompts/ocean"), restored.activeThemes().map { it.key })
            assertEquals("test.prompts/ocean", ThemePreferenceStore(context.noBackupFilesDir).current().themeKey)
        }
    }

    @Test fun disableAndReenableBeforeNextFrameClearsSelectionAndRestoresDurableBrand() = screen {
        select()
        beforeNextFrame {
            manager.setDataEnabled("test.prompts", false)
            manager.setDataEnabled("test.prompts", true)
            assertEquals("Revocation must be durable before another frame or Activity",
                ThemePreference(dynamic = false), ThemePreferenceStore(context.noBackupFilesDir).current())
            val restored = PluginManager(context)
            restored.restore()
            assertEquals(1, restored.activeThemes().size)
        }
        await { !state.saving && !choices.loading && choices.themes.size == 1 }
        assertNull(state.preference.themeKey)
        assertFalse(state.preference.dynamic)
        assertTrue(state.themeUnavailable)
        assertEquals(thetaBrandScheme(false).primary, colors.primary)
        assertEquals(MessageStyle.EDITORIAL, tokens.messageStyle)
        val previous = state
        rule.runOnIdle { appearanceGeneration++ }
        await { state !== previous && !state.loading && !choices.loading }
        assertEquals(ThemePreference(dynamic = false), state.preference)
        assertEquals(thetaBrandScheme(false).primary, colors.primary)
        assertEquals(MessageStyle.EDITORIAL, tokens.messageStyle)
    }

    @Test fun updateRemovingSelectedThemeCannotReselectItBeforeNextFrame() = screen {
        select()
        beforeNextFrame {
            installVersion(2, theme = false)
            assertTrue(manager.activeThemes().isEmpty())
            assertDiskBrand()
            installVersion(3)
            assertDiskBrand()
            assertEquals(1, manager.activeThemes().size)
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun replacementAfterFailedQuarantineRevocationCannotReselectBeforeNextFrame() = screen {
        select()
        beforeNextFrame {
            val page = manager.dataPlugins.single()
            val archive = files.resolve("plugins/test.prompts/1/plugin.zip")
            val originalArchive = archive.readBytes()
            archive.writeBytes(byteArrayOf(1, 2, 3))
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val bytes = file.readBytes()
            check(file.delete()); check(file.mkdir())
            try {
                try { manager.readDataPage(page, null); fail("Quarantine must report failed revocation") }
                catch (_: java.io.IOException) { }
                assertTrue(manager.dataPlugins.isEmpty())
            } finally { check(file.delete()); file.writeBytes(bytes); archive.writeBytes(originalArchive) }
            installVersion(2)
            assertDiskBrand()
            assertEquals(1, manager.activeThemes().size)
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun failedQuarantineRevocationSurvivesColdRestoreEvenWhenArchiveRecovers() = screen {
        select()
        beforeNextFrame {
            val page = manager.dataPlugins.single()
            val archive = files.resolve("plugins/test.prompts/1/plugin.zip")
            val original = archive.readBytes()
            archive.writeBytes(byteArrayOf(1, 2, 3))
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val preference = file.readBytes()
            check(file.delete()); check(file.mkdir())
            try {
                try { manager.readDataPage(page, null); fail("Quarantine must report failed revocation") }
                catch (_: java.io.IOException) { }
                assertTrue(manager.dataPlugins.isEmpty())
                assertEquals(listOf("test.prompts"), PluginThemeRevocations(files).pending())
                val blocked = PluginManager(context)
                try { blocked.restore(); fail("Cold admission must await pending revocation") }
                catch (_: java.io.IOException) { }
                assertFalse(blocked.dataThemesReady)
                assertTrue(blocked.dataPlugins.isEmpty())
            } finally { check(file.delete()); file.writeBytes(preference); archive.writeBytes(original) }
            val restored = PluginManager(context)
            restored.restore()
            assertTrue(restored.dataThemesReady)
            assertEquals(1, restored.activeThemes().size)
            assertDiskBrand()
            assertTrue(PluginThemeRevocations(files).pending().isEmpty())
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun coldInvalidArchiveRecordsRevocationBeforePreferenceCommit() =
        coldInventoryFailureRecovers(corruptRecord = false, unreadablePreference = false)

    @Test fun coldInvalidArchiveRecordsRevocationBeforePreferenceRead() =
        coldInventoryFailureRecovers(corruptRecord = false, unreadablePreference = true)

    @Test fun coldInvalidInstallRecordRetainsIdentityBeforePreferenceCommit() =
        coldInventoryFailureRecovers(corruptRecord = true, unreadablePreference = false)

    private fun coldInventoryFailureRecovers(corruptRecord: Boolean, unreadablePreference: Boolean) = screen {
        select()
        beforeNextFrame {
            val damaged = if (corruptRecord) files.resolve("plugin-data-catalog/test.prompts/selection.properties")
                else files.resolve("plugins/test.prompts/1/plugin.zip")
            val original = damaged.readBytes()
            val preferenceFile = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val originalPreference = preferenceFile.readBytes()
            val revocations = PluginThemeRevocations(files)
            val failingFileSystem = object : okio.ForwardingFileSystem(okio.FileSystem.SYSTEM) {
                override fun atomicMove(source: okio.Path, target: okio.Path) {
                    throw java.io.IOException("Cold theme preference commit unavailable")
                }
            }
            val blocked = PluginManager(context, PluginThemeLifecycle(context,
                ThemePreferenceStore(context.noBackupFilesDir, fileSystem = failingFileSystem)))
            damaged.writeText("broken")
            if (unreadablePreference) { check(preferenceFile.delete()); check(preferenceFile.mkdir()) }
            try {
                try { blocked.restore(); fail("Cold admission must await durable theme revocation") }
                catch (error: Exception) {
                    if (unreadablePreference) assertTrue(error is java.io.IOException)
                    else assertTrue(error is java.io.IOException)
                }
                assertFalse(blocked.dataThemesReady)
                assertTrue(blocked.dataPlugins.isEmpty())
                assertEquals("Isolation identity must survive failed preference IO",
                    listOf("test.prompts"), revocations.pending())
                if (!unreadablePreference) assertArrayEquals(originalPreference, preferenceFile.readBytes())
            } finally {
                damaged.writeBytes(original)
                if (unreadablePreference) { check(preferenceFile.delete()); preferenceFile.writeBytes(originalPreference) }
            }
            val restored = PluginManager(context)
            restored.restore()
            assertTrue(restored.dataThemesReady)
            assertEquals(1, restored.activeThemes().size)
            assertDiskBrand()
            assertTrue("Committed recovery must remove its intent", revocations.pending().isEmpty())
            PluginManager(context).restore()
            assertDiskBrand()
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun coldIsolationRetainsOtherPluginChoiceAndDoesNotRevokeFreshReplacementChoice() = screen {
        select()
        beforeNextFrame {
            installVersion(1, id = "other.plugin")
            val archive = files.resolve("plugins/test.prompts/1/plugin.zip")
            val original = archive.readBytes()
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val preference = file.readBytes()
            archive.writeText("broken")
            check(file.delete()); check(file.mkdir())
            try {
                val blocked = PluginManager(context)
                try { blocked.restore(); fail("Cold admission must await preference recovery") }
                catch (_: java.io.IOException) { }
                assertFalse(blocked.dataThemesReady)
                assertTrue(blocked.dataPlugins.isEmpty())
                assertEquals(listOf("test.prompts"), PluginThemeRevocations(files).pending())
            } finally { check(file.delete()); file.writeBytes(preference); archive.writeBytes(original) }
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            preferences.setTheme("other.plugin/ocean")
            PluginManager(context).restore()
            assertEquals("other.plugin/ocean", preferences.current().themeKey)
            assertEquals("other.plugin/ocean", ThemePreferenceSerializer().readFrom(okio.Buffer().write(file.readBytes())).themeKey)
            assertTrue(PluginThemeRevocations(files).pending().isEmpty())
        }
        await { !choices.loading && state.preference.themeKey == "other.plugin/ocean" }
        assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
        val previous = state
        rule.runOnIdle { appearanceGeneration++ }
        await { state !== previous && !state.loading && !choices.loading }
        assertEquals("other.plugin/ocean", state.preference.themeKey)
        beforeNextFrame {
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            preferences.setTheme("test.prompts/ocean")
            PluginManager(context).restore()
            assertEquals("test.prompts/ocean", preferences.current().themeKey)
            assertTrue(PluginThemeRevocations(files).pending().isEmpty())
        }
        await { state.preference.themeKey == "test.prompts/ocean" }
        assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
    }

    @Test fun digestMismatchedOldArchiveIsIsolatedBeforeConfirmedReplacement() = screen {
        select()
        beforeNextFrame {
            val archive = files.resolve("plugins/test.prompts/1/plugin.zip")
            // Keep a fully valid ZIP and manifest so staging reaches the old-selection digest
            // check. Truncated archives are correctly rejected by namespace validation first.
            ZipOutputStream(archive.outputStream()).use { zip ->
                mapOf("theta-plugin.json" to """{"schema":1,"type":"data","id":"test.prompts","name":"Changed","version":"1","versionCode":1,"apiLevel":1}""",
                    "themes/ocean.json" to """{"id":"ocean","displayName":"Changed","messageStyle":"bubble"}""")
                    .forEach { (path, text) ->
                        zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                    }
            }
            assertNotNull(PluginDataCatalog(files, 1).managementRecord("test.prompts").error)
            installVersion(2)
            assertEquals(2L, manager.dataPlugins.single().selection.state.version)
            assertEquals(2L, PluginDataCatalog(files, 1).managementRecord("test.prompts").state.version)
            assertEquals(1, manager.activeThemes().size)
            assertDiskBrand()
            PluginManager(context).restore()
            assertDiskBrand()
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun lifecycleRestorePreservesLoadedLegacyBrandChoiceWithoutANewPreferenceFile() = screen {
        val legacy = context.getSharedPreferences(APPEARANCE_STORE, Context.MODE_PRIVATE)
        assertTrue(legacy.edit().putBoolean(DYNAMIC_KEY, false).commit())
        try {
            beforeNextFrame {
                val preferences = ThemePreferenceStore(context.noBackupFilesDir, legacyDynamic = false)
                assertFalse(preferences.current().dynamic)
                assertFalse(context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME).exists())
                PluginManager(context).restore()
                assertFalse(preferences.updates.first().preference.dynamic)
                assertFalse(context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME).exists())
            }
            await { !state.preference.dynamic }
            assertLiveAndRecreatedBrand()
            beforeNextFrame {
                themePreferenceStore(context).setMode(ThemeMode.DARK)
                assertEquals(ThemePreference(ThemeMode.DARK, false),
                    ThemePreferenceSerializer().readFrom(okio.Buffer().write(context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME).readBytes())))
                assertEquals(ThemePreference(ThemeMode.DARK, false), themePreferenceStore(context).current())
            }
        } finally { assertTrue(legacy.edit().clear().commit()) }
    }

    @Test fun pendingRevocationPreservesAnotherPluginAndFinishesBeforeChoosingReplacementTheme() = screen {
        select()
        beforeNextFrame {
            installVersion(1, id = "other.plugin")
            val archive = files.resolve("plugins/test.prompts/1/plugin.zip")
            val original = archive.readBytes()
            val page = manager.dataPlugins.single { it.selection.manifest.id == "test.prompts" }
            archive.writeBytes(byteArrayOf(1, 2, 3))
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val bytes = file.readBytes()
            check(file.delete()); check(file.mkdir())
            try {
                try { manager.readDataPage(page, null); fail("Expected failed preference write") }
                catch (_: java.io.IOException) { }
            } finally { check(file.delete()); file.writeBytes(bytes); archive.writeBytes(original) }
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            preferences.setTheme("other.plugin/ocean")
            installVersion(2)
            assertTrue(PluginThemeRevocations(files).pending().isEmpty())
            assertEquals("other.plugin/ocean", preferences.current().themeKey)
            PluginManager(context).restore()
            assertEquals("other.plugin/ocean", preferences.current().themeKey)
            // Once a retry completes the pending intent, a fresh choice of the replacement
            // must survive a later restart rather than being mistaken for the revoked one.
            preferences.setTheme("test.prompts/ocean")
            PluginManager(context).restore()
            assertEquals("test.prompts/ocean", preferences.current().themeKey)
            assertEquals("test.prompts/ocean", ThemePreferenceSerializer().readFrom(okio.Buffer().write(file.readBytes())).themeKey)
        }
        await { !choices.loading && state.preference.themeKey == "test.prompts/ocean" }
        assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
    }

    @Test fun committedInstallWithFailedThemeWriteBlocksReplacementAndColdRestoreClearsExactMissingKey() = screen(
        beforeThemeCommit = { context ->
            if (PluginDataCatalog(context.filesDir, 1).managementRecord("test.prompts").state.version == 2L)
                throw java.io.IOException("Theme preference replacement unavailable after install commit")
        }
    ) {
        select()
        beforeNextFrame {
            try { installVersion(2, theme = false); fail("Installed update must report failed preference commit") }
            catch (_: java.io.IOException) { }
            assertEquals(2L, manager.dataPlugins.single().selection.state.version)
            assertEquals(2L, PluginDataCatalog(files, 1).managementRecord("test.prompts").state.version)
            assertTrue(manager.activeThemes().isEmpty())
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            assertEquals("test.prompts/ocean", preferences.current().themeKey)
            try { installVersion(3); fail("Replacement must finish pending revocation first") }
            catch (_: java.io.IOException) { }
            assertEquals(2L, PluginDataCatalog(files, 1).managementRecord("test.prompts").state.version)
            assertEquals(2L, manager.dataPlugins.single().selection.state.version)
            val restored = PluginManager(context)
            restored.restore()
            assertTrue(restored.activeThemes().isEmpty())
            assertDiskBrand()
            installVersion(3)
            assertEquals(1, manager.activeThemes().size)
            assertDiskBrand()
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun newThemeChoiceAfterFailedUpdateRevocationSurvivesColdRestore() {
        val failCommit = java.util.concurrent.atomic.AtomicBoolean(false)
        screen(beforeThemeCommit = { if (failCommit.get()) throw java.io.IOException("Preference commit unavailable") }) {
            select()
            beforeNextFrame {
                installVersion(2, themeIds = listOf("ocean", "forest"))
                failCommit.set(true)
                try { installVersion(3, themeIds = listOf("forest")); fail("Expected failed revocation commit") }
                catch (_: java.io.IOException) { }
                assertEquals(listOf("test.prompts"), PluginThemeRevocations(files).pending())
                assertEquals("test.prompts/ocean", ThemePreferenceStore(context.noBackupFilesDir).current().themeKey)
                state.setTheme("test.prompts/forest")
                withContext(Dispatchers.Default) { withTimeout(5000) { while (state.saving) delay(1) } }
                assertTrue("A new selection must report unfinished recovery", state.failed)
                assertEquals("test.prompts/ocean", ThemePreferenceStore(context.noBackupFilesDir).current().themeKey)
                assertEquals(listOf("test.prompts"), PluginThemeRevocations(files).pending())
                failCommit.set(false)
                // The same explicit selection entry used by the appearance screen, without
                // giving its automatic fallback effect a frame to clear the stale selection.
                state.setTheme("test.prompts/forest")
                withContext(Dispatchers.Default) { withTimeout(5000) { while (state.saving) delay(1) } }
                assertFalse(state.failed)
                assertTrue("A successful selection must finish older intents", PluginThemeRevocations(files).pending().isEmpty())
                val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
                assertEquals("test.prompts/forest", ThemePreferenceSerializer().readFrom(okio.Buffer().write(file.readBytes())).themeKey)
                val restored = PluginManager(context)
                restored.restore()
                assertEquals(listOf("test.prompts/forest"), restored.activeThemes().map { it.key })
                assertEquals("A completed new choice must outlive the old revocation",
                    "test.prompts/forest", ThemePreferenceStore(context.noBackupFilesDir).current().themeKey)
                assertEquals("test.prompts/forest", ThemePreferenceSerializer().readFrom(okio.Buffer().write(file.readBytes())).themeKey)
                assertTrue(PluginThemeRevocations(files).pending().isEmpty())
            }
            await { !choices.loading && state.preference.themeKey == "test.prompts/forest" }
            assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
            val previous = state
            rule.runOnIdle { appearanceGeneration++ }
            await { state !== previous && !state.loading && !choices.loading }
            assertEquals("test.prompts/forest", state.preference.themeKey)
            assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
        }
    }

    @Test fun staleThemeChoiceCannotReenterAfterDisableOrSafeModeRestore() = screen {
        select()
        beforeNextFrame {
            manager.setDataEnabled("test.prompts", false)
            state.setTheme("test.prompts/ocean")
            withContext(Dispatchers.Default) { withTimeout(5000) { while (state.saving) delay(1) } }
            assertTrue(state.failed)
            assertDiskBrand()
            manager.setDataEnabled("test.prompts", true)
            // Selection must not initialize a fresh manager ahead of startup's safe-mode flag.
            val restored = PluginManager(context)
            try { restored.selectTheme("test.prompts/ocean"); fail("Must await startup restore") }
            catch (error: PluginPackageException) { assertEquals(PluginFailureReason.DATA_RECOVERY, error.reason) }
            assertFalse(restored.dataThemesReady)
            restored.restore(skipPlugins = true)
            try { restored.selectTheme("test.prompts/ocean"); fail("Safe-mode themes are unavailable") }
            catch (error: PluginPackageException) { assertEquals(PluginFailureReason.DATA_PAGE_REVOKED, error.reason) }
            assertDiskBrand()
        }
        assertLiveAndRecreatedBrand()
    }

    @Test fun updatesKeepExistingThemeAndNeverRevokeAnotherPluginsNewSelection() = screen {
        select()
        beforeNextFrame {
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            installVersion(2)
            assertEquals("test.prompts/ocean", preferences.current().themeKey)
            installVersion(1, id = "other.plugin")
            preferences.setTheme("other.plugin/ocean")
            installVersion(3, theme = false)
            assertEquals("other.plugin/ocean", preferences.current().themeKey)
            PluginManager(context).restore()
            assertEquals("other.plugin/ocean", preferences.current().themeKey)
        }
        await { !choices.loading && state.preference.themeKey == "other.plugin/ocean" }
        assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
    }

    @Test fun failedPreferenceCommitBlocksReenableUntilRevocationCanBePersisted() = screen {
        select()
        beforeNextFrame {
            manager.setDataEnabled("test.prompts", false)
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            // Model an old/interrupted selection, then fail the real preference-file check.
            preferences.setTheme("test.prompts/ocean")
            val file = context.noBackupFilesDir.resolve(ThemePreferenceStore.FILE_NAME)
            val bytes = file.readBytes()
            check(file.delete()); check(file.mkdir())
            try {
                try { manager.setDataEnabled("test.prompts", true); fail("Enable must await durable revocation") }
                catch (_: java.io.IOException) { }
                assertTrue(manager.dataPlugins.none { it.active })
                assertFalse(PluginDataCatalog(files, 1).managementRecord("test.prompts").state.enabled)
            } finally { check(file.delete()); file.writeBytes(bytes) }
            manager.setDataEnabled("test.prompts", true)
            assertEquals(ThemePreference(dynamic = false), preferences.current())
        }
        await { !choices.loading && state.preference.themeKey == null }
        assertEquals(MessageStyle.EDITORIAL, tokens.messageStyle)
    }

    @Test fun coldRestoreAndUninstallRevokeWithoutCompositionAndKeepNewUserSelection() = screen {
        select()
        beforeNextFrame {
            val preferences = ThemePreferenceStore(context.noBackupFilesDir)
            // A durable disabled package with an old theme can survive an earlier process.
            PluginDataCatalog(files, 1).setEnabled("test.prompts", false)
            val restored = PluginManager(context)
            restored.restore()
            assertEquals(ThemePreference(dynamic = false), preferences.current())
            restored.setDataEnabled("test.prompts", true)
            assertNull(preferences.current().themeKey)
            preferences.setTheme("other.plugin/new")
            restored.setDataEnabled("test.prompts", false)
            assertEquals("other.plugin/new", preferences.current().themeKey)
            preferences.setTheme("test.prompts/ocean")
            restored.uninstallData("test.prompts")
            assertEquals(ThemePreference(dynamic = false), preferences.current())
            assertTrue(restored.dataPlugins.isEmpty())
        }
    }

    @Test fun selectionAppliesAndDisableImmediatelyRestoresPersistedBrandAndDoesNotReselectOnEnable() = screen {
        val safety = colors.errorContainer
        select()
        assertEquals(brandColor("#101030"), tokens.assistantText)
        assertEquals(MessageStyle.COMPACT, tokens.messageStyle)
        assertEquals(safety, colors.errorContainer)
        revoke { manager.setDataEnabled("test.prompts", false) }
        assertEquals(thetaBrandScheme(false).primary, colors.primary)
        assertFalse(state.preference.dynamic)
        assertTrue(state.themeUnavailable)
        rule.runOnIdle { scope.launch { manager.setDataEnabled("test.prompts", true) } }
        await { !choices.loading && choices.themes.size == 1 }
        assertNull(state.preference.themeKey)
        select()
        revoke { manager.uninstallData("test.prompts") }
        assertTrue(choices.themes.isEmpty())
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun chineseLargeTextDarkThemeHasReachableSingleChoiceAndSafeFallbackOnQuarantine() = screen(fontScale = 2f, dark = true) {
        select()
        val node = rule.onNodeWithText("Ocean 海洋", substring = true).fetchSemanticsNode()
        assertTrue(node.boundsInRoot.height >= 48f)
        assertTrue(node.boundsInRoot.width <= 320f)
        assertEquals(brandColor("#FFFFFF"), tokens.assistantText)
        val page = manager.dataPlugins.single()
        files.resolve("plugins/test.prompts/1/plugin.zip").writeBytes(byteArrayOf(1, 2, 3))
        revoke {
            try { manager.readDataPage(page, null); fail("Corrupt archive must be rejected") }
            catch (_: PluginPackageException) { }
        }
        assertTrue(manager.dataPlugins.isEmpty())
        assertEquals(thetaBrandScheme(true).errorContainer, colors.errorContainer)
        assertTrue(state.themeUnavailable)
    }

    @Test fun themeObserverDoesNotInitializePluginsBeforeSafeModeRestore() = screen(skipPlugins = true) {
        assertTrue(manager.dataThemesReady)
        assertTrue(manager.dataPlugins.none { it.active })
        assertTrue(choices.themes.isEmpty())
        assertEquals(MessageStyle.EDITORIAL, tokens.messageStyle)
    }
}
