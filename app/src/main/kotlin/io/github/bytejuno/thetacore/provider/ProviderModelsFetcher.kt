package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.protocol.Credentials
import io.github.bytejuno.thetacore.agent.provider.anthropic.AnthropicModelsClient
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiModelsClient

internal fun providerModelsFetcher(protocol: String): suspend (String, Credentials) -> List<String> = when (protocol) {
    "anthropic" -> AnthropicModelsClient()::fetch
    "gemini" -> io.github.bytejuno.thetacore.agent.provider.gemini.GeminiModelsClient()::fetch
    "openai-chat", "openai-responses" -> OpenAiModelsClient()::fetch
    else -> throw IllegalArgumentException("Protocol is not enabled")
}
