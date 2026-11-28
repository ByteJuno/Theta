package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.ledger.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReasoningRecoveryTest {
    private fun request() = chatRequest("m") { reasoningLevel = "high" }
    @Test fun `confirmed first step removes thinking once and records capability before retry`() = runTest {
        val fixture = RunFixture(); var notices = 0
        val provider = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig"), answer()))
        val loop = AgentRunLoop(RunIdentity("chain", "session", "run"), ModelTarget("provider", provider, Credentials { "fake" }),
            request(), fixture.history, LedgerToolExecutor(fixture.ledger, fixture.gate, fixture.definitions, fixture.bridge,
                RunApprovals { Decision.ALLOW_ONCE }), reasoningCapabilities = ReasoningCapabilityObserver { _, rejected ->
                assertEquals("high", rejected.reasoningLevel); assertEquals(1, provider.requests.size); notices++
            })
        val result = loop.run()
        assertEquals(RunState.COMPLETED, result.state); assertEquals(1, result.retries); assertEquals(1, notices)
        assertEquals(listOf("high", null), provider.requests.map { it.reasoningLevel })
    }

    @Test fun `second rejection does not loop and persistence failure prevents retry`() = runTest {
        val f = RunFixture(); val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig")))
        assertEquals(RunState.FAILED, f.loop(p, request = request()).run().state)
        assertEquals(2, p.requests.size)
        val fail = RunFixture(); val provider = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig")))
        assertFailsWith<IllegalStateException> {
            AgentRunLoop(RunIdentity("c", "s", "r"), ModelTarget("provider", provider, Credentials { "fake" }), request(), fail.history,
                LedgerToolExecutor(fail.ledger, fail.gate, fail.definitions, fail.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                reasoningCapabilities = ReasoningCapabilityObserver { _, _ -> error("storage unavailable") }).run()
        }
        assertEquals(1, provider.requests.size)
    }

    @Test fun `tool continuation server tools and partial response never change frozen thinking`() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val p = ScriptProvider(listOf(calls("tool"), failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig")))
        assertEquals(RunState.FAILED, f.loop(p, request = object : ChatRequest by f.request() { override val reasoningLevel = "high" }).run().state)
        assertEquals(listOf("high", "high"), p.requests.map { it.reasoningLevel })
        val server = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig")), ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
        assertEquals(RunState.PAUSED, RunFixture().loop(server, request = request()).run().state)
        assertEquals(1, server.requests.size)
        val partial = ScriptProvider(listOf(listOf(Events.started("r", "m"), Events.text(0, "started"),
            Events.failed(modelError(ModelErrorCode.UNSUPPORTED_PARAMETER, "rejected", param = "thinkingConfig")))))
        assertEquals(RunState.FAILED, RunFixture().loop(partial, request = request()).run().state)
        assertEquals(1, partial.requests.size)
    }

    @Test fun `prior signed history requires frozen thinking and unconfirmed evidence cannot downgrade`() {
        val signed = chatRequest("m") { reasoningLevel = "high"; messages = listOf(message(Role.ASSISTANT,
            textPart("answer", replayData("gemini", "m", "opaque")))) }
        val error = modelError(ModelErrorCode.UNSUPPORTED_PARAMETER, "rejected", param = "thinkingConfig")
        assertFalse(canRemoveReasoning(signed, error, ModelExecutionMode.GENERATION_ONLY, 0, message(Role.ASSISTANT), true, false))
        assertFalse(canRemoveReasoning(request(), error, ModelExecutionMode.GENERATION_ONLY, 0, message(Role.ASSISTANT), false, false))
    }

    @Test fun `output from a previous failed attempt freezes reasoning for later attempts`() = runTest {
        val provider = ScriptProvider(listOf(
            listOf(Events.started("r", "m"), Events.reasoning(0, "thinking"), Events.failed(modelError(ModelErrorCode.NETWORK, "reset"))),
            failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig")))
        assertEquals(RunState.FAILED, RunFixture().loop(provider, request = request()).run().state)
        assertEquals(listOf("high", "high"), provider.requests.map { it.reasoningLevel })
    }
}
