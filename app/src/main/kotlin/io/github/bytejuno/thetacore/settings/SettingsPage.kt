package io.github.bytejuno.thetacore.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ui.theme.LocalThetaThemeTokens
import io.github.bytejuno.thetacore.uiStrings

/** One full-height scroll owner; actions stay reachable with an IME, landscape or large text. */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    canGoBack: Boolean = true,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalThetaThemeTokens.current
    BackHandler { if (canGoBack) onBack() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = tokens.pageInset),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = canGoBack, onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_settings_back), uiStrings().get(R.string.settings_back))
                }
                Text(title, Modifier.weight(1f).padding(vertical = tokens.smallGap), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(tokens.pageInset), verticalArrangement = Arrangement.spacedBy(tokens.contentGap)) {
                content()
                actions()
            }
        }
    }
}
