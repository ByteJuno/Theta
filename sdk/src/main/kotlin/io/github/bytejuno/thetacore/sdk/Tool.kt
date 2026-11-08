package io.github.bytejuno.thetacore.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** First S1 invocation subset, not the full PROTOCOL agent, approval ledger or MCP API. */
interface Tool : Extension {
    val spec: ToolSpec
    suspend fun call(args: JsonObject, env: ToolEnv): ToolResult
}

enum class ToolRisk { READ, WRITE, DEVICE_CONTROL, ROOT }
enum class ToolEffect { NONE, APPLIED, UNKNOWN }

/** Final envelope: reading these fields never invokes plugin getters. Schema validation is S2. */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val risk: ToolRisk = ToolRisk.READ,
    val idempotent: Boolean = false,
    val parallelSafe: Boolean = false,
    val exclusiveResource: String? = null,
    val timeoutMs: Long? = null,
)

/** Text-only S1 results. No media/blob store or model-message normalization yet. */
data class ToolResult(
    val text: String = "",
    val structured: JsonElement? = null,
    val isError: Boolean = false,
    val effect: ToolEffect? = null,
)

/** Immutable host identifiers and explicit host hooks. Unattended calls cannot prompt. */
class ToolEnv(
    val runId: String,
    val sessionId: String,
    val callId: String,
    val unattended: Boolean,
    private val onProgress: (String, Float?) -> Unit,
    private val onApproval: suspend (String, ToolRisk) -> Boolean = { _, _ -> false },
    private val onInput: suspend (String, JsonObject) -> JsonObject? = { _, _ -> null },
) {
    fun progress(text: String, fraction: Float? = null) = onProgress(text, fraction)
    suspend fun requestApproval(summary: String, risk: ToolRisk): Boolean =
        if (unattended) false else onApproval(summary, risk)
    suspend fun requestInput(prompt: String, schema: JsonObject): JsonObject? =
        if (unattended) null else onInput(prompt, schema)
}
