package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.agent.transport.OkHttpModelTransport
import java.util.Collections

/** Non-secret settings. No credentials, headers or arbitrary JSON credential fields. */
class OpenAiProviderSettings(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val parameters: OpenAiParameters = OpenAiParameters(),
    val compat: OpenAiChatConfig.Compat = OpenAiChatConfig.Compat(),
) {
    init {
        require(providerId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid provider identifier" }
        require(model.isNotBlank() && model.length <= 256) { "Invalid model name" }
        require(baseUrl.length <= 4096) { "Invalid provider URL" }
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

/** Bearer tokens must be nonempty printable ASCII with no whitespace/header injection. */
fun validApiKey(token: String): Boolean = token.length in 1..16384 && token.all { it in '!'..'~' }

/** One snapshot per NEW run. Pass target() and request() to AgentRunLoop, never re-read a store
 * inside a running loop. Updates/deletion affect future snapshots only. Adapters still request
 * Credentials on every attempt (2.12), rather than caching them in the provider. */
class OpenAiRunSnapshot(val settings: OpenAiProviderSettings, private val credentials: ApiKeyCredentials) {
    fun target(transport: ModelTransport = OkHttpModelTransport()): ModelTarget =
        ModelTarget(settings.providerId, OpenAiChatProvider(settings.adapterConfig(), transport), credentials)

    fun request(configure: ChatRequestBuilder.() -> Unit = {}): ChatRequest = chatRequest(settings.model) {
        configure()
        settings.parameters.applyTo(this)
    }
    override fun toString() = "OpenAiRunSnapshot([configuration], [REDACTED])"
}
