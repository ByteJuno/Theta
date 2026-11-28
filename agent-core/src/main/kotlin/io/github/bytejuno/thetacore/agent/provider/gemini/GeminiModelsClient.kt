package io.github.bytejuno.thetacore.agent.provider.gemini

import io.github.bytejuno.thetacore.agent.protocol.Credentials
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiModelsClient
import io.github.bytejuno.thetacore.agent.transport.ModelAuthentication
import okhttp3.OkHttpClient

/** Bounded native models discovery with pageToken and generation capability filtering. */
class GeminiModelsClient {
    private val delegate = OpenAiModelsClient(OkHttpClient(), ModelAuthentication.GEMINI)
    suspend fun fetch(baseUrl: String, credentials: Credentials) = delegate.fetch(baseUrl, credentials)
}
