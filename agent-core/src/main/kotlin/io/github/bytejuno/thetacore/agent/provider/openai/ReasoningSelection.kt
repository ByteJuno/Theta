package io.github.bytejuno.thetacore.agent.provider.openai

/** A notice belongs to the admitted run, not to each request in its tool loop. */
data class ReasoningLevelAdjustment(val requested: String, val effective: String?)

private val effortOrder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

/** Unknown capabilities are permissive. Never raise effort to satisfy a capability list.
 * Custom names have no inferred ordering; when no lower level is known, use the model default. */
internal fun resolveReasoningLevel(requested: String?, supported: List<String>?): String? {
    if (requested == null || supported == null || requested in supported) return requested
    val rank = effortOrder.indexOf(requested)
    return effortOrder.take(rank.coerceAtLeast(0)).lastOrNull { it in supported }
}

internal fun OpenAiProviderSettings.resolveReasoning(): OpenAiProviderSettings {
    val effective = resolveReasoningLevel(parameters.reasoningLevel, reasoningLevels)
    if (effective == parameters.reasoningLevel) return this
    val p = parameters
    return OpenAiProviderSettings(providerId, baseUrl, model,
        OpenAiParameters(p.maxOutputTokens, p.temperature, p.topP, effective, p.parallelToolCalls, p.stopSequences),
        compat, systemPrompt, reasoningLevels)
}
