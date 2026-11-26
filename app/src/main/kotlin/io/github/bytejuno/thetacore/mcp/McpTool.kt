package io.github.bytejuno.thetacore.mcp

import io.github.bytejuno.thetacore.agent.mcp.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*
import java.util.Base64

/** Host adapter: remote annotations never lower risk or assert idempotence. */
internal class McpTool(private val client: McpClient, definition: McpToolDefinition) : Tool {
    private val parameters = definition.headers
    override val id = definition.name
    override val spec = ToolSpec(definition.name, definition.description, definition.inputSchema,
        definition.outputSchema, risk = ToolRisk.WRITE)
    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        val result = client.call(id, args, env.callId, mcpParameterHeaders(parameters, args), env::progress)
        val content = result["content"]?.jsonArray.orEmpty().map { item ->
            val obj = item.jsonObject
            when (obj.getValue("type").jsonPrimitive.content) {
                "text" -> {
                    val text = obj.getValue("text").jsonPrimitive.content
                    ToolContent.Text(text)
                }
                "image", "audio" -> ToolContent.Media(
                    if (obj.getValue("type").jsonPrimitive.content == "image") ToolMediaKind.IMAGE else ToolMediaKind.AUDIO,
                    obj.getValue("mimeType").jsonPrimitive.content,
                    Base64.getDecoder().decode(obj.getValue("data").jsonPrimitive.content))
                // JSON remains data. The normalizer durably stores the full original in BlobStore,
                // including short resources, and bounds the model-facing representation.
                "resource", "resource_link" -> {
                    val summary = "[MCP resource]\n$obj"
                    ToolContent.Text(summary)
                }
                else -> throw McpException()
            }
        }
        return ToolResult(structured = result["structuredContent"],
            isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true).withContent(content)
    }
}
