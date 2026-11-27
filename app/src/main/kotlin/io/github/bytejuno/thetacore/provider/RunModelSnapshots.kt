package io.github.bytejuno.thetacore.provider

import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot

/** Memory-only run configuration. Released with the tool catalog after actual worker exit. */
internal class RunModelSnapshots {
    private val images = mutableMapOf<String, OpenAiRunSnapshot?>()
    @Synchronized fun bind(runId: String, snapshot: OpenAiRunSnapshot) { images[runId] = snapshot.imageSnapshot }
    @Synchronized fun release(runId: String) { images.remove(runId) }
    @Synchronized fun image(runId: String, fallback: () -> OpenAiRunSnapshot): OpenAiRunSnapshot =
        if (!images.containsKey(runId)) fallback() else images[runId]
            ?: throw ProviderSettingsException(ProviderSettingsException.Failure.NOT_CONFIGURED)
}
