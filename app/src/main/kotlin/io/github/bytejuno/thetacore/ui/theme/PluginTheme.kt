package io.github.bytejuno.thetacore.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.bytejuno.thetacore.plugin.*
import kotlinx.serialization.json.*
import kotlin.math.pow

internal enum class MessageStyle { EDITORIAL, BUBBLE, COMPACT }

/** Only host-owned semantic slots are exposed. Error and safety roles are never writable. */
internal data class PluginTheme(
    val selection: SelectedPluginData,
    val id: String,
    val displayName: String,
    val useDynamicColor: Boolean,
    val light: Map<String, Color>,
    val dark: Map<String, Color>,
    val cornerScale: Float,
    val messageStyle: MessageStyle,
) {
    val key get() = "${selection.manifest.id}/$id"
}

private val themeColorKeys = setOf("background", "surface", "onBackground", "onSurface", "onSurfaceVariant",
    "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "userBubbleContainer", "onUserBubble",
    "assistantText", "capsuleContainer", "onCapsule")
private val themeRootKeys = setOf("id", "displayName", "useDynamicColor", "light", "dark", "cornerScale", "messageStyle")

internal fun parsePluginTheme(selection: SelectedPluginData, path: String, bytes: ByteArray): PluginTheme {
    dataRequire(bytes.size <= 16 * 1024, "Theme exceeds 16 KiB")
    val json = dataJson(bytes)
    dataRequire(json.keys.all { it in themeRootKeys }, "Unsupported theme field")
    val id = dataString(json, "id")
    dataRequire(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(id) && path == "themes/$id.json", "Invalid theme identity")
    val name = dataString(json, "displayName")
    dataRequire(name.isNotBlank() && name.length <= 80 && validThemeName(name), "Invalid theme name")
    val dynamic = json["useDynamicColor"]?.let {
        val value = it as? JsonPrimitive
        dataRequire(value != null && !value.isString && value.booleanOrNull != null, "Invalid dynamic color flag")
        value!!.boolean
    } ?: false
    val scale = json["cornerScale"]?.let {
        val value = it as? JsonPrimitive
        dataRequire(value != null && !value.isString, "Invalid corner scale")
        value!!.floatOrNull ?: throw PluginPackageException("Invalid corner scale")
    } ?: 1f
    dataRequire(scale.isFinite() && scale in 0.5f..1.5f, "Corner scale must be 0.5..1.5")
    val style = when (if ("messageStyle" in json) dataString(json, "messageStyle") else "editorial") {
        "editorial" -> MessageStyle.EDITORIAL
        "bubble" -> MessageStyle.BUBBLE
        "compact" -> MessageStyle.COMPACT
        else -> throw PluginPackageException("Unknown message style")
    }
    fun palette(key: String): Map<String, Color> {
        if (key !in json) return emptyMap()
        val value = json[key] as? JsonObject ?: throw PluginPackageException("Invalid $key palette")
        dataRequire(value.keys.all { it in themeColorKeys }, "Unsupported or protected theme color")
        return value.mapValues { (slot, _) ->
            val hex = dataString(value, slot)
            dataRequire(Regex("#[0-9a-fA-F]{6}").matches(hex), "Expected opaque #RRGGBB color")
            brandColor(hex)
        }
    }
    val theme = PluginTheme(selection, id, name, dynamic, palette("light"), palette("dark"), scale, style)
    for (dark in listOf(false, true)) dataRequire(resolvePluginTheme(theme, thetaBrandScheme(dark), dark) != null,
        "Theme text contrast must be at least 4.5:1")
    return theme
}

private fun validThemeName(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isISOControl() || char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069') return false
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}

internal data class ResolvedPluginTheme(val colors: ColorScheme, val tokens: ThetaThemeTokens)

/** Validate the actual composition too: dynamic colors depend on wallpaper and can change. */
internal fun resolvePluginTheme(theme: PluginTheme, base: ColorScheme, dark: Boolean): ResolvedPluginTheme? {
    val overrides = if (dark) theme.dark else theme.light
    fun c(key: String, fallback: Color) = overrides[key] ?: fallback
    val colors = base.copy(
        background = c("background", base.background), onBackground = c("onBackground", base.onBackground),
        surface = c("surface", base.surface), onSurface = c("onSurface", base.onSurface),
        onSurfaceVariant = c("onSurfaceVariant", base.onSurfaceVariant),
        surfaceContainerLowest = c("surfaceContainerLowest", base.surfaceContainerLowest),
        surfaceContainerLow = c("surfaceContainerLow", base.surfaceContainerLow),
        surfaceContainer = c("surfaceContainer", base.surfaceContainer),
        surfaceContainerHigh = c("surfaceContainerHigh", base.surfaceContainerHigh),
        surfaceContainerHighest = c("surfaceContainerHighest", base.surfaceContainerHighest),
        primary = c("primary", base.primary), onPrimary = c("onPrimary", base.onPrimary),
        primaryContainer = c("primaryContainer", base.primaryContainer), onPrimaryContainer = c("onPrimaryContainer", base.onPrimaryContainer),
    )
    val tokens = thetaTokens(colors).copy(
        userBubbleContainer = c("userBubbleContainer", colors.primaryContainer),
        onUserBubble = c("onUserBubble", colors.onPrimaryContainer),
        assistantText = c("assistantText", colors.onSurface),
        capsuleContainer = c("capsuleContainer", colors.surfaceContainer), onCapsule = c("onCapsule", colors.onSurfaceVariant),
        cornerScale = theme.cornerScale, messageStyle = theme.messageStyle,
    )
    val surfaces = listOf(colors.surface, colors.background, colors.surfaceContainerLowest, colors.surfaceContainerLow,
        colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest)
    val pairs = surfaces.flatMap { bg -> listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary,
        colors.error, tokens.assistantText).map { it to bg } } + listOf(
        colors.onBackground to colors.background, colors.onPrimary to colors.primary,
        colors.onPrimaryContainer to colors.primaryContainer, tokens.onUserBubble to tokens.userBubbleContainer,
        tokens.onCapsule to tokens.capsuleContainer,
        // Links and code/quote text may be rendered inside the user bubble.
        colors.primary to tokens.userBubbleContainer,
        colors.primary to colors.errorContainer, // Full-auto and approval action buttons stay readable.
    )
    return if (pairs.all { (fg, bg) -> themeContrast(fg, bg) >= 4.5 }) ResolvedPluginTheme(colors, tokens) else null
}

internal fun themeContrast(a: Color, b: Color): Double {
    fun luminance(color: Color): Double {
        fun channel(v: Float): Double = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
    val x = luminance(a); val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}
