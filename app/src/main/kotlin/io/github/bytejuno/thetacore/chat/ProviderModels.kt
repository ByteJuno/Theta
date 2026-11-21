package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.ModelsRequestException
import io.github.bytejuno.thetacore.agent.provider.openai.validApiKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ProviderModelsState(
    val loading: Boolean = false,
    val models: List<String>? = null,
    val failure: ModelsRequestException.Failure? = null,
)

/** UI-thread confined. Revision also rejects late results from non-cooperative clients. */
internal class ProviderModels(
    private val scope: CoroutineScope,
    private val fetch: suspend (String, String) -> List<String>,
) {
    private val current = MutableStateFlow(ProviderModelsState())
    val state = current.asStateFlow()
    private var revision = 0L
    private var job: Job? = null

    fun invalidate() {
        revision++
        job?.cancel(); job = null
        current.value = ProviderModelsState()
    }

    fun load(endpoint: String, secret: String) {
        invalidate()
        if (!validApiKey(secret)) {
            current.value = ProviderModelsState(failure = ModelsRequestException.Failure.INVALID_SETTINGS)
            return
        }
        val requested = revision
        current.value = ProviderModelsState(loading = true)
        job = scope.launch {
            try {
                val models = fetch(endpoint, secret)
                if (revision == requested) current.value = ProviderModelsState(models = models)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (revision == requested) current.value = ProviderModelsState(failure =
                    (error as? ModelsRequestException)?.failure ?: ModelsRequestException.Failure.NETWORK)
            } finally {
                if (revision == requested) {
                    current.value = current.value.copy(loading = false)
                    job = null
                }
            }
        }
    }
}
