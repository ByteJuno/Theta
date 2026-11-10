package io.github.bytejuno.thetacore.agent.provider.openai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Schema is already mapped by the run catalog; this adapter never silently drops constraints. */
enum class SchemaProfile { FULL, NO_TOP_LEVEL_COMBINATORS, BASIC }
class OpenAiChatConfig(
    val providerId: String,
    baseUrl: String,
    models: Set<String> = emptySet(),
    val compat: Compat = Compat(),
) {
    val models: Set<String> = java.util.Collections.unmodifiableSet(HashSet(models))
    val endpoint: HttpUrl
    init {
        require(providerId.isNotBlank())
        // URL parser exceptions may embed userinfo/query credentials: never preserve their cause.
        val base = try { baseUrl.toHttpUrl() } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid provider URL")
        }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        endpoint = base.newBuilder().apply {
            if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1)
            addPathSegments("chat/completions")
        }.build()
    }
    class Compat(
        val maxTokensField: String = "max_completion_tokens",
        val reasoningField: String? = null,
        val replayReasoning: Boolean = false,
        val streamUsage: Boolean = true,
        val schemaProfile: SchemaProfile = SchemaProfile.FULL,
        /** Explicit opt-in for compatible endpoints accepting content-block cache_control.
         * Standard OpenAI Chat has automatic caching and MUST leave this false. */
        val explicitCacheControl: Boolean = false,
    ) {
        init {
            require(maxTokensField in setOf("max_tokens", "max_completion_tokens"))
            require(reasoningField == null || reasoningField.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            require(reasoningField !in setOf("role", "content", "tool_calls", "refusal", "function_call"))
            require(!replayReasoning || reasoningField != null)
        }
    }
}
