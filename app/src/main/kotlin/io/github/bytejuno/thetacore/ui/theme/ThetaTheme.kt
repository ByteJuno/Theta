package io.github.bytejuno.thetacore.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Internal semantic tokens; safety colors always come from Material's error scheme. */
internal data class ThetaThemeTokens(
    val canvas: Color,
    val userBubbleContainer: Color,
    val onUserBubble: Color,
    val composerContainer: Color,
    val brandAccent: Color,
    val assistantText: Color = Color.Unspecified,
    val capsuleContainer: Color = composerContainer,
    val onCapsule: Color = onUserBubble,
    val cornerScale: Float = 1f,
    val messageStyle: MessageStyle = MessageStyle.EDITORIAL,
    val contentGap: Dp = 12.dp,
    val smallGap: Dp = 8.dp,
    val pageInset: Dp = 16.dp,
) {
    val capsule get() = RoundedCornerShape((24 * cornerScale).dp)
    val messageGap get() = if (messageStyle == MessageStyle.COMPACT) 4.dp else contentGap
}

internal val LocalThetaThemeTokens = staticCompositionLocalOf { thetaTokens(thetaBrandScheme(false)) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ThetaTheme(systemDark: Boolean = isSystemInDarkTheme(), manager: io.github.bytejuno.thetacore.plugin.PluginManager? = null,
    content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state = rememberThemePreferenceState(manager)
    val dark = state.preference.isDark(systemDark)
    val choices = rememberPluginThemeChoices(manager)
    fun base(isDark: Boolean, dynamic: Boolean): ColorScheme = if (dynamic && Build.VERSION.SDK_INT >= 31) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else thetaBrandScheme(isDark)
    val available = choices.themes.filter { theme -> listOf(false, true).all { mode ->
        resolvePluginTheme(theme, base(mode, theme.useDynamicColor), mode) != null
    } }
    val theme = available.singleOrNull { it.key == state.preference.themeKey }
    val resolved = theme?.let { resolvePluginTheme(it, base(dark, it.useDynamicColor), dark) }
    val colors = resolved?.colors ?: base(dark, state.preference.themeKey == null && state.preference.dynamic)
    LaunchedEffect(state.preference, state.loading, state.saving, choices.loading, available) {
        val key = state.preference.themeKey
        if (key != null && !state.loading && !state.saving && !state.failed && !choices.loading && available.none { it.key == key })
            state.clearUnavailableTheme(key)
    }
    CompositionLocalProvider(LocalThetaThemeTokens provides (resolved?.tokens ?: thetaTokens(colors)),
        LocalThemePreferenceState provides state, LocalThemeChoices provides choices.copy(themes = available)) {
        // Compose's platform MotionDurationScale remains in charge (including system scale 0).
        MaterialTheme(colorScheme = colors, shapes = themeShapes(theme?.cornerScale ?: 1f),
            motionScheme = MotionScheme.expressive(), content = content)
    }
}

internal fun themeShapes(scale: Float) = Shapes(
    extraSmall = RoundedCornerShape((4 * scale).dp), small = RoundedCornerShape((8 * scale).dp),
    medium = RoundedCornerShape((12 * scale).dp), large = RoundedCornerShape((16 * scale).dp),
    extraLarge = RoundedCornerShape((28 * scale).dp),
)
