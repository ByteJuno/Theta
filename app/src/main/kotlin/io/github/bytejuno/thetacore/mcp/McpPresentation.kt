package io.github.bytejuno.thetacore.mcp

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.plugin.McpPreset

internal fun McpConnectionView.stateText(): Int = when (state) {
    "recovery_blocked" -> R.string.mcp_recovery_blocked
    "ready" -> R.string.mcp_ready
    "refreshing" -> R.string.mcp_refreshing
    "failed" -> R.string.mcp_refresh_failed
    else -> R.string.mcp_disabled
}
internal fun McpPreset.requiresOAuth() = auth == "oauth"
internal fun McpPreset.isNoAuth() = auth == "none"
internal fun McpPreset.isApiKey() = auth == "apiKey"
internal fun McpConnectionView.isApiKey() = header == mcpHeader(true)
internal fun mcpHeader(apiKey: Boolean) = if (apiKey) "X-API-Key" else "Authorization"
