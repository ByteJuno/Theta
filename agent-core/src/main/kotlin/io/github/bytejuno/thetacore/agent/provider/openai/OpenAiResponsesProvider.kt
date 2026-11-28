package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

class OpenAiResponsesProvider(
    private val config: OpenAiResponsesConfig,
    private val transport: ModelTransport = OkHttpModelTransport(),
    mediaReader: MediaContentReader? = null,
    private val onSchemaDiagnostics: (ToolSchemaPreparation) -> Unit = {},
) : ModelProvider {
    private val codec = OpenAiResponsesCodec(config, mediaReader)
    override fun contextConfigurationKey(): String = buildJsonObject {
        put("version", 1); put("protocol", "openai-responses"); put("provider", config.providerId)
        put("endpoint", config.endpoint.toString()); put("schema", config.schemaProfile.name)
        put("imageInput", config.supportsImageInput)
    }.toString()
    override fun canReplay(data: ReplayData, targetModel: String) = codec.canReplay(data, targetModel)
    override fun canReplayMessage(message: Message, targetModel: String): Boolean =
        super.canReplayMessage(message, targetModel) && message.parts.all {
            (it !is ContentPart.Reasoning || it.replay != null) &&
                (it.replay == null || it is ContentPart.Reasoning && message.role == Role.ASSISTANT)
        }
    override fun supportsMediaInput(kind: MediaKind, model: String) = kind == MediaKind.IMAGE && config.supportsImageInput
    override fun supportsToolResultMedia(kind: MediaKind, model: String) = supportsMediaInput(kind, model)
    override fun executionMode(request: ChatRequest) = if (request.extraBody.isEmpty())
        ModelExecutionMode.GENERATION_ONLY else ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
    override fun toolNamesForExecution(request: ChatRequest) =
        ToolSchemaProfiles().prepare(request.tools, config.schemaProfile).tools.map { it.name }.toSet()
    override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = openAiEventFlow(
        config.endpoint.toString(), transport, auth, { codec.encode(request, onSchemaDiagnostics) },
        { OpenAiResponsesStream(request, codec) },
        normalizeErrorParameter = { param ->
            // Only known schema fields map to the kernel's protocol-neutral recovery parameter.
            // Never accept arbitrary paths or change Chat's error handling.
            if (request.responseSchema != null && param in setOf("text", "text.format", "text.format.type",
                    "text.format.schema", "text.format.name", "text.format.strict", "text.format.description"))
                "response_format" else param
        },
    )
}
