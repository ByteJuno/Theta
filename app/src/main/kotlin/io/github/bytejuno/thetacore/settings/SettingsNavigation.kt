package io.github.bytejuno.thetacore.settings

/** A shortcut starts at its destination; the hub adds a parent only when actually visited. */
internal enum class SettingsDestination { CHAT, HOME, PROVIDERS, PERSONAS, MCP, SAFETY, TOOLS, APPEARANCE, LANGUAGE, BACKGROUND }

internal data class SettingsNavigation(val stack: List<SettingsDestination> = emptyList()) {
    val current: SettingsDestination? get() = stack.lastOrNull()
    fun open(destination: SettingsDestination): SettingsNavigation {
        require(destination != SettingsDestination.CHAT)
        return if (current == destination) this else copy(stack = stack + destination)
    }
    fun back() = copy(stack = stack.dropLast(1))
}
