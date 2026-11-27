package io.github.bytejuno.thetacore.root

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*

/** All authorization is provided by the existing frozen catalog and durable invocation ledger. */
internal class HostRootTool(private val bridge: RootBridge) : Tool {
    override val id = "root_shell"
    override val spec = ToolSpec(id,
        "Run an explicitly authorized root shell command on this device. Non-idempotent; never retry an unknown outcome. " +
            "No detached daemons or jobs that escape the process group. Output capture is bounded to 1 MiB per stream; " +
            "larger captured results are available through the session blob reader.",
        Json.parseToJsonElement("""{"type":"object","properties":{"command":{"type":"string","minLength":1,"maxLength":16384},"timeoutMs":{"type":"integer","minimum":1,"maximum":120000}},"required":["command"],"additionalProperties":false}""").jsonObject,
        risk = ToolRisk.ROOT, idempotent = false, parallelSafe = false,
        exclusiveResource = "root-shell", timeoutMs = ROOT_MAX_TIMEOUT_MS + 2_000)

    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        val command = args["command"]?.jsonPrimitive?.takeIf { it.isString }?.content
        val timeout = args["timeoutMs"]?.jsonPrimitive?.takeIf { !it.isString }?.longOrNull ?: 30_000
        require(args.keys.all { it == "command" || it == "timeoutMs" })
        require(!command.isNullOrBlank() && command.length <= 16_384 && '\u0000' !in command)
        require("timeoutMs" !in args || args["timeoutMs"]?.jsonPrimitive?.let { !it.isString && it.longOrNull != null } == true)
        val result = bridge.execute(command, timeout)
        val completed = result.outcome == RootOutcome.COMPLETED
        val effect = when {
            result.outcome == RootOutcome.TIMED_OUT || result.outcome == RootOutcome.CANCELLED -> ToolEffect.UNKNOWN
            !result.commandStarted -> ToolEffect.NONE
            completed && result.exitCode == 0 -> ToolEffect.APPLIED
            else -> ToolEffect.UNKNOWN
        }
        // The host normalizer stores this complete bounded capture in the session BlobStore,
        // applies its 32 KiB model budget and emits the frozen blob_read retrieval alias.
        return ToolResult(structured = buildJsonObject {
            put("status", result.outcome.name.lowercase())
            put("exitCode", result.exitCode?.let(::JsonPrimitive) ?: JsonNull)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("outputTruncated", result.outputTruncated)
            put("captureLimitBytesPerStream", ROOT_OUTPUT_LIMIT)
            put("actualExit", result.actualExit)
        }, isError = !completed || result.exitCode != 0, effect = effect)
    }
}
