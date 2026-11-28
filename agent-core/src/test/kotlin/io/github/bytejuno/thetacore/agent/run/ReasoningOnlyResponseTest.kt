package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class ReasoningOnlyResponseTest {
    private fun reasoning(signed: Boolean, reason: FinishReason = FinishReason.STOP) = listOf(
        Events.started("response", "m"), Events.reasoning(0, "private reasoning"),
        Events.end(0, if (signed) replayData("p", "m", "opaque signature") else null),
        Events.finished(reason))

    @Test fun reasoningOnlyRetriesOnceThenFailsWithoutReplayHistory() = runTest {
        for (signed in listOf(false, true)) {
            val fixture = RunFixture()
            val before = fixture.history.snapshot()
            val provider = ScriptProvider(listOf(reasoning(signed)))
            val result = fixture.loop(provider).run()
            assertEquals(RunState.FAILED, result.state)
            assertEquals("Empty model response", result.reason)
            assertEquals(1, result.retries)
            assertEquals(0, result.steps)
            assertFalse(result.serverExecutionUnknown)
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests.all { it.messages == before.messages })
            assertEquals(before, fixture.history.snapshot())
            assertEquals(2, fixture.journal.attempts().size)
            fixture.journal.attempts().forEach { attempt ->
                val part = assertIs<ContentPart.Reasoning>(attempt.partial.parts.single())
                assertEquals("private reasoning", part.summary)
                assertEquals(if (signed) "opaque signature" else null, part.replay?.payload)
                assertEquals(FinishReason.STOP, (attempt.terminal as ModelEvent.Finished).reason)
                assertEquals(HistoryDisposition.DISCARD, attempt.disposition)
                assertFalse(attempt.cancelled)
            }
        }
    }

    @Test fun visibleRetrySucceedsWithoutReplayingTheDiscardedReasoning() = runTest {
        for (signed in listOf(false, true)) {
            val fixture = RunFixture()
            // A discarded signature must not become a replay compatibility prerequisite.
            val provider = ScriptProvider(listOf(reasoning(signed), answer()), replay = false)
            val result = fixture.loop(provider).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertEquals(1, result.retries)
            assertEquals(1, result.steps)
            assertEquals(2, provider.requests.size)
            assertEquals(provider.requests[0].messages, provider.requests[1].messages)
            assertEquals(listOf(Role.USER, Role.ASSISTANT), fixture.history.snapshot().messages.map { it.role })
            assertEquals("done", assertIs<ContentPart.Text>(fixture.history.snapshot().messages.last().parts.single()).text)
        }
    }

    @Test fun reasoningOnlyRespectsTheExistingRetryBudget() = runTest {
        val fixture = RunFixture()
        val provider = ScriptProvider(listOf(reasoning(true), answer()))
        val result = fixture.loop(provider, limits = RunLimits(maxRetries = 0)).run()
        assertEquals(RunState.FAILED, result.state)
        assertEquals("Empty model response", result.reason)
        assertEquals(0, result.retries)
        assertEquals(1, provider.requests.size)
        assertEquals(1, fixture.history.snapshot().messages.size)
    }

    @Test fun possibleServerExecutionPausesWithoutRecoveryOrHistoryAppend() = runTest {
        for (signed in listOf(false, true)) {
            val fixture = RunFixture(unattended = true)
            val before = fixture.history.snapshot()
            val provider = ScriptProvider(listOf(reasoning(signed), answer()), ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
            val result = fixture.loop(provider, RecoveryStrategy { _, _, _, _ -> error("Must not retry or fall back") }).run()
            assertEquals(RunState.PAUSED, result.state)
            assertEquals("Server execution state unknown", result.reason)
            assertTrue(result.serverExecutionUnknown)
            assertEquals(0, result.retries)
            assertEquals(1, provider.requests.size)
            assertEquals(before, fixture.history.snapshot())
            assertIs<ContentPart.Reasoning>(fixture.journal.attempts().single().partial.parts.single())
        }
    }

    @Test fun visibleTextAndToolCallsKeepTheirReasoningAndReplay() = runTest {
        for (withTool in listOf(false, true)) {
            val first = reasoning(true).dropLast(1) + if (withTool) listOf(
                Events.toolStart(1, "wire", "tool"), Events.toolDelta(1, "{}"), Events.end(1),
                Events.finished(FinishReason.STOP)) else listOf(
                Events.text(1, "visible answer"), Events.end(1), Events.finished(FinishReason.STOP))
            val fixture = RunFixture(listOf(testDefinition()))
            val provider = ScriptProvider(listOf(first, answer()))
            val result = fixture.loop(provider).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertEquals(0, result.retries)
            assertEquals(if (withTool) 2 else 1, provider.requests.size)
            assertEquals(if (withTool) 1 else 0, fixture.submitted.size)
            val kept = fixture.history.snapshot().messages[1].parts
            assertEquals("opaque signature", assertIs<ContentPart.Reasoning>(kept[0]).replay?.payload)
            if (withTool) assertIs<ContentPart.ToolCall>(kept[1])
            else assertEquals("visible answer", assertIs<ContentPart.Text>(kept[1]).text)
            validateHistory(fixture.history.snapshot().messages)
        }
    }

    @Test fun explicitPausedReasoningStillContinuesTheSameServerLoop() = runTest {
        val fixture = RunFixture()
        val provider = ScriptProvider(listOf(reasoning(true, FinishReason.PAUSED), answer()),
            ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
        val result = fixture.loop(provider).run()
        assertEquals(RunState.COMPLETED, result.state)
        assertEquals(0, result.retries)
        assertEquals(2, provider.requests.size)
        assertEquals("opaque signature", provider.requests[1].messages.last().parts.single().replay?.payload)
    }

    @Test fun rawReasoningSurvivesStorageReopenAndStillReachesTheEventConsumer() = runTest {
        for (mode in ModelExecutionMode.entries) {
            val root = Files.createTempDirectory("reasoning-only")
            val fixture = RunFixture()
            val provider = ScriptProvider(listOf(reasoning(true)), mode)
            val observed = mutableListOf<ModelEvent>()
            val attempts = if (mode == ModelExecutionMode.GENERATION_ONLY) 2 else 1
            TestRunHistory.create(root, "session", Durability.PROCESS_CRASH_ONLY).use { disk ->
                disk.append(0, listOf(message(Role.USER, textPart("goal"))))
                val result = AgentRunLoop(RunIdentity("chain", "session", "run"),
                    ModelTarget("provider", provider, Credentials { "fake" }), fixture.request(), disk,
                    LedgerToolExecutor(fixture.ledger, fixture.gate, fixture.definitions, fixture.bridge,
                        RunApprovals { Decision.ALLOW_ONCE }), StoredRunJournal(disk),
                    onEvent = { _, event -> observed += event }).run()
                assertEquals(if (mode == ModelExecutionMode.GENERATION_ONLY) RunState.FAILED else RunState.PAUSED, result.state)
                assertEquals(attempts, disk.journalPage().replies.size)
            }
            TestRunHistory.open(root, Durability.PROCESS_CRASH_ONLY).use { disk ->
                assertEquals(listOf(Role.USER), disk.snapshot().messages.map { it.role })
                assertEquals(mode != ModelExecutionMode.GENERATION_ONLY, disk.loadBarrier() != null)
                val replies = disk.journalPage().replies
                assertEquals(attempts, replies.size)
                replies.forEach { reply ->
                    assertEquals("EMPTY_RESPONSE", reply.reason)
                    val part = assertIs<ContentPart.Reasoning>(reply.partial.parts.single())
                    assertEquals("private reasoning", part.summary)
                    assertEquals("opaque signature", part.replay?.payload)
                }
            }
            assertEquals(attempts, provider.requests.size)
            assertEquals(List(attempts) { "private reasoning" }, observed.filterIsInstance<ModelEvent.ReasoningDelta>().map { it.text })
            repeat(attempts) { index ->
                val path = "journal/" + (index + 1).toString().padStart(20, '0')
                val framed = HistoryFrameInput(TestRunHistory.bytes(root, path).inputStream())
                val encoded = HistoryCodec.json.decodeFromString<String>(framed.readBytes().toString(Charsets.UTF_8))
                val stored = HistoryCodec.json.decodeFromString<StoredAttempt>(encoded)
                val part = assertIs<ContentPart.Reasoning>(HistoryCodec.decodeMessage(stored.partial!!).parts.single())
                assertEquals("private reasoning", part.summary)
                assertEquals("opaque signature", part.replay?.payload)
                assertTrue(stored.complete)
                assertEquals(FinishReason.STOP, stored.finish)
                assertEquals(HistoryDisposition.DISCARD.name, stored.disposition)
            }
        }
    }

    @Test fun terminalClassificationDistinguishesEmptySignedTextFromVisibleReplies() {
        for (reason in listOf(FinishReason.STOP, FinishReason.OTHER, FinishReason.TOOL_CALLS,
            FinishReason.PAUSED, FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED,
            FinishReason.REFUSAL, FinishReason.CONTENT_FILTER)) {
            for (signedText in listOf(false, true)) for (text in listOf("", "visible answer")) {
                val reducer = ModelEventReducer("provider", "request")
                val textReplay = if (signedText) replayData("p", "m", "text signature") else null
                val events = reasoning(true, reason).dropLast(1) + listOf(Events.text(1, text),
                    Events.end(1, textReplay), Events.finished(reason))
                events.forEach(reducer::accept)
                // Protocol 2.7: replay metadata does not make an empty answer visible.
                // Explicit continuation and truncation keep the original parts; refusals do not.
                val expected = when (reason) {
                    FinishReason.REFUSAL, FinishReason.CONTENT_FILTER -> HistoryDisposition.DISCARD
                    FinishReason.STOP, FinishReason.OTHER, FinishReason.TOOL_CALLS ->
                        if (text.isEmpty()) HistoryDisposition.DISCARD else HistoryDisposition.KEEP
                    else -> HistoryDisposition.KEEP
                }
                val scenario = "reason=$reason signedText=$signedText text='$text'"
                assertEquals(expected, reducer.historyDisposition(), scenario)
                val parts = reducer.assistantMessage().parts
                assertEquals(2, parts.size, scenario)
                val thought = assertIs<ContentPart.Reasoning>(parts[0])
                assertEquals("private reasoning", thought.summary, scenario)
                assertEquals(replayData("p", "m", "opaque signature"), thought.replay, scenario)
                val answer = assertIs<ContentPart.Text>(parts[1])
                assertEquals(text, answer.text, scenario)
                assertEquals(textReplay, answer.replay, scenario)
            }
        }
    }
}
