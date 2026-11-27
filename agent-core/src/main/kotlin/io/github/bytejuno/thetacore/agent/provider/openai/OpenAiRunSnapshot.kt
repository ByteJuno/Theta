package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.agent.transport.OkHttpModelTransport
import io.github.bytejuno.thetacore.agent.transport.ModelAuthentication
import io.github.bytejuno.thetacore.agent.provider.anthropic.*
import java.util.Collections

const val MAX_SYSTEM_PROMPT_CHARS = 32_000

/** Non-secret settings. No credentials, headers or arbitrary JSON credential fields. */
class OpenAiProviderSettings(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val parameters: OpenAiParameters = OpenAiParameters(),
    val compat: OpenAiChatConfig.Compat = OpenAiChatConfig.Compat(),
    systemPrompt: String? = null,
    reasoningLevels: List<String>? = null,
) {
    val systemPrompt: String? = systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
    val reasoningLevels: List<String>? = reasoningLevels?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid provider identifier" }
        require(model.isNotBlank() && model.length <= 256) { "Invalid model name" }
        require(baseUrl.length <= 4096) { "Invalid provider URL" }
        require(this.reasoningLevels == null || (this.reasoningLevels.size <= 16 &&
            this.reasoningLevels.distinct().size == this.reasoningLevels.size &&
            this.reasoningLevels.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })) { "Invalid reasoning levels" }
        require(this.systemPrompt == null || this.systemPrompt.length <= MAX_SYSTEM_PROMPT_CHARS) {
            "Invalid system prompt length"
        }
        adapterConfig()
    }
    fun adapterConfig() = OpenAiChatConfig(providerId, baseUrl, setOf(model), compat)
    override fun toString() = "OpenAiProviderSettings([configuration])"
}

class OpenAiParameters(
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val reasoningLevel: String? = null,
    val parallelToolCalls: Boolean? = null,
    stopSequences: List<String> = emptyList(),
) {
    val stopSequences: List<String> = Collections.unmodifiableList(ArrayList(stopSequences))
    init {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "Invalid output limit" }
        require(temperature == null || (temperature.isFinite() && temperature in 0.0..2.0)) { "Invalid temperature" }
        require(topP == null || (topP.isFinite() && topP in 0.0..1.0)) { "Invalid top-p" }
        require(reasoningLevel == null || reasoningLevel.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid reasoning level" }
        require(this.stopSequences.size <= 16 && this.stopSequences.all { it.length <= 4096 }) { "Invalid stop sequences" }
    }
    internal fun applyTo(builder: ChatRequestBuilder) = with(builder) {
        maxOutputTokens = this@OpenAiParameters.maxOutputTokens
        temperature = this@OpenAiParameters.temperature
        topP = this@OpenAiParameters.topP
        reasoningLevel = this@OpenAiParameters.reasoningLevel
        parallelToolCalls = this@OpenAiParameters.parallelToolCalls
        stopSequences = this@OpenAiParameters.stopSequences
    }
    override fun toString() = "OpenAiParameters([configuration])"
}

/** Fixed in memory at admission, never serialized into history, journals or invocation records. */
class ApiKeyCredentials(private val token: String) : Credentials {
    init { require(validApiKey(token)) { "Invalid API key" } }
    override suspend fun accessToken(): String = token
    override fun toString() = "ApiKeyCredentials([REDACTED])"
}

/** Explicit unauthenticated mode for local endpoints; never inferred from a blank password field. */
object NoAuthCredentials : Credentials {
    override suspend fun accessToken(): String = ""
    override fun toString() = "NoAuthCredentials"
}

/** Bearer tokens must be nonempty printable ASCII with no whitespace/header injection. */
fun validApiKey(token: String): Boolean = token.length in 1..16384 && token.all { it in '!'..'~' }

/** One snapshot per NEW run. Pass target() and request() to AgentRunLoop, never re-read a store
 * inside a running loop. Updates/deletion affect future snapshots only. Adapters still request
 * Credentials on every attempt (2.12), rather than caching them in the provider. */
class OpenAiRunSnapshot(
    settings: OpenAiProviderSettings,
    private val credentials: Credentials,
    val protocol: String = "openai-chat",
    val imageSnapshot: OpenAiRunSnapshot? = null,
) {
    private val requestedSettings = settings
    val settings: OpenAiProviderSettings = settings.resolveReasoning()
    val reasoningAdjustment: ReasoningLevelAdjustment? = settings.parameters.reasoningLevel
        ?.takeIf { it != this.settings.parameters.reasoningLevel }
        ?.let { ReasoningLevelAdjustment(it, this.settings.parameters.reasoningLevel) }
    constructor(settings: OpenAiProviderSettings, credentials: ApiKeyCredentials) : this(settings, credentials as Credentials)

    init { require(protocol in setOf("openai-chat", "openai-responses", "anthropic")) { "Protocol is not enabled" } }

    fun withImageSnapshot(image: OpenAiRunSnapshot?) = OpenAiRunSnapshot(requestedSettings, credentials, protocol, image)

    fun target(
        transport: ModelTransport = OkHttpModelTransport(if (protocol == "anthropic") ModelAuthentication.ANTHROPIC else ModelAuthentication.BEARER),
        mediaReader: MediaContentReader? = null,
    ): ModelTarget = when (protocol) {
        "openai-chat" -> ModelTarget(settings.providerId, OpenAiChatProvider(settings.adapterConfig(), transport, mediaReader), credentials)
        "openai-responses" -> ModelTarget(settings.providerId, OpenAiResponsesProvider(responsesConfig(), transport, mediaReader), credentials)
        "anthropic" -> ModelTarget(settings.providerId, AnthropicProvider(anthropicConfig(), transport, mediaReader), credentials)
        else -> error("Protocol is not enabled")
    }

    private fun responsesConfig() = OpenAiResponsesConfig(settings.providerId, settings.baseUrl, setOf(settings.model))
    private fun anthropicConfig() = AnthropicConfig(settings.providerId, settings.baseUrl, setOf(settings.model))

    /** Use the same wire representation for admission and submission, including opaque replay. */
    fun requestByteEstimator(mediaReader: MediaContentReader? = null): (ChatRequest) -> Long = when (protocol) {
        "openai-chat" -> OpenAiChatCodec(settings.adapterConfig(), mediaReader)::estimateRequestBytes
        "openai-responses" -> OpenAiResponsesCodec(responsesConfig(), mediaReader)::estimateRequestBytes
        "anthropic" -> AnthropicCodec(anthropicConfig(), mediaReader)::estimateRequestBytes
        else -> error("Protocol is not enabled")
    }

    fun request(configure: ChatRequestBuilder.() -> Unit = {}): ChatRequest = chatRequest(settings.model) {
        settings.systemPrompt?.let { system = it }
        configure()
        settings.parameters.applyTo(this)
    }
    override fun toString() = "OpenAiRunSnapshot([configuration], [REDACTED])"
}
