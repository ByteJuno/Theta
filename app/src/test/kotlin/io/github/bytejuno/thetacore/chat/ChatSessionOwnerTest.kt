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
    @Test fun imageToolUsesNormalApprovalStoresPrivatelyAndColdOpenDoesNotGenerate() = runBlocking {
        val root = Files.createTempDirectory("chat-images").toFile()
        val executions = AtomicInteger()
        val payload = byteArrayOf(1, 2, 3)
        val attachments = ImageAttachments(root)
        val imageTool = io.github.bytejuno.thetacore.plugin.tools.HostImageGenerationTool({
            OpenAiRunSnapshot(OpenAiProviderSettings("images", "https://example.invalid/v1", "images"), ApiKeyCredentials("image-key"))
        }, attachments, { _, prompt ->
            assertEquals("画一只在窗边睡觉的猫", prompt)
            executions.incrementAndGet(); payload
        }, { it })
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val index = requests.incrementAndGet()
                emit(Events.started("image-response-$index", "model"))
                if (index == 1) {
                    emit(Events.toolStart(0, "image-wire", "host_generate_image"))
                    emit(Events.toolDelta(0, """{"prompt":"画一只在窗边睡觉的猫"}"""))
                    emit(Events.end(0)); emit(Events.finished(FinishReason.TOOL_CALLS))
                } else {
                    emit(Events.text(0, "done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        try {
            val first = fixture(root, executions, model)
            first.host.catalog.registerHost(IMAGE_TOOL_KEY, "1", imageTool)
            first.owner.open().join(); first.owner.send("画一只在窗边睡觉的猫").join()
            await { first.owner.state.value.records.values.any { it.pending != null } }
            val call = first.owner.state.value.records.values.single()
            assertEquals(0, executions.get())
            first.owner.decide(call.callId, call.pending!!.id, Decision.ALLOW_ONCE).join()
            await { !first.owner.state.value.active }
            assertEquals(1, executions.get())
            assertEquals(IMAGE_COMPLETE, first.owner.state.value.records.getValue(call.callId).result!!.text)
            val sessionId = first.owner.state.value.sessionId!!
            assertArrayEquals(payload, attachments.read(sessionId, call.callId))
            assertTrue(first.failures.toString(), first.failures.isEmpty())
            first.close()
            val cold = fixture(root, executions, model)
            cold.owner.open().join()
            assertEquals(sessionId, cold.owner.state.value.sessionId)
            assertEquals(2, requests.get()); assertEquals(1, executions.get())
            assertEquals(IMAGE_COMPLETE, cold.owner.state.value.records.getValue(call.callId).result!!.text)
            assertArrayEquals(payload, ImageAttachments(root).read(sessionId, call.callId))
            cold.owner.deleteCurrentSession().join()
            assertFalse(cold.store.sessionIds().contains(sessionId))
            assertNull(ImageAttachments(root).read(sessionId, call.callId))
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun ordinaryPromptWritingAndApiQuestionsDoNotGenerateWithoutModelToolCalls() = runBlocking {
        val root = Files.createTempDirectory("chat-image-text").toFile()
        val executions = AtomicInteger()
        try {
            val fixture = fixture(root, executions, provider(false))
            fixture.host.catalog.registerHost(IMAGE_TOOL_KEY, "1", tool(executions))
            fixture.owner.open().join()
            for (text in listOf("帮我写一个画猫的提示词", "解释生图 API")) {
                fixture.owner.send(text).join(); await { !fixture.owner.state.value.active }
                assertEquals(R.string.chat_complete, fixture.owner.state.value.notice)
            }
            assertEquals(0, executions.get())
            assertTrue(fixture.owner.state.value.records.isEmpty())
            fixture.close()
        } finally { root.deleteRecursively() }
    }
    private val schema = Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject
    private fun tool(count: AtomicInteger, output: ToolResult = ToolResult("done", effect = ToolEffect.APPLIED)) = object : Tool {
        override val id = "write"
        override val spec = ToolSpec("write", "test write", schema, risk = ToolRisk.WRITE, timeoutMs = 5_000)
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
            count.incrementAndGet(); return output
        }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }
    private suspend fun fixture(root: File, count: AtomicInteger, provider: ModelProvider, hostTool: Tool = tool(count),
        fault: (SqliteWriteStage) -> Unit = {}): Fixture {
        val store = AndroidAgentStore.open(root, BundledSQLiteDriver(), fault)
        val budget = PluginExecutionBudget()
        val host = HostToolBridgeRoot(budget, hostTool).initialize { store }
        return Fixture(store, budget, host, provider, ImageAttachments(root))
    }
    private inner class Fixture(val store: AndroidAgentStore, val budget: PluginExecutionBudget,
        val host: HostToolExecutionService, provider: ModelProvider, images: ImageAttachments) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failures = mutableListOf<Throwable>()
        val owner = ChatSessionOwner(scope, { host }, {
            OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model"), ApiKeyCredentials("test-key"))
        }, { synchronized(failures) { failures += it } }, { ModelTarget("test", provider, Credentials { "test-key" }) }, images)
        suspend fun close() {
            owner.stop().join()
            await { !owner.state.value.active }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close(); store.close()
        }
    }
    private fun provider(tool: Boolean = true, streaming: CompletableDeferred<Unit>? = null, calls: Int = 1) = object : ModelProvider {
        var requests = 0
        override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
        override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
            emit(Events.started("response-${requests++}", "model"))
            if (tool && requests == 1) {
                repeat(calls) { index ->
                    emit(Events.toolStart(index, "wire-$index", "host_clock")); emit(Events.toolDelta(index, "{}")); emit(Events.end(index))
                }
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

    @Test fun refusedFilteredAndInterruptedRepliesSurviveColdOpenButNeverReplay() = runBlocking {
        for (finish in listOf(FinishReason.REFUSAL, FinishReason.CONTENT_FILTER, null)) {
            val root = Files.createTempDirectory("chat-retained").toFile()
            val requests = mutableListOf<ChatRequest>()
            val allowSuccess = java.util.concurrent.atomic.AtomicBoolean(false)
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    emit(Events.started("retained-${requests.size}", "model"))
                    if (!allowSuccess.get()) {
                        emit(Events.text(0, "private non-replayable reply"))
                        if (finish == null) throw java.io.IOException("connection interrupted")
                        emit(Events.end(0)); emit(Events.finished(finish))
                    } else {
                        emit(Events.text(0, "safe answer")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                first.owner.open().join(); first.owner.send("question").join(); await { !first.owner.state.value.active }
                val reply = first.owner.state.value.journal.first()
                assertEquals("private non-replayable reply", (reply.partial.parts.single() as ContentPart.Text).text)
                assertEquals(finish?.name ?: "NETWORK", reply.reason)
                assertEquals(1, first.owner.state.value.messages.size)
                val session = first.owner.state.value.sessionId
                first.close()
                val cold = fixture(root, AtomicInteger(), model)
                cold.owner.open().join()
                assertEquals(session, cold.owner.state.value.sessionId)
                assertEquals(reply.requestId, cold.owner.state.value.journal.first().requestId)
                allowSuccess.set(true)
                cold.owner.send("next question").join(); await { !cold.owner.state.value.active }
                assertEquals(R.string.chat_complete, cold.owner.state.value.notice)
                assertFalse(requests.last().messages.any { message -> message.parts.any { part ->
                    part is ContentPart.Text && part.text.contains("non-replayable")
                } })
                assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                cold.close()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun oversizedHistoryOffersExplicitBoundedRecoveryWithoutLosingOriginals() = runBlocking {
        val root = Files.createTempDirectory("chat-context").toFile()
        val requests = mutableListOf<ChatRequest>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                requests += request
                emit(Events.started("bounded-${requests.size}", "model"))
                emit(Events.text(0, if (request.tools.isEmpty()) "Prior conversation summary" else "final answer"))
                emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
            }
        }
        try {
            val seed = fixture(root, AtomicInteger(), model)
            seed.store.createHistory("session").use { disk ->
                repeat(34) { disk.append(disk.revision(), listOf(message(Role.USER, textPart("u".repeat(32_000))),
                    message(Role.ASSISTANT, textPart("a".repeat(32_000))))) }
            }
            seed.owner.open().join(); seed.owner.send("Continue this conversation").join(); await { !seed.owner.state.value.active }
            assertTrue(requests.isEmpty())
            assertEquals(R.string.chat_pause_context, chatOutcome(seed.owner.state.value.result!!))
            assertEquals("BUDGET", seed.owner.state.value.checkpoint!!.pauseClass)
            seed.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.owner.open().join()
            assertEquals("BUDGET", cold.owner.state.value.checkpoint!!.pauseClass)
            assertTrue(requests.isEmpty())
            val selection = requireNotNull(cold.owner.state.value.recovery)
            cold.owner.recover(selection, ChatRecoveryAction.SUMMARIZE, 1_000_000, confirmed = true).join()
            await { !cold.owner.state.value.active }
            assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
            assertTrue(requests.any { it.tools.isEmpty() })
            assertTrue(requests.last().tools.isNotEmpty())
            assertTrue(requests.last().messages.size < 69)
            assertEquals(0L, cold.owner.state.value.acceptedMessages)
            assertTrue(cold.failures.toString(), cold.failures.isEmpty())
            cold.close()
            val verify = fixture(root, AtomicInteger(), model)
            verify.store.openHistory("session").use { disk ->
                val stored = disk.snapshot().messages
                assertEquals(70, stored.size)
                assertEquals("u".repeat(32_000), (stored.first().parts.single() as ContentPart.Text).text)
                assertEquals("a".repeat(32_000), (stored[67].parts.single() as ContentPart.Text).text)
                assertTrue(disk.chatResult()!!.steps > 1)
            }
            verify.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun savedContextCheckpointRequiresSelectionAndPreservesTaskBudget() = runBlocking {
        val root = Files.createTempDirectory("chat-checkpoint").toFile()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("checkpoint-$count", "model")); emit(Events.text(0, "bounded answer")); emit(Events.end(0))
                emit(Events.finished(if (request.tools.isNotEmpty() && count <= 2) FinishReason.CONTEXT_EXCEEDED else FinishReason.STOP))
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("unfinished goal").join(); await { !first.owner.state.value.active }
            first.owner.resume(summaryWindowTokens = 8192).join(); await { !first.owner.state.value.active }
            val checkpoint = requireNotNull(first.owner.state.value.checkpoint)
            assertTrue(first.failures.toString(), checkpoint.canContinue)
            val count = requests.get()
            first.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.owner.open().join()
            assertEquals(count, requests.get())
            assertEquals(checkpoint.id, cold.owner.state.value.checkpoint!!.id)
            val selection = requireNotNull(cold.owner.state.value.recovery)
            cold.owner.recover(selection, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = false).join()
            assertEquals(count, requests.get())
            cold.owner.recover(selection, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = true).join()
            await { !cold.owner.state.value.active }
            assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
            assertTrue(cold.owner.state.value.result!!.steps > checkpoint.steps)
            assertEquals(1, cold.failures.size)
            cold.owner.recover(selection, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = true).join()
            assertEquals(count + 1, requests.get())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun largerWindowReadmissionPreservesRawHistoryWithoutSummaryCalls() = runBlocking {
        val root = Files.createTempDirectory("chat-window-readmit").toFile()
        val requests = AtomicInteger()
        try {
            val first = fixture(root, AtomicInteger(), countingProvider(requests) { FinishReason.STOP })
            first.store.createHistory("session").use { disk ->
                disk.saveChatContext(ChatTaskContext("session", "goal", 0, null, 1024))
            }
            first.owner.open().join(); first.owner.send("goal ".repeat(400)).join()
            await { !first.owner.state.value.active }
            assertEquals(0, requests.get())
            assertEquals("BUDGET", first.owner.state.value.checkpoint!!.pauseClass)
            val token = requireNotNull(first.owner.state.value.recovery)
            first.owner.recover(token, ChatRecoveryAction.READMIT, 1024, true).join()
            assertEquals(0, requests.get())
            first.owner.recover(token, ChatRecoveryAction.READMIT, 8192, true).join()
            await { !first.owner.state.value.active }
            assertEquals(1, requests.get())
            assertEquals(R.string.chat_complete, first.owner.state.value.notice)
            assertEquals("goal ".repeat(400), (first.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
            first.owner.recover(token, ChatRecoveryAction.READMIT, 8192, true).join()
            assertEquals(1, requests.get())
            first.close()
            val reopened = fixture(root, AtomicInteger(), countingProvider(requests) { FinishReason.STOP })
            reopened.store.openHistory("session").use { disk ->
                assertNull(disk.chatSummaryWindow())
                assertEquals(8192L, disk.chatContextWindow())
            }
            reopened.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun coldUnattendedRunsCannotBePromotedByUiOrDirectResume() = runBlocking {
        for (uncertain in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-unattended-recovery").toFile()
            val requests = AtomicInteger()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests.incrementAndGet()
                    emit(Events.started("uncertain", "model"))
                    throw java.io.IOException("unknown outcome")
                }
            }
            try {
                val seed = fixture(root, AtomicInteger(), model)
                seed.store.createHistory("session").use { disk ->
                    disk.saveChatContext(ChatTaskContext("session", "goal", 0))
                    disk.append(0, listOf(message(Role.USER, textPart("goal"))))
                    val identity = RunIdentity("session", "session", "unattended", true)
                    val definitions = seed.host.catalog.freeze(identity.runId).values.toList()
                    val result = AgentRunLoop(identity, ModelTarget("test", model, Credentials { "test" }),
                        chatRequest("model") {
                            maxOutputTokens = 100
                            tools = definitions.map { modelTool(it.modelName, it.description, Json.parseToJsonElement(it.inputSchema).jsonObject) }
                        }, disk,
                        LedgerToolExecutor(seed.host.ledger, seed.host.catalog.gate, definitions,
                            seed.host.runBridge(), RunApprovals { error("No tool should request approval") }),
                        journal = StoredRunJournal(disk),
                        budget = ContextBudget({ 100L }, { if (uncertain) 8192L else 10L }),
                        contextPolicy = ContextPolicy("goal")).runChatTask()
                    disk.saveChatResult(result)
                }
                val submitted = requests.get()
                seed.close()
                val cold = fixture(root, AtomicInteger(), model)
                cold.owner.open().join()
                val token = requireNotNull(cold.owner.state.value.recovery)
                assertTrue(token.identity.unattended)
                assertTrue(recoveryActions(cold.owner.state.value).isEmpty())
                val action = if (uncertain) ChatRecoveryAction.RETRY else ChatRecoveryAction.READMIT
                cold.owner.recover(token, action, 8192, true).join()
                cold.owner.recover(token.copy(identity = token.identity.copy(unattended = false)), action, 8192, true).join()
                cold.owner.resume(summaryWindowTokens = 8192, reconcileId = token.reconciliation?.id).join()
                cold.owner.send("continue this unattended task").join()
                assertEquals(submitted, requests.get())
                assertEquals(token, cold.owner.state.value.recovery)
                cold.close()
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun cancellationRetainsPartialReplyAndJournalPagingSurvivesReopen() = runBlocking {
        val root = Files.createTempDirectory("chat-journal-pages").toFile()
        val arrived = CompletableDeferred<Unit>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                emit(Events.started("cancelled", "model")); emit(Events.text(0, "unfinished text"))
                arrived.complete(Unit); awaitCancellation()
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("start").join(); arrived.await()
            await { first.owner.previewText() == "unfinished text" }
            first.owner.stop().join(); await { !first.owner.state.value.active }
            assertEquals("INTERRUPTED", first.owner.state.value.journal.single().reason)
            assertEquals("unfinished text", (first.owner.state.value.journal.single().partial.parts.single() as ContentPart.Text).text)
            val session = first.owner.state.value.sessionId!!
            first.close()
            val seed = fixture(root, AtomicInteger(), provider(false))
            seed.store.openHistory(session).use { disk ->
                val journal = StoredRunJournal(disk)
                repeat(25) { index ->
                    journal.submitted("refusal-$index", "test", ModelExecutionMode.GENERATION_ONLY)
                    journal.finished(ModelAttempt("refusal-$index", "test", ModelExecutionMode.GENERATION_ONLY,
                        message(Role.ASSISTANT, textPart("refusal-$index")), Events.finished(FinishReason.REFUSAL), null, false))
                }
            }
            seed.owner.open().join()
            assertEquals(20, seed.owner.state.value.journal.size)
            seed.owner.olderReplies().join()
            assertEquals(26, seed.owner.state.value.journal.size)
            assertNull(seed.owner.state.value.olderJournal)
            assertEquals(1, seed.owner.state.value.messages.size)
            seed.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun uncertainRequestNeedsExactExplicitReconciliationAfterColdOpen() = runBlocking {
        val root = Files.createTempDirectory("chat-reconciliation").toFile()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("remote-$count", "model")); emit(Events.text(0, "remote reply"))
                if (count == 1) throw java.io.IOException("unknown server outcome")
                emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("goal").join(); await { !first.owner.state.value.active }
            val receipt = requireNotNull(first.owner.state.value.reconciliation)
            val completedSteps = first.owner.state.value.result!!.steps
            assertEquals(R.string.chat_pause_reconcile, chatOutcome(first.owner.state.value.result!!))
            first.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.owner.open().join(); cold.owner.resume().join()
            assertEquals(1, requests.get())
            cold.owner.resume(reconcileId = "stale").join()
            assertEquals(receipt.id, cold.owner.state.value.reconciliation!!.id)
            val selection = requireNotNull(cold.owner.state.value.recovery)
            cold.owner.recover(selection, ChatRecoveryAction.RETRY, confirmed = false).join()
            assertEquals(1, requests.get())
            cold.owner.recover(selection.copy(revision = selection.revision - 1), ChatRecoveryAction.RETRY, confirmed = true).join()
            assertEquals(receipt.id, cold.owner.state.value.reconciliation!!.id)
            cold.owner.recover(selection, ChatRecoveryAction.RETRY, confirmed = true).join(); await { !cold.owner.state.value.active }
            assertEquals(2, requests.get())
            assertEquals(R.string.chat_complete, cold.owner.state.value.notice)
            assertNull(cold.owner.state.value.reconciliation)
            assertEquals(completedSteps + 1, cold.owner.state.value.result!!.steps)
            cold.owner.recover(selection, ChatRecoveryAction.RETRY, confirmed = true).join()
            assertEquals(2, requests.get())
            cold.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun coldFirstUncertainRequestRetainsRecoveryIdentity() = runBlocking { crashWhileSubmitted(false) }
    @Test fun coldUncertainRequestAfterCompletedTurnRemainsRecoverable() = runBlocking { crashWhileSubmitted(true) }

    private suspend fun crashWhileSubmitted(priorCompleted: Boolean) {
        val root = Files.createTempDirectory("chat-cold-recovery").toFile()
        val entered = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.MAY_EXECUTE_SERVER_TOOLS
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("cold-$count", "model"))
                if (count == if (priorCompleted) 2 else 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                } else {
                    emit(Events.text(0, "answer")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
        }
        try {
            val live = File(root, "live").also { it.mkdir() }
            val saved = File(root, "saved").also { it.mkdir() }
            val seed = fixture(live, AtomicInteger(), model)
            try {
                seed.owner.open().join()
                if (priorCompleted) {
                    seed.owner.send("completed goal").join(); await { !seed.owner.state.value.active }
                    assertEquals(RunState.COMPLETED, seed.owner.state.value.result!!.state)
                }
                seed.owner.send("uncertain goal").join()
                withTimeout(10_000) { entered.await() }
                BundledSQLiteDriver().open(File(live, AgentDatabase.FILE_NAME).path).use { db ->
                    db.prepare("VACUUM INTO ?").use {
                        it.bindText(1, File(saved, AgentDatabase.FILE_NAME).path); it.step()
                    }
                }
            } finally { seed.close() }
            val cold = fixture(saved, AtomicInteger(), model)
            try {
                cold.owner.open().join()
                val state = cold.owner.state.value
                assertNotNull("Unknown server result must remain pending", state.reconciliation)
                assertNotNull("Fresh attended submission must retain its recovery identity", state.recovery)
                assertEquals(setOf(ChatRecoveryAction.RETRY), recoveryActions(state))
                val count = requests.get()
                cold.owner.recover(state.recovery!!, ChatRecoveryAction.RETRY, confirmed = true).join()
                await { !cold.owner.state.value.active }
                assertEquals(count + 1, requests.get())
                assertEquals(RunState.COMPLETED, cold.owner.state.value.result!!.state)
            } finally { cold.close() }
        } finally { root.deleteRecursively() }
    }

    @Test fun checkpointResumeRejectsChangedCatalogInsteadOfGuessingAliases() = runBlocking {
        val root = Files.createTempDirectory("chat-catalog-checkpoint").toFile()
        val requests = AtomicInteger()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                val count = requests.incrementAndGet()
                emit(Events.started("catalog-$count", "model")); emit(Events.text(0, "summary")); emit(Events.end(0))
                emit(Events.finished(if (request.tools.isNotEmpty()) FinishReason.CONTEXT_EXCEEDED else FinishReason.STOP))
            }
        }
        try {
            val first = fixture(root, AtomicInteger(), model)
            first.owner.open().join(); first.owner.send("goal").join(); await { !first.owner.state.value.active }
            first.owner.resume(summaryWindowTokens = 8192).join(); await { !first.owner.state.value.active }
            assertTrue(first.owner.state.value.checkpoint!!.canContinue)
            val count = requests.get()
            first.close()
            val cold = fixture(root, AtomicInteger(), model)
            cold.host.catalog.registerHost("host/clock", "2", tool(AtomicInteger()))
            cold.owner.open().join(); cold.owner.resume().join(); await { !cold.owner.state.value.active }
            assertEquals(count, requests.get())
            assertNotNull(cold.owner.state.value.error)
            assertTrue(cold.owner.state.value.checkpoint!!.canContinue)
            cold.close()
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

    @Test fun completedQuestionDoesNotCancelSubsequentApproval() = runBlocking {
        for (decision in listOf(Decision.ALLOW_ONCE, Decision.DENY)) {
            val root = Files.createTempDirectory("chat-next-approval").toFile()
            val executions = AtomicInteger()
            val requests = AtomicInteger()
            val delegate = provider()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    if (requests.incrementAndGet() == 1) {
                        emit(Events.started("first", "model")); emit(Events.text(0, "first answer"))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    } else delegate.chat(request, auth).collect { emit(it) }
                }
            }
            try {
                val fixture = fixture(root, executions, model)
                try {
                    fixture.owner.open().join(); fixture.owner.send("first question").join()
                    await { !fixture.owner.state.value.active }
                    fixture.owner.send("write after first answer").join()
                    await { fixture.owner.state.value.records.values.any { it.pending != null } || !fixture.owner.state.value.active }
                    fixture.owner.latest().join()
                    assertTrue(fixture.failures.toString(), fixture.owner.state.value.active)
                    val record = fixture.owner.state.value.records.values.single()
                    assertNotNull(record.pending); assertEquals(0, executions.get())
                    assertNull(fixture.owner.state.value.checkpoint)
                    fixture.owner.decide(record.callId, record.pending!!.id, decision).join()
                    await { !fixture.owner.state.value.active }
                    assertEquals(if (decision == Decision.ALLOW_ONCE) 1 else 0, executions.get())
                    assertEquals(decision, fixture.host.ledger.get(record.callId)!!.receipts.single().decision)
                    assertEquals(6, fixture.owner.state.value.messages.size)
                    assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                } finally { fixture.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun rejectedContextRecoveryShrinksBeforeGenerationEvenWhenNewWindowWouldFit() = runBlocking {
        for (window in listOf(8192L, 32768L)) {
            val root = Files.createTempDirectory("chat-rejected-context").toFile()
            val requests = mutableListOf<ChatRequest>()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    if (requests.size == 1) {
                        emit(Events.failed(modelError(ModelErrorCode.CONTEXT_TOO_LONG, "window rejected")))
                    } else {
                        emit(Events.started("recovered-${requests.size}", "model"))
                        emit(Events.text(0, if (request.tools.isEmpty()) "summary of earlier answers" else "final answer"))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                first.store.createHistory("session").use { disk ->
                    disk.append(0, listOf(message(Role.USER, textPart("u".repeat(5000))),
                        message(Role.ASSISTANT, textPart("a".repeat(5000)))))
                }
                first.owner.open().join(); first.owner.send("continue goal").join(); await { !first.owner.state.value.active }
                assertEquals(1, requests.size)
                val retries = first.owner.state.value.result!!.retries
                first.close()
                val cold = fixture(root, AtomicInteger(), model)
                try {
                    cold.owner.open().join(); assertEquals(1, requests.size)
                    cold.owner.resume(summaryWindowTokens = window).join(); await { !cold.owner.state.value.active }
                    assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
                    assertTrue(requests[1].tools.isEmpty())
                    val generation = requests.last()
                    assertTrue(generation.tools.isNotEmpty())
                    val codec = OpenAiChatCodec(OpenAiChatConfig("test", "https://example.invalid/v1", setOf("model")))
                    assertTrue(codec.encode(generation).length < codec.encode(requests.first()).length)
                    assertTrue(256L + codec.encode(generation).toByteArray(Charsets.UTF_8).size + generation.maxOutputTokens!! <= window)
                    assertTrue(cold.owner.state.value.result!!.retries >= retries)
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
                val verified = fixture(root, AtomicInteger(), model)
                try {
                    verified.store.openHistory("session").use { disk ->
                        assertEquals(4, disk.snapshot().messages.size)
                        assertEquals("u".repeat(5000), (disk.snapshot().messages.first().parts.single() as ContentPart.Text).text)
                    }
                } finally { verified.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun contextExceededReadmissionSummarizesBeforeGenerationAcrossColdOpen() = runBlocking {
        for (incomplete in listOf(false, true)) {
            val root = Files.createTempDirectory("chat-context-exceeded").toFile()
            val requests = mutableListOf<ChatRequest>()
            val executions = AtomicInteger()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request
                    emit(Events.started("context-${requests.size}", "model"))
                    if (requests.size == 1) {
                        emit(Events.text(0, "truncated reply")); emit(Events.end(0))
                        if (incomplete) {
                            emit(Events.toolStart(1, "wire", "host_clock"))
                            emit(Events.toolDelta(1, "{")); emit(Events.end(1))
                        }
                        emit(Events.finished(FinishReason.CONTEXT_EXCEEDED))
                    } else {
                        emit(Events.text(0, if (request.tools.isEmpty()) "prior summary" else "final answer"))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, executions, model)
                try {
                    first.store.createHistory("session").use { disk ->
                        disk.append(0, listOf(message(Role.USER, textPart("u".repeat(5000))),
                            message(Role.ASSISTANT, textPart("a".repeat(5000)))))
                    }
                    first.owner.open().join(); first.owner.send("continue goal").join()
                    await { !first.owner.state.value.active }
                    assertEquals(1, requests.size)
                    assertEquals("BUDGET", first.owner.state.value.checkpoint!!.pauseClass)
                    assertTrue(first.failures.toString(), first.failures.isEmpty())
                } finally { first.close() }
                val cold = fixture(root, executions, model)
                try {
                    cold.owner.open().join(); assertEquals(1, requests.size)
                    cold.owner.resume(summaryWindowTokens = 32768).join()
                    await { !cold.owner.state.value.active }
                    assertEquals(cold.failures.toString(), R.string.chat_complete, cold.owner.state.value.notice)
                    assertTrue(requests[1].tools.isEmpty())
                    assertTrue(requests.last().tools.isNotEmpty())
                    val codec = OpenAiChatCodec(OpenAiChatConfig("test", "https://example.invalid/v1", setOf("model")))
                    assertTrue(codec.encode(requests.last()).length < codec.encode(requests.first()).length)
                    assertFalse(requests.last().messages.any { m -> m.parts.any { it is ContentPart.ToolCall } })
                    assertEquals(0, executions.get())
                    assertEquals(if (incomplete) 4 else 5, cold.owner.state.value.messages.size)
                    assertEquals("u".repeat(5000), (cold.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
                    assertTrue(cold.owner.state.value.result!!.steps >= 2)
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun incompleteTruncatedRepliesSurviveColdOpenAndNeverReplay() = runBlocking {
        for (finish in listOf(FinishReason.LENGTH, FinishReason.CONTEXT_EXCEEDED)) {
            val root = Files.createTempDirectory("chat-truncated-reply").toFile()
            val requests = mutableListOf<ChatRequest>()
            val model = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    requests += request; emit(Events.started("truncated-${requests.size}", "model"))
                    if (requests.size == 1) {
                        emit(Events.text(0, "discarded partial reply")); emit(Events.end(0))
                        emit(Events.toolStart(1, "incomplete", "host_clock")); emit(Events.toolDelta(1, "{"))
                        emit(Events.end(1))
                        emit(Events.finished(finish))
                    } else {
                        emit(Events.text(0, "safe answer")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            try {
                val first = fixture(root, AtomicInteger(), model)
                first.owner.open().join(); first.owner.send("question").join(); await { !first.owner.state.value.active }
                val reply = first.owner.state.value.journal.single()
                assertEquals(finish.name, reply.reason)
                assertEquals(if (finish == FinishReason.LENGTH) R.string.chat_reply_length else R.string.chat_reply_context,
                    replyReason(reply.reason))
                assertEquals(1, first.owner.state.value.messages.size)
                assertTrue(first.failures.toString(), first.failures.isEmpty()); first.close()
                val cold = fixture(root, AtomicInteger(), model)
                try {
                    cold.owner.open().join(); assertEquals(reply.requestId, cold.owner.state.value.journal.single().requestId)
                    cold.owner.send("new question").join(); await { !cold.owner.state.value.active }
                    assertEquals(R.string.chat_complete, cold.owner.state.value.notice)
                    assertFalse(requests.last().messages.any { message -> message.parts.any { part ->
                        part is ContentPart.ToolCall || part is ContentPart.Text && part.text.contains("discarded partial")
                    } })
                    assertTrue(cold.failures.toString(), cold.failures.isEmpty())
                } finally { cold.close() }
            } finally { root.deleteRecursively() }
        }
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
                val completed = fixture(root, count, provider(false))
                completed.store.createHistory("session").close()
                completed.owner.open().join(); completed.owner.send("earlier question").join()
                await { !completed.owner.state.value.active }
                assertEquals(R.string.chat_complete, completed.owner.state.value.notice)
                completed.close()
                val seed = fixture(root, count, provider(false))
                val d = seed.host.catalog.freeze("old-run").getValue("host_clock")
                val binding = callBinding("test", "request", "response", 0, "wire")
                seed.store.openHistory("session").use { history ->
                    val chain = history.chatTaskChainId()
                    history.append(history.revision(), listOf(message(Role.USER, textPart("goal"))))
                    history.prepareStep(history.revision(), RunIdentity(chain, "session", "old-run"), "step",
                        message(Role.ASSISTANT, toolCallPart(binding, d.modelName, "{}")))
                    val input = InvocationInput(BindingValue.copyOf(binding), chain, "session", "old-run", "step", 0,
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
                assertEquals(5, cold.owner.state.value.messages.size)
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

    @Test fun failedApprovalWriteSettlesWithoutWorkerOrUncaughtFailure() = runBlocking {
        for (stage in listOf(SqliteWriteStage.BEFORE_COMMIT, SqliteWriteStage.AFTER_COMMIT)) {
            val root = Files.createTempDirectory("chat-write-failure").toFile()
            val inject = java.util.concurrent.atomic.AtomicBoolean(false)
            val count = AtomicInteger()
            val fixture = fixture(root, count, provider()) {
                if (it == stage && inject.compareAndSet(true, false)) error("Injected approval write failure")
            }
            try {
                val owner = fixture.owner
                owner.open().join(); owner.send("goal").join()
                await { owner.state.value.records.values.any { it.pending != null } }
                val record = owner.state.value.records.values.single()
                inject.set(true); owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
                await { !owner.state.value.active }
                assertFalse(inject.get()); assertEquals(0, count.get()); assertNotNull(owner.state.value.error)
                assertTrue(fixture.host.submissionsClosed)
            } finally { fixture.close(); root.deleteRecursively() }
        }
    }

    @Test fun lateToolExitPublishesRecoveryOnIdleChat() = runBlocking {
        val root = Files.createTempDirectory("chat-late-recovery").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = object : Tool {
            override val id = "write"
            override val spec = ToolSpec(id, "test write", schema, risk = ToolRisk.WRITE)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return ToolResult("late", effect = ToolEffect.UNKNOWN)
            }
        }
        val fixture = fixture(root, AtomicInteger(), provider(), write)
        try {
            val owner = fixture.owner
            owner.open().join(); owner.send("goal").join()
            await { owner.state.value.records.values.any { it.pending != null } }
            val record = owner.state.value.records.values.single()
            owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            withTimeout(10_000) { entered.await() }
            owner.stop().join(); await { !owner.state.value.active }
            assertNull(owner.state.value.recoveries[record.callId])
            release.complete(Unit); fixture.host.awaitObservedExit(record.callId)
            val recovery = fixture.host.ledger.pendingRecoveries().single()
            await { owner.state.value.recoveries[record.callId] == recovery }
            assertEquals(InstanceState.EXIT_CONFIRMED, owner.state.value.records.getValue(record.callId).instance!!.state)
            assertFalse(owner.state.value.active)
        } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
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

    @Test fun parallelUnknownResultRenewsInlineApprovalWithoutLosingTheCall() = runBlocking {
        for (decision in listOf(Decision.DENY, Decision.ALLOW_ONCE)) {
            val root = Files.createTempDirectory("chat-renewal").toFile()
            val count = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val parallelTool = object : Tool {
                override val id = "write"
                override val spec = ToolSpec(id, "test write", schema, risk = ToolRisk.WRITE, parallelSafe = true, timeoutMs = 30_000)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = if (count.incrementAndGet() == 1) {
                    release.await(); ToolResult("interrupted", isError = true, effect = ToolEffect.UNKNOWN)
                } else ToolResult("done", effect = ToolEffect.APPLIED)
            }
            val fixture = fixture(root, count, provider(calls = 2), parallelTool)
            try {
                val owner = fixture.owner
                owner.open().join(); owner.send("two writes").join()
                suspend fun pending(order: Int): InvocationRecord {
                    await { owner.state.value.records.values.any { it.pending != null && it.input.callOrder == order } }
                    return owner.state.value.records.values.single { it.input.callOrder == order }
                }
                val first = pending(0)
                owner.decide(first.callId, first.pending!!.id, Decision.ALLOW_ONCE).join()
                val second = pending(1)
                val oldId = second.pending!!.id
                release.complete(Unit)
                await { fixture.host.ledger.get(first.callId)?.instance?.state == InstanceState.EXIT_CONFIRMED }
                owner.decide(second.callId, oldId, Decision.ALLOW_ONCE).join()
                val updated = requireNotNull(pending(1).pending)
                assertNotEquals(oldId, updated.id)
                assertEquals(listOf(first.callId), updated.unknownCalls)
                assertEquals(1, count.get())
                owner.decide(second.callId, updated.id, decision).join()
                await { !owner.state.value.active }
                val final = requireNotNull(fixture.host.ledger.get(second.callId))
                assertEquals(updated.id, final.receipts.single().request.id)
                assertEquals(decision, final.receipts.single().decision)
                assertEquals(if (decision == Decision.DENY) Status.REJECTED else Status.SUCCEEDED, final.state)
                assertEquals(if (decision == Decision.DENY) 1 else 2, count.get())
                assertEquals(R.string.chat_complete, owner.state.value.notice)
            } finally { release.complete(Unit); fixture.close(); root.deleteRecursively() }
        }
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
