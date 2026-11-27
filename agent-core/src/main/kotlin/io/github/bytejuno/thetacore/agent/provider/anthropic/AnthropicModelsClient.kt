package io.github.bytejuno.thetacore.agent.provider.anthropic

import io.github.bytejuno.thetacore.agent.protocol.Credentials
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiModelsClient
import io.github.bytejuno.thetacore.agent.transport.ModelAuthentication
import okhttp3.OkHttpClient

/** Reuses bounded, cancellable, redirect-free discovery with native authentication and pagination. */
class AnthropicModelsClient {
    private val delegate = OpenAiModelsClient(OkHttpClient(), ModelAuthentication.ANTHROPIC)
    suspend fun fetch(baseUrl: String, credentials: Credentials) = delegate.fetch(baseUrl, credentials)
}
