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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Real Compose layout/semantics and persistence, not a device pixel-rendering claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en-w360dp-h640dp-mdpi")
class PluginThemeUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun screen(fontScale: Float = 1f, dark: Boolean = false, skipPlugins: Boolean = false, block: Harness.() -> Unit) {
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
                override fun getApplicationContext(): Context = this
            }
            val catalog = PluginDataCatalog(context.filesDir, 1)
            installPromptBundle(catalog, mapOf("themes/ocean.json" to """{"id":"ocean","displayName":"Ocean 海洋",
                "light":{"assistantText":"#101030"},"dark":{"assistantText":"#FFFFFF"},"cornerScale":1.5,"messageStyle":"compact"}"""))
            val manager = PluginManager(context)
            val harness = Harness(manager, context.filesDir)
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
                    ThetaTheme(systemDark = dark, manager = manager) {
                        harness.state = LocalThemePreferenceState.current
                        harness.choices = LocalThemeChoices.current
                        harness.colors = MaterialTheme.colorScheme
                        harness.tokens = LocalThetaThemeTokens.current
                        harness.scope = rememberCoroutineScope()
                        AppearanceSettingsScreen {}
                    }
                }
            }
            harness.await { !harness.state.loading && !harness.choices.loading && harness.choices.themes.size == if (skipPlugins) 0 else 1 }
            harness.block()
        } finally { controller.pause().stop().destroy(); root.deleteRecursively() }
    }

    private inner class Harness(val manager: PluginManager, val files: File) {
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
        fun revoke(action: suspend () -> Unit) {
            rule.runOnIdle { scope.launch { action() } }
            await { !state.saving && !choices.loading && state.preference.themeKey == null }
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
