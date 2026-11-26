package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ChatEntrySessionsTest {
    private class Fixture(val risk: ToolRisk = ToolRisk.READ, val configured: Boolean = true,
        val hold: CompletableDeferred<Unit>? = null, val presenceFails: Boolean = false,
        val disconnectFirst: Boolean = false, val interleaved: Boolean = false) {
        val root = Files.createTempDirectory("entry-session").toFile()
        val store = AndroidAgentStore.open(root, BundledSQLiteDriver())
        val budget = PluginExecutionBudget()
        val executions = AtomicInteger()
        val requests = AtomicInteger()
        val tool = object : Tool {
            override val id = "read"
            override val spec = ToolSpec("read", "Read fixture", Json.parseToJsonElement("""{"type":"object"}""").jsonObject, risk = risk)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                executions.incrementAndGet()
                return ToolResult("done", effect = ToolEffect.NONE)
            }
        }
        lateinit var host: HostToolExecutionService
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val presence = CopyOnWriteArrayList<ChatTaskProgress?>()
        val model = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials) = flow {
                val turn = requests.incrementAndGet()
                emit(Events.started("entry-$turn", "model"))
                if (interleaved) {
                    emit(Events.text(0, "A"))
                    emit(Events.text(1, "C"))
                    emit(Events.end(1))
                    hold?.await()
                    emit(Events.text(0, "B"))
                    emit(Events.end(0))
                    emit(Events.finished(FinishReason.STOP))
                    return@flow
                }
                emit(Events.text(0, if (turn == 1) "before tool" else "answer"))
                if (disconnectFirst && turn == 1) throw java.io.IOException("connection lost")
                hold?.await()
                emit(Events.end(0))
                if (turn == 1) {
                    emit(Events.toolStart(1, "call", "host_clock"))
                    emit(Events.toolDelta(1, "{}")); emit(Events.end(1))
                }
                emit(Events.finished(if (turn == 1) FinishReason.TOOL_CALLS else FinishReason.STOP))
            }
        }
        val owner = ChatSessionOwner(scope, { host }, {
            check(configured) { "secret/internal stack must not escape" }
            OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model"), ApiKeyCredentials("test"))
        }, targetForRun = { ModelTarget("test", model, Credentials { "test" }) }, presence = object : ChatTaskPresence {
            override suspend fun update(executionId: String, progress: ChatTaskProgress) {
                if (presenceFails) throw ChatTaskPresenceException(IllegalStateException("internal"))
                presence += progress
            }
            override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) { presence += waiting }
        })
        suspend fun initialize() { host = HostToolBridgeRoot(budget, tool).initialize { store } }
        suspend fun close() {
            hold?.complete(Unit)
            owner.stop().join()
            withTimeout(10_000) { owner.state.first { !it.active } }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close(); store.close(); root.deleteRecursively()
        }
    }

    private suspend fun unavailable(action: suspend () -> Unit) {
        try { action(); fail("Expected safe rejection") }
        catch (error: EntryUnavailableException) { assertFalse(error.message.orEmpty().contains("secret")) }
    }

    @Test fun readyEntryDrivesRealKernelAndRetainsOrderedEventsBeforeSubscription() = runBlocking {
        val f = Fixture(); f.initialize()
        val pluginScope = CoroutineScope(SupervisorJob())
        val opened = CompletableDeferred<EntrySession>()
        val hostRef = CompletableDeferred<EntryHost>()
        val adapter = object : EntryAdapter {
            override val id = "voice"
            override val displayName = "Voice"
            override fun isAvailable(device: DeviceInfo) = true
            override suspend fun attach(host: EntryHost) {
                unavailable { host.open(EntryRequest("too early")) }
                host.ready(); hostRef.complete(host)
                opened.complete(host.open(EntryRequest("goal")))
                awaitCancellation()
            }
        }
        val runtime = PluginEntryRuntime(f.budget, "entry", pluginScope, adapter, DeviceInfo(33),
            openSession = f.owner.entrySessions::open)
        try {
            runtime.start()
            val entry = withTimeout(10_000) { opened.await() }
            val seen = CopyOnWriteArrayList<SessionEvent>()
            val collect = launch { entry.events.collect { seen += it } }
            withTimeout(10_000) { while (seen.none { it === SessionEvent.Finished }) delay(10) }
            assertEquals("before toolanswer", seen.filterIsInstance<SessionEvent.Text>().joinToString("") { it.delta })
            val toolIndex = seen.indexOfFirst { it is SessionEvent.ToolActivity }
            assertTrue(toolIndex > seen.indexOfFirst { it is SessionEvent.Text })
            assertTrue(seen.indexOfFirst { it === SessionEvent.Finished } > toolIndex)
            assertTrue(seen.filterIsInstance<SessionEvent.ToolActivity>().any { it.state == "SUCCEEDED" })
            assertEquals(1, f.executions.get())
            assertEquals(ChatTaskProgress.Model, f.presence.first())
            validateHistory(f.owner.state.value.messages)
            entry.send("follow up")
            withTimeout(10_000) { while (seen.count { it === SessionEvent.Finished } < 2) delay(10) }
            assertEquals(2L, f.owner.state.value.acceptedMessages)
            runtime.close()
            unavailable { entry.send("after disable") }
            unavailable { hostRef.await().open(EntryRequest("after disable")) }
            withTimeout(10_000) { runtime.actualExited.await() }
            collect.cancelAndJoin()
            assertEquals(0, f.owner.entrySessions.handleCount)
            val bound = f.owner.state.value.sessionId!!
            val replacements = List(32) { f.owner.entrySessions.open(EntryRequest("", bound)) }
            replacements.forEach { it.close() }
        } finally { runtime.close(); pluginScope.cancel(); f.close() }
    }

    @Test fun cancellationOnReturnReclaimsEveryHandleAndLeavesAdmittedRunAlone() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val f = Fixture(hold = hold); f.initialize()
        val resumes = Channel<Runnable>(Channel.UNLIMITED)
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                check(resumes.trySend(block).isSuccess)
            }
        }
        try {
            f.store.createHistory("saved").close()
            repeat(33) { index ->
                val allowOpen = CountDownLatch(1)
                val text = if (index == 32) "go" else ""
                val opening = launch(caller, start = CoroutineStart.UNDISPATCHED) {
                    f.owner.entrySessions.open(EntryRequest(text, "saved")) {
                        allowOpen.await(10, TimeUnit.SECONDS)
                    }
                    fail("Cancelled caller received a handle")
                }
                allowOpen.countDown()
                val resume = withTimeout(10_000) { resumes.receive() }
                opening.cancel()
                resume.run()
                opening.join()
                assertEquals(0, f.owner.entrySessions.handleCount)
            }
            assertTrue(f.owner.state.value.active)
            hold.complete(Unit)
            withTimeout(10_000) { f.owner.state.first { !it.active } }
            assertEquals(RunState.COMPLETED, f.owner.state.value.result?.state)
            assertEquals(1L, f.owner.state.value.acceptedMessages)
            validateHistory(f.owner.state.value.messages)
        } finally { f.close() }
    }

    @Test fun entryAdmissionPreservesUnsentChatDraft() = runBlocking {
        val f = Fixture(); f.initialize()
        try {
            f.owner.open().join()
            val state = f.owner.state.value
            val entry = f.owner.entrySessions.open(EntryRequest("external", state.sessionId))
            val after = f.owner.state.value
            assertEquals(1L, after.acceptedMessages)
            assertNull(after.acceptedDraft)
            entry.close()
            withTimeout(10_000) { f.owner.state.first { !it.active } }
            val receipt = ChatDraftReceipt(requireNotNull(state.sessionId), "draft", emptySet())
            f.owner.send("unsent", draft = receipt).join()
            assertSame(receipt, f.owner.state.value.acceptedDraft)
        } finally { f.close() }
    }

    @Test fun interleavedTextStreamsInStoredPartOrder() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val f = Fixture(hold = hold, interleaved = true); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest("parts"))
            val chunks = mutableListOf<String>()
            withTimeout(10_000) {
                entry.events.first {
                    if (it is SessionEvent.Text) {
                        chunks += it.delta
                        hold.complete(Unit)
                    }
                    it === SessionEvent.Finished
                }
            }
            val reply = f.owner.state.value.messages.last().parts.filterIsInstance<ContentPart.Text>()
            assertEquals(listOf("AB", "C"), reply.map { it.text })
            assertEquals("ABC", chunks.joinToString(""))
        } finally { f.close() }
    }

    @Test fun returningFromAnotherEntrySessionRestoresUnsubmittedTextAndImages() = runBlocking {
        val f = Fixture(); f.initialize()
        try {
            f.owner.open().join()
            val original = requireNotNull(f.owner.state.value.sessionId)
            val drafts = ChatComposerDrafts()
            val image = PendingUserImage(mimeType = "image/png", bytes = byteArrayOf(1))
            drafts[original].edit("still editing")
            drafts[original].images = listOf(image)
            val entry = f.owner.entrySessions.open(EntryRequest("external"))
            val other = requireNotNull(f.owner.state.value.sessionId)
            assertNotEquals(original, other)
            assertEquals("", drafts[other].input)
            assertTrue(drafts[other].images.isEmpty())
            withTimeout(10_000) { entry.events.first { it === SessionEvent.Finished } }
            f.owner.select(original).join()
            val restored = drafts[f.owner.state.value.sessionId]
            assertEquals("still editing", restored.input)
            assertEquals(listOf(image), restored.images)
        } finally { f.close() }
    }

    @Test fun revocationAtAdmissionDiscardsOnlyTheUnpublishedNewSession() = runBlocking {
        val f = Fixture(); f.initialize()
        try {
            f.owner.open().join()
            val before = f.owner.state.value
            var checks = 0
            unavailable { f.owner.entrySessions.open(EntryRequest("first")) { ++checks == 1 } }
            assertSame(before, f.owner.state.value)
            assertEquals(listOf(before.sessionId), f.store.sessionIds())
            assertEquals(0, f.requests.get())
            f.owner.send("still bound").join()
            assertEquals(1L, f.owner.state.value.acceptedMessages)
        } finally { f.close() }
    }

    @Test fun staleLedgerSnapshotCannotReviveApprovalAfterNewerRevision() = runBlocking {
        val f = Fixture(ToolRisk.WRITE); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest("write"))
            val state = withTimeout(10_000) { f.owner.state.first { it.records.values.any { r -> r.pending != null } } }
            val record = state.records.values.single { it.pending != null }
            val id = requireNotNull(state.sessionId)
            val approval = requireNotNull(record.pending)
            val next = record.copy(revision = record.revision + 1, pending = approval.copy(id = "next", summary = "new decision"))
            val done = next.copy(revision = next.revision + 1, state = Status.REJECTED, pending = null)
            f.owner.entrySessions.records(id, listOf(next, next, done, record))
            f.owner.entrySessions.modelEvent(id, Events.text(0, "boundary"))
            val seen = mutableListOf<SessionEvent>()
            withTimeout(10_000) { entry.events.first { seen += it; it == SessionEvent.Text("boundary") } }
            val activity = seen.filterIsInstance<SessionEvent.ToolActivity>().map { it.state }
            assertEquals("REJECTED", activity.last())
            assertEquals(1, activity.count { it == "WAITING_APPROVAL" })
            assertEquals(listOf(approval.summary, "new decision"), seen.filterIsInstance<SessionEvent.NeedsApproval>().map { it.summary })
        } finally { f.close() }
    }

    @Test fun disconnectedAttemptStreamsSeparatelyFromSuccessfulRetry() = runBlocking {
        val f = Fixture(disconnectFirst = true); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest("hello"))
            val seen = mutableListOf<SessionEvent>()
            withTimeout(10_000) { entry.events.first { seen += it; it === SessionEvent.Finished } }
            assertEquals(5, seen.size)
            assertEquals(SessionEvent.Text("before tool"), seen[0])
            assertTrue((seen[1] as SessionEvent.AttemptEnded).discardText)
            assertEquals(SessionEvent.Text("answer"), seen[2])
            assertFalse((seen[3] as SessionEvent.AttemptEnded).discardText)
            assertSame(SessionEvent.Finished, seen[4])
            assertEquals(2, f.requests.get())
            val state = f.owner.state.value
            assertEquals("NETWORK", state.journal.single().reason)
            assertEquals("before tool", (state.journal.single().partial.parts.single() as ContentPart.Text).text)
            val reply = state.messages.single { it.role == Role.ASSISTANT }
            assertEquals("answer", (reply.parts.single() as ContentPart.Text).text)
        } finally { f.close() }
    }

    @Test fun approvalAndConcurrentSendsNeverBypassUserDecision() = runBlocking {
        val f = Fixture(ToolRisk.WRITE); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest("write"))
            val seen = CopyOnWriteArrayList<SessionEvent>()
            val collect = launch { entry.events.collect { seen += it } }
            withTimeout(10_000) { while (seen.none { it is SessionEvent.NeedsApproval }) delay(10) }
            val session = f.owner.state.value.sessionId!!
            withTimeout(10_000) { f.owner.state.first { it.records.values.any { record -> record.pending != null } } }
            val pending = f.owner.state.value.records.values.single { it.pending != null }
            unavailable { entry.send("interrupt") }
            unavailable { f.owner.entrySessions.open(EntryRequest("other", session)) }
            assertEquals(0, f.executions.get()); assertEquals(1, f.requests.get())
            assertEquals(1L, f.owner.state.value.acceptedMessages)
            entry.close(); entry.close()
            unavailable { entry.send("closed") }
            assertEquals(pending.pending, f.host.ledger.get(pending.callId)!!.pending)
            f.owner.decide(pending.callId, pending.pending!!.id, Decision.DENY).join()
            withTimeout(10_000) { f.owner.state.first { !it.active } }
            assertEquals(0, f.executions.get())
            assertEquals(Status.REJECTED, f.host.ledger.get(pending.callId)!!.state)
            validateHistory(f.owner.state.value.messages)
            collect.cancelAndJoin()
        } finally { f.close() }
    }

    @Test fun missingSessionMissingModelAndUnavailableStorageFailWithoutFabricatingSession() = runBlocking {
        val f = Fixture(configured = false); f.initialize()
        try {
            f.owner.open().join()
            val original = f.owner.state.value.sessionId
            unavailable { f.owner.entrySessions.open(EntryRequest("", "missing")) }
            assertEquals(original, f.owner.state.value.sessionId)
            unavailable { f.owner.entrySessions.open(EntryRequest("hello")) }
            assertEquals(listOf(original), f.store.sessionIds())
            assertEquals(original, f.owner.state.value.sessionId)
            f.store.createHistory("other").close()
            unavailable { f.owner.entrySessions.open(EntryRequest("hello", "other")) }
            assertEquals(original, f.owner.state.value.sessionId)
            assertEquals(0, f.requests.get())
            assertEquals(0, f.owner.entrySessions.handleCount)
            assertEquals(0L, f.owner.state.value.acceptedMessages)
            val failed = ChatSessionOwner(f.scope, { throw IllegalStateException("database inaccessible") }, { error("not reached") })
            unavailable { failed.entrySessions.open(EntryRequest("hello")) }
            assertNull(failed.state.value.sessionId)
        } finally { f.close() }
    }

    @Test fun bindingExistingSessionAndCancellingCollectorReleaseOnlyHandle() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val f = Fixture(hold = hold); f.initialize()
        try {
            f.store.createHistory("saved").use { it.append(it.revision(), listOf(message(Role.USER, textPart("old")))) }
            val entry = f.owner.entrySessions.open(EntryRequest("hello", "saved"))
            withTimeout(10_000) { entry.events.first { it is SessionEvent.Text } }
            assertEquals(0, f.owner.entrySessions.handleCount)
            unavailable { entry.send("closed by collector") }
            assertTrue(f.owner.state.value.active)
            assertEquals("saved", f.owner.state.value.sessionId)
            hold.complete(Unit)
            withTimeout(10_000) { f.owner.state.first { !it.active } }
            validateHistory(f.owner.state.value.messages)
            assertEquals("old", (f.owner.state.value.messages.first().parts.single() as ContentPart.Text).text)
        } finally { f.close() }
    }

    @Test fun foregroundFailureIsSanitizedAndDoesNotSubmitModel() = runBlocking {
        val f = Fixture(presenceFails = true); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest("hello"))
            val event = withTimeout(10_000) { entry.events.first { it is SessionEvent.Failed } } as SessionEvent.Failed
            assertFalse(event.message.contains("internal"))
            assertEquals(0, f.requests.get()); assertEquals(0, f.executions.get())
        } finally { f.close() }
    }

    @Test fun rejectedUiSendCannotChangeSuccessfulEntryTerminalEvent() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val f = Fixture(hold = hold); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest("accepted"))
            f.owner.send("too late").join()
            assertNotNull(f.owner.state.value.error)
            hold.complete(Unit)
            val terminal = withTimeout(10_000) {
                entry.events.first { it === SessionEvent.Finished || it is SessionEvent.Failed }
            }
            assertEquals(RunState.COMPLETED, f.owner.state.value.result?.state)
            assertSame(SessionEvent.Finished, terminal)
        } finally { f.close() }
    }

    @Test fun revokedDuringOpenCannotStartRunAndLateSendRemainsRejected() = runBlocking {
        val f = Fixture(); f.initialize()
        try {
            var admitted = true
            val entry = f.owner.entrySessions.open(EntryRequest("")) { admitted }
            admitted = false
            unavailable { entry.send("revoked") }
            unavailable { f.owner.entrySessions.open(EntryRequest("revoked")) { false } }
            assertEquals(0, f.requests.get())
            entry.close()
        } finally { f.close() }
    }

    @Test fun simultaneousSendsHaveExactlyOneAdmissionAndUiSwitchPreservesBinding() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val f = Fixture(hold = hold); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest(""))
            val bound = f.owner.state.value.sessionId
            f.owner.newSession().join()
            assertNotEquals(bound, f.owner.state.value.sessionId)
            val results = coroutineScope {
                (1..2).map { async(Dispatchers.Default) { runCatching { entry.send("message $it") }.isSuccess } }.awaitAll()
            }
            assertEquals(1, results.count { it })
            assertEquals(bound, f.owner.state.value.sessionId)
            assertEquals(1L, f.owner.state.value.acceptedMessages)
            entry.close()
        } finally { f.close() }
    }

    @Test fun outOfOrderTextOverflowFailsOnlyTheEntry() = runBlocking {
        val f = Fixture(); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest(""))
            val id = requireNotNull(f.owner.state.value.sessionId)
            f.owner.entrySessions.modelEvent(id, Events.text(0, "early"))
            repeat(9) { f.owner.entrySessions.modelEvent(id, Events.text(1, "x".repeat(8_000))) }
            unavailable { entry.send("incomplete") }
            val event = withTimeout(10_000) { entry.events.first { it is SessionEvent.Failed } }
            assertTrue((event as SessionEvent.Failed).message.contains("out-of-order"))
            assertEquals(0, f.owner.entrySessions.handleCount)
        } finally { f.close() }
    }

    @Test fun entryQueuesAreBoundedAndOverflowIsExplicit() = runBlocking {
        val f = Fixture(); f.initialize()
        try {
            val entry = f.owner.entrySessions.open(EntryRequest(""))
            val id = f.owner.state.value.sessionId!!
            repeat(300) { f.owner.entrySessions.modelEvent(id, Events.text(0, "delta")) }
            unavailable { entry.send("delivery is incomplete") }
            val event = withTimeout(10_000) { entry.events.first { it is SessionEvent.Failed } }
            assertTrue((event as SessionEvent.Failed).message.contains("slow"))
            assertEquals(0, f.owner.entrySessions.handleCount)
        } finally { f.close() }
    }
}
