package io.github.bytejuno.thetacore.settings

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal class SettingsNavigator(initial: SettingsNavigation = SettingsNavigation()) {
    var navigation by mutableStateOf(initial); private set
    fun open(destination: SettingsDestination) { navigation = navigation.open(destination) }
    fun back() { navigation = navigation.back() }
}

@Composable
internal fun rememberSettingsNavigator(): SettingsNavigator = rememberSaveable(saver = listSaver(
    save = { it.navigation.stack.map(SettingsDestination::name) },
    restore = { SettingsNavigator(SettingsNavigation(it.map(SettingsDestination::valueOf))) },
)) { SettingsNavigator() }
