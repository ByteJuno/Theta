package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ContextPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root: Path get() = temporary.root.toPath()
    private val durability = Durability.PROCESS_CRASH_ONLY
    private val identity = RunIdentity("chain", "session", "run")
    private fun user(text: String) = message(Role.USER, textPart(text))
    private fun cost(r: ChatRequest): Long = (r.system?.length ?: 0).toLong() +
        r.tools.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length } +
        r.messages.sumOf { m -> m.parts.sumOf { p -> when (p) {
            is ContentPart.Text -> p.text.length.toLong()
            is ContentPart.Reasoning -> (p.summary?.length ?: 0).toLong() + (p.replay?.payload?.length ?: 0)
            is ContentPart.ToolCall -> p.argsJson.length.toLong() + p.name.length
            is ContentPart.ToolResult -> p.text.length.toLong()
        } } }
    private fun budget(window: Long? = 1500) = ContextBudget(::cost, { window })
    private fun summary(p: ModelProvider = ScriptProvider(listOf(answer("completed work retained"))), window: Long = 1800) =
        SummaryModel(ModelTarget("summary", p, Credentials { "test" }), chatRequest("m") {
            toolChoice = ToolChoice.None; maxOutputTokens = 100
        }, budget(window))
    private fun loop(f: RunFixture, p: ModelProvider, h: RunHistory, policy: ContextPolicy,
                     id: RunIdentity = identity, b: RequestBudget = budget(), request: ChatRequest = f.request(),
                     limits: RunLimits = RunLimits()) = AgentRunLoop(id,
        ModelTarget("provider", p, Credentials { "test" }), request, h,
        LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { Decision.ALLOW_ONCE }),
        f.journal, budget = b, limits = limits, timeouts = EventTimeouts(100, 100), contextPolicy = policy)

    private fun retryId(h: FileRunHistory) = assertNotNull(h.retryReconciliation()).id
    private fun confirmRetry(h: FileRunHistory, id: String, caller: RunIdentity = identity) =
        h.confirmRetryAfterReconciliation(id, caller, "Host inspected request journal and reconciled external results")

    @Test fun `definite generation failure releases continuation admission without losing view or counters`() = runTest {
        for (code in listOf(ModelErrorCode.INVALID_REQUEST, ModelErrorCode.AUTH, ModelErrorCode.MODEL_NOT_FOUND)) {
            val f = RunFixture()
            FileRunHistory.create(root.resolve("definite-$code"), "session", durability).use { h ->
                h.append(0, listOf(user("old")))
                val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
                assertEquals(RunState.FAILED, loop(f, ScriptProvider(listOf(failure(code))), h,
                    ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run().state)
                val p = ScriptProvider(listOf(answer()))
                val result = loop(f, p, h, ContextPolicy("goal", 1), identity.copy(runId = "retry")).run()
                assertEquals(RunState.COMPLETED, result.state)
                assertEquals(1, p.requests.size); assertTrue(result.steps > saved.steps)
                assertNotNull(h.loadView())
                assertFailsWith<IllegalArgumentException> {
                    loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run()
                }
            }
        }
    }

    // Exercise the real adapter: it converts pre-terminal transport failures into a normally
    // closed Failed flow. Counting exchanges detects both same-run retries and cold readmission.
    private class FaultTransport(private val fault: String) : ModelTransport {
        var requests = 0
        override fun exchange(url: String, body: String, token: String) = flow<TransportEvent> {
            requests++
            emit(TransportEvent.Headers(200, "text/event-stream", null))
            if (requests == 1) when (fault) {
                "network" -> throw java.io.IOException("reset before terminal")
                "protocol" -> { emit(TransportEvent.Bytes("data: not-json\n\n".toByteArray())); return@flow }
                "unknown" -> error("transport internal failure")
            }
            emit(TransportEvent.Bytes(("data: {\"id\":\"response\",\"model\":\"m\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").toByteArray()))
            if (fault == "terminal-reset") throw java.io.IOException("reset after terminal")
        }
    }
    private fun realProvider(transport: ModelTransport) =
        OpenAiChatProvider(OpenAiChatConfig("provider", "https://example.invalid/v1"), transport)

    private suspend fun admittedTransportFailure(fault: String, automatic: Boolean) {
        val f = RunFixture(); val transport = FaultTransport(fault); val p = realProvider(transport)
        FileRunHistory.create(root.resolve("admitted-$fault-$automatic"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val saved = checkpoint(h, "CONTINUATION").copy(
                settingsKey = settingsKey("provider", f.request(), p.contextConfigurationKey()))
            h.saveCheckpoint(saved)
            val limits = RunLimits(maxRetries = if (automatic) 12 else saved.retries)
            val result = loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id),
                saved.identity(), limits = limits).run()
            assertEquals(1, transport.requests, "uncertainty must not enter automatic recovery")
            assertEquals(RunState.PAUSED, result.state)
            assertEquals("ADMITTED", h.loadCheckpoint()!!.pauseClass)
            assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal"), b = budget(10_000)).run().state)
            assertEquals(1, transport.requests)
            val stale = retryId(h)
            h.saveCheckpoint(h.loadCheckpoint()!!.copy(id = "new-evidence"))
            assertFalse(confirmRetry(h, stale))
            val current = retryId(h)
            assertFalse(confirmRetry(h, current, identity.copy(unattended = true)))
            assertTrue(confirmRetry(h, current)); assertFalse(confirmRetry(h, current))
            assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal")).run().state)
            assertEquals(2, transport.requests)
        }
    }
    @Test fun `real transport network cannot automatically resubmit admitted continuation`() = runTest {
        admittedTransportFailure("network", true)
    }
    @Test fun `real transport network cannot retire admitted continuation when retries exhausted`() = runTest {
        admittedTransportFailure("network", false)
    }
    @Test fun `real transport protocol cannot automatically resubmit admitted continuation`() = runTest {
        admittedTransportFailure("protocol", true)
    }
    @Test fun `real transport unknown cannot automatically resubmit admitted continuation`() = runTest {
        admittedTransportFailure("unknown", true)
    }

    private suspend fun reservedTransportFailure(fault: String) {
        val f = RunFixture(); val transport = FaultTransport(fault)
        val mainTransport = FaultTransport("success"); val p = realProvider(mainTransport)
        FileRunHistory.create(root.resolve("reserved-$fault"), "session", durability).use { h ->
            h.append(0, listOf(user("old".repeat(1000)), user("goal")))
            assertEquals(RunState.PAUSED, loop(f, p, h,
                ContextPolicy("goal", 1, summary(realProvider(transport)))).run().state)
            assertEquals(1, transport.requests); assertEquals(0, mainTransport.requests)
            val saved = h.loadCheckpoint()!!
            assertEquals("SUMMARY_RESERVED", saved.pauseClass)
            assertFailsWith<IllegalArgumentException> {
                loop(f, p, h, ContextPolicy("goal", 1, readmitBudgetCheckpointId = saved.id),
                    b = budget(10_000)).run()
            }
            assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal"), b = budget(10_000)).run().state)
            assertEquals(0, mainTransport.requests); assertEquals(1, transport.requests)
            val stale = retryId(h)
            h.saveCheckpoint(saved.copy(id = "new-summary-evidence"))
            assertFalse(confirmRetry(h, stale))
            val current = retryId(h)
            assertFalse(confirmRetry(h, current, identity.copy(unattended = true)))
            assertTrue(confirmRetry(h, current))
            assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal"), b = budget(10_000)).run().state)
            assertEquals(1, mainTransport.requests); assertEquals(1, transport.requests)
        }
    }
    @Test fun `real transport network retains summary reservation across window change`() = runTest {
        reservedTransportFailure("network")
    }
    @Test fun `real transport protocol retains summary reservation across window change`() = runTest {
        reservedTransportFailure("protocol")
    }
    @Test fun `real transport unknown retains summary reservation across window change`() = runTest {
        reservedTransportFailure("unknown")
    }
    @Test fun `real transport terminal then reset completes admitted generation`() = runTest {
        val f = RunFixture(); val transport = FaultTransport("terminal-reset"); val p = realProvider(transport)
        FileRunHistory.create(root.resolve("terminal-reset"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val saved = checkpoint(h, "CONTINUATION").copy(
                settingsKey = settingsKey("provider", f.request(), p.contextConfigurationKey()))
            h.saveCheckpoint(saved)
            assertEquals(RunState.COMPLETED, loop(f, p, h,
                ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run().state)
            assertEquals(1, transport.requests); assertNull(h.retryReconciliation())
        }
    }

    @Test fun `closed failure evidence only accepts definite request rejections`() = runTest {
        val definite = setOf(ModelErrorCode.AUTH, ModelErrorCode.INVALID_REQUEST, ModelErrorCode.MODEL_NOT_FOUND,
            ModelErrorCode.CONTEXT_TOO_LONG, ModelErrorCode.UNSUPPORTED_PARAMETER, ModelErrorCode.RATE_LIMIT,
            ModelErrorCode.QUOTA, ModelErrorCode.CONTENT_BLOCKED)
        for (code in ModelErrorCode.entries) {
            val event = Events.failed(modelError(code, "failure"))
            val evidence = ResponseEvidence()
            evidence.observe(flow { emit(event) }).toList()
            assertEquals(code in definite, evidence.confirms(event), code.name)
            assertFalse(evidence.confirms(Events.failed(modelError(code, "different event"))))
        }
    }

    @Test fun `real HTTP request rejections release admitted generation`() = runTest {
        for (status in listOf(400, 401, 404)) {
            val f = RunFixture()
            val transport = object : ModelTransport {
                var requests = 0
                override fun exchange(url: String, body: String, token: String) = flow<TransportEvent> {
                    requests++
                    emit(TransportEvent.Headers(status, "application/json", null))
                    if (status == 404) emit(TransportEvent.Bytes(
                        "{\"error\":{\"code\":\"model_not_found\"}}".toByteArray()))
                }
            }
            val p = realProvider(transport)
            FileRunHistory.create(root.resolve("http-rejection-$status"), "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                val saved = checkpoint(h, "CONTINUATION").copy(
                    settingsKey = settingsKey("provider", f.request(), p.contextConfigurationKey()))
                h.saveCheckpoint(saved)
                assertEquals(RunState.FAILED, loop(f, p, h,
                    ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity(),
                    limits = RunLimits(maxRetries = saved.retries)).run().state)
                assertEquals(1, transport.requests); assertNull(h.retryReconciliation())
                val next = FaultTransport("success")
                assertEquals(RunState.COMPLETED, loop(f, realProvider(next), h, ContextPolicy("goal")).run().state)
                assertEquals(1, next.requests)
            }
        }
    }

    @Test fun `accepted unknown write allows ordinary question but next write still requires unknown approval`() = runTest {
        val definition = testDefinition(risk = Risk.WRITE).copy(exclusiveResource = "external-file")
        val f = RunFixture(listOf(definition, testDefinition("read")))
        f.onSubmit = { f.ledger.recordOutcome(it.callId, it.attemptId, Status.FAILED, "write uncertain", Effect.UNKNOWN) }
        FileRunHistory.create(root.resolve("accepted-write"), "session", durability).use { h ->
            h.append(0, listOf(user("write")))
            assertEquals(RunState.PAUSED, loop(f, ScriptProvider(listOf(calls("tool"))), h, ContextPolicy("goal")).run().state)
            val ticket = f.submitted.single()
            assertIs<Mutation.Blocked>(f.ledger.resolveRecovery(ticket.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
            f.ledger.recordActualExit(ticket.callId, ticket.attemptId, "worker exited")
            assertIs<Mutation.Applied>(f.ledger.resolveRecovery(ticket.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
            h.append(h.revision(), listOf(user("what happened?")))
            f.onSubmit = { f.ledger.complete(it.callId, it.attemptId, Status.SUCCEEDED, "read complete") }
            val p = ScriptProvider(listOf(calls("read"), answer("final answer")))
            assertEquals(RunState.COMPLETED, f.loop(p, history = h).run().state)
            assertEquals(2, p.requests.size)
            assertEquals(InstanceState.EXIT_CONFIRMED, f.ledger.get(f.submitted.last().callId)!!.instance!!.state)
            var approval: ApprovalRequest? = null
            f.approve = { approval = it; Decision.DENY }
            val next = ScriptProvider(listOf(calls("tool", args = "{\"next\":true}"), answer()))
            f.loop(next, history = h).run()
            assertTrue(ticket.callId in assertNotNull(approval).unknownCalls)
            assertEquals(2, f.submitted.size)
            assertEquals(Effect.UNKNOWN, f.ledger.get(ticket.callId)!!.result!!.effect)
            assertEquals(RecoveryResolution.ACCEPTED_UNCERTAINTY, f.ledger.get(ticket.callId)!!.isolation!!.resolution)
        }
    }

    @Test fun `retry acknowledgement is atomic durable and bound to latest evidence`() = runTest {
        for (stage in HistoryWriteStage.entries) {
            val dir = root.resolve("confirm-$stage")
            lateinit var token: String
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                h.saveBarrier(ExecutionBarrier("session", "chain", "run", "uncertain", 4, 2, "barrier-1"))
                h.saveCheckpoint(checkpoint(h).copy(serverUnresolved = true, pauseClass = "ADMITTED"))
                val stale = retryId(h)
                h.saveBarrier(h.loadBarrier()!!.copy(id = "barrier-2"))
                assertFalse(confirmRetry(h, stale))
                token = retryId(h)
            }
            FileRunHistory.open(dir, durability) { point ->
                if (point.file == "reconciliations.json" && point.stage == stage) error("injected")
            }.use { h ->
                assertFailsWith<HistoryStorageException> { confirmRetry(h, token) }
                assertFailsWith<IllegalStateException> { h.retryReconciliation() }
            }
            FileRunHistory.open(dir, durability).use { h ->
                val committed = stage in setOf(HistoryWriteStage.AFTER_REPLACE, HistoryWriteStage.AFTER_DIRECTORY_SYNC)
                assertEquals(committed, h.retryReconciliation() == null)
                val f = RunFixture(); val p = ScriptProvider(listOf(answer()))
                assertEquals(if (committed) RunState.COMPLETED else RunState.PAUSED,
                    loop(f, p, h, ContextPolicy("goal")).run().state)
                assertEquals(if (committed) 1 else 0, p.requests.size)
                assertTrue(Files.exists(dir.resolve("execution.json"))) // receipt, not deletion
                if (!committed) assertTrue(confirmRetry(h, retryId(h)))
                assertFalse(confirmRetry(h, token))
                assertTrue(Files.size(dir.resolve("reconciliations.json")) > 0)
            }
        }
    }

    @Test fun `unclosed admitted failures and transport exceptions require reconciliation not automatic retry`() = runTest {
        for (end in listOf("throw", "unclosed", "missing-terminal")) {
            val f = RunFixture()
            FileRunHistory.create(root.resolve("uncertain-$end"), "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
                val p = object : ModelProvider by ScriptProvider(emptyList()) {
                    override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                        if (end == "throw") error("transport disappeared")
                        if (end == "unclosed") {
                            emit(Events.failed(modelError(ModelErrorCode.INVALID_REQUEST, "not closed")))
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                }
                assertEquals(RunState.PAUSED, loop(f, p, h,
                    ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run().state)
                assertEquals("ADMITTED", h.loadCheckpoint()!!.pauseClass)
                val next = ScriptProvider(listOf(answer()))
                assertEquals(RunState.PAUSED, loop(f, next, h, ContextPolicy("goal")).run().state)
                assertTrue(next.requests.isEmpty())
                assertTrue(confirmRetry(h, retryId(h)))
                assertEquals(RunState.COMPLETED, loop(f, next, h, ContextPolicy("goal")).run().state)
            }
        }
    }

    @Test fun `summary definite failure supports changed window while interrupted summary needs confirmation`() = runTest {
        for (end in listOf("failed", "cancel", "unclosed")) {
            val f = RunFixture()
            FileRunHistory.create(root.resolve("summary-$end"), "session", durability).use { h ->
                h.append(0, listOf(user("old".repeat(1000)), user("goal")))
                val p = ScriptProvider(listOf(answer()))
                val s = if (end == "failed") ScriptProvider(listOf(failure(ModelErrorCode.INVALID_REQUEST)))
                    else object : ModelProvider by ScriptProvider(emptyList()) {
                        override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                            assertFalse(confirmRetry(h, retryId(h))) // no confirmation while run is active
                            if (end == "unclosed") emit(Events.failed(modelError(ModelErrorCode.INVALID_REQUEST, "not EOF")))
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                val run = loop(f, p, h, ContextPolicy("goal", 1, summary(s)))
                if (end == "cancel") assertFailsWith<kotlinx.coroutines.CancellationException> {
                    kotlinx.coroutines.withTimeout(10) { run.run() }
                } else assertEquals(RunState.PAUSED, run.run().state)
                assertTrue(p.requests.isEmpty())
                val saved = h.loadCheckpoint()!!
                if (end == "failed") {
                    assertEquals("BUDGET", saved.pauseClass)
                    assertEquals(RunState.COMPLETED, loop(f, p, h,
                        ContextPolicy("goal", 1, readmitBudgetCheckpointId = saved.id), b = budget(10_000)).run().state)
                } else {
                    assertEquals("SUMMARY_RESERVED", saved.pauseClass)
                    assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal"), b = budget(10_000)).run().state)
                    assertFalse(confirmRetry(h, retryId(h), identity.copy(unattended = true)))
                    assertTrue(confirmRetry(h, retryId(h)))
                    assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal"), b = budget(10_000)).run().state)
                }
                assertEquals(1, p.requests.size)
                assertTrue(h.loadAccounting("chain")!!.retries >= 1)
            }
        }
    }

    @Test fun `replay incompatible failed summary has attended current evidence retry path`() = runTest {
        for (end in listOf("failed", "cancel", "unclosed")) {
            val f = RunFixture()
            FileRunHistory.create(root.resolve("review-$end"), "session", durability).use { h ->
                h.append(0, listOf(user("goal"), message(Role.ASSISTANT,
                    textPart("old", replayData("opaque", "m", "retain me")))))
                val p = ScriptProvider(listOf(answer()), replay = false)
                val s = if (end == "failed") ScriptProvider(listOf(failure(ModelErrorCode.INVALID_REQUEST)))
                    else object : ModelProvider by ScriptProvider(emptyList()) {
                        override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                            if (end == "unclosed") emit(Events.failed(modelError(ModelErrorCode.INVALID_REQUEST, "unclosed")))
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                val run = loop(f, p, h, ContextPolicy("goal", summaryModel = summary(s)))
                if (end == "cancel") assertFailsWith<kotlinx.coroutines.CancellationException> {
                    kotlinx.coroutines.withTimeout(10) { run.run() }
                } else assertEquals(RunState.PAUSED, run.run().state)
                assertTrue(p.requests.isEmpty())
                assertEquals(if (end == "failed") "REVIEW" else "SUMMARY_RESERVED", h.loadCheckpoint()!!.pauseClass)
                p.replay = true // host fixes adapter, but configuration alone is not confirmation
                assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal")).run().state)
                assertTrue(p.requests.isEmpty())
                val stale = retryId(h)
                h.saveCheckpoint(h.loadCheckpoint()!!.copy(id = "new-review"))
                assertFalse(confirmRetry(h, stale))
                val current = retryId(h)
                assertFalse(confirmRetry(h, current, identity.copy(unattended = true)))
                assertTrue(confirmRetry(h, current)); assertFalse(confirmRetry(h, current))
                val result = loop(f, p, h, ContextPolicy("goal")).run()
                assertEquals(RunState.COMPLETED, result.state); assertEquals(1, p.requests.size)
                assertTrue(result.retries >= 1)
            }
        }
    }

    @Test fun `reconciliation does not release live local execution or external isolation`() = runTest {
        val f = RunFixture(listOf(testDefinition(risk = Risk.WRITE)))
        f.onSubmit = { f.ledger.recordOutcome(it.callId, it.attemptId, Status.FAILED, "uncertain", Effect.UNKNOWN) }
        FileRunHistory.create(root.resolve("live-confirm"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            loop(f, ScriptProvider(listOf(calls("tool"))), h, ContextPolicy("goal")).run()
            h.saveBarrier(ExecutionBarrier("session", "chain", "run", "remote uncertain", 1, 0, "barrier"))
            assertTrue(confirmRetry(h, retryId(h)))
            val p = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal")).run().state)
            assertTrue(p.requests.isEmpty())
            val record = f.ledger.get(f.submitted.single().callId)!!
            assertEquals(InstanceState.UNCONFIRMED, record.instance!!.state)
            assertNull(record.isolation!!.resolution)
        }
    }

    @Test fun `confirmed retry reserves fresh identity even with context policy disabled`() = runTest {
        for (pauseClass in listOf("ADMITTED", "REVIEW")) {
        val f = RunFixture()
        FileRunHistory.create(root.resolve("retry-again-$pauseClass"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            h.saveCheckpoint(checkpoint(h).copy(pauseClass = pauseClass))
            val first = retryId(h)
            assertTrue(confirmRetry(h, first))
            val cancelled = object : ModelProvider by ScriptProvider(emptyList()) {
                override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                    assertNotEquals(first, retryId(h))
                    assertFalse(confirmRetry(h, retryId(h)))
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                kotlinx.coroutines.withTimeout(10) { f.loop(cancelled, history = h).run() }
            }
            val second = retryId(h)
            assertNotEquals(first, second); assertFalse(confirmRetry(h, first))
            val p = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, f.loop(p, history = h).run().state)
            assertTrue(p.requests.isEmpty()); assertTrue(confirmRetry(h, second))
            assertEquals(RunState.COMPLETED, f.loop(p, history = h).run().state)
            assertNull(h.retryReconciliation()); assertEquals("COMPLETE", h.loadCheckpoint()!!.kind)
        }
        }
    }

    @Test fun `budget includes all input and output reservation and unknown is not zero`() {
        val r = chatRequest("m") { system = "system"; messages = listOf(user("history"), user("new"))
            tools = listOf(modelTool("t", "description", buildJsonObject { put("type", "object") }))
            maxOutputTokens = 100 }
        assertEquals(cost(r) + 100, budget().tokens(r))
        val noOutput = chatRequest("m") { messages = r.messages }
        assertNull(budget().tokens(noOutput))
        assertEquals(cost(noOutput) + 200, ContextBudget(::cost, { 1000 }, 200).tokens(noOutput))
        assertNull(budget(null).limit("m"))
        assertFailsWith<ArithmeticException> { ContextBudget({ Long.MAX_VALUE }, { Long.MAX_VALUE }).tokens(r) }
    }

    @Test fun `cache keys exclude request id and new content but bind settings and catalog`() {
        val a = chatRequest("m") { requestId = "one"; system = "fixed"; reasoningLevel = "high"
            tools = listOf(modelTool("z", "Z", JsonObject(emptyMap())), modelTool("a", "A", JsonObject(emptyMap()))) }
        val b = copyRequest(a, listOf(user("new")), requestId = "two")
        assertEquals(listOf("a", "z"), a.tools.map { it.name })
        assertEquals(settingsKey("p", a), settingsKey("p", b))
        assertNotEquals(settingsKey("p", a), settingsKey("q", a))
        assertNotEquals(settingsKey("p", a), settingsKey("p", copyRequest(a, model = "other")))
        for (changed in listOf(
            object : ChatRequest by a { override val system = "changed" },
            object : ChatRequest by a { override val reasoningLevel = "low" },
            object : ChatRequest by a { override val tools = emptyList<ModelTool>() },
            object : ChatRequest by a { override val maxOutputTokens = 500 }))
            assertNotEquals(settingsKey("p", a), settingsKey("p", changed))
    }

    @Test fun `provider endpoint and translation changes invalidate context key`() {
        val req = chatRequest("m")
        fun key(url: String, explicit: Boolean = false) = OpenAiChatProvider(OpenAiChatConfig("p", url,
            compat = OpenAiChatConfig.Compat(explicitCacheControl = explicit))).contextConfigurationKey()
        assertEquals(settingsKey("p", req, key("https://a.test")), settingsKey("p", req, key("https://a.test")))
        assertNotEquals(settingsKey("p", req, key("https://a.test")), settingsKey("p", req, key("https://b.test")))
        assertNotEquals(settingsKey("p", req, key("https://a.test")), settingsKey("p", req, key("https://a.test", true)))
    }

    @Test fun `breakpoint preserves source and unsupported adapter ignores hint`() {
        val source = listOf(user("old"), message(Role.ASSISTANT, textPart("answer")), user("new"))
        val view = cachedView(source)
        assertEquals(listOf(false, true, false), view.map { it.cacheBreakpoint })
        assertTrue(source.none { it.cacheBreakpoint })
        val req = chatRequest("m") { messages = view; system = "stable" }
        assertTrue(copyRequest(req).messages[1].cacheBreakpoint)
        val normal = OpenAiChatCodec(OpenAiChatConfig("p", "https://example.test"))
        assertEquals(normal.encode(req), normal.encode(copyRequest(req, source)))
        val supported = OpenAiChatCodec(OpenAiChatConfig("p", "https://example.test",
            compat = OpenAiChatConfig.Compat(explicitCacheControl = true)))
        val wire = Json.parseToJsonElement(supported.encode(req)).jsonObject["messages"]!!.jsonArray
        assertEquals("ephemeral", wire[2].jsonObject["content"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("new", wire.last().jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun `unsupported explicit caching retries once without changing history`() = runTest {
        val f = RunFixture()
        val h = MemoryRunHistory(listOf(user("old"), message(Role.ASSISTANT, textPart("answer")), user("new")))
        val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "cache_control"), answer()))
        assertEquals(RunState.COMPLETED, f.loop(p, history = h).run().state)
        assertTrue(p.requests[0].messages.any { it.cacheBreakpoint })
        assertTrue(p.requests[1].messages.none { it.cacheBreakpoint })
        assertEquals(p.requests[0].messages.map(HistoryCodec::encodeMessage), p.requests[1].messages.map(HistoryCodec::encodeMessage))
    }

    @Test fun `oversized active tool transaction checkpoints without splitting or replaying`() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        f.onSubmit = { f.ledger.complete(it.callId, it.attemptId, Status.SUCCEEDED, "x".repeat(5000)) }
        val p = ScriptProvider(listOf(calls("tool"), answer()))
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val result = loop(f, p, h, ContextPolicy("goal")).run()
            assertEquals(RunState.PAUSED, result.state)
            assertEquals(1, p.requests.size); assertEquals(1, f.submitted.size)
            assertEquals(3, h.snapshot().messages.size)
            validateHistory(h.snapshot().messages)
            val saved = assertNotNull(h.loadCheckpoint())
            assertEquals("PAUSED", saved.kind); assertEquals(1, saved.steps)
            assertEquals(f.ledger.get(f.submitted.single().callId), saved.ledger.single())
        }
        FileRunHistory.open(root.resolve("h"), durability).use { h ->
            val saved = assertNotNull(h.loadCheckpoint())
            assertTrue(saved.matches(h.snapshot(), settingsKey("provider", f.request())))
            assertEquals(3, saved.sourceCount); assertEquals(3, h.snapshot().messages.size)
        }
    }

    @Test fun `prior compaction changes only request view and retains current goal`() = runTest {
        val f = RunFixture(); val p = ScriptProvider(listOf(answer()))
        val summarizer = ScriptProvider(listOf(answer("old facts")))
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("old".repeat(1000)), message(Role.ASSISTANT, textPart("past"))))
            h.append(1, listOf(user("goal")))
            val result = loop(f, p, h, ContextPolicy("goal", 2, summary(summarizer)),
                limits = RunLimits(maxRetries = 50)).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertEquals("goal", (p.requests.single().messages.last().parts.single() as ContentPart.Text).text)
            assertEquals(4, h.snapshot().messages.size)
            assertTrue(h.snapshot().messages.first().parts.filterIsInstance<ContentPart.Text>().single().text.length > 2000)
            assertTrue(summarizer.requests.all { it.tools.isEmpty() && it.toolChoice == ToolChoice.None && it.extraBody.isEmpty() })
            assertTrue(summarizer.requests.all { budget(1800).tokens(it)!! <= 1800 })
            assertEquals("COMPLETE", h.loadCheckpoint()!!.kind)
            assertEquals("VIEW", h.loadView()!!.kind)
        }
    }

    @Test fun `checkpoint continuation reconstructs after restart with inherited counters`() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        f.onSubmit = { f.ledger.complete(it.callId, it.attemptId, Status.SUCCEEDED, "x".repeat(5000)) }
        lateinit var saved: ContextCheckpoint
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val p = ScriptProvider(listOf(calls("tool")))
            val result = loop(f, p, h, ContextPolicy("goal", summaryModel = summary()),
                limits = RunLimits(maxRetries = 50)).run()
            saved = h.loadCheckpoint()!!
            assertEquals("CONTINUATION", saved.kind); assertEquals(result.steps, saved.steps)
            assertTrue(saved.steps > 1); assertEquals(1, p.requests.size)
        }
        FileRunHistory.open(root.resolve("h"), durability).use { h ->
            assertEquals(saved, h.loadCheckpoint())
            val p = ScriptProvider(listOf(answer()))
            val result = loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertEquals(saved.steps + 1, result.steps); assertEquals(saved.retries, result.retries)
            assertTrue(p.requests.single().messages.flatMap { it.parts }.none { it is ContentPart.ToolCall || it is ContentPart.ToolResult || it.replay != null })
            assertEquals(1, f.submitted.size); assertEquals(4, h.snapshot().messages.size)
        }
    }

    @Test fun `oversized user or system pauses without summarizing or truncating`() = runTest {
        for (system in listOf(false, true)) {
            val f = RunFixture(); val p = ScriptProvider(listOf(answer())); val s = ScriptProvider(listOf(answer()))
            FileRunHistory.create(root.resolve("h$system"), "session", durability).use { h ->
                h.append(0, listOf(user(if (system) "goal" else "x".repeat(3000))))
                val req = if (system) copyRequest(object : ChatRequest by f.request() { override val system = "x".repeat(3000) }) else f.request()
                assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal", summaryModel = summary(s)), request = req).run().state)
                assertTrue(p.requests.isEmpty()); assertTrue(s.requests.isEmpty())
                assertEquals("PAUSED", h.loadCheckpoint()!!.kind)
                assertEquals(1, h.snapshot().messages.size)
            }
        }
    }

    @Test fun `unknown window tries once then saves checkpoint not unchanged retry`() = runTest {
        val f = RunFixture(); val p = ScriptProvider(listOf(failure(ModelErrorCode.CONTEXT_TOO_LONG)))
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal"), b = budget(null)).run().state)
            assertEquals(1, p.requests.size); assertNotNull(h.checkpointInfo())
        }
    }

    @Test fun `server loop and unknown effect never trigger summary continuation`() = runTest {
        for (unknown in listOf(false, true)) {
            val f = RunFixture(); val s = ScriptProvider(listOf(answer()))
            val p = ScriptProvider(listOf(if (unknown) failure(ModelErrorCode.NETWORK)
                else answer("x".repeat(3000), FinishReason.PAUSED)), mode = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
            FileRunHistory.create(root.resolve("h$unknown"), "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal", summaryModel = summary(s))).run().state)
                assertEquals(1, p.requests.size); assertTrue(s.requests.isEmpty())
                assertTrue(h.loadCheckpoint()!!.serverUnresolved); assertEquals("PAUSED", h.loadCheckpoint()!!.kind)
            }
        }
    }

    @Test fun `summary provider with possible server tools is never submitted`() = runTest {
        val f = RunFixture(); val s = ScriptProvider(listOf(answer()), mode = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("x".repeat(2500)), message(Role.ASSISTANT, textPart("old")), user("goal")))
            loop(f, ScriptProvider(listOf(answer())), h, ContextPolicy("goal", 2, summary(s))).run()
            assertTrue(s.requests.isEmpty()); assertEquals("PAUSED", h.loadCheckpoint()!!.kind)
        }
    }

    @Test fun `summary failure persists pause and never resends full oversized request`() = runTest {
        val f = RunFixture(); val p = ScriptProvider(listOf(answer()))
        val s = ScriptProvider(listOf(failure(ModelErrorCode.SERVER)))
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("x".repeat(2500)), message(Role.ASSISTANT, textPart("old")), user("goal")))
            loop(f, p, h, ContextPolicy("goal", 2, summary(s))).run()
            assertTrue(p.requests.isEmpty()); assertEquals("PAUSED", h.loadCheckpoint()!!.kind)
            assertTrue(s.requests.all { budget(1800).tokens(it)!! <= 1800 })
        }
    }

    @Test fun `real adapter preserves cache rejection parameter for normal fallback`() = runTest {
        val transport = object : ModelTransport {
            override fun exchange(url: String, body: String, token: String) = flow {
                emit(TransportEvent.Headers(400, "application/json", null))
                emit(TransportEvent.Bytes("""{"error":{"code":"unsupported_parameter","param":"cache_control","message":"unsupported"}}""".toByteArray()))
            }
        }
        val provider = OpenAiChatProvider(OpenAiChatConfig("p", "https://example.test"), transport)
        val result = provider.chat(chatRequest("m"), Credentials { "fake" }).toList()
        val error = assertIs<ModelEvent.Failed>(result.last()).error
        assertEquals(ModelErrorCode.UNSUPPORTED_PARAMETER, error.code)
        assertEquals("cache_control", error.param)
    }

    @Test fun `tool pair summary input stays paired or referenced bounded data and keeps ordering`() = runTest {
        val binding = callBinding("p", "request", "response", 0, "wire")
        val pair = listOf(message(Role.ASSISTANT, textPart("before"),
            toolCallPart(binding, "tool", "{}"), textPart("after")),
            message(Role.TOOL, toolResultPart(binding.callId, "result".repeat(500))))
        val p = ScriptProvider(listOf(answer("short")))
        var attempts = 0
        assertNotNull(ContextSummarizer(summary(p, 1000), MemoryRunJournal(), EventTimeouts(100, 100)) {
            ++attempts < 100
        }.compact(pair, "session@7"))
        assertTrue(p.requests.size > 2)
        assertTrue(p.requests.all { budget(1000).tokens(it)!! <= 1000 })
        assertTrue(p.requests.all { it.messages.all { m -> m.role == Role.USER && m.parts.all { part -> part is ContentPart.Text } } })
        val first = (p.requests.first().messages.single().parts.single() as ContentPart.Text).text
        assertTrue(first.contains("session@7 messages 0..1")); assertTrue(first.contains("offset 0"))
        assertTrue(first.indexOf("before") < first.indexOf(binding.callId))
        assertEquals(pair, pair.map { message(it.role, *it.parts.toTypedArray()) })
        assertEquals(listOf(false, true), cachedView(pair).map { it.cacheBreakpoint })
    }

    @Test fun `persisted pause prevents automatic resubmission after reopen`() = runTest {
        val f = RunFixture(); val dir = root.resolve("h")
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("goal"))); h.saveCheckpoint(checkpoint(h))
        }
        FileRunHistory.open(dir, durability).use { h ->
            val p = ScriptProvider(listOf(answer()))
            val result = loop(f, p, h, ContextPolicy("goal")).run()
            assertEquals(RunState.PAUSED, result.state); assertEquals(4, result.steps); assertEquals(2, result.retries)
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test fun `continuation admission is consumed before provider submission`() = runTest {
        val f = RunFixture(); val dir = root.resolve("h")
        lateinit var saved: ContextCheckpoint
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("goal"))); saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
            val p = ScriptProvider(listOf(failure(ModelErrorCode.INVALID_REQUEST)))
            loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run()
            assertEquals(1, p.requests.size); assertEquals("COMPLETE", h.loadCheckpoint()!!.kind)
        }
        FileRunHistory.open(dir, durability).use { h ->
            h.append(h.revision(), listOf(user("new input is not admission acknowledgement")))
            val p = ScriptProvider(listOf(answer()))
            assertNotNull(h.loadView())
            assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal")).run().state)
            assertFailsWith<IllegalArgumentException> {
                loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run()
            }
            assertEquals(1, p.requests.size)
        }
    }

    @Test fun `checkpoint budget is rechecked on continuation and limits never reset`() = runTest {
        val f = RunFixture()
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal"))); val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
            val p = ScriptProvider(listOf(answer()))
            val result = loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity(),
                limits = RunLimits(maxSteps = 4)).run()
            assertEquals(RunState.PAUSED, result.state); assertEquals(4, result.steps); assertTrue(p.requests.isEmpty())
        }
        FileRunHistory.create(root.resolve("small"), "session", durability).use { h ->
            h.append(0, listOf(user("goal"))); val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
            val p = ScriptProvider(listOf(answer()))
            val result = loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity(), b = budget(150)).run()
            assertEquals(RunState.PAUSED, result.state); assertTrue(p.requests.isEmpty())
        }
    }

    @Test fun `cold reopen reconstructs continuation from disk ledger not in memory fixtures`() = runTest {
        val definition = testDefinition()
        val dir = root.resolve("h"); val ledgerDir = root.resolve("ledger")
        lateinit var saved: ContextCheckpoint
        TestInvocationStore.create(ledgerDir).use { store ->
            val gate = DefinitionGate().apply { install(definition) }
            val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            ledger.recoverColdStart("first-process")
            val bridge = object : ToolExecutionBridge {
                override fun submit(ticket: ExecutionTicket) {
                    ledger.complete(ticket.callId, ticket.attemptId, Status.SUCCEEDED, "x".repeat(4000))
                }
                override suspend fun awaitTerminal(callId: String) = Unit
                override fun requestCancel(callId: String) = Unit
            }
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                val p = ScriptProvider(listOf(calls("tool")))
                AgentRunLoop(identity, ModelTarget("provider", p, Credentials { "fake" }),
                    RunFixture(listOf(definition)).request(), h, LedgerToolExecutor(ledger, gate, listOf(definition), bridge),
                    budget = budget(), limits = RunLimits(maxRetries = 50),
                    contextPolicy = ContextPolicy("goal", summaryModel = summary())).run()
                saved = h.loadCheckpoint()!!; assertEquals("CONTINUATION", saved.kind)
            }
        }
        TestInvocationStore.open(ledgerDir).use { store ->
            val gate = DefinitionGate().apply { install(definition) }
            val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
            ledger.recoverColdStart("second-process")
            val bridge = object : ToolExecutionBridge {
                override fun submit(ticket: ExecutionTicket) = error("Completed work replayed")
                override suspend fun awaitTerminal(callId: String) = error("Unexpected await")
                override fun requestCancel(callId: String) = error("Unexpected cancel")
            }
            FileRunHistory.open(dir, durability).use { h ->
                val p = ScriptProvider(listOf(answer()))
                val result = AgentRunLoop(saved.identity(), ModelTarget("provider", p, Credentials { "fake" }),
                    RunFixture(listOf(definition)).request(), h, LedgerToolExecutor(ledger, gate, listOf(definition), bridge),
                    budget = budget(), contextPolicy = ContextPolicy("goal", resumeCheckpointId = saved.id)).run()
                assertEquals(RunState.COMPLETED, result.state); assertEquals(saved.steps + 1, result.steps)
                assertEquals(saved.ledger, store.records())
                assertTrue(p.requests.single().messages.flatMap { it.parts }.all { it is ContentPart.Text })
            }
        }
    }

    @Test fun `unknown tool effect and isolation survive checkpoint without summary`() = runTest {
        val f = RunFixture(listOf(testDefinition(risk = Risk.WRITE)))
        f.onSubmit = { f.ledger.recordOutcome(it.callId, it.attemptId, Status.SUCCEEDED, "remote outcome", Effect.UNKNOWN) }
        val s = ScriptProvider(listOf(answer()))
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val p = ScriptProvider(listOf(calls("tool")))
            val result = loop(f, p, h, ContextPolicy("goal", summaryModel = summary(s))).run()
            assertEquals(RunState.PAUSED, result.state)
            val saved = assertNotNull(h.loadCheckpoint())
            assertEquals("PAUSED", saved.kind); assertFalse(saved.serverUnresolved)
            assertEquals("LOCAL_WAIT", saved.pauseClass); assertNull(h.loadBarrier())
            assertEquals(Effect.UNKNOWN, saved.ledger.single().result!!.effect)
            assertNotNull(saved.ledger.single().isolation); assertTrue(s.requests.isEmpty())
        }
    }

    @Test fun `pending intent forbids checkpoint commit before ledger reconciliation`() {
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal"))); val saved = checkpoint(h)
            val binding = callBinding("p", "request", "response", 0, "wire")
            h.prepareStep(1, identity, "step", message(Role.ASSISTANT, toolCallPart(binding, "tool", "{}")))
            assertFailsWith<IllegalStateException> { h.saveCheckpoint(saved) }
            assertTrue(Files.exists(root.resolve("h/pending.json"))); assertFalse(Files.exists(root.resolve("h/context.json")))
        }
    }

    private fun checkpoint(h: FileRunHistory, kind: String = "PAUSED") = h.snapshot().let { source ->
        ContextCheckpoint(id = "checkpoint", sessionId = "session", taskChainId = "chain", runId = "run",
            nextRunId = if (kind == "CONTINUATION") "next" else null, unattended = false,
            sourceRevision = source.revision, sourceCount = source.messages.size, sourceDigest = sourceDigest(source.messages),
            settingsKey = settingsKey("provider", RunFixture().request()), goal = "goal", steps = 4, retries = 2,
            kind = kind, summary = if (kind == "PAUSED") null else "summary", reason = "budget", serverUnresolved = false,
            ledger = emptyList(), catalog = emptyList())
    }

    @Test fun `checkpoint atomic faults reopen old or committed value never half value`() {
        for (stage in HistoryWriteStage.entries) {
            val dir = root.resolve(stage.name)
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("goal"))); h.saveCheckpoint(checkpoint(h))
            }
            FileRunHistory.open(dir, durability) { point ->
                if (point.file == "context.json" && point.stage == stage) error("injected")
            }.use { h ->
                assertFailsWith<HistoryStorageException> { h.saveCheckpoint(checkpoint(h).copy(id = "new")) }
                assertFailsWith<IllegalStateException> { h.snapshot() }
            }
            FileRunHistory.open(dir, durability).use { h ->
                val committed = stage >= HistoryWriteStage.AFTER_REPLACE
                assertEquals(if (committed) "new" else "checkpoint", h.loadCheckpoint()!!.id)
                assertEquals(1, h.snapshot().messages.size)
            }
        }
    }

    @Test fun `corrupt final checkpoint fails closed and abandoned temporary is ignored`() {
        val dir = root.resolve("h")
        FileRunHistory.create(dir, "session", durability).use { h -> h.append(0, listOf(user("goal"))); h.saveCheckpoint(checkpoint(h)) }
        Files.write(dir.resolve(".context.json.tmp"), byteArrayOf(1, 2, 3))
        FileRunHistory.open(dir, durability).use { h -> assertNotNull(h.loadCheckpoint()); assertFalse(Files.exists(dir.resolve(".context.json.tmp"))) }
        Files.write(dir.resolve("context.json"), byteArrayOf(1, 2, 3))
        FileRunHistory.open(dir, durability).use { h ->
            assertFailsWith<HistoryStorageException> { h.loadCheckpoint() }
            assertEquals(1, h.snapshot().messages.size)
        }
    }

    @Test fun `stale revision and changed settings cannot resume`() = runTest {
        val f = RunFixture()
        FileRunHistory.create(root.resolve("h"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
            val p = ScriptProvider(listOf(answer()))
            val changed = copyRequest(object : ChatRequest by f.request() { override val system = "changed" })
            assertFailsWith<IllegalArgumentException> {
                loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity(), request = changed).run()
            }
            h.append(1, listOf(user("edited continuation")))
            assertNull(h.checkpointInfo())
            assertFailsWith<IllegalArgumentException> {
                loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run()
            }
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test fun `server barriers survive append configuration changes and every paused limit`() = runTest {
        for (scenario in listOf("failure", "paused-limit", "step-limit", "cancel")) {
            val dir = root.resolve(scenario)
            val f = RunFixture(); val s = ScriptProvider(listOf(answer()))
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                val p = if (scenario == "cancel") object : ModelProvider by ScriptProvider(emptyList()) {
                    override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
                    override fun chat(request: ChatRequest, credentials: Credentials) = flow<ModelEvent> {
                        kotlinx.coroutines.awaitCancellation()
                    }
                } else ScriptProvider(listOf(if (scenario == "failure") failure(ModelErrorCode.NETWORK)
                    else answer("server progress", FinishReason.PAUSED)), mode = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS)
                val run = loop(f, p, h, ContextPolicy("goal", summaryModel = summary(s)),
                    limits = if (scenario == "step-limit") RunLimits(maxSteps = 2) else RunLimits(maxPaused = 1))
                if (scenario == "cancel") assertFailsWith<kotlinx.coroutines.CancellationException> {
                    kotlinx.coroutines.withTimeout(10) { run.run() }
                }
                else assertTrue(run.run().serverExecutionUnknown)
                assertNotNull(h.loadBarrier())
            }
            FileRunHistory.open(dir, durability).use { h ->
                h.append(h.revision(), listOf(user("appended".repeat(1000))))
                val p = ScriptProvider(listOf(answer()))
                val result = loop(f, p, h, ContextPolicy("different goal", summaryModel = summary(s)),
                    request = copyRequest(f.request(), model = "changed"), b = budget(10)).run()
                assertEquals(RunState.PAUSED, result.state); assertTrue(result.serverExecutionUnknown)
                assertTrue(p.requests.isEmpty()); assertTrue(s.requests.isEmpty())
                val token = retryId(h)
                assertFalse(confirmRetry(h, token, identity.copy(unattended = true)))
                assertFalse(confirmRetry(h, token, identity.copy(sessionId = "wrong")))
                assertFalse(confirmRetry(h, "stale"))
                assertTrue(confirmRetry(h, token))
                assertFalse(confirmRetry(h, token))
            }
            FileRunHistory.open(dir, durability).use { h ->
                val p = ScriptProvider(listOf(answer()))
                assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal"), b = budget(100_000)).run().state)
                assertEquals(1, p.requests.size)
            }
        }
    }

    @Test fun `budget readmission validates changed settings source ledger and unsafe pause`() = runTest {
        for (addSummary in listOf(false, true)) {
            val dir = root.resolve("readmit$addSummary"); val f = RunFixture()
            lateinit var saved: ContextCheckpoint
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("old".repeat(800)), message(Role.ASSISTANT, textPart("past")), user("goal")))
                loop(f, ScriptProvider(listOf(answer())), h, ContextPolicy("goal", 2)).run()
                saved = h.loadCheckpoint()!!; assertEquals("BUDGET", saved.pauseClass)
            }
            FileRunHistory.open(dir, durability).use { h ->
                val p = ScriptProvider(listOf(answer())); val s = ScriptProvider(listOf(answer("facts")))
                val policy = ContextPolicy("goal", 2, if (addSummary) summary(s) else null,
                    readmitBudgetCheckpointId = saved.id)
                assertFailsWith<IllegalArgumentException> {
                    loop(f, p, h, ContextPolicy("goal", 2, readmitBudgetCheckpointId = saved.id)).run()
                }
                val result = loop(f, p, h, policy, b = budget(if (addSummary) 1500 else 10000)).run()
                assertEquals(RunState.COMPLETED, result.state); assertEquals(1, p.requests.size)
                assertEquals(addSummary, s.requests.isNotEmpty())
            }
        }
        FileRunHistory.create(root.resolve("unsafe"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            h.saveCheckpoint(checkpoint(h).copy(pauseClass = "ADMITTED"))
            val p = ScriptProvider(listOf(answer()))
            assertFailsWith<IllegalArgumentException> {
                loop(RunFixture(), p, h, ContextPolicy("goal", readmitBudgetCheckpointId = "checkpoint"), b = budget(10000)).run()
            }
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test fun `continuation view survives successful admission append and cold reopen`() = runTest {
        val f = RunFixture(); val dir = root.resolve("view")
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("old".repeat(1000))))
            val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
            assertEquals(RunState.COMPLETED, loop(f, ScriptProvider(listOf(answer())), h,
                ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run().state)
            assertNotNull(h.loadView())
        }
        FileRunHistory.open(dir, durability).use { h ->
            val count = h.snapshot().messages.size
            h.append(h.revision(), listOf(user("next question")))
            val p = ScriptProvider(listOf(answer())); val s = ScriptProvider(listOf(answer()))
            assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("next goal", count, summary(s)),
                id = identity.copy(runId = "later")).run().state)
            assertTrue(s.requests.isEmpty())
            val text = p.requests.single().messages.first().parts.filterIsInstance<ContentPart.Text>().single().text
            assertTrue(text.contains("Summary data:")); assertTrue(text.contains("summary"))
        }
    }

    @Test fun `default limits pack thirteen complete messages into bounded summary calls`() = runTest {
        val f = RunFixture(); val s = ScriptProvider(listOf(answer("facts"))); val p = ScriptProvider(listOf(answer()))
        FileRunHistory.create(root.resolve("packed"), "session", durability).use { h ->
            h.append(0, (0..13).map { user("history-$it ".repeat(20)) } + user("goal"))
            val result = loop(f, p, h, ContextPolicy("goal", 14, summary(s, 10000))).run()
            assertEquals(RunState.COMPLETED, result.state)
            assertEquals(1, s.requests.size); assertEquals(1, result.retries)
            val input = s.requests.single().messages.single().parts.filterIsInstance<ContentPart.Text>().single().text
            assertTrue(input.indexOf("history-0 ") < input.indexOf("history-13 "))
        }
    }

    @Test fun `definitely rejected summary reservations retain counters through explicit budget readmission`() = runTest {
        val f = RunFixture(); val dir = root.resolve("failed-summary")
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("old".repeat(1000)), user("goal")))
            repeat(3) { n ->
                val s = ScriptProvider(listOf(failure(ModelErrorCode.INVALID_REQUEST))); val p = ScriptProvider(listOf(answer()))
                val saved = h.loadCheckpoint()
                val result = loop(f, p, h, ContextPolicy("goal", 1, summary(s, 1800L + n),
                    readmitBudgetCheckpointId = saved?.id), limits = RunLimits(maxRetries = 2)).run()
                assertEquals(RunState.PAUSED, result.state); assertTrue(p.requests.isEmpty())
                assertEquals(minOf(n + 1, 2), result.retries)
                assertEquals(if (n < 2) 1 else 0, s.requests.size)
            }
        }
    }

    @Test fun `checkpoint streams escaped raw result and late evidence without giant preframe writes`() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        val raw = "raw\u0000\"\\😀".repeat(150_000)
        f.onSubmit = { f.ledger.complete(it.callId, it.attemptId, Status.SUCCEEDED, raw) }
        FileRunHistory.create(root.resolve("stream"), "session", durability).use { h ->
            h.append(0, listOf(user("goal")))
            loop(f, ScriptProvider(listOf(calls("tool"))), h, ContextPolicy("goal")).run()
            val saved = h.loadCheckpoint()!!
            val record = saved.ledger.single()
            val enriched = saved.copy(ledger = listOf(record.copy(evidence = listOf(
                ExitEvidence(record.instance!!.attemptId, raw + "late", record.result)))))
            var largestWrite = 0; var chars = 0L
            val writer = object : java.io.Writer() {
                override fun write(cbuf: CharArray, off: Int, len: Int) { largestWrite = maxOf(largestWrite, len); chars += len }
                override fun write(str: String, off: Int, len: Int) { largestWrite = maxOf(largestWrite, len); chars += len }
                override fun flush() = Unit
                override fun close() = Unit
            }
            HistoryCodec.writeValue(enriched, writer)
            assertTrue(largestWrite <= 8192, "Preframe write allocated $largestWrite chars")
            assertTrue(chars > raw.length * 3L) // escaping plus original result and late evidence/outcome
            h.saveCheckpoint(enriched)
            assertEquals(enriched, h.loadCheckpoint())
            // Serializer parity (including field order) is checked on the same full schema with small texts.
            val smallResult = record.result!!.copy(text = "small")
            val small = enriched.copy(ledger = listOf(record.copy(result = smallResult,
                evidence = listOf(ExitEvidence(record.instance.attemptId, "late", smallResult)))))
            val out = java.io.ByteArrayOutputStream()
            HistoryCodec.writeValue(small, out)
            assertEquals(HistoryCodec.json.encodeToString(ContextCheckpoint.serializer(), small), out.toString("UTF-8"))
        }
    }

    @Test fun `budget fitting transaction larger than transport buffer is never split`() = runTest {
        val s = ScriptProvider(listOf(answer("facts")))
        val binding = callBinding("p", "request", "response", 0, "wire")
        val pair = listOf(message(Role.ASSISTANT, toolCallPart(binding, "tool", "{}")),
            message(Role.TOOL, toolResultPart(binding.callId, "x".repeat(80_000))))
        assertNotNull(ContextSummarizer(summary(s, 100_000), MemoryRunJournal(), EventTimeouts(100, 100)) {
            true
        }.compact(pair, "original@1"))
        assertEquals(1, s.requests.size)
        assertFalse(s.requests.single().messages.single().parts.filterIsInstance<ContentPart.Text>().single().text.contains("fragment"))
    }

    @Test fun `crash after consume before response retains view but cannot replay admission`() = runTest {
        val f = RunFixture(); val dir = root.resolve("admission-crash")
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("old")))
            val saved = checkpoint(h, "CONTINUATION"); h.saveCheckpoint(saved)
            val p = object : ModelProvider by ScriptProvider(emptyList()) {
                override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                    assertEquals("ADMITTED", h.loadCheckpoint()!!.pauseClass)
                    assertNotNull(h.loadView())
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                kotlinx.coroutines.withTimeout(10) {
                    loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), saved.identity()).run()
                }
            }
        }
        FileRunHistory.open(dir, durability).use { h ->
            h.append(h.revision(), listOf(user("next")))
            val p = ScriptProvider(listOf(answer())); val s = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, loop(f, p, h, ContextPolicy("goal", 1, summary(s))).run().state)
            assertTrue(p.requests.isEmpty()); assertTrue(s.requests.isEmpty()); assertNotNull(h.loadView())
            val token = retryId(h)
            assertFalse(confirmRetry(h, token, identity.copy(unattended = true)))
            assertTrue(confirmRetry(h, token)); assertFalse(confirmRetry(h, token))
            assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal", 1)).run().state)
            assertEquals(1, p.requests.size)
        }
    }

    @Test fun `large escaped summary source stays bounded and references final fragment`() = runTest {
        val s = ScriptProvider(listOf(answer("short")))
        val text = "\u0000\"\\😀".repeat(20_000) + "FINAL-EVIDENCE"
        var attempts = 0
        assertNotNull(ContextSummarizer(summary(s, 100_000), MemoryRunJournal(), EventTimeouts(100, 100)) {
            ++attempts <= 100
        }.compact(listOf(user(text)), "original@1"))
        assertTrue(s.requests.all { budget(100_000).tokens(it)!! <= 100_000 })
        assertTrue(s.requests.any { it.messages.single().parts.filterIsInstance<ContentPart.Text>().single().text.contains("FINAL-EVIDENCE") })
        assertTrue(s.requests.first().messages.single().parts.filterIsInstance<ContentPart.Text>().single().text.contains("offset 0"))
    }

    @Test fun `unresolved admissions are session scoped across chain config history and policy changes`() = runTest {
        for (reservation in listOf("ADMITTED", "SUMMARY_RESERVED")) for (disabled in listOf(false, true)) {
            val dir = root.resolve("$reservation-$disabled"); val f = RunFixture()
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("goal")))
                h.saveCheckpoint(checkpoint(h).copy(pauseClass = reservation))
            }
            FileRunHistory.open(dir, durability).use { h ->
                h.append(h.revision(), listOf(user("different history")))
                val p = ScriptProvider(listOf(answer())); val s = ScriptProvider(listOf(answer()))
                val result = AgentRunLoop(identity.copy(taskChainId = "other-chain", runId = "other-run"),
                    ModelTarget("changed-provider", p, Credentials { "fake" }), copyRequest(f.request(), model = "changed"), h,
                    LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge), budget = budget(10_000),
                    contextPolicy = if (disabled) null else ContextPolicy("changed goal", 1, summary(s))).run()
                assertEquals(RunState.PAUSED, result.state)
                assertEquals(0, result.steps); assertEquals(0, result.retries)
                assertEquals(reservation, h.loadCheckpoint()!!.pauseClass)
                assertTrue(p.requests.isEmpty()); assertTrue(s.requests.isEmpty()); assertNull(h.loadBarrier())
                val token = retryId(h)
                assertTrue(confirmRetry(h, token)); assertFalse(confirmRetry(h, token))
                assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal"), b = budget(10_000)).run().state)
                assertEquals(1, p.requests.size)
            }
        }
    }

    @Test fun `reused view keeps provenance but current chain limits survive repeated cold restarts`() = runTest {
        val dir = root.resolve("accounting"); val f = RunFixture()
        lateinit var original: ContextCheckpoint
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("old")))
            original = checkpoint(h, "VIEW").copy(steps = 49, retries = 11)
            h.saveCheckpoint(original)
        }
        repeat(4) { n ->
            FileRunHistory.open(dir, durability).use { h ->
                h.append(h.revision(), listOf(user("B-$n")))
                val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "cache_control"), answer()))
                val result = loop(f, p, h, ContextPolicy("B goal", 1),
                    identity.copy(taskChainId = "B", runId = "B-$n"), limits = RunLimits(maxSteps = 2, maxRetries = 2)).run()
                assertEquals(minOf(n + 1, 2), result.steps)
                assertEquals(minOf(n + 1, 2), result.retries)
                assertEquals(if (n < 2) RunState.COMPLETED else RunState.PAUSED, result.state)
                assertEquals(if (n < 2) 2 else 0, p.requests.size)
                assertEquals("B", h.loadCheckpoint()!!.taskChainId)
                assertEquals("B-$n", h.loadCheckpoint()!!.runId)
                assertEquals(original, h.loadView())
            }
        }
        FileRunHistory.open(dir, durability).use { h ->
            val p = ScriptProvider(listOf(answer()))
            val result = loop(f, p, h, ContextPolicy("independent", 1), identity.copy(taskChainId = "C", runId = "C")).run()
            assertEquals(1, result.steps); assertEquals(0, result.retries)
            assertEquals(original, h.loadView())
        }
    }

    @Test fun `local actual exit resolves wait but unknown isolation cold recovery and remote barriers do not`() = runTest {
        for (scenario in listOf("exit", "unknown", "cold", "remote", "clear", "accept", "late", "sticky")) {
            val dir = root.resolve("local-$scenario"); val ledgerDir = root.resolve("ledger-$scenario")
            val definition = testDefinition(); val request = RunFixture(listOf(definition)).request()
            lateinit var ticket: ExecutionTicket
            TestInvocationStore.create(ledgerDir).use { store ->
                val gate = DefinitionGate().apply { install(definition) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("first")
                val bridge = object : ToolExecutionBridge {
                    override fun submit(value: ExecutionTicket) {
                        ticket = value
                        ledger.recordOutcome(value.callId, value.attemptId, Status.SUCCEEDED, "read result",
                            if (scenario in setOf("unknown", "clear", "accept", "late", "sticky")) Effect.UNKNOWN else Effect.NONE)
                    }
                    override suspend fun awaitTerminal(callId: String) = Unit
                    override fun requestCancel(callId: String) = Unit
                }
                FileRunHistory.create(dir, "session", durability).use { h ->
                    h.append(0, listOf(user("goal")))
                    val s = ScriptProvider(listOf(answer()))
                    val result = AgentRunLoop(identity, ModelTarget("provider", ScriptProvider(listOf(calls("tool"))), Credentials { "fake" }),
                        request, h, LedgerToolExecutor(ledger, gate, listOf(definition), bridge), budget = budget(),
                        contextPolicy = ContextPolicy("goal", summaryModel = summary(s))).run()
                    assertEquals(RunState.PAUSED, result.state); assertFalse(result.serverExecutionUnknown)
                    assertEquals("LOCAL_WAIT", h.loadCheckpoint()!!.pauseClass)
                    assertFalse(h.loadCheckpoint()!!.serverUnresolved); assertNull(h.loadBarrier()); assertTrue(s.requests.isEmpty())
                    val waiting = ScriptProvider(listOf(answer()))
                    assertEquals(RunState.PAUSED, AgentRunLoop(identity.copy(runId = "waiting"),
                        ModelTarget("provider", waiting, Credentials { "fake" }), request, h,
                        LedgerToolExecutor(ledger, gate, listOf(definition), bridge), contextPolicy = ContextPolicy("goal")).run().state)
                    assertTrue(waiting.requests.isEmpty()); assertNull(h.loadBarrier())
                    if (scenario == "remote") h.saveBarrier(ExecutionBarrier("session", "chain", "run", "remote uncertain", 1, 0))
                }
                assertIs<Mutation.Blocked>(ledger.resolveRecovery(ticket.callId, RecoveryResolution.VERIFIED_CLEAR))
                if (scenario == "sticky") ledger.markRestartRequired(ticket.callId, ticket.attemptId)
                if (scenario != "cold") ledger.recordActualExit(ticket.callId, ticket.attemptId, "actual exit")
                if (scenario in setOf("clear", "accept", "late", "sticky")) {
                    assertIs<Mutation.Applied>(ledger.resolveRecovery(ticket.callId,
                        if (scenario == "accept") RecoveryResolution.ACCEPTED_UNCERTAINTY else RecoveryResolution.VERIFIED_CLEAR))
                    val executor = LedgerToolExecutor(ledger, gate, listOf(definition), bridge)
                    val record = ledger.get(ticket.callId)!!
                    assertEquals(Effect.UNKNOWN, record.result!!.effect) // forensic truth never rewritten
                    assertEquals(scenario in setOf("accept", "sticky"), executor.checkpointBlocked(listOf(record)))
                    assertTrue(executor.checkpointBlocked(listOf(record.copy(isolation = null))))
                    if (scenario == "late") {
                        ledger.recordOutcome(ticket.callId, ticket.attemptId, Status.FAILED, "new uncertain evidence", Effect.UNKNOWN)
                        assertNull(ledger.get(ticket.callId)!!.isolation!!.resolution)
                        assertTrue(executor.checkpointBlocked(listOf(ledger.get(ticket.callId)!!)))
                    }
                }
            }
            TestInvocationStore.open(ledgerDir).use { store ->
                val gate = DefinitionGate().apply { install(definition) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("second")
                val bridge = object : ToolExecutionBridge {
                    override fun submit(ticket: ExecutionTicket) = error("No tool replay")
                    override suspend fun awaitTerminal(callId: String) = error("No tool await")
                    override fun requestCancel(callId: String) = error("No tool cancel")
                }
                FileRunHistory.open(dir, durability).use { h ->
                    val p = ScriptProvider(listOf(answer()))
                    val executor = LedgerToolExecutor(ledger, gate, listOf(definition), bridge)
                    val stale = h.loadCheckpoint()!!.ledger
                    assertTrue(executor.checkpointBlocked(stale))
                    if (scenario == "clear") {
                        assertFalse(executor.checkpointBlocked(executor.currentCheckpointRecords(stale)))
                        assertFailsWith<IllegalArgumentException> {
                            executor.currentCheckpointRecords(listOf(stale.single().copy(input = stale.single().input.copy(argsJson = "{\"changed\":true}"))))
                        }
                    }
                    val result = AgentRunLoop(identity.copy(runId = "reopened"), ModelTarget("provider", p, Credentials { "fake" }), request, h,
                        executor, budget = budget(), contextPolicy = ContextPolicy("goal")).run()
                    assertEquals(if (scenario in setOf("exit", "unknown", "clear", "accept", "late", "sticky")) RunState.COMPLETED else RunState.PAUSED, result.state)
                    assertEquals(if (scenario in setOf("exit", "unknown", "clear", "accept", "late", "sticky")) 1 else 0, p.requests.size)
                    assertEquals(scenario == "remote", result.serverExecutionUnknown)
                    assertEquals(scenario == "remote", h.loadBarrier() != null)
                }
            }
        }
    }

    @Test fun `disk ledger precision preserves raw and late evidence for continuation readmission and view`() = runTest {
        for (kind in listOf("CONTINUATION", "PAUSED", "VIEW", "ADMITTED")) for (changed in listOf("none", "evidence", "result", "legacy-summary")) {
            if (kind == "ADMITTED" && changed in listOf("evidence", "result")) continue
            val dir = root.resolve("precision-$kind-$changed"); val ledgerDir = root.resolve("precision-ledger-$kind-$changed")
            val definition = testDefinition(); val request = RunFixture(listOf(definition)).request()
            lateinit var saved: ContextCheckpoint
            TestInvocationStore.create(ledgerDir).use { store ->
                val gate = DefinitionGate().apply { install(definition) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("first")
                val bridge = object : ToolExecutionBridge {
                    override fun submit(ticket: ExecutionTicket) {
                        val pending = assertIs<Mutation.Applied>(ledger.requestRiskUpgrade(ticket.callId,
                            ticket.attemptId, Risk.WRITE, "Persist this runtime approval summary")).record.pending!!
                        assertIs<Mutation.Applied>(ledger.resolveApproval(ticket.callId, pending, Decision.ALLOW_ONCE))
                        ledger.complete(ticket.callId, ticket.attemptId, Status.SUCCEEDED, "raw\uD800 original", Effect.NONE)
                        ledger.recordOutcome(ticket.callId, ticket.attemptId, Status.SUCCEEDED, "late\uDC00 evidence", Effect.NONE)
                    }
                    override suspend fun awaitTerminal(callId: String) = Unit
                    override fun requestCancel(callId: String) = Unit
                }
                FileRunHistory.create(dir, "session", durability).use { h ->
                    h.append(0, listOf(user("goal")))
                    val executor = LedgerToolExecutor(ledger, gate, listOf(definition), bridge)
                    AgentRunLoop(identity, ModelTarget("provider", ScriptProvider(listOf(calls("tool"), answer())), Credentials { "fake" }),
                        request, h, executor, budget = budget(), contextPolicy = ContextPolicy("goal")).run()
                    val records = executor.checkpointRecords(h.snapshot().messages)
                    saved = checkpoint(h, if (kind == "ADMITTED") "CONTINUATION" else kind).copy(catalog = listOf(definition), ledger = records,
                        settingsKey = settingsKey("provider", request), pauseClass = "BUDGET", budgetKey = "1500|null")
                    h.saveCheckpoint(saved)
                    if (kind == "ADMITTED") {
                        var submitted = false
                        val interrupted = object : ModelProvider by ScriptProvider(emptyList()) {
                            override fun chat(request: ChatRequest, auth: Credentials) = flow<ModelEvent> {
                                submitted = true
                                kotlinx.coroutines.awaitCancellation()
                            }
                        }
                        assertFailsWith<kotlinx.coroutines.CancellationException> {
                            kotlinx.coroutines.withTimeout(10) {
                                AgentRunLoop(saved.identity(), ModelTarget("provider", interrupted, Credentials { "fake" }),
                                    request, h, executor, budget = budget(),
                                    contextPolicy = ContextPolicy("goal", resumeCheckpointId = saved.id)).run()
                            }
                        }
                        assertTrue(submitted)
                        saved = h.loadCheckpoint()!!
                        assertEquals("ADMITTED", saved.pauseClass)
                        assertNotNull(h.loadView())
                    }
                    assertTrue(saved.ledger.single().result!!.text.contains('\uD800'))
                    assertTrue(saved.ledger.single().evidence.last().text.contains('\uDC00'))
                    val record = records.single()
                    if (changed == "evidence") ledger.recordOutcome(record.callId, record.instance!!.attemptId, Status.SUCCEEDED, "genuinely changed evidence", Effect.NONE)
                    if (changed == "legacy-summary") {
                        saved = saved.copy(ledger = records.map { r -> r.copy(receipts = r.receipts.map {
                            it.copy(request = it.request.copy(summary = ""))
                        }) })
                        h.saveCheckpoint(saved)
                    }
                    if (changed == "result") {
                        // A durable snapshot with actually different logical output is not a UTF-8 equivalence.
                        saved = saved.copy(ledger = listOf(record.copy(result = record.result!!.copy(text = "changed result"))))
                        h.saveCheckpoint(saved)
                    }
                }
            }
            TestInvocationStore.open(ledgerDir).use { store ->
                val gate = DefinitionGate().apply { install(definition) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("second")
                val bridge = object : ToolExecutionBridge {
                    override fun submit(ticket: ExecutionTicket) = error("No tool replay")
                    override suspend fun awaitTerminal(callId: String) = error("No await")
                    override fun requestCancel(callId: String) = error("No cancel")
                }
                FileRunHistory.open(dir, durability).use { h ->
                    val p = ScriptProvider(listOf(answer()))
                    if (kind == "ADMITTED") assertTrue(confirmRetry(h, retryId(h)))
                    val policy = when (kind) {
                        "ADMITTED" -> ContextPolicy("goal")
                        "CONTINUATION" -> ContextPolicy("goal", resumeCheckpointId = saved.id)
                        "PAUSED" -> ContextPolicy("goal", readmitBudgetCheckpointId = saved.id)
                        else -> ContextPolicy("goal", h.snapshot().messages.size)
                    }
                    val run = AgentRunLoop(if (kind == "CONTINUATION") saved.identity() else identity.copy(runId = "restart"),
                        ModelTarget("provider", p, Credentials { "fake" }), request, h,
                        LedgerToolExecutor(ledger, gate, listOf(definition), bridge), budget = budget(10_000), contextPolicy = policy)
                    if (kind == "VIEW" && changed in setOf("evidence", "result")) {
                        // A stale derived view is dropped, never a session lock: raw history is sent.
                        assertEquals(RunState.COMPLETED, run.run().state); assertEquals(1, p.requests.size)
                        assertTrue(p.requests.single().messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
                            .none { it.text.startsWith("Summary continuation") })
                    }
                    else if (changed != "none") { assertFailsWith<IllegalArgumentException> { run.run() }; assertTrue(p.requests.isEmpty()) }
                    else {
                        assertEquals(saved.ledger.map { it.atLedgerPrecision() }, store.records())
                        assertEquals(RunState.COMPLETED, run.run().state); assertEquals(1, p.requests.size)
                    }
                    // Source history retains original UTF-16; comparison does not rewrite evidence.
                    assertTrue(h.snapshot().messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().single().text.contains('\uD800'))
                }
            }
        }
    }

    @Test fun `serial blocked second claim persists truth and admits only after actual first exit`() = runTest {
        for (withPolicy in listOf(false, true)) {
            val dir = root.resolve("serial-$withPolicy"); val ledgerDir = root.resolve("serial-ledger-$withPolicy")
            val definitions = listOf(testDefinition("a"), testDefinition("b"))
            val request = RunFixture(definitions).request()
            lateinit var ticket: ExecutionTicket
            lateinit var paired: List<Message>
            TestInvocationStore.create(ledgerDir).use { store ->
                val gate = DefinitionGate().apply { definitions.forEach(::install) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("first")
                var submissions = 0
                val bridge = object : ToolExecutionBridge {
                    override fun submit(value: ExecutionTicket) {
                        submissions++; ticket = value
                        assertEquals("a", value.definition.name)
                        ledger.recordOutcome(value.callId, value.attemptId, Status.SUCCEEDED, "real-a", Effect.NONE)
                    }
                    override suspend fun awaitTerminal(callId: String) = Unit
                    override fun requestCancel(callId: String) = Unit
                }
                fun run(h: FileRunHistory, p: ModelProvider, runId: String) = AgentRunLoop(identity.copy(runId = runId),
                    ModelTarget("provider", p, Credentials { "fake" }), request, h,
                    LedgerToolExecutor(ledger, gate, definitions, bridge),
                    contextPolicy = if (withPolicy) ContextPolicy("goal") else null)
                FileRunHistory.create(dir, "session", durability).use { h ->
                    h.append(0, listOf(user("goal")))
                    val p = ScriptProvider(listOf(calls("a", "b")))
                    val result = run(h, p, "run").run()
                    assertEquals(RunState.PAUSED, result.state)
                    assertTrue(result.reason.startsWith("Tool step interrupted"))
                    assertFalse(result.serverExecutionUnknown); assertNull(h.loadBarrier())
                    assertEquals(1, p.requests.size); assertEquals(1, submissions)
                    assertEquals(1, h.loadAccounting("chain")!!.steps)
                    assertEquals(if (withPolicy) "LOCAL_WAIT" else null, h.loadCheckpoint()?.pauseClass)
                    paired = h.snapshot().messages
                    val records = store.records().sortedBy { it.input.callOrder }
                    assertEquals(Status.SUCCEEDED, records[0].state)
                    assertEquals("real-a", records[0].result!!.text)
                    assertEquals(InstanceState.UNCONFIRMED, records[0].instance!!.state)
                    assertEquals(Status.CANCELLED, records[1].state)
                    assertEquals(Effect.NONE, records[1].result!!.effect); assertNull(records[1].instance)
                    assertEquals(HistoryCodec.encodeMessage(ledger.resultsForStep("session", "run", records[0].input.stepId,
                        records.map { it.callId })!!), HistoryCodec.encodeMessage(paired.last()))
                }
                // Reopen history from disk while the actual first worker is still live.
                FileRunHistory.open(dir, durability).use { h ->
                    val p = ScriptProvider(listOf(answer()))
                    val result = run(h, p, "waiting").run()
                    assertEquals(RunState.PAUSED, result.state); assertEquals(1, result.steps)
                    assertTrue(p.requests.isEmpty()); assertFalse(result.serverExecutionUnknown)
                    assertEquals(paired, h.snapshot().messages); assertNull(h.loadBarrier())
                }
                ledger.recordActualExit(ticket.callId, ticket.attemptId, "real exit")
            }
            TestInvocationStore.open(ledgerDir).use { store ->
                val gate = DefinitionGate().apply { definitions.forEach(::install) }
                val ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("second")
                val bridge = object : ToolExecutionBridge {
                    override fun submit(ticket: ExecutionTicket) = error("No replay")
                    override suspend fun awaitTerminal(callId: String) = error("No await")
                    override fun requestCancel(callId: String) = error("No cancel")
                }
                FileRunHistory.open(dir, durability).use { h ->
                    assertFalse(h.recoverPending(ledger).recovered)
                    val p = ScriptProvider(listOf(answer()))
                    val result = AgentRunLoop(identity.copy(runId = "after-exit"),
                        ModelTarget("provider", p, Credentials { "fake" }), request, h,
                        LedgerToolExecutor(ledger, gate, definitions, bridge),
                        contextPolicy = if (withPolicy) ContextPolicy("goal") else null).run()
                    assertEquals(RunState.COMPLETED, result.state); assertEquals(2, result.steps)
                    assertEquals(1, p.requests.size); assertEquals(paired, p.requests.single().messages.map { message(it.role, *it.parts.toTypedArray()) })
                    assertNull(h.loadBarrier())
                }
            }
        }
    }

    @Test fun `cross chain length and repeated call pauses retain cumulative counts not view provenance`() = runTest {
        for (end in listOf("length", "duplicate")) {
            val dir = root.resolve("paused-accounting-$end")
            val f = RunFixture(if (end == "duplicate") listOf(testDefinition()) else emptyList())
            lateinit var original: ContextCheckpoint
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("old")))
                original = checkpoint(h, "VIEW").copy(steps = 49, retries = 11, catalog = f.definitions,
                    settingsKey = settingsKey("provider", f.request()))
                h.saveCheckpoint(original)
            }
            repeat(3) { n ->
                FileRunHistory.open(dir, durability).use { h ->
                    h.append(h.revision(), listOf(user("B-$n")))
                    val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "cache_control"),
                        if (end == "length") answer("partial", FinishReason.LENGTH) else calls("tool")))
                    val result = loop(f, p, h, ContextPolicy("B", 1), identity.copy(taskChainId = "B", runId = "B-$n"),
                        limits = RunLimits(maxSteps = 2, maxRetries = 2, repeatedCalls = 1)).run()
                    assertEquals(RunState.PAUSED, result.state)
                    assertEquals(minOf(n + 1, 2), result.steps); assertEquals(minOf(n + 1, 2), result.retries)
                    assertEquals(if (n < 2) 2 else 0, p.requests.size)
                    assertEquals(result.steps, h.loadAccounting("B")!!.steps)
                    assertEquals(result.retries, h.loadAccounting("B")!!.retries)
                    assertEquals("B", h.loadAccounting("B")!!.taskChainId)
                    assertEquals(original, h.loadView()); assertTrue(f.submitted.isEmpty())
                }
            }
        }
    }

    @Test fun `exceptions cancellation and poisoned append retain reserved accounting across reopen`() = runTest {
        for (end in listOf("stream-cancel", "recovery-throw", "tool-cancel", "append-fail", "model-failed")) {
            val dir = root.resolve("exit-$end"); val f = RunFixture(listOf(testDefinition()))
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("old")))
                h.saveCheckpoint(checkpoint(h, "VIEW").copy(steps = 49, retries = 11, catalog = f.definitions,
                    settingsKey = settingsKey("provider", f.request())))
            }
            FileRunHistory.open(dir, durability, fault = {
                if (end == "append-fail" && it.file == "batches/3.json" && it.stage == HistoryWriteStage.BEFORE_WRITE)
                    error("append failure")
            }).use { h ->
                h.append(h.revision(), listOf(user("B")))
                val script = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "cache_control"),
                    calls("tool"), failure(if (end == "recovery-throw") ModelErrorCode.AUTH else ModelErrorCode.INVALID_REQUEST)))
                val p = object : ModelProvider by script {
                    override fun chat(request: ChatRequest, auth: Credentials) = if (end == "stream-cancel" && script.requests.size == 2)
                        flow<ModelEvent> { kotlinx.coroutines.awaitCancellation() } else script.chat(request, auth)
                }
                if (end == "tool-cancel") f.onAwait = { kotlinx.coroutines.awaitCancellation() }
                val run = AgentRunLoop(identity.copy(taskChainId = "B", runId = end), ModelTarget("provider", p, Credentials { "fake" }),
                    f.request(), h, LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge),
                    recovery = RecoveryStrategy { _, _, _, _ -> error("recovery failed") }, budget = budget(),
                    contextPolicy = ContextPolicy("B", 1))
                when (end) {
                    "stream-cancel", "tool-cancel" -> assertFailsWith<kotlinx.coroutines.CancellationException> {
                        kotlinx.coroutines.withTimeout(20) { run.run() }
                    }
                    "append-fail" -> {
                        val error = assertFailsWith<HistoryStorageException> { run.run() }
                        assertTrue(error.suppressed.isNotEmpty()) // final accounting cannot mask poisoned writer failure
                    }
                    "recovery-throw" -> assertEquals("recovery failed", assertFailsWith<IllegalStateException> { run.run() }.message)
                    else -> assertEquals(RunState.FAILED, run.run().state)
                }
            }
            FileRunHistory.open(dir, durability).use { h ->
                val accounting = h.loadAccounting("B")!!
                assertEquals("B", accounting.taskChainId); assertEquals(1, accounting.steps); assertEquals(1, accounting.retries)
                if (end == "append-fail") assertTrue(h.recoverPending(f.ledger).recovered)
                assertEquals(1, h.snapshot().messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().size)
                val p = ScriptProvider(listOf(answer()))
                val result = loop(f, p, h, ContextPolicy("B", 1), identity.copy(taskChainId = "B", runId = "restart"),
                    limits = RunLimits(maxSteps = 1, maxRetries = 1)).run()
                assertEquals(RunState.PAUSED, result.state); assertEquals(1, result.steps); assertEquals(1, result.retries)
                assertTrue(p.requests.isEmpty()); assertEquals(1, f.submitted.size)
            }
        }
    }

    @Test fun `exit accounting never overwrites stronger admission state`() = runTest {
        for (kind in listOf("ADMITTED", "SUMMARY_RESERVED", "CONTINUATION", "BUDGET", "LOCAL_WAIT", "REMOTE")) {
            val dir = root.resolve("preserve-$kind"); val f = RunFixture()
            lateinit var saved: ContextCheckpoint
            FileRunHistory.create(dir, "session", durability).use { h ->
                h.append(0, listOf(user("old")))
                saved = checkpoint(h, if (kind == "CONTINUATION") kind else "PAUSED").copy(pauseClass = kind)
                h.saveCheckpoint(saved)
                h.saveAccounting(RunAccounting("session", "B", "previous", 2, 1))
                if (kind == "REMOTE") h.saveBarrier(ExecutionBarrier("session", "chain", "run", "unresolved", 2, 1))
            }
            FileRunHistory.open(dir, durability).use { h ->
                val p = ScriptProvider(listOf(answer()))
                // LOCAL_WAIT without blockers is allowed to settle, but a pre-request failure must
                // still preserve it. Every other reservation refuses admission altogether.
                val run = AgentRunLoop(identity.copy(taskChainId = "B", runId = "B"),
                    ModelTarget("provider", p, Credentials { "fake" }), f.request(), h,
                    LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge),
                    budget = RequestBudget { error("budget failed") }, contextPolicy = ContextPolicy("B"))
                if (kind == "LOCAL_WAIT") assertFailsWith<IllegalStateException> { run.run() }
                else assertEquals(RunState.PAUSED, run.run().state)
                assertEquals(saved, h.loadCheckpoint()); assertTrue(p.requests.isEmpty())
                assertEquals(2, h.loadAccounting("B")!!.steps); assertEquals(1, h.loadAccounting("B")!!.retries)
            }
        }
    }

    @Test fun `A B A restarts retain independent step and retry caps without old context override`() = runTest {
        for (cap in listOf("steps", "retries")) {
            val dir = root.resolve("chains-$cap"); val f = RunFixture()
            FileRunHistory.create(dir, "session", durability).use { it.append(0, listOf(user("goal"))) }
            for ((n, chain) in listOf("A", "B", "A", "B", "A").withIndex()) {
                FileRunHistory.open(dir, durability).use { h ->
                    h.append(h.revision(), listOf(user("history changed $n")))
                    // An old same-chain context must not replace durable counters (in either direction).
                    if (n == 2) h.saveCheckpoint(checkpoint(h, "COMPLETE").copy(taskChainId = "A",
                        summary = null, steps = 49, retries = 11))
                    val p = ScriptProvider(listOf(failure(ModelErrorCode.UNSUPPORTED_PARAMETER, "cache_control"), answer()))
                    val result = loop(f, p, h, ContextPolicy("goal"), identity.copy(taskChainId = chain, runId = "r$n"),
                        limits = RunLimits(maxSteps = if (cap == "steps") 2 else 50,
                            maxRetries = if (cap == "retries") 2 else 12)).run()
                    val expected = if (n < 2) 1 else 2
                    assertEquals(expected, result.steps); assertEquals(expected, result.retries)
                    assertEquals(if (n < 4) RunState.COMPLETED else if (cap == "steps") RunState.PAUSED else RunState.FAILED, result.state)
                    // Retry exhaustion still permits the initial request, but never another retry.
                    assertEquals(if (n < 4) 2 else if (cap == "steps") 0 else 1, p.requests.size)
                    assertEquals(expected, h.loadAccounting(chain)!!.steps)
                    if (n >= 1) assertEquals(if (n < 2) 1 else 2, h.loadAccounting("A")!!.steps)
                }
            }
        }
    }

    @Test fun `source corruption cannot be hidden by valid checkpoint`() {
        val dir = root.resolve("h")
        FileRunHistory.create(dir, "session", durability).use { h ->
            h.append(0, listOf(user("first"))); h.append(1, listOf(user("goal"))); h.saveCheckpoint(checkpoint(h))
        }
        Files.write(dir.resolve("batches/1.json"), byteArrayOf(1, 2, 3))
        FileRunHistory.open(dir, durability).use { h -> assertFailsWith<HistoryStorageException> { h.checkpointInfo() } }
    }

    @Test fun `budget pause or summary offer superseded by new input does not lock session`() = runTest {
        for (offer in listOf(true, false)) {
            val f = RunFixture(if (offer) listOf(testDefinition()) else emptyList())
            f.onSubmit = { f.ledger.complete(it.callId, it.attemptId, Status.SUCCEEDED, "x".repeat(5000)) }
            FileRunHistory.create(root.resolve("superseded-$offer"), "session", durability).use { h ->
                if (offer) {
                    h.append(0, listOf(user("goal")))
                    loop(f, ScriptProvider(listOf(calls("tool"))), h, ContextPolicy("goal", summaryModel = summary()),
                        limits = RunLimits(maxRetries = 50)).run()
                    assertEquals("CONTINUATION", h.loadCheckpoint()!!.kind)
                } else {
                    h.append(0, listOf(user("old".repeat(800)), message(Role.ASSISTANT, textPart("past")), user("goal")))
                    loop(f, ScriptProvider(listOf(answer())), h, ContextPolicy("goal", 2)).run()
                    assertEquals("BUDGET", h.loadCheckpoint()!!.pauseClass); assertEquals("PAUSED", h.loadCheckpoint()!!.kind)
                }
                val saved = h.loadCheckpoint()!!
                val idle = ScriptProvider(listOf(answer()))
                // Without new input the record still requires explicit selection or readmission.
                assertEquals(RunState.PAUSED, loop(f, idle, h, ContextPolicy("goal", h.snapshot().messages.size),
                    identity.copy(runId = "idle"), b = budget(10_000)).run().state)
                assertTrue(idle.requests.isEmpty())
                h.append(h.revision(), listOf(user("new short question")))
                assertNull(h.retryReconciliation()); assertNull(h.checkpointInfo())
                val p = ScriptProvider(listOf(answer()))
                val result = loop(f, p, h, ContextPolicy("goal", h.snapshot().messages.size - 1),
                    identity.copy(runId = "next"), b = budget(if (offer) 1500 else 10_000)).run()
                assertEquals(RunState.COMPLETED, result.state); assertEquals(1, p.requests.size)
                assertTrue(result.steps >= saved.steps)
                if (offer) assertTrue(p.requests.single().messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
                    .any { it.text.startsWith("Summary continuation") })
                assertFailsWith<IllegalArgumentException> {
                    loop(f, p, h, ContextPolicy("goal", resumeCheckpointId = saved.id), identity.copy(runId = "stale")).run()
                }
            }
        }
        FileRunHistory.create(root.resolve("superseded-review"), "session", durability).use { h ->
            h.append(0, listOf(user("goal"))); h.saveCheckpoint(checkpoint(h))
            h.append(h.revision(), listOf(user("new input is not review acknowledgement")))
            val p = ScriptProvider(listOf(answer()))
            assertEquals(RunState.PAUSED, loop(RunFixture(), p, h, ContextPolicy("goal", 1), b = budget(10_000)).run().state)
            assertTrue(p.requests.isEmpty())
        }
    }

    @Test fun `accepted uncertainty allows ordinary prior compaction but undecided isolation does not`() = runTest {
        for (decided in listOf(false, true)) {
            val f = RunFixture(listOf(testDefinition(risk = Risk.WRITE)))
            f.onSubmit = { f.ledger.recordOutcome(it.callId, it.attemptId, Status.FAILED, "write uncertain", Effect.UNKNOWN) }
            FileRunHistory.create(root.resolve("accepted-compact-$decided"), "session", durability).use { h ->
                h.append(0, listOf(user("x".repeat(1400))))
                assertEquals(RunState.PAUSED, loop(f, ScriptProvider(listOf(calls("tool"))), h,
                    ContextPolicy("goal"), b = budget(100_000)).run().state)
                val ticket = f.submitted.single()
                f.ledger.recordActualExit(ticket.callId, ticket.attemptId, "worker exited")
                if (decided) assertIs<Mutation.Applied>(f.ledger.resolveRecovery(ticket.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
                repeat(2) { n ->
                    h.append(h.revision(), listOf(user("question $n")))
                    val s = ScriptProvider(listOf(answer("write outcome unknown; user accepted")))
                    val p = ScriptProvider(listOf(answer("a$n")))
                    val result = loop(f, p, h, ContextPolicy("goal", h.snapshot().messages.size - 1, summary(s)),
                        identity.copy(runId = "r$n"), limits = RunLimits(maxRetries = 50)).run()
                    assertEquals(if (decided) RunState.COMPLETED else RunState.PAUSED, result.state)
                    assertEquals(if (decided) 1 else 0, p.requests.size)
                    if (!decided || n > 0) assertTrue(s.requests.isEmpty()) // decided view is reused, not re-summarized
                }
                assertEquals(Effect.UNKNOWN, f.ledger.get(ticket.callId)!!.result!!.effect)
                if (decided) {
                    var approval: ApprovalRequest? = null
                    f.approve = { approval = it; Decision.DENY }
                    val next = ScriptProvider(listOf(calls("tool", args = "{\"next\":true}"), answer()))
                    h.append(h.revision(), listOf(user("write again")))
                    f.loop(next, history = h).run()
                    assertTrue(ticket.callId in assertNotNull(approval).unknownCalls)
                    assertEquals(1, f.submitted.size)
                }
            }
        }
    }

    @Test fun `late benign ledger evidence drops stale view instead of locking session`() = runTest {
        val f = RunFixture(listOf(testDefinition()))
        FileRunHistory.create(root.resolve("late-view"), "session", durability).use { h ->
            h.append(0, listOf(user("x".repeat(1400))))
            assertEquals(RunState.COMPLETED, loop(f, ScriptProvider(listOf(calls("tool"), answer())), h,
                ContextPolicy("goal"), b = budget(100_000)).run().state)
            h.append(h.revision(), listOf(user("q2")))
            val s = ScriptProvider(listOf(answer("short summary")))
            assertEquals(RunState.COMPLETED, loop(f, ScriptProvider(listOf(answer("a2"))), h,
                ContextPolicy("goal", h.snapshot().messages.size - 1, summary(s)), identity.copy(runId = "r2"),
                limits = RunLimits(maxRetries = 50)).run().state)
            val stale = assertNotNull(h.loadView())
            val ticket = f.submitted.single()
            assertIs<Mutation.Applied>(f.ledger.recordActualExit(ticket.callId, ticket.attemptId, "supervisor reaped process"))
            repeat(2) { n ->
                h.append(h.revision(), listOf(user("q3-$n")))
                val p = ScriptProvider(listOf(answer("a3")))
                assertEquals(RunState.COMPLETED, loop(f, p, h, ContextPolicy("goal", h.snapshot().messages.size - 1, summary(s)),
                    identity.copy(runId = "r3-$n"), limits = RunLimits(maxRetries = 50)).run().state)
                assertEquals(1, p.requests.size)
            }
            val fresh = assertNotNull(h.loadView())
            assertNotEquals(stale.id, fresh.id)
            assertEquals(f.ledger.get(ticket.callId)!!.atLedgerPrecision(), fresh.ledger.single().atLedgerPrecision())
            assertTrue(f.submitted.size == 1)
        }
    }
}
