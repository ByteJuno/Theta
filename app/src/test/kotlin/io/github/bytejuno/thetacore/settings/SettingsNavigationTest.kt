package io.github.bytejuno.thetacore.settings

import io.github.bytejuno.thetacore.chat.backFromSettings
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun returningFromUnrelatedSettingsDoesNotRefreshTheChatHistoryPage() {
        for (destination in SettingsDestination.entries.filter { it != SettingsDestination.CHAT && it != SettingsDestination.HOME }) {
            for (throughHub in listOf(false, true)) {
                val navigator = SettingsNavigator()
                var historyRefreshes = 0
                var personaReloads = 0
                if (throughHub) navigator.open(SettingsDestination.HOME)
                navigator.open(destination)
                backFromSettings(navigator, { historyRefreshes++ }, { personaReloads++ })
                assertEquals(if (destination == SettingsDestination.PROVIDERS) 1 else 0, historyRefreshes)
                assertEquals(if (throughHub) 0 else 1, personaReloads)
                if (throughHub) {
                    assertEquals(SettingsDestination.HOME, navigator.navigation.current)
                    backFromSettings(navigator, { historyRefreshes++ }, { personaReloads++ })
                }
                assertNull(navigator.navigation.current)
                assertEquals(if (destination == SettingsDestination.PROVIDERS) 1 else 0, historyRefreshes)
                assertEquals(1, personaReloads)
            }
        }
    }

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
