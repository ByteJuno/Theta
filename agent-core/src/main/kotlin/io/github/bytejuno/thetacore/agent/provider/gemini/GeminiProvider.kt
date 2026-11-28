package io.github.bytejuno.thetacore.agent.provider.gemini

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.serialization.json.*

class GeminiConfig(
    val providerId: String,
    baseUrl: String,
    models: Set<String> = emptySet(),
    val schemaProfile: SchemaProfile = SchemaProfile.BASIC,
) {
    private val validated = OpenAiChatConfig(providerId, baseUrl, models)
    val models = validated.models
    val base = validated.endpoint.newBuilder().removePathSegment(validated.endpoint.pathSize - 1)
        .removePathSegment(validated.endpoint.pathSize - 2).build()
    fun endpoint(model: String) = base.newBuilder().addPathSegment("models")
        .addPathSegment(model.removePrefix("models/").also {
            require(it.matches(Regex("[A-Za-z0-9._-]{1,256}"))) { "Invalid Gemini model" }
        } + ":streamGenerateContent").addQueryParameter("alt", "sse").build()
}

class GeminiProvider(
    private val config: GeminiConfig,
    private val transport: ModelTransport = OkHttpModelTransport(ModelAuthentication.GEMINI),
    mediaReader: MediaContentReader? = null,
    private val onSchemaDiagnostics: (ToolSchemaPreparation) -> Unit = {},
) : ModelProvider {
    private val codec = GeminiCodec(config, mediaReader)
    override fun contextConfigurationKey() = buildJsonObject {
        put("version", 1); put("protocol", "gemini"); put("provider", config.providerId)
        put("endpoint", config.base.toString()); put("schema", config.schemaProfile.name)
    }.toString()
    override fun canReplay(data: ReplayData, targetModel: String) = codec.canReplay(data, targetModel)
    override fun canReplayMessage(message: Message, targetModel: String) = codec.canReplayMessage(message, targetModel)
    override fun supportsMediaInput(kind: MediaKind, model: String) = kind == MediaKind.IMAGE
    override fun executionMode(request: ChatRequest) = if (request.extraBody.isEmpty())
        ModelExecutionMode.GENERATION_ONLY else ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
    override fun toolNamesForExecution(request: ChatRequest) =
        ToolSchemaProfiles().prepare(request.tools, config.schemaProfile).tools.map { it.name }.toSet()
    override fun chat(request: ChatRequest, auth: Credentials) = openAiEventFlow(
        config.endpoint(request.model).toString(),
        if (transport is OkHttpModelTransport) transport.withAuthentication(ModelAuthentication.GEMINI) else transport,
        auth, { codec.encode(request, onSchemaDiagnostics) }, { GeminiStream(request, codec) },
        decodeHttpError = ::geminiError, requireAuthentication = true,
    )
}

internal fun geminiError(error: JsonObject?, status: Int?, retryAfter: Long?): ModelError {
    val http = status ?: (error?.get("code") as? JsonPrimitive)?.intOrNull
    val message = error?.string("message").orEmpty()
    // Only a fixed parameter name escapes this boundary. Never retain echoed request values.
    val thinking = http == 400 && Regex("(?i)(thinking[_ .]?level|thinking[_ .]?config|thinking[_ .]?budget)").containsMatchIn(message) &&
        Regex("(?i)(not supported|unsupported|unknown|not allowed|not available|invalid)").containsMatchIn(message)
    val code = when {
        thinking -> ModelErrorCode.UNSUPPORTED_PARAMETER
        http == 400 && Regex("(?i)(input token|context|input length).*(exceed|too long)").containsMatchIn(message) -> ModelErrorCode.CONTEXT_TOO_LONG
        http == 401 || http == 403 -> ModelErrorCode.AUTH
        http == 404 -> ModelErrorCode.MODEL_NOT_FOUND
        http == 429 -> ModelErrorCode.RATE_LIMIT
        http == 503 -> ModelErrorCode.OVERLOADED
        http in 500..599 -> ModelErrorCode.SERVER
        http in 400..499 -> ModelErrorCode.INVALID_REQUEST
        else -> ModelErrorCode.UNKNOWN
    }
    return modelError(code, "Model request failed", http, retryAfter, if (thinking) "thinkingConfig" else null)
}
