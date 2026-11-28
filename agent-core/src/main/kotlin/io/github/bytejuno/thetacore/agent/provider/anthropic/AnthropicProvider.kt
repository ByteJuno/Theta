package io.github.bytejuno.thetacore.agent.provider.anthropic

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.serialization.json.*

class AnthropicConfig(
    val providerId: String,
    baseUrl: String,
    models: Set<String> = emptySet(),
    val schemaProfile: SchemaProfile = SchemaProfile.NO_TOP_LEVEL_COMBINATORS,
) {
    private val validated = OpenAiChatConfig(providerId, baseUrl, models)
    val models = validated.models
    val endpoint = validated.endpoint.newBuilder()
        .removePathSegment(validated.endpoint.pathSize - 1)
        .removePathSegment(validated.endpoint.pathSize - 2).addPathSegment("messages").build()
}

class AnthropicProvider(
    private val config: AnthropicConfig,
    private val transport: ModelTransport = OkHttpModelTransport(ModelAuthentication.ANTHROPIC),
    mediaReader: MediaContentReader? = null,
    private val onSchemaDiagnostics: (ToolSchemaPreparation) -> Unit = {},
) : ModelProvider {
    private val codec = AnthropicCodec(config, mediaReader)
    override fun contextConfigurationKey() = buildJsonObject {
        put("version", 1); put("protocol", "anthropic"); put("provider", config.providerId)
        put("endpoint", config.endpoint.toString()); put("schema", config.schemaProfile.name)
    }.toString()
    override fun canReplay(data: ReplayData, targetModel: String) = codec.canReplay(data, targetModel)
    override fun canReplayMessage(message: Message, targetModel: String) =
        super.canReplayMessage(message, targetModel) && message.parts.all {
            (it !is ContentPart.Reasoning || it.replay != null) &&
                (it.replay == null || it is ContentPart.Reasoning && message.role == Role.ASSISTANT) &&
                // Rejected malformed arguments stay in history/ledger. Anthropic cannot
                // encode them as tool_use.input, so ask the kernel for a checkpoint.
                (it !is ContentPart.ToolCall || runCatching {
                    Json.parseToJsonElement(it.argsJson) is JsonObject
                }.getOrDefault(false))
        }
    override fun supportsMediaInput(kind: MediaKind, model: String) = kind == MediaKind.IMAGE
    override fun executionMode(request: ChatRequest) = if (request.extraBody.isEmpty())
        ModelExecutionMode.GENERATION_ONLY else ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
    override fun toolNamesForExecution(request: ChatRequest) =
        ToolSchemaProfiles().prepare(request.tools, config.schemaProfile).tools.map { it.name }.toSet()
    override fun chat(request: ChatRequest, auth: Credentials) = openAiEventFlow(
        config.endpoint.toString(), if (transport is OkHttpModelTransport) transport.withAuthentication(ModelAuthentication.ANTHROPIC) else transport,
        auth, { codec.encode(request, onSchemaDiagnostics) },
        { AnthropicStream(request, codec) }, decodeHttpError = ::anthropicError, requireAuthentication = true,
    )
}

internal fun anthropicError(error: JsonObject?, status: Int?, retryAfter: Long?): ModelError {
    val type = error?.string("type")
    val inputTooLong = type == "invalid_request_error" &&
        error?.string("message")?.trim()?.startsWith("prompt is too long", ignoreCase = true) == true
    val code = if (inputTooLong) ModelErrorCode.CONTEXT_TOO_LONG else when (type) {
        "authentication_error", "permission_error" -> ModelErrorCode.AUTH
        "rate_limit_error" -> ModelErrorCode.RATE_LIMIT
        "overloaded_error" -> ModelErrorCode.OVERLOADED
        "api_error" -> ModelErrorCode.SERVER
        "not_found_error" -> ModelErrorCode.MODEL_NOT_FOUND
        "invalid_request_error", "request_too_large" -> ModelErrorCode.INVALID_REQUEST
        else -> when (status) {
            401, 403 -> ModelErrorCode.AUTH
            429 -> ModelErrorCode.RATE_LIMIT
            529, 503 -> ModelErrorCode.OVERLOADED
            in 500..599 -> ModelErrorCode.SERVER
            in 400..499 -> ModelErrorCode.INVALID_REQUEST
            else -> ModelErrorCode.UNKNOWN
        }
    }
    return modelError(code, "Model request failed", status, retryAfter)
}
