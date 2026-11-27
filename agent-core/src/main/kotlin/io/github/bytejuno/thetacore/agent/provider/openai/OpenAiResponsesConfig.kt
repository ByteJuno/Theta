package io.github.bytejuno.thetacore.agent.provider.openai

/** Responses shares the validated origin, credentials and transport with Chat, not its compat flags. */
class OpenAiResponsesConfig(
    val providerId: String,
    baseUrl: String,
    models: Set<String> = emptySet(),
    val schemaProfile: SchemaProfile = SchemaProfile.FULL,
    val supportsImageInput: Boolean = true,
) {
    private val validated = OpenAiChatConfig(providerId, baseUrl, models)
    val models: Set<String> = validated.models
    val endpoint = validated.endpoint.newBuilder()
        .removePathSegment(validated.endpoint.pathSize - 1)
        .removePathSegment(validated.endpoint.pathSize - 2)
        .addPathSegment("responses").build()
}
