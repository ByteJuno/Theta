package io.github.bytejuno.thetacore.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.bytejuno.thetacore.*
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.chat.*
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens

@Composable
internal fun SettingsScreen(host: ThetaApplication, destination: SettingsDestination,
    navigate: (SettingsDestination) -> Unit, back: () -> Unit, personaEnabled: Boolean) {
    val strings = uiStrings()
    when (destination) {
        SettingsDestination.HOME -> SettingsPage(strings.get(R.string.chat_settings), back) {
            SettingsGroup(R.string.settings_models_agent, listOf(
                R.string.providers_title to SettingsDestination.PROVIDERS,
                R.string.settings_personas_skills to SettingsDestination.PERSONAS,
                R.string.memory_title to SettingsDestination.MEMORY,
                R.string.mcp_title to SettingsDestination.MCP), navigate)
            SettingsGroup(R.string.settings_safety, listOf(R.string.chat_approval_settings to SettingsDestination.SAFETY), navigate)
            SettingsGroup(R.string.settings_customization, listOf(R.string.settings_appearance to SettingsDestination.APPEARANCE), navigate)
            SettingsGroup(R.string.settings_system, listOf(
                R.string.language_title to SettingsDestination.LANGUAGE,
                R.string.background_title to SettingsDestination.BACKGROUND), navigate)
        }
        SettingsDestination.PROVIDERS -> ProviderScreen(host, back)
        SettingsDestination.SAFETY -> ChatApprovalDialog(host, back) { navigate(SettingsDestination.TOOLS) }
        SettingsDestination.TOOLS -> ToolApprovalDialog(host, back)
        SettingsDestination.MEMORY -> io.github.bytejuno.thetacore.memory.MemoryScreen(host, back)
        SettingsDestination.MCP -> io.github.bytejuno.thetacore.mcp.McpScreen(host, back)
        SettingsDestination.PERSONAS -> PersonaSettingsScreen(host, personaEnabled, back)
        SettingsDestination.APPEARANCE -> AppearanceSettingsScreen(back)
        SettingsDestination.LANGUAGE -> SettingsPage(strings.get(R.string.language_title), back) { AppLanguageSetting() }
        SettingsDestination.BACKGROUND -> SettingsPage(strings.get(R.string.background_title), back) { BackgroundDiagnostics() }
        SettingsDestination.CHAT -> Unit
    }
}

@Composable
private fun SettingsGroup(title: Int, entries: List<Pair<Int, SettingsDestination>>, navigate: (SettingsDestination) -> Unit) {
    val strings = uiStrings()
    val tokens = LocalThetaThemeTokens.current
    Text(strings.get(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Card(shape = tokens.capsule, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        entries.forEachIndexed { index, (label, destination) ->
            TextButton(onClick = { navigate(destination) }, modifier = Modifier.fillMaxWidth()) {
                Text(strings.get(label), Modifier.fillMaxWidth().padding(tokens.smallGap), style = MaterialTheme.typography.bodyLarge)
            }
            if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = tokens.pageInset))
        }
    }
}
