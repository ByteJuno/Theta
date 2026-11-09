package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.*

class RunRecoveryTest {
    @Test fun everyErrorCodeHasBoundedGenerationOnlyPolicyAndServerGate() {
        val expected = mapOf(
            ModelErrorCode.AUTH to RecoveryAction.REFRESH_CREDENTIALS,
            ModelErrorCode.RATE_LIMIT to RecoveryAction.ROTATE_CREDENTIALS,
            ModelErrorCode.QUOTA to RecoveryAction.FALLBACK,
            ModelErrorCode.OVERLOADED to RecoveryAction.RETRY,
            ModelErrorCode.SERVER to RecoveryAction.RETRY,
            ModelErrorCode.NETWORK to RecoveryAction.RETRY,
            ModelErrorCode.TIMEOUT to RecoveryAction.RETRY,
            ModelErrorCode.CONTEXT_TOO_LONG to RecoveryAction.SHRINK_REQUEST,
            ModelErrorCode.UNSUPPORTED_PARAMETER to RecoveryAction.REMOVE_PARAMETER,
            ModelErrorCode.INVALID_REQUEST to null, ModelErrorCode.MODEL_NOT_FOUND to null,
            ModelErrorCode.CONTENT_BLOCKED to RecoveryAction.FALLBACK,
            ModelErrorCode.PROTOCOL to RecoveryAction.RETRY, ModelErrorCode.UNKNOWN to RecoveryAction.RETRY)
        assertEquals(ModelErrorCode.entries.toSet(), expected.keys)
        for ((code, action) in expected) {
            val error = modelError(code, "test", param = "temperature")
            assertEquals(action, recoveryDecision(error, ModelExecutionMode.GENERATION_ONLY, 0).action, code.name)
            assertNull(recoveryDecision(error, ModelExecutionMode.GENERATION_ONLY, 4).action, code.name)
            val server = recoveryDecision(error, ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS, 0)
            assertNull(server.action); assertTrue(server.serverUnknown)
        }
        assertNull(recoveryDecision(modelError(ModelErrorCode.AUTH, "x"), ModelExecutionMode.GENERATION_ONLY, 1).action)
        assertEquals(listOf(1000L, 2000L, 4000L), (0..2).map {
            recoveryDecision(modelError(ModelErrorCode.SERVER, "x"), ModelExecutionMode.GENERATION_ONLY, it).delayMs })
        assertEquals(RecoveryAction.FALLBACK, recoveryDecision(modelError(ModelErrorCode.SERVER, "x"), ModelExecutionMode.GENERATION_ONLY, 3).action)
    }

    @Test fun retryableFailuresRetryExactHistoryWithFreshIdsAndKeepPartialEvidence() = runTest {
        for (code in listOf(ModelErrorCode.SERVER, ModelErrorCode.OVERLOADED, ModelErrorCode.NETWORK,
            ModelErrorCode.TIMEOUT, ModelErrorCode.PROTOCOL, ModelErrorCode.UNKNOWN, ModelErrorCode.RATE_LIMIT)) {
            val partial = listOf(Events.started("r", "m"), Events.text(0, "discard"), Events.end(0),
                Events.usage(tokenUsage(outputTokens = 7)), Events.failed(modelError(code, "test", retryAfterMs = 17)))
            val f = RunFixture()
            val p = ScriptProvider(listOf(partial, answer()))
            assertEquals(RunState.COMPLETED, f.loop(p).run().state, code.name)
            assertEquals(2, p.requests.size, code.name)
            assertNotEquals(p.requests[0].requestId, p.requests[1].requestId)
            assertEquals(p.requests[0].messages, p.requests[1].messages)
            assertEquals(7L, f.journal.attempts().first().usage!!.outputTokens)
            assertEquals("discard", (f.journal.attempts().first().partial.parts.single() as ContentPart.Text).text)
            assertEquals(2, f.history.snapshot().messages.size)
        }
    }

