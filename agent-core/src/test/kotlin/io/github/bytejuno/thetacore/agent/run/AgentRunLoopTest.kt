package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class AgentRunLoopTest {
    @Test fun multipleStepsAndStopCallsUseInternalIdsAndPairedHistory() = runTest {
        val f = RunFixture(listOf(testDefinition("a"), testDefinition("b")))
        val p = ScriptProvider(listOf(calls("a", "b", reason = FinishReason.STOP), calls("b"), answer()))
        val loop = f.loop(p)
        assertEquals(RunState.COMPLETED, loop.run().state)
        assertEquals(listOf("a", "b", "b"), f.submitted.map { it.definition.name })
        assertEquals(listOf(1, 3, 5), p.requests.map { it.messages.size })
        assertEquals(3, f.submitted.map { it.callId }.distinct().size)
        assertTrue(f.submitted.none { it.callId.startsWith("wire") })
        validateHistory(f.history.snapshot().messages)
        assertFailsWith<IllegalStateException> { loop.run() }
    }

    @Test fun parallelCompletionOrderDoesNotChangeResultsAndSerialIsBarrier() = runTest {
        val f = RunFixture(listOf(testDefinition("a", true), testDefinition("b", true), testDefinition("c")))
        f.onSubmit = { t ->
            if (t.definition.name == "b") {
                f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "second")
                val first = f.submitted.first()
                f.ledger.complete(first.callId, first.attemptId, Status.SUCCEEDED, "first")
            } else if (t.definition.name == "c") f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "third")
        }
        val p = ScriptProvider(listOf(calls("a", "b", "c"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertEquals(listOf("submit:a", "submit:b"), f.order.take(2))
        assertTrue(f.order.indexOf("submit:c") > f.order.indexOf("await:${f.submitted[1].callId}"))
        val results = p.requests[1].messages.last().parts.filterIsInstance<ContentPart.ToolResult>()
        assertEquals(f.submitted.map { it.callId }, results.map { it.callId })
        assertTrue(results[0].text.contains("first")); assertTrue(results[1].text.contains("second"))
    }

    @Test fun failedSubmitAfterAppliedCallDoesNotReplayOrLieAboutEffect() = runTest {
        val f = RunFixture(listOf(testDefinition("a", risk = Risk.WRITE), testDefinition("b", risk = Risk.WRITE)))
        f.onSubmit = { t ->
            if (t.definition.name == "a") f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "applied")
            else error("submit acknowledgement lost")
        }
        val p = ScriptProvider(listOf(calls("a", "b")))
        assertEquals(RunState.PAUSED, f.loop(p).run().state)
        assertEquals(1, p.requests.size)
        assertEquals(listOf(Effect.APPLIED, Effect.UNKNOWN), f.store.records().map { it.result!!.effect })
        assertEquals(3, f.history.snapshot().messages.size)
        validateHistory(f.history.snapshot().messages)
        assertEquals(1, f.cancelled.size)
    }

    @Test fun cancellationCommitsRealCompletedRunningAndUnstartedResults() = runTest {
        val f = RunFixture(listOf(testDefinition("a", risk = Risk.WRITE), testDefinition("b", risk = Risk.WRITE), testDefinition("c")))
        f.onSubmit = { t -> if (t.definition.name == "a") f.ledger.complete(t.callId, t.attemptId, Status.SUCCEEDED, "done") }
        val reached = CompletableDeferred<Unit>()
        f.onAwait = { id -> if (f.ledger.get(id)!!.input.modelName == "b") { reached.complete(Unit); awaitCancellation() } }
        val p = ScriptProvider(listOf(calls("a", "b", "c")))
        val job = launch { f.loop(p).run() }
        reached.await(); job.cancelAndJoin()
        assertEquals(listOf(Effect.APPLIED, Effect.UNKNOWN, Effect.NONE), f.store.records().map { it.result!!.effect })
        assertEquals(listOf(Status.SUCCEEDED, Status.CANCELLED, Status.CANCELLED), f.store.records().map { it.state })
        assertEquals(2, f.submitted.size)
        validateHistory(f.history.snapshot().messages)
        assertEquals(3, f.history.snapshot().messages.size)
    }

    @Test fun streamCancellationDiscardsPartialAndRecordsEvidence() = runTest {
        val f = RunFixture()
        val ready = CompletableDeferred<Unit>()
        val p = object : ModelProvider {
            override fun chat(request: ChatRequest, auth: Credentials) = flow {
                emit(Events.started("r", "m")); emit(Events.text(0, "partial")); ready.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { f.loop(p).run() }
        ready.await(); yield(); job.cancelAndJoin()
        assertEquals(1, f.history.snapshot().messages.size)
        assertTrue(f.journal.attempts().single().cancelled)
        assertEquals(ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS, f.journal.attempts().single().mode)
    }

    @Test fun unknownInvalidDeniedAndUnattendedToolsStillPairWithoutSubmission() = runTest {
        for (kind in listOf("unknown", "invalid", "denied", "unattended")) {
            val f = RunFixture(listOf(testDefinition(risk = Risk.WRITE)), unattended = kind == "unattended")
            f.approve = { Decision.DENY }
            val p = ScriptProvider(listOf(calls(if (kind == "unknown") "missing" else "tool",
                args = if (kind == "invalid") "[]" else "{}"), answer()))
            assertEquals(RunState.COMPLETED, f.loop(p).run().state)
            assertTrue(f.submitted.isEmpty())
            assertEquals(Status.REJECTED, f.store.records().single().state)
            validateHistory(f.history.snapshot().messages)
        }
    }

    @Test fun staleDefinitionIsNotApprovedUnderOldDescription() = runTest {
        val d = testDefinition(risk = Risk.WRITE)
        val f = RunFixture(listOf(d))
        f.approve = { f.gate.install(d.copy(version = "2")); Decision.ALLOW_ONCE }
        val p = ScriptProvider(listOf(calls("tool"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertTrue(f.submitted.isEmpty())
        assertEquals(Status.REJECTED, f.store.records().single().state)
    }

    @Test fun completeTruncationCancelsAndIncompleteTruncationDiscards() = runTest {
        for (reason in listOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)) {
            for (args in listOf("{}", "{")) {
                val f = RunFixture(listOf(testDefinition()))
                val p = ScriptProvider(listOf(calls("tool", reason = reason, args = args)))
                assertEquals(RunState.PAUSED, f.loop(p).run().state)
                assertTrue(f.submitted.isEmpty())
                assertEquals(if (args == "{}") 3 else 1, f.history.snapshot().messages.size)
                if (args == "{}") assertEquals(Effect.NONE, f.store.records().single().result!!.effect)
            }
        }
    }

    @Test fun replayAndPartOrderSurvivePausedAndTools() = runTest {
        val replay = replayData("signed", "m", "untouched payload")
        val first = listOf(Events.started("r", "m"), Events.reasoning(0, "think"), Events.end(0, replay),
            Events.text(1, "text"), Events.end(1, replay), Events.toolStart(2, "wire", "tool"),
            Events.toolDelta(2, "{}"), Events.end(2, replay), Events.finished(FinishReason.TOOL_CALLS))
        val f = RunFixture(listOf(testDefinition()))
        val p = ScriptProvider(listOf(first, answer("continue", FinishReason.PAUSED), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        val parts = p.requests[1].messages[1].parts
        assertIs<ContentPart.Reasoning>(parts[0]); assertIs<ContentPart.Text>(parts[1]); assertIs<ContentPart.ToolCall>(parts[2])
        assertTrue(parts.all { it.replay == replay })
        assertEquals("continue", (p.requests[2].messages.last().parts.single() as ContentPart.Text).text)
    }

    @Test fun pausedCanContinueServerLoopButFailedContinuationCannotRetry() = runTest {
        val f = RunFixture()
        val p = ScriptProvider(listOf(answer("pause", FinishReason.PAUSED), failure(ModelErrorCode.NETWORK)),
            ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
        val result = f.loop(p).run()
        assertTrue(result.serverExecutionUnknown); assertEquals(RunState.PAUSED, result.state)
        assertEquals(2, p.requests.size); assertEquals(2, f.history.snapshot().messages.size)
    }

    @Test fun boundedEmptyPausedStepsAndRepeatedCalls() = runTest {
        val empty = ScriptProvider(listOf(listOf(Events.started("r", "m"), Events.finished(FinishReason.STOP))))
        assertEquals(RunState.FAILED, RunFixture().loop(empty).run().state); assertEquals(2, empty.requests.size)
        val paused = ScriptProvider(listOf(answer(reason = FinishReason.PAUSED)))
        assertEquals(RunState.PAUSED, RunFixture().loop(paused, limits = RunLimits(maxPaused = 1)).run().state)
        assertEquals(2, paused.requests.size)
        val f = RunFixture(listOf(testDefinition()))
        val repeated = ScriptProvider(listOf(calls("tool")))
        assertEquals(RunState.PAUSED, f.loop(repeated).run().state)
        assertEquals(3, repeated.requests.size); assertEquals(2, f.submitted.size)
        assertEquals(Status.CANCELLED, f.store.records().last().state)
        val limited = ScriptProvider(listOf(calls("missing")))
        assertEquals(1, RunFixture().loop(limited, limits = RunLimits(maxSteps = 1)).run().steps)
    }

    @Test fun historyWriteFailureNeverReexecutesAndLeavesNoHalfAppend() = runTest {
        val f = RunFixture(listOf(testDefinition(risk = Risk.WRITE)))
        val history = object : RunHistory {
            override fun snapshot() = f.history.snapshot()
            override fun append(expectedRevision: Long, messages: List<Message>) { error("storage uncertain") }
        }
        val p = ScriptProvider(listOf(calls("tool")))
        assertFailsWith<IllegalStateException> { f.loop(p, history = history).run() }
        assertEquals(1, p.requests.size); assertEquals(1, f.submitted.size)
        assertEquals(Effect.APPLIED, f.store.records().single().result!!.effect)
        assertEquals(1, f.history.snapshot().messages.size)
    }

    @Test fun historyRejectsOrphansReorderingDuplicatesAndInterleaving() {
        val a = toolCallPart(callBinding("p", "r", null, 0, "a"), "a", "{}")
        val b = toolCallPart(callBinding("p", "r", null, 1, "b"), "b", "{}")
        val assistant = message(Role.ASSISTANT, a, b)
        val results = message(Role.TOOL, toolResultPart(a.binding.callId, "a"), toolResultPart(b.binding.callId, "b"))
        val h = MemoryRunHistory()
        assertFailsWith<IllegalArgumentException> { h.append(0, listOf(assistant)) }
        assertFailsWith<IllegalArgumentException> { h.append(0, listOf(results)) }
        assertFailsWith<IllegalArgumentException> { h.append(0, listOf(assistant, message(Role.USER, textPart("interrupt")), results)) }
        assertFailsWith<IllegalArgumentException> { h.append(0, listOf(assistant, message(Role.TOOL, *results.parts.reversed().toTypedArray()))) }
        h.append(0, listOf(assistant, results))
        assertFailsWith<IllegalArgumentException> { h.append(1, listOf(assistant, results)) }
        assertEquals(2, h.snapshot().messages.size)
    }

    @Test fun logicalTimeoutDoesNotReleaseSerialBarrierOrAcceptLateResultTwice() = runTest {
        val f = RunFixture(listOf(testDefinition("a", risk = Risk.WRITE), testDefinition("b")))
        f.onSubmit = { t -> f.ledger.timeout(t.callId) }
        val p = ScriptProvider(listOf(calls("a", "b")))
        assertEquals(RunState.PAUSED, f.loop(p).run().state)
        assertEquals(1, f.submitted.size)
        val ticket = f.submitted.single()
        assertEquals(Effect.UNKNOWN, f.ledger.get(ticket.callId)!!.result!!.effect)
        f.ledger.complete(ticket.callId, ticket.attemptId, Status.SUCCEEDED, "late applied", Effect.APPLIED)
        assertEquals(Status.TIMED_OUT, f.ledger.get(ticket.callId)!!.state)
        assertEquals(3, f.history.snapshot().messages.size)
        assertEquals(2, f.history.snapshot().messages.last().parts.size)
    }

    @Test fun sameExclusiveResourceWaitsEvenForParallelSafeTools() = runTest {
        val f = RunFixture(listOf(testDefinition("a", true).copy(exclusiveResource = "shared"),
            testDefinition("b", true).copy(exclusiveResource = "shared")))
        f.onSubmit = {}
        f.onAwait = { id ->
            val t = f.submitted.single { it.callId == id }
            f.ledger.complete(id, t.attemptId, Status.SUCCEEDED, "done")
        }
        val p = ScriptProvider(listOf(calls("a", "b"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertTrue(f.order.indexOf("submit:b") > f.order.indexOf("await:${f.submitted[0].callId}"))
    }

    @Test fun otherReasonCompletesAndPreservesRawReason() = runTest {
        val events = answer(reason = FinishReason.OTHER).dropLast(1) + Events.finished(FinishReason.OTHER, "vendor_reason")
        val f = RunFixture()
        val result = f.loop(ScriptProvider(listOf(events))).run()
        assertEquals(RunState.COMPLETED, result.state)
        assertEquals("vendor_reason", result.reason)
    }

    @Test fun initialReplayIncompatibilityAndKnownBudgetStopBeforeSubmission() = runTest {
        val f = RunFixture()
        f.history.append(0, listOf(message(Role.ASSISTANT, textPart("signed", replayData("p", "m", "raw")))))
        val p = ScriptProvider(listOf(answer()), replay = false)
        assertEquals(RunState.PAUSED, f.loop(p).run().state)
        assertTrue(p.requests.isEmpty())
        val q = ScriptProvider(listOf(answer()))
        val budget = object : RequestBudget {
            override fun tokens(request: ChatRequest) = 200L
            override fun limit(model: String) = 100L
        }
        assertEquals(RunState.PAUSED, RunFixture().loop(q, budget = budget).run().state)
        assertTrue(q.requests.isEmpty())
    }

    @Test fun failedStreamWithCompleteCallsNeverReachesLedgerAndFailureResultCanContinue() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val failedCalls = calls("tool").dropLast(1) + Events.failed(modelError(ModelErrorCode.NETWORK, "interrupted"))
        val p = ScriptProvider(listOf(failedCalls, answer()))
        assertEquals(RunState.COMPLETED, f.loop(p).run().state)
        assertTrue(f.store.records().isEmpty()); assertTrue(f.submitted.isEmpty())
        assertEquals(1, p.requests[1].messages.size)

        val g = RunFixture(listOf(testDefinition()))
        g.onSubmit = { t -> g.ledger.complete(t.callId, t.attemptId, Status.FAILED, "read failed", Effect.NONE) }
        val q = ScriptProvider(listOf(calls("tool"), answer()))
        assertEquals(RunState.COMPLETED, g.loop(q).run().state)
        assertTrue((q.requests[1].messages.last().parts.single() as ContentPart.ToolResult).isError)
    }

    @Test fun emptyServerPausedDoesNotResubmitOldHistory() = runTest {
        val f = RunFixture()
        val before = f.history.snapshot()
        val p = ScriptProvider(listOf(
            listOf(Events.started("server-loop", "m"), Events.finished(FinishReason.PAUSED)), answer()),
            ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
        val result = f.loop(p, RecoveryStrategy { _, _, _, _ -> error("Must not recover empty PAUSED") }).run()
        assertEquals(RunState.PAUSED, result.state)
        assertTrue(result.serverExecutionUnknown)
        assertEquals(0, result.retries)
        assertEquals(1, p.requests.size)
        assertEquals(before, f.history.snapshot())
        assertEquals(FinishReason.PAUSED, (f.journal.attempts().single().terminal as ModelEvent.Finished).reason)
    }

    @Test fun selfCancelledApprovalCannotSubmitWriteAndPairsCancelledNoneResults() = runTest {
        val f = RunFixture(listOf(testDefinition("a", risk = Risk.WRITE), testDefinition("b", risk = Risk.WRITE)))
        var approvals = 0
        f.approve = {
            approvals++
            currentCoroutineContext().cancel()
            Decision.ALLOW_ONCE
        }
        val p = ScriptProvider(listOf(calls("a", "b")))
        val job = launch { f.loop(p).run() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, approvals)
        assertTrue(f.submitted.isEmpty())
        assertTrue(f.cancelled.isEmpty())
        assertEquals(listOf(Status.CANCELLED, Status.CANCELLED), f.store.records().map { it.state })
        assertTrue(f.store.records().all { it.instance == null && it.result!!.effect == Effect.NONE })
        val history = f.history.snapshot().messages
        validateHistory(history)
        assertEquals(3, history.size)
        assertEquals(f.store.records().map { it.callId }, history.last().parts.map { (it as ContentPart.ToolResult).callId })
        assertEquals(1, p.requests.size)
    }

    @Test fun selfCancelledBarrierCannotSubmitFollowingWrite() = runTest {
        val f = RunFixture(listOf(testDefinition("a", parallel = true, risk = Risk.WRITE),
            testDefinition("b", risk = Risk.WRITE)))
        f.onAwait = { currentCoroutineContext().cancel() }
        val p = ScriptProvider(listOf(calls("a", "b")))
        val job = launch { f.loop(p).run() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("a"), f.submitted.map { it.definition.name })
        assertEquals(listOf(Status.SUCCEEDED, Status.CANCELLED), f.store.records().map { it.state })
        assertEquals(listOf(Effect.APPLIED, Effect.NONE), f.store.records().map { it.result!!.effect })
        assertNull(f.store.records().last().instance)
        validateHistory(f.history.snapshot().messages)
        assertEquals(3, f.history.snapshot().messages.size)
        assertEquals(1, p.requests.size)
    }
}
