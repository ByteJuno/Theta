package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.ledger.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReasoningRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun request() = chatRequest("m") { reasoningLevel = "high" }

    @Test fun `prior summary accounting permits first assistant thinking recovery without resetting budgets`() = runTest {
        for (inheritedSteps in listOf(0, 1)) {
            val f = RunFixture()
            val provider = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "thinkingConfig"), answer()))
            val summarizer = ScriptProvider(listOf(answer("old facts")))
            val budget = ContextBudget({ r ->
                (r.system?.length ?: 0).toLong() + r.messages.sumOf { m ->
                    m.parts.filterIsInstance<ContentPart.Text>().sumOf { it.text.length.toLong() }
                }
            }, { 1500L })
            val summary = SummaryModel(ModelTarget("summary", summarizer, Credentials { "fake" }), chatRequest("m") {
                toolChoice = ToolChoice.None; maxOutputTokens = 100
            }, ContextBudget({ 100L }, { 10000L }))
            val dir = temp.newFolder().toPath()
            TestRunHistory.create(dir, "session", Durability.PROCESS_CRASH_ONLY).use { history ->
                history.append(0, listOf(message(Role.USER, textPart("old".repeat(1000))),
                    message(Role.ASSISTANT, textPart("past")), message(Role.USER, textPart("goal"))))
                var notices = 0
                val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                    ModelTarget("provider", provider, Credentials { "fake" }),
                    chatRequest("m") { reasoningLevel = "high"; maxOutputTokens = 100 }, history,
                    LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
                    budget = budget, contextPolicy = ContextPolicy("goal", 2, summary), initialSteps = inheritedSteps,
                    reasoningCapabilities = ReasoningCapabilityObserver { _, _ -> notices++ }).run()
                assertEquals(1, summarizer.requests.size)
                assertEquals(if (inheritedSteps == 0) RunState.COMPLETED else RunState.FAILED, result.state)
                assertEquals(if (inheritedSteps == 0) listOf("high", null) else listOf("high"), provider.requests.map { it.reasoningLevel })
                assertEquals(if (inheritedSteps == 0) 1 else 0, notices)
                assertEquals(2, result.steps)
                assertEquals(if (inheritedSteps == 0) 2 else 1, result.retries)
                assertEquals(result.steps, history.loadAccounting("chain")!!.steps)
                assertEquals(result.retries, history.loadAccounting("chain")!!.retries)
            }
            TestRunHistory.open(dir, Durability.PROCESS_CRASH_ONLY).use { history ->
                assertEquals(2, history.loadAccounting("chain")!!.steps)
                assertEquals(if (inheritedSteps == 0) 2 else 1, history.loadAccounting("chain")!!.retries)
            }
        }
    }

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
