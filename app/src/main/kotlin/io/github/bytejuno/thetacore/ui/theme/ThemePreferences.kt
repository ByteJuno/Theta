package io.github.bytejuno.thetacore.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val BRAND_HEX = Regex("#[0-9a-fA-F]{6}")
internal const val APPEARANCE_STORE = "theta-appearance"
internal const val DYNAMIC_KEY = "dynamic"

internal fun brandColor(hex: String): Color {
    require(BRAND_HEX.matches(hex))
    return Color(0xFF000000 or hex.substring(1).toLong(16))
}

internal fun thetaBrandScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = brandColor("#B3C5FF"), onPrimary = brandColor("#002C75"),
    primaryContainer = brandColor("#23488C"), onPrimaryContainer = brandColor("#DBE3FF"),
    background = brandColor("#121316"), surface = brandColor("#121316"),
    onBackground = brandColor("#E4E2E6"), onSurface = brandColor("#E4E2E6"),
    surfaceContainer = brandColor("#1C1F26"), surfaceContainerHigh = brandColor("#242832"),
    surfaceContainerLow = brandColor("#181B22"), surfaceContainerHighest = brandColor("#30343F"),
) else lightColorScheme(
    primary = brandColor("#315DCE"), onPrimary = Color.White,
    primaryContainer = brandColor("#E8EEFA"), onPrimaryContainer = brandColor("#152D60"),
    background = brandColor("#FAF9F7"), surface = brandColor("#FAF9F7"),
    onBackground = brandColor("#1B1B20"), onSurface = brandColor("#1B1B20"),
    surfaceContainer = brandColor("#F1F3F8"), surfaceContainerHigh = brandColor("#E9ECF3"),
    surfaceContainerLow = brandColor("#F5F6FA"), surfaceContainerHighest = brandColor("#E2E6EF"),
)
internal fun thetaTokens(colors: ColorScheme) = ThetaThemeTokens(colors.surface,
    colors.primaryContainer, colors.onPrimaryContainer, colors.surfaceContainer, brandColor("#FFC94D"))
