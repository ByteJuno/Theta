package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

class OpenAiChatProvider(
    private val config: OpenAiChatConfig,
    private val transport: ModelTransport = OkHttpModelTransport(),
    private val mediaReader: MediaContentReader? = null,
    /** Request-scoped diagnostics for the host UI/log owner; must not throw or retain credentials. */
    private val onSchemaDiagnostics: (ToolSchemaPreparation) -> Unit = {},
) : ModelProvider {
    private val codec = OpenAiChatCodec(config, mediaReader)
    override fun contextConfigurationKey(): String = buildJsonObject {
        put("version", 1); put("provider", config.providerId); put("endpoint", config.endpoint.toString())
        put("maxTokens", config.compat.maxTokensField); put("reasoning", config.compat.reasoningField)
        put("replay", config.compat.replayReasoning); put("usage", config.compat.streamUsage)
        put("schema", config.schemaProfile.name); put("cache", config.compat.explicitCacheControl)
        if (!config.compat.supportsImageInput) put("imageInput", false)
    }.toString()
    override fun canReplay(data: ReplayData, targetModel: String): Boolean = codec.canReplay(data, targetModel)
    override fun canReplayMessage(message: Message, targetModel: String): Boolean =
        super.canReplayMessage(message, targetModel) &&
            message.parts.filterIsInstance<ContentPart.Reasoning>().all { !config.compat.replayReasoning || it.replay != null }
    override fun supportsMediaInput(kind: MediaKind, model: String): Boolean =
        kind == MediaKind.IMAGE && config.compat.supportsImageInput
    override fun executionMode(request: ChatRequest): ModelExecutionMode =
        if (request.extraBody.isEmpty()) ModelExecutionMode.GENERATION_ONLY else ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
    override fun toolNamesForExecution(request: ChatRequest): Set<String> =
        ToolSchemaProfiles().prepare(request.tools, config.schemaProfile).tools.map { it.name }.toSet()

    override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = openAiEventFlow(
        config.endpoint.toString(), transport, auth,
        { codec.encode(request, onSchemaDiagnostics) },
        { OpenAiChatStream(config, request, codec) },
    )
}