    @Test fun noImplicitRefreshFallbackOrShrinkAndNoRetryForInvalidRequest() = runTest {
        for (code in listOf(ModelErrorCode.AUTH, ModelErrorCode.QUOTA, ModelErrorCode.CONTENT_BLOCKED,
            ModelErrorCode.CONTEXT_TOO_LONG, ModelErrorCode.UNSUPPORTED_PARAMETER,
            ModelErrorCode.INVALID_REQUEST, ModelErrorCode.MODEL_NOT_FOUND)) {
            val f = RunFixture()
            val p = ScriptProvider(listOf(failure(code, "temperature"), answer()))
            assertNotEquals(RunState.COMPLETED, f.loop(p).run().state, code.name)
            assertEquals(1, p.requests.size, code.name)
            assertEquals(1, f.history.snapshot().messages.size)
        }
    }

    @Test fun allServerErrorsPauseBeforeInvokingRecovery() = runTest {
        for (code in ModelErrorCode.entries) {
            val p = ScriptProvider(listOf(failure(code)), ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
            val strategy = RecoveryStrategy { _, _, _, _ -> error("must not run recovery") }
            val r = RunFixture().loop(p, strategy).run()
            assertTrue(r.serverExecutionUnknown, code.name); assertEquals(RunState.PAUSED, r.state)
            assertEquals(1, p.requests.size)
        }
    }

    @Test fun refreshOnceRateLimitWaitAndFiniteBackoff() = runTest {
        val f = RunFixture()
        val p = ScriptProvider(listOf(failure(ModelErrorCode.AUTH)))
        var refreshes = 0
        val strategy = RecoveryStrategy { action, _, target, request ->
            assertEquals(RecoveryAction.REFRESH_CREDENTIALS, action); refreshes++
            RecoveryProposal(target.copy(credentials = Credentials { "refreshed" }), request)
        }
        assertEquals(RunState.FAILED, f.loop(p, strategy).run().state)
        assertEquals(1, refreshes); assertEquals(2, p.requests.size)
        val limited = ScriptProvider(listOf(failure(ModelErrorCode.RATE_LIMIT, retryAfter = 234)))
        val before = testScheduler.currentTime
        assertEquals(RunState.FAILED, RunFixture().loop(limited).run().state)
        assertEquals(702L, testScheduler.currentTime - before); assertEquals(4, limited.requests.size)
        val server = ScriptProvider(listOf(failure(ModelErrorCode.SERVER)))
        val start = testScheduler.currentTime
        RunFixture().loop(server).run()
        assertEquals(7000L, testScheduler.currentTime - start); assertEquals(4, server.requests.size)
    }

    @Test fun parameterRemovalAndActualBudgetReductionAreRequired() = runTest {
        val f = RunFixture()
        val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "temperature"), answer()))
        val remove = RecoveryStrategy { _, _, target, request -> RecoveryProposal(target,
            object : ChatRequest by request { override val temperature: Double? = null }) }
        assertEquals(RunState.COMPLETED, f.loop(p, remove).run().state)
        assertNull(p.requests[1].temperature)
        for (shrink in listOf(false, true)) {
            val q = ScriptProvider(listOf(failure(ModelErrorCode.CONTEXT_TOO_LONG), answer()))
            val strategy = RecoveryStrategy { _, _, target, request -> RecoveryProposal(target,
                object : ChatRequest by request { override val maxOutputTokens = if (shrink) 50 else 100 }) }
            val result = RunFixture().loop(q, strategy, budget = RequestBudget { 1000L + (it.maxOutputTokens ?: 0) }).run()
            assertEquals(if (shrink) RunState.COMPLETED else RunState.PAUSED, result.state)
            assertEquals(if (shrink) 2 else 1, q.requests.size)
        }
    }

    @Test fun lengthIncreaseOnceAndServerGateAppliesToEmptyAndTruncation() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val p = ScriptProvider(listOf(calls("tool", reason = FinishReason.LENGTH, args = "{"), answer()))
        val strategy = RecoveryStrategy { action, _, target, request ->
            assertEquals(RecoveryAction.INCREASE_OUTPUT, action)
            RecoveryProposal(target, object : ChatRequest by request { override val maxOutputTokens = 200 })
        }
        assertEquals(RunState.COMPLETED, f.loop(p, strategy).run().state)
        assertEquals(200, p.requests[1].maxOutputTokens); assertTrue(f.submitted.isEmpty())
        for (events in listOf(calls("tool", reason = FinishReason.LENGTH, args = "{"),
            listOf(Events.started("r", "m"), Events.finished(FinishReason.STOP)))) {
            val q = ScriptProvider(listOf(events), ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
            assertTrue(RunFixture(listOf(testDefinition())).loop(q, strategy).run().serverExecutionUnknown)
            assertEquals(1, q.requests.size)
        }
    }

    @Test fun fallbackChecksEveryReplayAndNeverStripsHistory() = runTest {
        for (compatible in listOf(false, true)) {
            val f = RunFixture()
            f.history.append(0, listOf(message(Role.ASSISTANT, textPart("signed", replayData("p", "m", "raw")))))
            val p = ScriptProvider(listOf(failure(ModelErrorCode.QUOTA)))
            val alternate = ScriptProvider(listOf(answer()), replay = compatible)
            val strategy = RecoveryStrategy { _, _, target, request ->
                RecoveryProposal(target.copy(id = "alternate", provider = alternate), copyRequest(request, model = "other"))
            }
            val r = f.loop(p, strategy).run()
            assertEquals(if (compatible) RunState.COMPLETED else RunState.PAUSED, r.state)
            assertEquals(if (compatible) 1 else 0, alternate.requests.size)
            assertEquals("raw", f.history.snapshot().messages[1].parts.single().replay!!.payload)
        }
    }

    @Test fun refusalAndFilterDiscardAndUseExplicitFallback() = runTest {
        for (reason in listOf(FinishReason.REFUSAL, FinishReason.CONTENT_FILTER)) {
            val f = RunFixture()
            val p = ScriptProvider(listOf(answer("refused", reason)))
            val alternate = ScriptProvider(listOf(answer()))
            val strategy = RecoveryStrategy { _, _, target, request -> RecoveryProposal(target.copy(id = "other", provider = alternate), request) }
            assertEquals(RunState.COMPLETED, f.loop(p, strategy).run().state)
            assertEquals(1, alternate.requests.single().messages.size)
            assertEquals("refused", (f.journal.attempts().first().partial.parts.single() as ContentPart.Text).text)
        }
    }

    @Test fun retryAfterToolsDoesNotRepeatToolStepAndGlobalRetryBudgetStopsAlternatingErrors() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val p = ScriptProvider(listOf(calls("tool"), failure(ModelErrorCode.NETWORK), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertEquals(1, f.submitted.size); assertEquals(p.requests[1].messages, p.requests[2].messages)
        val q = ScriptProvider(listOf(failure(ModelErrorCode.NETWORK), failure(ModelErrorCode.UNKNOWN), failure(ModelErrorCode.SERVER)))
        val result = RunFixture().loop(q, limits = RunLimits(maxRetries = 2)).run()
        assertEquals(2, result.retries); assertEquals(3, q.requests.size)
    }

    @Test fun timeoutEofDuplicateTerminalAndThrownTransportUseFailurePath() = runTest {
        for (kind in listOf("timeout", "eof", "duplicate", "throw")) {
            var requests = 0
            val p = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials) = flow {
                    requests++
                    emit(Events.started("r", "m"))
                    when (kind) {
                        "timeout" -> awaitCancellation()
                        "eof" -> Unit
                        "duplicate" -> { emit(Events.finished(FinishReason.STOP)); emit(Events.finished(FinishReason.STOP)) }
                        else -> error("transport")
                    }
                }
            }
            val f = RunFixture()
            assertEquals(RunState.FAILED, f.loop(p, limits = RunLimits(maxRetries = 1)).run().state)
            assertEquals(2, requests); assertEquals(1, f.history.snapshot().messages.size)
            assertTrue(f.journal.attempts().all { it.terminal is ModelEvent.Failed })
        }
    }

    @Test fun mutableConfigurationIsFrozenAndRecoveryCannotChangeReasoning() = runTest {
        val f = RunFixture()
        var systemText = "original"
        val request = object : ChatRequest by f.request() { override val system get() = systemText }
        val p = ScriptProvider(listOf(failure(ModelErrorCode.AUTH), answer()))
        val strategy = RecoveryStrategy { _, _, target, r -> RecoveryProposal(target,
            object : ChatRequest by r { override val reasoningLevel = "changed" }) }
        val loop = f.loop(p, strategy, request = request)
        systemText = "mutated"
        assertEquals(RunState.FAILED, loop.run().state)
        assertEquals("original", p.requests.single().system)
    }

    @Test fun alternatingTransientErrorsShareThreeRetriesThenFallbackOrStop() = runTest {
        for (useFallback in listOf(false, true)) {
            val codes = listOf(ModelErrorCode.OVERLOADED, ModelErrorCode.SERVER, ModelErrorCode.NETWORK, ModelErrorCode.TIMEOUT)
            val script = ScriptProvider(codes.map { failure(it) } + listOf(answer("must not reach original endpoint")))
            val requestTimes = mutableListOf<Long>()
            val start = testScheduler.currentTime
            val p = object : ModelProvider by script {
                override fun chat(request: ChatRequest, auth: Credentials): kotlinx.coroutines.flow.Flow<ModelEvent> {
                    requestTimes += testScheduler.currentTime - start
                    return script.chat(request, auth)
                }
            }
            // A genuinely different endpoint gets its own transient budget, starting at one second.
            val alternate = ScriptProvider(listOf(failure(ModelErrorCode.SERVER), answer()))
            val actions = mutableListOf<RecoveryAction>()
            val strategy = RecoveryStrategy { action, error, target, request ->
                actions += action
                assertEquals(RecoveryAction.FALLBACK, action)
                assertEquals(ModelErrorCode.TIMEOUT, error!!.code)
                assertEquals(4, script.requests.size)
                assertEquals(7000L, testScheduler.currentTime - start)
                if (useFallback) RecoveryProposal(target.copy(id = "alternate", provider = alternate), request) else null
            }
            val result = RunFixture().loop(p, strategy).run()
            assertEquals(listOf(0L, 1000L, 3000L, 7000L), requestTimes)
            assertEquals(listOf(RecoveryAction.FALLBACK), actions)
            assertEquals(if (useFallback) RunState.COMPLETED else RunState.FAILED, result.state)
            assertEquals(if (useFallback) 5 else 3, result.retries)
            assertEquals(if (useFallback) 2 else 0, alternate.requests.size)
            assertEquals(if (useFallback) 8000L else 7000L, testScheduler.currentTime - start)
        }
    }

    @Test fun sharedTransientBudgetResetsOnlyAfterCompletedStep() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val p = ScriptProvider(listOf(failure(ModelErrorCode.OVERLOADED), failure(ModelErrorCode.SERVER),
            failure(ModelErrorCode.NETWORK), calls("tool"), failure(ModelErrorCode.TIMEOUT),
            failure(ModelErrorCode.NETWORK), failure(ModelErrorCode.SERVER), answer()))
        val start = testScheduler.currentTime
        val result = f.loop(p).run()
        assertEquals(RunState.COMPLETED, result.state)
        assertEquals(6, result.retries)
        assertEquals(14000L, testScheduler.currentTime - start)
        assertEquals(8, p.requests.size)
        assertEquals(1, f.submitted.size)
    }

    @Test fun eachUnsupportedParameterIsRemovedOnceAndRepeatsStayBounded() = runTest {
        val strategy = RecoveryStrategy { _, error, target, r -> RecoveryProposal(target, object : ChatRequest by r {
            override val temperature = if (error!!.param == "temperature") null else r.temperature
            override val topP = if (error!!.param == "top_p") null else r.topP
        }) }
        val f = RunFixture()
        val base = f.request()
        val request = object : ChatRequest by base { override val topP: Double? = 0.5 }
        val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "temperature"),
            failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "top_p"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p, strategy, request = request).run().state)
        assertEquals(3, p.requests.size)
        assertNull(p.requests[2].temperature); assertNull(p.requests[2].topP)

        val repeated = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "temperature")))
        assertEquals(RunState.FAILED, RunFixture().loop(repeated, strategy).run().state)
        assertEquals(2, repeated.requests.size)
    }

    @Test fun recoveryStrategyLocalTimeoutFailsRunWithResult() = runTest {
        val f = RunFixture()
        val p = ScriptProvider(listOf(failure(ModelErrorCode.AUTH), answer()))
        val strategy = RecoveryStrategy { _, _, _, _ -> withTimeout(10) { awaitCancellation() } }
        assertEquals(RunState.FAILED, f.loop(p, strategy).run().state)
        assertEquals(1, p.requests.size)
        assertEquals(1, f.history.snapshot().messages.size)
    }
}
