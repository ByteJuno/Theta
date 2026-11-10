package io.github.bytejuno.thetacore.plugin.tools

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*

/** A synchronous read stays on the bounded invocation worker until the backend actually returns.
 * No detached work, screenshot, input injection, root or accessibility service is implied.
 */
internal fun interface ScreenBackend {
    fun readDefaultDisplay(): ScreenInfo?
}

/** Mode dimensions are physical display-mode pixels, NOT app/window bounds or tap coordinates. */
internal data class ScreenInfo(val modeWidthPixels: Int, val modeHeightPixels: Int,
    val rotationQuarterTurns: Int, val state: String)

internal class HostScreenTool(private val backend: ScreenBackend) : Tool {
    override val id = "screen_info"
    override val spec = ToolSpec("screen_info",
        "Read default display state, rotation and physical mode size; does not capture pixels or control the screen",
        Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject,
        outputSchema = Json.parseToJsonElement("""{"type":"object","properties":{"modeWidthPixels":{"type":"integer","minimum":1},"modeHeightPixels":{"type":"integer","minimum":1},"rotationQuarterTurns":{"type":"integer","minimum":0,"maximum":3},"state":{"type":"string"}},"required":["modeWidthPixels","modeHeightPixels","rotationQuarterTurns","state"],"additionalProperties":false}""").jsonObject,
        risk = ToolRisk.READ, parallelSafe = true, exclusiveResource = "screen", timeoutMs = 5_000)

    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        val info = backend.readDefaultDisplay()
            ?: return ToolResult("Default display unavailable", isError = true, effect = ToolEffect.NONE)
        require(info.modeWidthPixels > 0 && info.modeHeightPixels > 0 && info.rotationQuarterTurns in 0..3)
        return ToolResult("Default display information (not a screenshot or input coordinate space)",
            buildJsonObject {
                put("modeWidthPixels", info.modeWidthPixels)
                put("modeHeightPixels", info.modeHeightPixels)
                put("rotationQuarterTurns", info.rotationQuarterTurns)
                put("state", info.state)
            }, effect = ToolEffect.NONE)
    }
}
