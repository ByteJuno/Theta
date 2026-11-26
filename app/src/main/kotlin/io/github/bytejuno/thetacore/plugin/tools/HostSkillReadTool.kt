package io.github.bytejuno.thetacore.plugin.tools

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class HostSkillReadTool(private val read: suspend (String, String, String) -> String) : Tool {
    override val id = "skill_read"
    override val spec = ToolSpec("skill_read", "Read an enabled data skill by pluginId/skillName. Optional path is a .md/.txt document relative to that skill directory; default SKILL.md. Large output is retrieved with blob_read.",
        Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","minLength":3,"maxLength":330},"path":{"type":"string","minLength":1,"maxLength":257}},"required":["id"],"additionalProperties":false}""").jsonObject,
        risk = ToolRisk.READ, idempotent = true, parallelSafe = true)

    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = try {
        ToolResult(read(env.runId, args.getValue("id").jsonPrimitive.content,
            args["path"]?.jsonPrimitive?.content ?: "SKILL.md"), effect = ToolEffect.NONE)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        ToolResult("Skill document unavailable for this run. Its snapshotted version may be missing or revoked. Check the id/path and enabled package, then start a new run to use the current version.",
            isError = true, effect = ToolEffect.NONE)
    }
}
