package io.github.bytejuno.thetacore.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ui.theme.*
import io.github.bytejuno.thetacore.uiStrings

@Composable
internal fun AppearanceSettingsScreen(back: () -> Unit) {
    val strings = uiStrings()
    val state = LocalThemePreferenceState.current
    val enabled = !state.loading && !state.saving
    SettingsPage(strings.get(R.string.settings_appearance), back, canGoBack = !state.saving) {
        if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.failed) {
            Text(strings.get(R.string.settings_theme_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = state::load, enabled = enabled) { Text(strings.get(R.string.chat_provider_reload)) }
        }
        Text(strings.get(R.string.settings_theme_mode), style = MaterialTheme.typography.titleMedium)
        listOf(ThemeMode.SYSTEM to R.string.settings_theme_system, ThemeMode.LIGHT to R.string.settings_theme_light,
            ThemeMode.DARK to R.string.settings_theme_dark).forEach { (mode, label) ->
            ThemeOption(strings.get(label), state.preference.mode == mode, enabled) { state.setMode(mode) }
        }
        Text(strings.get(R.string.settings_theme_palette), style = MaterialTheme.typography.titleMedium)
        ThemeOption(strings.get(R.string.chat_dynamic_color), state.preference.dynamic, enabled) { state.setDynamic(true) }
        ThemeOption(strings.get(R.string.settings_theme_brand), !state.preference.dynamic, enabled) { state.setDynamic(false) }
        HorizontalDivider()
        Text(strings.get(R.string.settings_future_themes), style = MaterialTheme.typography.titleMedium)
        Text(strings.get(R.string.settings_future_themes_detail), style = MaterialTheme.typography.bodyMedium)
        Text(strings.get(R.string.settings_future_chat), style = MaterialTheme.typography.titleMedium)
        Text(strings.get(R.string.settings_future_chat_detail), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, enabled: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton,
        onClick = select).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 12.dp))
    }
}
