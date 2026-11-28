package io.github.bytejuno.thetacore.memory

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.memory.MemoryStore
import io.github.bytejuno.thetacore.plugin.openTestStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "en-w360dp-h640dp-mdpi")
class MemoryScreenTest {
    @get:Rule val rule = createEmptyComposeRule()
    private fun screen(scale: Float = 1f, dark: Boolean = false, block: (ComponentActivity, MemoryStore) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val store = openTestStore(Files.createTempDirectory("memory-ui").toFile())
        try {
            val activity = controller.get()
            val view = ComposeView(activity); activity.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { MemoryContent(store.memories) {} }
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodesWithText(activity.getString(R.string.memory_empty)).fetchSemanticsNodes().isNotEmpty() }
            block(activity, store.memories)
        } finally { controller.pause().stop().destroy(); store.close() }
    }
    private fun click(activity: ComponentActivity, resource: Int) {
        rule.onNodeWithText(activity.getString(resource)).performScrollTo().performClick()
    }
    private fun saved() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(MemoryUiKeys.FEEDBACK).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun addEditPinSearchDisableAndConfirmedClearUsePersistentStore() = screen { activity, store ->
        click(activity, R.string.memory_add)
        rule.onNodeWithTag(MemoryUiKeys.CONTENT).performTextInput("Prefers tea")
        click(activity, R.string.memory_save); saved()
        assertEquals("Prefers tea", store.search().single().content)
        click(activity, R.string.memory_edit)
        rule.onNodeWithTag(MemoryUiKeys.CONTENT).performTextReplacement("Prefers Chinese")
        click(activity, R.string.memory_save); saved()
        click(activity, R.string.memory_pin); saved(); assertTrue(store.search().single().pinned)
        rule.onNodeWithTag(MemoryUiKeys.SEARCH).performScrollTo().performTextInput("missing")
        rule.waitUntil(10_000) { rule.onAllNodesWithText(activity.getString(R.string.memory_empty)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(MemoryUiKeys.ENABLED).performScrollTo().performClick(); saved()
        assertFalse(store.enabled()); assertEquals(1, store.usage().count)
        click(activity, R.string.memory_clear)
        rule.onNodeWithText(activity.getString(R.string.memory_cancel)).performClick()
        assertEquals(1, store.usage().count)
        click(activity, R.string.memory_clear)
        rule.onNodeWithText(activity.getString(R.string.memory_confirm)).performClick(); saved()
        assertEquals(0, store.usage().count)
    }

    @Test @Config(qualifiers = "zh-w320dp-h640dp-mdpi")
    fun chineseSmallScreenLargeTextHasReachableEditorAndActions() = screen(2f, true) { activity, store ->
        click(activity, R.string.memory_add)
        rule.onNodeWithTag(MemoryUiKeys.CONTENT).performTextInput("偏好中文与大字体")
        rule.onNodeWithTag(MemoryUiKeys.CATEGORY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(activity.getString(R.string.memory_save)).performScrollTo().assertIsDisplayed().performClick()
        saved(); assertEquals("偏好中文与大字体", store.search().single().content)
        click(activity, R.string.memory_delete)
        rule.onNodeWithText(activity.getString(R.string.memory_confirm)).performClick(); saved()
        assertEquals(0, store.usage().count)
    }
}
