package io.github.bytejuno.thetacore.overlay

import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.*
import io.github.bytejuno.thetacore.chat.*
import io.github.bytejuno.thetacore.plugin.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FloatingProgressRuntimeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val windows get() = (shadowOf(app.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl).views
    private val schema = Json.parseToJsonElement("""{"type":"object","additionalProperties":false}""").jsonObject

    @Test fun coldApprovalAppearsWithoutProgressAndClearsOnSwitchDecisionAndStop() = scenario {
        val root = Files.createTempDirectory("floating-cold").toFile()
        val calls = AtomicInteger()
        val tool = writeTool(calls)
        val seedStore = AndroidAgentStore.open(root, BundledSQLiteDriver())
        val seedBudget = PluginExecutionBudget()
        val seedHost = HostToolBridgeRoot(seedBudget, tool).initialize { seedStore }
        val definition = seedHost.catalog.freeze("saved-run").getValue("host_clock")
        val binding = callBinding("test", "request", "response", 0, "wire")
        seedStore.createHistory("saved").use { history ->
            history.append(0, listOf(message(Role.USER, textPart("goal"))))
            history.prepareStep(history.revision(), RunIdentity("chain", "saved", "saved-run"), "step",
                message(Role.ASSISTANT, toolCallPart(binding, definition.modelName, "{}")))
            assertTrue(seedHost.prepare(InvocationInput(BindingValue.copyOf(binding), "chain", "saved",
                "saved-run", "step", 0, "{}", definition, false, Risk.WRITE)) is Mutation.Applied)
        }
        val original = seedHost.ledger.get(binding.callId)!!
        seedBudget.close(); seedStore.close()
        val f = fixture(root, tool)
        try {
            // Runtime is installed before open(), as in ThetaApplication.onCreate().
            f.owner.open().join()
            assertTrue(f.owner.state.value.pending)
            assertFalse(f.owner.state.value.active)
            assertEquals(original, f.host.ledger.get(binding.callId))
            assertEquals(0, f.events.get()); assertEquals(0, calls.get())
            val stale = attention(R.string.floating_progress_approval)
            withContext(Dispatchers.Main) {
                stale.status.performClick()
                assertEquals(floatingTaskIntent(app).component, shadowOf(app).nextStartedActivity.component)
            }
            f.owner.newSession().join(); emptyWindow()
            withContext(Dispatchers.Main) { stale.stopButton.performClick() }
            f.owner.latest().join()
            assertEquals(original, f.host.ledger.get(binding.callId))
            f.owner.select("saved").join(); attention(R.string.floating_progress_approval)
            f.owner.decide(binding.callId, original.pending!!.id, Decision.DENY).join()
            await { !f.owner.state.value.active }; emptyWindow()
            assertFalse(f.owner.state.value.pending)
            assertEquals(Status.REJECTED, f.host.ledger.get(binding.callId)!!.state)
            assertEquals(0, calls.get()); assertEquals(0, f.models.get())

            // A new saved request in the same session must not be cancelled by the old view.
            val nextBinding = callBinding("test", "next-request", "next-response", 0, "next-wire")
            val nextDefinition = f.host.catalog.freeze("next-run").getValue("host_clock")
            f.owner.newSession().join()
            f.store.openHistory("saved").use { history ->
                history.prepareStep(history.revision(), RunIdentity("chain", "saved", "next-run"), "next-step",
                    message(Role.ASSISTANT, toolCallPart(nextBinding, nextDefinition.modelName, "{}")))
                assertTrue(f.host.prepare(InvocationInput(BindingValue.copyOf(nextBinding), "chain", "saved",
                    "next-run", "next-step", 0, "{}", nextDefinition, false, Risk.WRITE)) is Mutation.Applied)
            }
            f.owner.select("saved").join()
            val next = attention(R.string.floating_progress_approval)
            withContext(Dispatchers.Main) { stale.stopButton.performClick() }
            f.owner.latest().join()
            assertNotNull(f.host.ledger.get(nextBinding.callId)!!.pending)
            withContext(Dispatchers.Main) { next.stopButton.performClick() }
            await { !f.owner.state.value.pending }; emptyWindow()
            assertEquals(Status.CANCELLED, f.host.ledger.get(nextBinding.callId)!!.state)
            assertEquals(0, calls.get()); assertEquals(0, f.models.get())
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    @Test fun lateExitRecoveryAppearsAfterIdleAndClearsOnSwitchAndResolutionWithoutReplay() = lateExit(false)

    @Test fun timedOutToolLateExitShowsRecoveryWithoutRestartingExecution() = lateExit(true)

    private fun lateExit(timeout: Boolean) = scenario {
        val root = Files.createTempDirectory("floating-late").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val tool = object : Tool {
            override val id = "write"
            override val spec = ToolSpec(id, "test write", schema, risk = ToolRisk.WRITE,
                timeoutMs = if (timeout) 250 else 30_000)
            override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                calls.incrementAndGet()
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return ToolResult("late", effect = ToolEffect.UNKNOWN)
            }
        }
        val f = fixture(root, tool)
        try {
            f.owner.open().join(); f.owner.send("goal").join()
            await { f.owner.state.value.records.values.any { it.pending != null } }
            val record = f.owner.state.value.records.values.single()
            f.owner.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE).join()
            withTimeout(10_000) { entered.await() }
            if (!timeout) f.owner.stop().join()
            await { !f.owner.state.value.active }; emptyWindow()
            assertEquals(if (timeout) Status.TIMED_OUT else Status.CANCELLED, f.host.ledger.get(record.callId)!!.state)
            assertFalse(f.owner.state.value.pending)
            assertTrue(f.owner.state.value.recoveries.isEmpty())
            val eventsBeforeExit = f.events.get()
            val modelsBeforeExit = f.models.get()
            release.complete(Unit); f.host.awaitObservedExit(record.callId)
            await { record.callId in f.owner.state.value.recoveries }
            assertEquals(InstanceState.EXIT_CONFIRMED, f.owner.state.value.records.getValue(record.callId).instance!!.state)
            attention(R.string.floating_progress_recovery)
            assertEquals(eventsBeforeExit, f.events.get())
            val session = f.owner.state.value.sessionId!!
            f.owner.newSession().join(); emptyWindow()
            f.owner.select(session).join()
            val recoveryView = attention(R.string.floating_progress_recovery)
            val request = f.owner.state.value.recoveries.getValue(record.callId)
            withContext(Dispatchers.Main) { recoveryView.stopButton.performClick() }
            f.owner.latest().join()
            assertEquals(request, f.owner.state.value.recoveries[record.callId])
            f.owner.recover(record.callId, request.id, RecoveryResolution.ACCEPTED_UNCERTAINTY).join()
            // Accepting uncertainty deliberately retains isolation and a verification action.
            // Only verified clearance removes the ledger's outstanding recovery request.
            attention(R.string.floating_progress_recovery)
            val verification = f.owner.state.value.recoveries.getValue(record.callId)
            assertNotEquals(request.id, verification.id)
            f.owner.recover(record.callId, verification.id, RecoveryResolution.VERIFIED_CLEAR).join()
            emptyWindow()
            assertTrue(f.owner.state.value.recoveries.isEmpty())
            assertFalse(f.owner.state.value.active); assertFalse(f.owner.state.value.pending)
            assertEquals(1, calls.get()); assertEquals(modelsBeforeExit, f.models.get())
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { release.complete(Unit); f.close(); root.deleteRecursively() }
    }

    @Test fun queuedSavedStopMustNotCancelReplacementRequest() = scenario {
        val root = Files.createTempDirectory("floating-queued-stop").toFile()
        val calls = AtomicInteger()
        val f = fixture(root, writeTool(calls))
        try {
            val session = "saved"
            f.store.createHistory(session).close()
            suspend fun prepare(run: String): String {
                val definition = f.host.catalog.freeze(run).getValue("host_clock")
                val binding = callBinding("test", run, run, 0, run)
                f.store.openHistory(session).use { history ->
                    history.prepareStep(history.revision(), RunIdentity("chain", session, run), run,
                        message(Role.ASSISTANT, toolCallPart(binding, definition.modelName, "{}")))
                    assertTrue(f.host.prepare(InvocationInput(BindingValue.copyOf(binding), "chain", session,
                        run, run, 0, "{}", definition, false, Risk.WRITE)) is Mutation.Applied)
                }
                return binding.callId
            }
            prepare("first")
            f.owner.open().join()
            attention(R.string.floating_progress_approval)
            // Capture the exact asynchronous owner callback arguments sent by a valid click.
            // Delay its execution to model IO scheduling after the main-thread identity check.
            val oldState = f.owner.state.value
            val entry = floatingAttention(oldState)!!
            var queued: Triple<String?, String?, String?>? = null
            stopFloatingTask(FloatingTask(entry.taskId, entry.phase, entry.toolName), session, oldState) { execution, saved, request ->
                queued = Triple(execution, saved, request)
            }
            assertNotNull(queued)
            f.owner.stop(expectedPendingSessionId = session).join()
            f.owner.newSession().join()
            val next = prepare("second")
            f.owner.select(session).join()
            val newRequest = f.host.ledger.get(next)!!.pending!!.id
            attention(R.string.floating_progress_approval)
            f.owner.stop(queued!!.first, queued!!.second, queued!!.third).join()
            val current = f.host.ledger.get(next)!!
            assertEquals("A stop admitted for the previous saved request must preserve its replacement", newRequest, current.pending?.id)
            assertEquals(Status.WAITING_APPROVAL, current.state)
            withContext(Dispatchers.Main) { attention(R.string.floating_progress_approval).stopButton.performClick() }
            await { !f.owner.state.value.pending }; emptyWindow()
            assertEquals(Status.CANCELLED, f.host.ledger.get(next)!!.state)
            assertEquals(0, calls.get()); assertEquals(0, f.models.get())
            assertTrue(f.failures.toString(), f.failures.isEmpty())
        } finally { f.close(); root.deleteRecursively() }
    }

    private fun writeTool(calls: AtomicInteger) = object : Tool {
        override val id = "write"
        override val spec = ToolSpec(id, "test write", schema, risk = ToolRisk.WRITE)
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
            calls.incrementAndGet(); return ToolResult("done", effect = ToolEffect.APPLIED)
        }
    }

    private suspend fun fixture(root: File, tool: Tool): Fixture {
        val store = AndroidAgentStore.open(root, BundledSQLiteDriver())
        val budget = PluginExecutionBudget()
        val host = HostToolBridgeRoot(budget, tool).initialize { store }
        return Fixture(store, budget, host).also { f ->
            withContext(Dispatchers.Main) {
                ShadowSettings.setCanDrawOverlays(true)
                f.runtime.install(); f.runtime.setEnabled(true)
            }
        }
    }

    private inner class Fixture(val store: AndroidAgentStore, val budget: PluginExecutionBudget,
        val host: HostToolExecutionService) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val failures = CopyOnWriteArrayList<Throwable>()
        val models = AtomicInteger()
        val events = AtomicInteger()
        val runtime = FloatingProgressRuntime(app) { owner }
        val owner: ChatSessionOwner = ChatSessionOwner(scope, { host }, {
            OpenAiRunSnapshot(OpenAiProviderSettings("test", "https://example.invalid/v1", "model"), ApiKeyCredentials("test-key"))
        }, diagnostic = { failures += it }, targetForRun = {
            ModelTarget("test", object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials) = flow {
                    val number = models.incrementAndGet()
                    emit(Events.started("response-$number", "model"))
                    if (number == 1) {
                        emit(Events.toolStart(0, "wire", "host_clock")); emit(Events.toolDelta(0, "{}")); emit(Events.end(0))
                        emit(Events.finished(FinishReason.TOOL_CALLS))
                    } else {
                        emit(Events.text(0, "done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }, Credentials { "test-key" })
        }, presence = object : ChatTaskPresence {
            override suspend fun update(executionId: String, progress: ChatTaskProgress) = withContext(Dispatchers.Main) {
                events.incrementAndGet(); runtime.present(progress.outputTask(executionId))
            }
            override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) = withContext(Dispatchers.Main) {
                events.incrementAndGet(); runtime.present(waiting?.outputTask(executionId))
            }
        })
        suspend fun close() {
            owner.stop().join(); await { !owner.state.value.active }
            withContext(Dispatchers.Main) { runtime.setEnabled(false) }
            scope.coroutineContext[Job]!!.cancelAndJoin(); budget.close(); store.close()
        }
    }

    private suspend fun attention(text: Int): FloatingProgressView {
        await { windows.singleOrNull() is FloatingProgressView }
        return withContext(Dispatchers.Main) {
            (windows.single() as FloatingProgressView).also { assertEquals(app.getString(text), it.status.text.toString()) }
        }
    }
    private suspend fun emptyWindow() = await { windows.isEmpty() }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }

    // Pump the real Android main queue while SQLite/owner/tool work runs on its normal dispatchers.
    private fun scenario(block: suspend () -> Unit) = runBlocking {
        val work = async(Dispatchers.IO) { block() }
        while (!work.isCompleted) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
        work.await()
    }
}
