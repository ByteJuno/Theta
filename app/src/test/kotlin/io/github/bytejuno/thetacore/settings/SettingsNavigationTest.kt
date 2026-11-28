package io.github.bytejuno.thetacore.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun hubChildAndNestedSafetyReturnToTheirActualParent() {
        val chat = SettingsNavigation()
        val home = chat.open(SettingsDestination.HOME)
        val safety = home.open(SettingsDestination.SAFETY)
        val tools = safety.open(SettingsDestination.TOOLS)
        assertEquals(safety, tools.back())
        assertEquals(home, tools.back().back())
        assertEquals(chat, tools.back().back().back())
        assertNull(chat.current)
        assertEquals(chat, chat.back())
    }

    @Test fun everyHubDestinationAndDirectShortcutReturnsWithoutPhantomHub() {
        for (destination in SettingsDestination.entries.filter { it != SettingsDestination.CHAT && it != SettingsDestination.HOME }) {
            val shortcut = SettingsNavigation().open(destination)
            assertEquals(destination, shortcut.current)
            assertNull(shortcut.back().current)
            val hub = SettingsNavigation().open(SettingsDestination.HOME)
            assertEquals(hub, hub.open(destination).back())
        }
    }

    @Test fun repeatedClickDoesNotDuplicatePageAndRestorationKeepsParents() {
        val stack = SettingsNavigation().open(SettingsDestination.HOME).open(SettingsDestination.PROVIDERS)
        assertEquals(stack, stack.open(SettingsDestination.PROVIDERS))
        val restored = SettingsNavigation(stack.stack.map { SettingsDestination.valueOf(it.name) })
        assertEquals(SettingsDestination.HOME, restored.back().current)
    }
}
