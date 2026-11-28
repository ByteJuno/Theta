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
    val contentGap: Dp = 12.dp,
    val smallGap: Dp = 8.dp,
    val pageInset: Dp = 16.dp,
    val capsule: RoundedCornerShape = RoundedCornerShape(24.dp),
)

internal val LocalThetaThemeTokens = staticCompositionLocalOf { thetaTokens(thetaBrandScheme(false)) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ThetaTheme(systemDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state = rememberThemePreferenceState()
    val dark = state.preference.isDark(systemDark)
    val colors = if (state.preference.dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else thetaBrandScheme(dark)
    CompositionLocalProvider(LocalThetaThemeTokens provides thetaTokens(colors),
        LocalThemePreferenceState provides state) {
        // Compose's platform MotionDurationScale remains in charge (including system scale 0).
        MaterialTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
    }
}
