package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.mcp.validateMcpUrl
import kotlinx.serialization.json.*

internal data class McpPreset(val key: String, val name: String, val url: String, val auth: String)
internal fun parseMcpPreset(pluginId: String, path: String, bytes: ByteArray): McpPreset {
    val o = dataJson(bytes)
    dataRequire(o.keys.all { it in setOf("id", "name", "url", "auth") }, "Unsupported MCP preset field")
    val id = dataString(o, "id")
    val name = dataString(o, "name")
    dataRequire(id.length <= 128 && name.length <= 128, "MCP preset metadata too long")
    val auth = dataString(o, "auth")
    dataRequire(auth in setOf("none", "bearer", "apiKey", "oauth"), "Unsupported MCP authentication")
    val url = validateMcpUrl(dataString(o, "url"))
    return McpPreset("$pluginId/$path", name, url, auth)
}
internal fun PluginDataCatalog.readMcpPresets(selection: SelectedPluginData): List<McpPreset> =
    readEntries(selection) { it.startsWith("mcp/") && it.endsWith(".json") }.map { (path, bytes) ->
        parseMcpPreset(selection.manifest.id, path, bytes)
    }
