package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.provider.openai.ModelsRequestException
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class ProviderModelsTest {
    @Test fun explicitSavedKeyReuseKeepsErrorsSanitizedAndInvalidatesOnProfileChange() = runBlocking {
        var calls = 0
        val models = ProviderModels(this) { _, key ->
            calls++; assertEquals("", key)
            throw ProviderSettingsException(ProviderSettingsException.Failure.ENDPOINT_CHANGED)
        }
        models.load("endpoint", ""); yield()
        assertEquals(0, calls)
        models.load("endpoint", "", reuseSavedKey = true); yield()
        assertEquals(1, calls)
        assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, models.state.value.settingsFailure)
        assertNull(models.state.value.failure)
        models.invalidate()
        assertEquals(ProviderModelsState(), models.state.value)
        models.load("endpoint", " ", reuseSavedKey = true); yield()
        assertEquals(1, calls) // Whitespace is not an absent key.
        assertEquals(ModelsRequestException.Failure.INVALID_SETTINGS, models.state.value.failure)
    }

    @Test fun malformedKeysFailBeforeCredentialConstructionAndCanBeCorrected() = runBlocking {
        var calls = 0
        val models = ProviderModels(this) { _, _ -> calls++; listOf("model") }
        for (key in listOf("", "synthetic-key ", "key\r\n", "密钥", "k".repeat(16_385))) {
            models.load("endpoint", key); yield()
            assertEquals(ProviderModelsState(failure = ModelsRequestException.Failure.INVALID_SETTINGS), models.state.value)
            assertEquals(0, calls)
        }
        models.load("endpoint", "synthetic-key"); yield()
        assertEquals(1, calls)
        assertEquals(ProviderModelsState(models = listOf("model")), models.state.value)
    }

    @Test fun noRequestUntilExplicitLoadAndFailureAllowsRetry() = runBlocking {
        var calls = 0
        val models = ProviderModels(this) { _, _ ->
            calls++
            if (calls == 1) throw ModelsRequestException(ModelsRequestException.Failure.HTTP)
            listOf("manual-model")
        }
        assertEquals(0, calls); assertEquals(ProviderModelsState(), models.state.value)
        models.load("endpoint", "synthetic-key"); yield()
        assertEquals(ModelsRequestException.Failure.HTTP, models.state.value.failure)
        assertFalse(models.state.value.loading)
        models.load("endpoint", "synthetic-key"); yield()
        assertEquals(listOf("manual-model"), models.state.value.models)
        assertNull(models.state.value.failure)
    }

    @Test fun changedProfileEndpointOrCloseInvalidatesEvenLateNonCooperativeResults() = runBlocking {
        for (lateFailure in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            val models = ProviderModels(this) { endpoint, _ ->
                if (endpoint == "old") {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                } else listOf("new-model")
            }
            models.load("old", "synthetic-key"); entered.await()
            models.invalidate(); yield()
            assertTrue(cancelled); assertEquals(ProviderModelsState(), models.state.value)
            lateinit var oldRequest: Continuation<List<String>>
            val late = ProviderModels(this) { endpoint, _ ->
                // Deliberately non-cancellable callback: exercise the revision guard itself.
                if (endpoint == "old") suspendCoroutine { oldRequest = it } else listOf("fresh")
            }
            late.load("old", "synthetic-key"); yield()
            late.invalidate(); late.load("new", "new-key"); yield()
            if (lateFailure) oldRequest.resumeWithException(ModelsRequestException(ModelsRequestException.Failure.HTTP))
            else oldRequest.resume(listOf("stale"))
            yield(); yield()
            assertEquals(ProviderModelsState(models = listOf("fresh")), late.state.value)
            late.invalidate()
            assertEquals(ProviderModelsState(), late.state.value)
        }
    }

    @Test fun cancelledOlderRequestDoesNotClearNewLoadingState() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val models = ProviderModels(this) { endpoint, _ ->
            if (endpoint == "old") awaitCancellation() else { release.await(); listOf("new") }
        }
        models.load("old", "synthetic-key"); yield()
        models.load("new", "synthetic-key"); yield()
        assertTrue(models.state.value.loading); assertNull(models.state.value.failure)
        release.complete(Unit); yield()
        assertEquals(listOf("new"), models.state.value.models)
    }
}
