package io.github.bytejuno.thetacore.ui.theme

import androidx.compose.ui.graphics.Color

private val BRAND_HEX = Regex("#[0-9a-fA-F]{6}")
internal const val APPEARANCE_STORE = "theta-appearance"
internal const val DYNAMIC_KEY = "dynamic"

internal fun brandColor(hex: String): Color {
    require(BRAND_HEX.matches(hex))
    return Color(0xFF000000 or hex.substring(1).toLong(16))
}
