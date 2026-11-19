package io.github.bytejuno.thetacore.plugin.tools

import io.github.bytejuno.thetacore.agent.ledger.InvocationLedger
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Bounded, session-scoped retrieval. Registered before catalog freeze, never injected into old runs. */
internal class HostBlobReadTool(private val ledger: InvocationLedger) : Tool {
    override val id = "blob_read"
    override val spec = ToolSpec("blob_read", "Read tool-result blob bytes from this session, including earlier runs, in base64 chunks. Pass id and byte offset; follow nextOffset until null.",
        Json.parseToJsonElement("""{"type":"object","properties":{"id":{"type":"string","minLength":64,"maxLength":64},"offset":{"type":"integer","minimum":0}},"required":["id","offset"],"additionalProperties":false}""").jsonObject,
        risk = ToolRisk.READ, idempotent = true, parallelSafe = true)
    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        // An unrepresentable integer is outside every blob. Keep ownership checks first.
        val offset = args.getValue("offset").jsonPrimitive.longOrNull ?: -1L
        val (ref, bytes) = try {
            ledger.readSessionBlob(env.sessionId, args.getValue("id").jsonPrimitive.content, offset)
        } catch (error: io.github.bytejuno.thetacore.agent.ledger.BlobReadException) {
            return ToolResult(text = requireNotNull(error.message),
                isError = true, effect = ToolEffect.NONE)
        }
        // Plain text avoids recursively archiving the retrieval response as another raw envelope.
        return ToolResult(text = buildJsonObject {
            put("id", ref.id); put("mimeType", ref.mimeType); put("sizeBytes", ref.sizeBytes)
            put("offset", offset); put("encoding", "base64")
            put("data", Base64.getEncoder().encodeToString(bytes))
            put("nextOffset", if (offset + bytes.size < ref.sizeBytes) JsonPrimitive(offset + bytes.size) else JsonNull)
        }.toString(), effect = ToolEffect.NONE)
    }
}
