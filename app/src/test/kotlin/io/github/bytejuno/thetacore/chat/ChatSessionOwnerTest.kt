package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ChatSessionOwnerTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject
    private fun tool(count: AtomicInteger, output: ToolResult = ToolResult("done", effect = ToolEffect.APPLIED)) = object : Tool {
        override val id = "write"
        override val spec = ToolSpec("write", "test write", schema, risk = ToolRisk.WRITE, timeoutMs = 5_000)
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
            count.incrementAndGet(); return output
        }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }
    private suspend fun fixture(root: File, count: AtomicInteger, provider: ModelProvider, hostTool: Tool = tool(count)): Fixture {
        val store = AndroidAgentStore.open(root, BundledSQLiteDriver())
        val budget = PluginExecutionBudget()
        val host = HostToolBridgeRoot(budget, hostTool).initialize { store }
        return Fixture(store, budget, host, provider)
    }
    private inner class Fixture(val store: AndroidAgentStore, val budget: PluginExecutionBudget,
        val host: HostToolExecutionService, provider: ModelProvider) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failures = mutableListOf<Throwable>()
        val owner = ChatSessionOwner(scope, { host }, {
            OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model"), ApiKeyCredentials("test-key"))
        }, { synchronized(failures) { failures += it } }, { ModelTarget("test", provider, Credentials { "test-key" }) })
        suspend fun close() {
            owner.stop().join()
            await { !owner.state.value.active }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close(); store.close()
        }
    }
    private fun provider(tool: Boolean = true, streaming: CompletableDeferred<Unit>? = null) = object : ModelProvider {
        var requests = 0
        override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
        override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
            emit(Events.started("response-${requests++}", "model"))
            if (tool && requests == 1) {
                emit(Events.toolStart(0, "wire", "host_clock")); emit(Events.toolDelta(0, "{}")); emit(Events.end(0))
                emit(Events.finished(FinishReason.TOOL_CALLS))
            } else {
                emit(Events.text(0, "hello")); streaming?.await(); emit(Events.text(0, " world")); emit(Events.end(0))
                emit(Events.finished(FinishReason.STOP))
            }
        }
    }
    @Test fun completedQuestionsHaveIndependentBudgetsInOnePersistentSession() = runBlocking {
        val root = Files.createTempDirectory("chat-budgets").toFile()
        val requests = AtomicInteger()
        val model = countingProvider(requests) { FinishReason.STOP }
        try {
            var f = fixture(root, AtomicInteger(), model)
            f.owner.open().join()
            val session = f.owner.state.value.sessionId
            repeat(51) { index ->
                if (index == 50) {
                    f.close()
                    f = fixture(root, AtomicInteger(), model)
                    f.owner.open().join()
                }
                f.owner.send("question $index").join()
                await { !f.owner.state.value.active }
                assertEquals(R.string.chat_complete, f.owner.state.value.notice)
                assertEquals(index + 1, requests.get())
            }
            assertEquals(session, f.owner.state.value.sessionId)
            assertTrue(f.failures.toString(), f.failures.isEmpty())
            f.close()
        } finally { root.deleteRecursively() }
    }

    private fun countingProvider(requests: AtomicInteger, finish: (Int) -> FinishReason) = object : ModelProvider {
        override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
        override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
            val number = requests.incrementAndGet()
            emit(Events.started("response-$number", "model"))
            emit(Events.text(0, "reply")); emit(Events.end(0)); emit(Events.finished(finish(number)))
        }
    }

    @Test fun pausedTaskStillExhaustsItsOwnBudgetAfterColdOpen() = runBlocking {
        val root = Files.createTempDirectory("chat-paused-budget").toFile()
        val requests = AtomicInteger()
        val model = countingProvider(requests) { if (it == 1) FinishReason.STOP else FinishReason.LENGTH }
        try {
            val f = fixture(root, AtomicInteger(), model)
            f.owner.open().join()
            f.owner.send("first task").join(); await { !f.owner.state.value.active }
            assertEquals(R.string.chat_complete, f.owner.state.value.notice)
            repeat(50) {
                f.owner.send("continue unfinished task").join(); await { !f.owner.state.value.active }
                assertEquals(R.string.chat_paused, f.owner.state.value.notice)
                assertEquals(it + 2, requests.get())
            }
            f.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.owner.open().join(); cold.owner.send("continue after restart").join()
            await { !cold.owner.state.value.active }
            assertEquals(R.string.chat_paused, cold.owner.state.value.notice)
            assertEquals(51, requests.get())
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun realLoopApprovesThroughHostApiAndPersistsAcrossReopen() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider())
            f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            assertEquals(0, count.get()); assertTrue(showApproval(record)); assertEquals(R.string.chat_tool_approval, toolState(record))
            f.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !f.owner.state.value.active }
            assertEquals(1, count.get()); assertTrue(f.failures.toString(), f.failures.isEmpty())
            assertEquals(Decision.ALLOW_ONCE, f.host.ledger.get(record.callId)!!.receipts.single().decision)
            assertTrue(f.owner.state.value.messages.last().parts.filterIsInstance<ContentPart.Text>().single().text.contains("hello"))
            val session = f.owner.state.value.sessionId
            f.close()
            val reopened = fixture(root, count, provider(false)); reopened.owner.open().join()
            assertEquals(session, reopened.owner.state.value.sessionId)
            assertEquals(4, reopened.owner.state.value.messages.size); assertEquals(1, count.get())
            reopened.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectionRecordsReceiptAndDoesNotExecute() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider()); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            f.owner.decide(record.callId, record.pending!!.id, Decision.DENY).join(); await { !f.owner.state.value.active }
            assertEquals(0, count.get()); assertEquals(Status.REJECTED, f.host.ledger.get(record.callId)!!.state)
            assertEquals(Decision.DENY, f.host.ledger.get(record.callId)!!.receipts.single().decision)
            assertTrue(f.failures.toString(), f.failures.isEmpty()); f.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun storedApprovalSurvivesColdOpenAndUsesOriginalCallForBothDecisions() = runBlocking {
        for (decision in listOf(Decision.ALLOW_ONCE, Decision.DENY)) {
            val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
            try {
                val seed = fixture(root, count, provider(false))
                val d = seed.host.catalog.freeze("old-run").getValue("host_clock")
                val binding = callBinding("test", "request", "response", 0, "wire")
                seed.store.createHistory("session").use { history ->
                    history.append(0, listOf(message(Role.USER, textPart("goal"))))
                    history.prepareStep(1, RunIdentity("session", "session", "old-run"), "step",
                        message(Role.ASSISTANT, toolCallPart(binding, d.modelName, "{}")))
                    val input = InvocationInput(BindingValue.copyOf(binding), "session", "session", "old-run", "step", 0,
                        "{}", d, false, Risk.WRITE)
                    assertTrue(seed.host.prepare(input) is Mutation.Applied)
                }
                val original = seed.host.ledger.get(binding.callId)!!
                seed.close()
                val cold = fixture(root, count, provider(false)); cold.owner.open().join()
                assertEquals(original, cold.host.ledger.get(binding.callId)); assertEquals(0, count.get())
                assertTrue(cold.owner.state.value.pending)
                assertTrue(showApproval(cold.owner.state.value.records.getValue(binding.callId)))
                assertEquals(R.string.chat_tool_approval, toolState(cold.owner.state.value.records[binding.callId]))
                cold.owner.decide(binding.callId, original.pending!!.id, decision).join()
                await { !cold.owner.state.value.active }
                assertEquals(if (decision == Decision.DENY) 0 else 1, count.get())
                assertEquals(decision, cold.host.ledger.get(binding.callId)!!.receipts.single().decision)
                assertFalse(cold.owner.state.value.pending)
                assertEquals(3, cold.owner.state.value.messages.size) // no model request on cold decision
                assertTrue(cold.failures.toString(), cold.failures.isEmpty()); cold.close()
            } finally { root.deleteRecursively() }
        }
    }
    @Test fun coldApprovalPreservesStructuredAndOversizedOutputAndOriginalRetrievalAlias() = runBlocking {
        for (large in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-cold-output").toFile()
            val count = AtomicInteger()
            val payload = ToolResult(if (large) "x".repeat(68000) else "done",
                structured = Json.parseToJsonElement("""{"ok":true}"""), effect = ToolEffect.APPLIED)
            val write = tool(count, payload)
            try {
                val live = File(root, "live").also { it.mkdir() }
                val saved = File(root, "saved").also { it.mkdir() }
                val seed = fixture(live, count, provider(), write)
                // Aliases collide only in the original process. Capture the real pending transaction
                // before stopping its owner, so teardown cannot inject cancellation into the copy.
                seed.host.catalog.registerHost("host/blob/read", "1", write)
                seed.owner.open().join(); seed.owner.send("goal").join()
                await { seed.owner.state.value.records.values.any { it.pending != null } }
                val original = seed.owner.state.value.records.values.single()
                val run = original.input.runId
                val reader = seed.host.catalog.frozen(run)!!.values.single { it.toolKey == "host/blob_read" }
                assertNotEquals("host_blob_read", reader.modelName)
                BundledSQLiteDriver().open(File(live, AgentDatabase.FILE_NAME).path).use { db ->
                    db.prepare("VACUUM INTO ?").use {
                        it.bindText(1, File(saved, AgentDatabase.FILE_NAME).path); it.step()
                    }
                }
                seed.close()
                val cold = fixture(saved, count, provider(), write)
                cold.owner.open().join()
                val request = cold.host.ledger.get(original.callId)!!.pending!!
                assertEquals(reader, cold.host.catalog.definition(run, reader.modelName))
                cold.owner.decide(original.callId, request.id, Decision.ALLOW_ONCE).join()
                await { !cold.owner.state.value.active }
                val record = cold.host.ledger.get(original.callId)!!
                assertEquals(Status.SUCCEEDED, record.state)
                val result = requireNotNull(record.result)
                assertEquals(Effect.APPLIED, result.effect)
                assertEquals(1, record.receipts.size)
                assertEquals(1, count.get())
                val part = cold.owner.state.value.messages.last().parts.single() as ContentPart.ToolResult
                assertEquals(ModelResultText.encode(result.status, result.effect, result.text), part.text)
                assertFalse(part.isError)
                assertTrue(part.text.contains(if (large) "Retrieve with ${reader.modelName}(" else "[structured JSON]"))
                val raw = requireNotNull(result.output).raw
                assertEquals(raw, cold.host.ledger.readBlob(run, raw.id, 0).first)
                cold.owner.send("again").join()
                await { cold.owner.state.value.records.values.any { it.pending != null } }
                val next = cold.owner.state.value.records.values.single { it.pending != null }
                cold.owner.decide(next.callId, next.pending!!.id, Decision.ALLOW_ONCE).join()
                await { !cold.owner.state.value.active }
                assertEquals(2, count.get()); assertEquals(Status.SUCCEEDED, cold.host.ledger.get(next.callId)!!.state)
                assertTrue(cold.failures.toString(), cold.failures.isEmpty()); cold.close()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun completedTextTasksReleaseBothRunDirectories() = runBlocking {
        val root = Files.createTempDirectory("chat-catalog-lifetime").toFile()
        try {
            val f = fixture(root, AtomicInteger(), provider(false)); f.owner.open().join()
            repeat(24) {
                f.owner.send("question $it").join(); await { !f.owner.state.value.active }
                assertEquals(R.string.chat_complete, f.owner.state.value.notice)
            }
            f.owner.newSession().join()
            assertEquals(0, f.host.catalog.frozenRunCount)
            assertEquals(0, f.host.catalog.gate.frozenRunCount)
            f.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun validatedStreamingAppearsBeforeFinishAndCancellationSettlesOwner() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val hold = CompletableDeferred<Unit>()
        try {
            val f = fixture(root, AtomicInteger(), provider(false, hold)); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.draft == "hello" }
            assertTrue(f.owner.state.value.active); assertEquals(1, f.owner.state.value.messages.size)
            f.owner.stop().join(); await { !f.owner.state.value.active }
            assertEquals(R.string.chat_stopped, f.owner.state.value.notice); assertEquals("", f.owner.state.value.draft)
            f.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun historyPagingAndNewSessionStaySeparate() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile()
        try {
            val f = fixture(root, AtomicInteger(), provider(false))
            f.store.createHistory("session").use { h -> repeat(85) { h.append(h.revision(), listOf(message(Role.USER, textPart("message-$it")))) } }
            f.owner.open().join(); assertEquals(40, f.owner.state.value.messages.size)
            f.owner.older().join(); assertEquals("message-5", (f.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
            f.owner.newSession().join(); assertTrue(f.owner.state.value.messages.isEmpty())
            f.owner.select("session").join(); assertEquals(40, f.owner.state.value.messages.size)
            f.close()
        } finally { root.deleteRecursively() }
    }
    @Test fun staleUiApprovalCannotGrantAndUnattendedNeverShowsActions() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider()); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            assertFalse(showApproval(record.copy(input = record.input.copy(unattended = true))))
            f.owner.decide(record.callId, "stale-id", Decision.ALLOW_ONCE).join()
            assertEquals(record.pending, f.host.ledger.get(record.callId)!!.pending)
            assertEquals(0, count.get()); assertNotNull(f.owner.state.value.error)
            f.owner.stop().join(); await { !f.owner.state.value.active }
            assertEquals(Status.CANCELLED, f.host.ledger.get(record.callId)!!.state)
            assertNull(f.host.ledger.get(record.callId)!!.instance)
            f.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun changedDefinitionCannotExecuteRecoveredApprovalButCanBeDenied() = runBlocking {
        val root = Files.createTempDirectory("chat24-").toFile(); val count = AtomicInteger()
        try {
            val f = fixture(root, count, provider()); f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val original = f.owner.state.value.records.values.single()
            f.host.catalog.registerHost("host/clock", "2", tool(count))
            f.owner.decide(original.callId, original.pending!!.id, Decision.ALLOW_ONCE).join()
            assertEquals(0, count.get())
            val current = f.host.ledger.get(original.callId)!!
            // The ledger may reject a stale definition outright; no permission is silently renewed.
            assertTrue(current.state == Status.REJECTED || current.pending != null)
            if (current.pending != null) f.owner.decide(current.callId, current.pending!!.id, Decision.DENY).join()
            await { !f.owner.state.value.active }
            assertEquals(0, count.get()); f.close()
        } finally { root.deleteRecursively() }
    }

}
