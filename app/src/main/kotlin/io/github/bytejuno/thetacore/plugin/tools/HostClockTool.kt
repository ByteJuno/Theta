package io.github.bytejuno.thetacore.plugin.tools

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*

/** Deterministic when injected; no accounts, files, devices, shell or network. */
internal class HostClockTool(private val nowMillis: () -> Long = System::currentTimeMillis) : Tool {
    override val id = "clock"
    override val spec = ToolSpec("clock", "Read host clock in milliseconds",
        Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject,
        outputSchema = Json.parseToJsonElement("""{"type":"object","properties":{"epochMillis":{"type":"integer"}},"required":["epochMillis"],"additionalProperties":false}""").jsonObject,
        risk = ToolRisk.READ, parallelSafe = true, idempotent = false, timeoutMs = 1_000)
    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        val now = nowMillis()
        return ToolResult("Host clock: $now", buildJsonObject { put("epochMillis", now) }, effect = ToolEffect.NONE)
    }
}
