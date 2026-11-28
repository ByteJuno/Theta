package io.github.bytejuno.thetacore.ui.plugins

import android.app.Application
import androidx.compose.foundation.ScrollState
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en-w360dp-h640dp-mdpi")
class PluginsScreenTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private fun withScreen(staging: Boolean = false, safe: Boolean = false, fontScale: Float = 1f,
        dark: Boolean = false, populated: Boolean = false, busy: Boolean = false,
        data: ManagedDataRecord? = null, block: Screen.() -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val compose = ComposeView(activity)
            activity.setContentView(compose)
            val screen = Screen(compose, composeRule)
            val plugins = if (populated) listOf(ManagedPlugin(
                PluginManifest("sample.plugin", "Fixture", "1.0", 1, 1, "sample.Entry"), PluginState()).apply { canRollback = true; busy(busy) }) else emptyList()
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        PluginsScreen(plugins, listOfNotNull(data), emptyList(), listOf("rejected.plugin").filter { safe },
                            PluginRestorePolicy(safe, safe, if (safe) setOf("suspect.plugin") else emptySet()), false,
                            null, safe, staging, safe, HostEnvironmentStatus(),
                            onBack = { screen.back++ }, onResume = { screen.resume++ },
                            onRefreshEnvironment = { screen.refresh++ }, onOpenAccessibility = { screen.accessibility++ },
                            onTool = {}, onCancelReading = { screen.cancel++ }, onRetryCleanup = { screen.cleanup++ },
                            onOpen = {}, onPick = { screen.codeImports++ }, onPickData = { screen.dataImports++ },
                            onOpenData = {}, onToggleData = { screen.dataToggles++ }, onUninstallData = { screen.dataRemovals++ }, onToggle = { screen.toggles++ },
                            onUninstall = { screen.removals++ }, onRollback = { screen.rollbacks++ }, onRemoveRejected = { screen.rejected++ }, scrollState = screen.scroll)
                    }
                }
            }
            screen.settle()
            screen.block()
        } finally { controller.pause().stop().destroy() }
    }

    private class Screen(val view: ComposeView, val rule: ComposeTestRule) {
        val scroll = ScrollState(0)
        var back = 0; var resume = 0; var refresh = 0; var accessibility = 0; var cancel = 0
        var removals = 0; var rollbacks = 0; var dataToggles = 0; var dataRemovals = 0
        var cleanup = 0; var codeImports = 0; var dataImports = 0; var toggles = 0; var rejected = 0
        fun settle() {
            rule.mainClock.advanceTimeBy(160)
            rule.waitForIdle()
        }
        private fun owner(view: View): SemanticsOwner? {
            view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }?.let {
                return it.invoke(view) as SemanticsOwner
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        fun nodes(merged: Boolean = false): List<SemanticsNode> = descendants(if (merged)
            requireNotNull(owner(view)).rootSemanticsNode else requireNotNull(owner(view)).unmergedRootSemanticsNode)
        fun text(id: Int) = view.context.getString(id)
        fun matches(label: String, merged: Boolean = false) = nodes(merged).filter {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }
        fun count(id: Int) = matches(text(id)).size
        fun click(id: Int) = click(text(id))
        fun click(label: String) {
            // Accessibility exports only visible merged controls. Bring the target into view first.
            val target = matches(label).single()
            scroll.dispatchRawDelta(target.positionInRoot.y - 160f)
            settle()
            check(matches(label, true).isNotEmpty()) { "Missing control after scroll: $label" }
            val action = matches(label, true).single().config[SemanticsActions.OnClick].action
            assertTrue(requireNotNull(action).invoke())
            settle()
        }
        fun disabled(id: Int) = matches(text(id), true).single().config.contains(SemanticsProperties.Disabled)
        fun scrollToBottom() {
            scroll.dispatchRawDelta(scroll.maxValue.toFloat())
            settle()
        }
    }

    @Test fun defaultAllHasUniqueImportAndEmptySectionsInOneScrollContainer() = withScreen {
        for (id in listOf(R.string.import_code, R.string.import_data, R.string.data_packages, R.string.code_packages,
            R.string.no_plugins, R.string.no_data_packages, R.string.plugin_env_advisory_notice)) assertEquals(text(id), 1, count(id))
        assertEquals(1, nodes(true).count { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null })
        assertEquals(true, matches(text(R.string.plugins_filter_all), true).single().config.getOrNull(SemanticsProperties.Selected))
        click(R.string.import_code); click(R.string.import_data); click(R.string.retry_import_cleanup)
        assertEquals(1, codeImports); assertEquals(1, dataImports); assertEquals(1, cleanup)
        scroll.dispatchRawDelta(-scroll.value.toFloat()); settle()
        scrollToBottom()
        assertTrue("Footer must be scroll-reachable", matches(text(R.string.plugin_env_advisory_notice)).single().boundsInRoot.height > 0f)
    }

    @Test fun everyFilterShowsOnlyItsSectionsAndOneDisclaimer() = withScreen {
        for ((tab, sections) in listOf(
            R.string.plugins_filter_code to listOf(1, 0, 0), R.string.plugins_filter_data to listOf(0, 1, 0),
            R.string.plugins_filter_environment to listOf(0, 0, 1), R.string.plugins_filter_all to listOf(1, 1, 1))) {
            click(tab)
            assertEquals(sections, listOf(count(R.string.code_packages), count(R.string.data_packages), count(R.string.plugin_env_host_title)))
            assertEquals(1, count(R.string.plugin_env_advisory_notice))
        }
    }

    @Test fun environmentExpandsAndCollapsesWithoutLosingSummaryOrActions() = withScreen {
        assertEquals(0, count(R.string.plugin_env_refresh))
        click(R.string.plugin_env_host_title)
        assertEquals(1, count(R.string.plugin_env_refresh))
        click(R.string.plugin_env_refresh); click(R.string.plugin_env_open_accessibility)
        assertEquals(1, refresh); assertEquals(1, accessibility)
        click(R.string.plugin_env_host_title)
        assertEquals(0, count(R.string.plugin_env_refresh))
        assertEquals(1, count(R.string.plugin_env_host_title))
    }

    @Test fun readingDisablesImportsAndKeepsCancelAndCleanup() = withScreen(staging = true) {
        assertTrue(disabled(R.string.import_code)); assertTrue(disabled(R.string.import_data))
        click(R.string.cancel_reading); click(R.string.retry_import_cleanup)
        assertEquals(1, cancel); assertEquals(1, cleanup)
    }

    @Test fun safeModeDefaultsToAllAndKeepsRecoveryActionsAcrossFilters() = withScreen(safe = true, populated = true) {
        assertEquals(1, count(R.string.safe_mode_all)); assertEquals(1, count(R.string.recovery_requested))
        assertEquals(1, matches("Disable Fixture").size)
        assertEquals(1, matches(view.context.getString(R.string.suspect_plugins, "suspect.plugin")).size)
        assertTrue(disabled(R.string.import_data))
        click("Disable Fixture"); assertEquals(1, toggles)
        click(R.string.plugins_filter_environment)
        click(R.string.resume_next_start); click("Clear-data uninstall rejected.plugin")
        assertEquals(1, resume); assertEquals(1, rejected)
    }

    @Test fun normalModeHasNoRecoveryBanner() = withScreen {
        assertEquals(0, count(R.string.safe_mode_all)); assertEquals(0, count(R.string.resume_next_start))
    }

    @Test @Config(qualifiers = "zh-w360dp-h640dp-mdpi")
    fun largeTextAndDarkThemeKeepActionsWithinSmallScreenWidth() = withScreen(fontScale = 1.6f, dark = true) {
        for (id in listOf(R.string.import_code, R.string.import_data, R.string.plugins_filter_environment)) {
            val node = matches(text(id), true).single()
            assertTrue("${text(id)} ${node.boundsInRoot}", node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= 360f)
            // The target may begin below the fold; its layout remains full width, not ellipsized.
            assertTrue(node.size.height >= 48)
        }
    }
    @Test fun codeActionsKeepExactLabelsAndDispatchOnce() = withScreen(populated = true) {
        for (label in listOf("Disable Fixture", "Uninstall Fixture", "Roll back Fixture")) {
            assertEquals(1, matches(label).size)
            click(label)
        }
        assertEquals(1, toggles); assertEquals(1, removals); assertEquals(1, rollbacks)
        assertEquals(1, count(R.string.plugin_env_advisory_notice))
    }

    @Test fun busyCodeKeepsMutationControlsDisabled() = withScreen(populated = true, busy = true) {
        for (label in listOf("Disable Fixture", "Uninstall Fixture", "Roll back Fixture")) {
            assertTrue(matches(label, true).single().config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun pendingDataDeletionKeepsRetryVisibleAndEnableDisabled() = withScreen(data = ManagedDataRecord(
        PluginDataRecord("sample.data", PluginDataState(version = 1, pendingDelete = true)), null)) {
        assertTrue(disabled(R.string.enable_data))
        assertEquals(1, count(R.string.retry_data_uninstall))
        click(R.string.retry_data_uninstall)
        assertEquals(1, dataRemovals); assertEquals(0, dataToggles)
    }

}
