package io.github.bytejuno.thetacore.plugin

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.ApiKeyCredentials
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.chat.ChatApprovalSettingsStore
import io.github.bytejuno.thetacore.chat.ChatSessionOwner
import io.github.bytejuno.thetacore.sdk.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PluginToolBridgeTest {
    private val emptySchema: JsonObject = buildJsonObject { put("type", "object") }

    private fun callUuid(seed: String): String =
        java.util.UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()

    private fun binding(callId: String, wireId: String): BindingValue =
        BindingValue("test-provider", "req-$callId", "resp-$callId", 0, wireId, callId)

    private fun simpleTool(
        id: String,
        description: String = "Test tool $id",
        risk: ToolRisk = ToolRisk.READ,
        parallelSafe: Boolean = false,
        exclusiveResource: String? = null,
        timeoutMs: Long? = null,
        body: suspend (JsonObject, ToolEnv) -> ToolResult = { _, _ -> ToolResult("ok:$id", effect = ToolEffect.NONE) },
    ): Tool = object : Tool {
        override val id: String = id
        override val spec: ToolSpec = ToolSpec(
            name = id,
            description = description,
            inputSchema = emptySchema,
            risk = risk,
            parallelSafe = parallelSafe,
            exclusiveResource = exclusiveResource,
            timeoutMs = timeoutMs,
        )

        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = body(args, env)
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) delay(15)
        }
    }

    @Test
    fun registerPluginValidatesIdsForbidsReservedPrefixesTracksGenerationsAndCleansUpFailures(): Unit = runBlocking {
        val budget = PluginExecutionBudget(processSlots = 4, perPluginSlots = 2, cancellationThreads = 2)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = DefinitionGate()
        val validator = Schema202012Validator()
        val catalog = FrozenToolCatalog(gate, budget, owner, validator)
        try {
            val tool = simpleTool("echo")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("host", "1", tool, "echo") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("mcp", "1", tool, "echo") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("invalid_no_dot", "1", tool, "echo") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("com.example.demo", "1", tool, "host/echo") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("com.example.demo", "1", tool, "bad/slash") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("com.example.demo", "1", tool, "..") }
            }

            val v1 = catalog.registerPlugin("com.example.demo", "1", simpleTool("echo", description = "Echo v1"))
            assertEquals("com.example.demo/echo", v1.toolKey)
            assertEquals(Source.THIRD_PARTY, v1.source)
            assertEquals("com.example.demo", v1.owner)
            assertEquals("1", v1.version)
            assertEquals(1L, v1.generation)
            assertNull(v1.builtinToolKey)

            val v2 = catalog.registerPlugin("com.example.demo", "2", simpleTool("echo", description = "Echo v2"), "echo")
            assertEquals(2L, v2.generation)
            assertNotEquals(v1.digest, v2.digest)

            // Failed registration must clean up gate instead of leaving REFRESH_PENDING stuck.
            val brokenTool = object : Tool {
                override val id = "broken"
                override val spec: ToolSpec
                    get() = throw IllegalStateException("broken spec")
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult = ToolResult()
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { catalog.registerPlugin("com.example.demo", "2", brokenTool, "broken") }
            }
            val frozenAfterFailure = catalog.freeze("run-after-failure")
            assertTrue(frozenAfterFailure.values.any { it.toolKey == "com.example.demo/echo" })
            assertTrue(frozenAfterFailure.values.none { it.toolKey == "com.example.demo/broken" })
        } finally {
            owner.cancel()
            budget.close()
        }
    }

    @Test
    fun pluginToolBridgeSyncsLifecycleAndImmediatelyRejectsRevokedOrFaultedToolsAsStale(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-plugin-bridge-lifecycle").toFile()
        val budget = PluginExecutionBudget(processSlots = 6, perPluginSlots = 2, cancellationThreads = 2)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var store: AndroidAgentStore? = null
        try {
            store = openTestStore(root)
            val gate = DefinitionGate()
            val validator = Schema202012Validator()
            val ledger = InvocationLedger(store, gate, validator, policy = ApprovalPolicy(mode = { ApprovalMode.BALANCED }))
            ledger.recoverColdStart("epoch-1")
            val catalog = FrozenToolCatalog(gate, budget, owner, validator, store.runCatalogStore())
            val service = HostToolExecutionService(budget, owner, catalog, ledger, validator, sessionStore = store)
            val bridge = PluginToolBridge(owner)

            val pluginId = "com.example.notes"
            val token = Any()
            val calls = AtomicInteger()
            val onEnableTool = simpleTool("list", risk = ToolRisk.READ) { _, _ ->
                calls.incrementAndGet()
                ToolResult("listed", effect = ToolEffect.NONE)
            }
            val runtimeTool = simpleTool("append", risk = ToolRisk.WRITE) { _, _ ->
                calls.incrementAndGet()
                ToolResult("appended", effect = ToolEffect.APPLIED)
            }

            bridge.attachPlugin(pluginId, "10", token)
            val registrations = PluginRegistrations.tracked<Extension> { entries, closed ->
                bridge.onRegistrationsChanged(pluginId, token, entries, closed)
            }

            // Register during onEnable before activation & before catalog attach.
            val revokeList = registrations.register(onEnableTool.id, onEnableTool)
            bridge.activatePlugin(pluginId, token)

            // Attach catalog AFTER plugin activated (startup order B).
            bridge.attachCatalog(catalog)
            assertEquals(setOf("$pluginId/list"), catalog.pluginToolKeys(pluginId))

            // Runtime registration while active.
            val revokeAppend = registrations.register(runtimeTool.id, runtimeTool)
            bridge.awaitPending(pluginId)
            assertEquals(setOf("$pluginId/list", "$pluginId/append"), catalog.pluginToolKeys(pluginId))

            // Freeze run-1 with both tools, prepare an unsubmitted call for append, then revoke append.
            val run1 = catalog.freeze("run-1")
            val appendDef = run1.values.single { it.toolKey == "$pluginId/append" }
            val staleCallId = callUuid("call-stale-1")
            val input = InvocationInput(
                binding(staleCallId, "wire-1"),
                "chain-1",
                "session-1",
                "run-1",
                "step-1",
                0,
                "{}",
                appendDef,
                unattended = false,
                initialRisk = appendDef.declaredRisk,
            )
            val prepared = service.prepare(input)
            assertTrue(prepared is Mutation.Applied && prepared.record.state == Status.READY)

            // Immediate revocation via Registration.revoke()
            revokeAppend()
            assertEquals(setOf("$pluginId/list"), catalog.pluginToolKeys(pluginId))

            // Old snapshot READY call MUST be rejected as STALE and never execute tool body.
            val dispatched = service.dispatch(staleCallId)
            assertTrue("Expected Applied STALE rejection, got $dispatched", dispatched is Mutation.Applied)
            val rejected = (dispatched as Mutation.Applied).record
            assertEquals(Status.REJECTED, rejected.state)
            assertEquals(Effect.NONE, rejected.result?.effect)
            assertTrue(rejected.result?.text.orEmpty().contains("STALE"))
            assertEquals(0, calls.get())

            // New run no longer exposes revoked tool, still exposes list tool.
            val run2 = catalog.freeze("run-2")
            assertTrue(run2.values.any { it.toolKey == "$pluginId/list" })
            assertTrue(run2.values.none { it.toolKey == "$pluginId/append" })

            // Simulate fault isolation / disable via PluginRegistrations.beginClose()
            registrations.beginClose()
            assertTrue(catalog.pluginToolKeys(pluginId).isEmpty())
            assertThrows(PluginClosedException::class.java) {
                registrations.register("late", simpleTool("late"))
            }
            val run3 = catalog.freeze("run-3")
            assertTrue(run3.values.none { it.owner == pluginId })
            revokeList() // idempotent
        } finally {
            owner.cancel()
            budget.close()
            store?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun chatSessionOwnerDiscoversAndRunsBridgedPluginToolsAcrossStrictAndBalancedModes(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-plugin-chat-e2e").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val budget = PluginExecutionBudget(processSlots = 6, perPluginSlots = 2, cancellationThreads = 2)
        var agentStore: AndroidAgentStore? = null
        try {
            val approvalStore = ChatApprovalSettingsStore(File(root, "no-backup").apply { mkdirs() })
            assertEquals(ApprovalMode.BALANCED, approvalStore.ensureLoaded().mode)

            val dbDir = File(root, "db").apply { mkdirs() }
            agentStore = AndroidAgentStore.open(dbDir, BundledSQLiteDriver())
            val service = HostToolBridgeRoot(budget).initialize(policy = approvalStore.policy()) { agentStore }
            val bridge = PluginToolBridge(scope)
            bridge.attachCatalog(service.catalog)

            val readCalls = AtomicInteger()
            val writeCalls = AtomicInteger()
            val devCalls = AtomicInteger()
            val pluginId = "com.example.assistant"
            val token = Any()
            var readDesc = "Read plugin note v1"
            val readToolV1 = object : Tool {
                override val id = "read_note"
                override val spec get() = ToolSpec(id, readDesc, emptySchema, risk = ToolRisk.READ)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    readCalls.incrementAndGet()
                    return ToolResult("note-content", effect = ToolEffect.NONE)
                }
            }
            val writeTool = simpleTool("write_note", risk = ToolRisk.WRITE) { _, _ ->
                writeCalls.incrementAndGet()
                ToolResult("saved-note", effect = ToolEffect.APPLIED)
            }
            val deviceTool = simpleTool("tap_ui", risk = ToolRisk.DEVICE_CONTROL) { _, _ ->
                devCalls.incrementAndGet()
                ToolResult("tapped-ui", effect = ToolEffect.APPLIED)
            }

            bridge.attachPlugin(pluginId, "1", token)
            val registrations = PluginRegistrations.tracked<Extension> { entries, closed ->
                bridge.onRegistrationsChanged(pluginId, token, entries, closed)
            }
            val revokeRead = registrations.register(readToolV1.id, readToolV1)
            registrations.register(writeTool.id, writeTool)
            registrations.register(deviceTool.id, deviceTool)
            bridge.activatePlugin(pluginId, token)
            bridge.awaitPending(pluginId)

            val seenModelTools = CopyOnWriteArrayList<List<String>>()
            var targetToolKey: String? = null
            var emitted = false
            val fakeProvider = object : ModelProvider {
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    seenModelTools += request.tools.map { it.name }
                    emit(Events.started("resp", request.model))
                    val wanted = targetToolKey
                    val modelName = wanted?.let { key ->
                        val suffix = key.substringAfter('/')
                        request.tools.firstOrNull { it.name.contains(suffix) }?.name
                    }
                    if (modelName != null && !emitted) {
                        emitted = true
                        emit(Events.toolStart(0, "wire-1", modelName))
                        emit(Events.toolDelta(0, "{}"))
                        emit(Events.end(0))
                        emit(Events.finished(FinishReason.TOOL_CALLS))
                    } else {
                        emit(Events.text(0, "Done"))
                        emit(Events.end(0))
                        emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            val snapshot = OpenAiRunSnapshot(
                OpenAiProviderSettings("chat", "https://api.example.com/v1", "gpt-test"),
                ApiKeyCredentials("sk-test"),
            )
            val failures = CopyOnWriteArrayList<Throwable>()
            val chatOwner = ChatSessionOwner(
                scope = scope,
                service = { service },
                configuration = { snapshot },
                diagnostic = { failures += it },
                targetForRun = { ModelTarget("chat", fakeProvider, ApiKeyCredentials("sk-test")) },
            )
            chatOwner.open().join()
            awaitUntil { !chatOwner.state.value.loading && chatOwner.state.value.sessionId != null }

            // 1. BALANCED mode: third-party READ and WRITE run without prompting.
            targetToolKey = "$pluginId/read_note"; emitted = false
            chatOwner.send("read my note").join()
            awaitUntil { !chatOwner.state.value.active && readCalls.get() == 1 }
            val readRecord = chatOwner.state.value.records.values.single { it.input.definition?.toolKey == "$pluginId/read_note" }
            assertEquals(Status.SUCCEEDED, readRecord.state)
            assertEquals(Source.THIRD_PARTY, readRecord.input.definition?.source)
            assertEquals(pluginId, readRecord.input.definition?.owner)

            targetToolKey = "$pluginId/write_note"; emitted = false
            chatOwner.send("write my note").join()
            awaitUntil { !chatOwner.state.value.active && writeCalls.get() == 1 }

            // 2. BALANCED mode: third-party DEVICE_CONTROL prompts, then runs after ALLOW_ONCE.
            targetToolKey = "$pluginId/tap_ui"; emitted = false
            chatOwner.send("tap button").join()
            awaitUntil {
                chatOwner.state.value.records.values.any {
                    it.input.definition?.toolKey == "$pluginId/tap_ui" && it.pending != null
                }
            }
            assertEquals(0, devCalls.get())
            val devPending = chatOwner.state.value.records.values.single {
                it.input.definition?.toolKey == "$pluginId/tap_ui" && it.pending != null
            }
            chatOwner.decide(devPending.callId, devPending.pending!!.id, Decision.ALLOW_ONCE).join()
            awaitUntil { !chatOwner.state.value.active && devCalls.get() == 1 }

            // 3. STRICT mode: third-party READ asks first time; ALLOW_ALWAYS authorizes same digest,
            //    and re-registering with changed description (new digest) requires approval again.
            approvalStore.setMode(ApprovalMode.STRICT)
            targetToolKey = "$pluginId/read_note"; emitted = false
            chatOwner.send("strict read 1").join()
            awaitUntil {
                chatOwner.state.value.records.values.any {
                    it.input.definition?.toolKey == "$pluginId/read_note" && it.pending != null
                }
            }
            val strictPending = chatOwner.state.value.records.values.single {
                it.input.definition?.toolKey == "$pluginId/read_note" && it.pending != null
            }
            chatOwner.decide(strictPending.callId, strictPending.pending!!.id, Decision.ALLOW_ALWAYS).join()
            awaitUntil { !chatOwner.state.value.active && readCalls.get() == 2 }

            // Same digest -> ALLOW_ALWAYS applies automatically without prompt.
            targetToolKey = "$pluginId/read_note"; emitted = false
            chatOwner.send("strict read 2").join()
            awaitUntil { !chatOwner.state.value.active && readCalls.get() == 3 }

            // Re-register read_note with modified description -> new digest invalidates ALLOW_ALWAYS!
            revokeRead()
            readDesc = "Read plugin note v2 (changed)"
            val readToolV2 = object : Tool {
                override val id = "read_note"
                override val spec get() = ToolSpec(id, readDesc, emptySchema, risk = ToolRisk.READ)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    readCalls.incrementAndGet()
                    return ToolResult("note-v2", effect = ToolEffect.NONE)
                }
            }
            val revokeReadV2 = registrations.register(readToolV2.id, readToolV2)
            bridge.awaitPending(pluginId)

            targetToolKey = "$pluginId/read_note"; emitted = false
            chatOwner.send("strict read after digest change").join()
            awaitUntil {
                chatOwner.state.value.records.values.any {
                    it.input.definition?.toolKey == "$pluginId/read_note" && it.pending != null
                }
            }
            val digestChangePending = chatOwner.state.value.records.values.single {
                it.input.definition?.toolKey == "$pluginId/read_note" && it.pending != null
            }
            chatOwner.decide(digestChangePending.callId, digestChangePending.pending!!.id, Decision.DENY).join()
            awaitUntil { !chatOwner.state.value.active }
            assertEquals(3, readCalls.get())

            // 4. Revoke read_note -> next run's tool catalog sent to model no longer contains read_note.
            revokeReadV2()
            seenModelTools.clear()
            targetToolKey = null; emitted = false
            chatOwner.send("hello without read tool").join()
            awaitUntil { !chatOwner.state.value.active && seenModelTools.isNotEmpty() }
            assertTrue(seenModelTools.last().none { it.contains("read_note") })
            assertTrue(seenModelTools.last().any { it.contains("write_note") })
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            scope.cancel()
            budget.close()
            agentStore?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun runningPluginToolsRespectPerPluginBudgetAndCrossSessionExclusiveResourceLease(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-plugin-budget-lease").toFile()
        val budget = PluginExecutionBudget(processSlots = 8, perPluginSlots = 2, cancellationThreads = 2)
        val metadataBudget = PluginExecutionBudget(processSlots = 4, perPluginSlots = 1, cancellationThreads = 2)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var store: AndroidAgentStore? = null
        try {
            store = openTestStore(root)
            val gate = DefinitionGate()
            val validator = Schema202012Validator()
            val ledger = InvocationLedger(store, gate, validator, policy = ApprovalPolicy(mode = { ApprovalMode.BALANCED }))
            ledger.recoverColdStart("epoch-budget")
            val catalog = FrozenToolCatalog(gate, metadataBudget, owner, validator, store.runCatalogStore())
            val service = HostToolExecutionService(budget, owner, catalog, ledger, validator, sessionStore = store)

            val releaseA1 = CompletableDeferred<Unit>()
            val enteredA1 = CompletableDeferred<Unit>()
            val releaseA2 = CompletableDeferred<Unit>()
            val enteredA2 = CompletableDeferred<Unit>()

            catalog.registerPlugin(
                "com.example.alpha",
                "1",
                simpleTool("slot_one", parallelSafe = true, exclusiveResource = "device-screen") { _, _ ->
                    enteredA1.complete(Unit)
                    releaseA1.await()
                    ToolResult("a1", effect = ToolEffect.NONE)
                },
                "slot_one",
            )
            catalog.registerPlugin(
                "com.example.alpha",
                "1",
                simpleTool("slot_two", parallelSafe = true) { _, _ ->
                    enteredA2.complete(Unit)
                    releaseA2.await()
                    ToolResult("a2", effect = ToolEffect.NONE)
                },
                "slot_two",
            )
            catalog.registerPlugin(
                "com.example.alpha",
                "1",
                simpleTool("slot_three", parallelSafe = true) { _, _ -> ToolResult("a3", effect = ToolEffect.NONE) },
                "slot_three",
            )
            catalog.registerPlugin(
                "com.example.beta",
                "1",
                simpleTool("other_screen", parallelSafe = true, exclusiveResource = "device-screen") { _, _ ->
                    ToolResult("beta-screen", effect = ToolEffect.NONE)
                },
                "other_screen",
            )
            catalog.registerPlugin(
                "com.example.beta",
                "1",
                simpleTool("beta_free", parallelSafe = true) { _, _ -> ToolResult("beta-free", effect = ToolEffect.NONE) },
                "beta_free",
            )

            val run1 = catalog.freeze("run-s1")
            val run2 = catalog.freeze("run-s2")
            val cA1 = callUuid("c-a1")
            val cA2 = callUuid("c-a2")
            val cA3 = callUuid("c-a3")
            val cBs = callUuid("c-bs")
            val cBf = callUuid("c-bf")
            val cA3Fresh = callUuid("c-a3-fresh")
            fun input(callId: String, session: String, run: String, def: FrozenDefinition) = InvocationInput(
                binding(callId, "w-$callId"),
                "chain-$session",
                session,
                run,
                "step-$callId",
                0,
                "{}",
                def,
                unattended = false,
                initialRisk = def.declaredRisk,
            )

            val a1Def = run1.values.single { it.toolKey == "com.example.alpha/slot_one" }
            val a2Def = run1.values.single { it.toolKey == "com.example.alpha/slot_two" }
            val a3Def = run1.values.single { it.toolKey == "com.example.alpha/slot_three" }
            val betaScreenDef = run2.values.single { it.toolKey == "com.example.beta/other_screen" }
            val betaFreeDef = run2.values.single { it.toolKey == "com.example.beta/beta_free" }

            assertTrue(service.prepare(input(cA1, "s1", "run-s1", a1Def)) is Mutation.Applied)
            assertTrue(service.prepare(input(cA2, "s1", "run-s1", a2Def)) is Mutation.Applied)
            assertTrue(service.prepare(input(cA3, "s1", "run-s1", a3Def)) is Mutation.Applied)
            assertTrue(service.prepare(input(cBs, "s2", "run-s2", betaScreenDef)) is Mutation.Applied)
            assertTrue(service.prepare(input(cBf, "s2", "run-s2", betaFreeDef)) is Mutation.Applied)

            assertTrue(service.dispatch(cA1) is Mutation.Applied)
            assertTrue(service.dispatch(cA2) is Mutation.Applied)
            enteredA1.await()
            enteredA2.await()

            // Revoke alpha mid-flight: already running calls keep holding plugin budget & exclusiveResource lease!
            catalog.revokePlugin("com.example.alpha")

            // 3rd call for another plugin's non-conflicting tool still runs immediately.
            assertTrue(service.dispatch(cBf) is Mutation.Applied)
            service.awaitObservedExit(cBf)
            assertEquals(Status.SUCCEEDED, ledger.get(cBf)?.state)

            // Cross-session call competing for exclusiveResource "device-screen" is blocked while c-a1 runs.
            val blockedLease = service.dispatch(cBs)
            assertTrue("Expected exclusiveResource block, got $blockedLease", blockedLease is Mutation.Blocked)
            assertEquals("Resource occupied or externally isolated", (blockedLease as Mutation.Blocked).reason)

            // Re-register alpha/slot_three in a new run while c-a1 and c-a2 still occupy alpha's 2 perPluginSlots.
            catalog.registerPlugin(
                "com.example.alpha",
                "2",
                simpleTool("slot_three", parallelSafe = true) { _, _ -> ToolResult("a3", effect = ToolEffect.NONE) },
                "slot_three",
            )
            val run3 = catalog.freeze("run-s3")
            val a3Fresh = run3.values.single { it.toolKey == "com.example.alpha/slot_three" }
            assertTrue(service.prepare(input(cA3Fresh, "s3", "run-s3", a3Fresh)) is Mutation.Applied)
            val blockedBudget = service.dispatch(cA3Fresh)
            assertTrue("Expected per-plugin budget block, got $blockedBudget", blockedBudget is Mutation.Blocked)
            assertEquals("Execution capacity unavailable", (blockedBudget as Mutation.Blocked).reason)

            // Release running calls -> lease and plugin slots free up.
            releaseA1.complete(Unit)
            releaseA2.complete(Unit)
            service.awaitObservedExit(cA1)
            service.awaitObservedExit(cA2)

            assertTrue(service.dispatch(cBs) is Mutation.Applied)
            assertTrue(service.dispatch(cA3Fresh) is Mutation.Applied)
            service.awaitObservedExit(cBs)
            service.awaitObservedExit(cA3Fresh)
            assertEquals(Status.SUCCEEDED, ledger.get(cBs)?.state)
            assertEquals(Status.SUCCEEDED, ledger.get(cA3Fresh)?.state)
        } finally {
            owner.cancel()
            metadataBudget.close()
            budget.close()
            store?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun toolEnvProgressResetsIdleTimeoutEnforcesHardCapAndSyncsToChatActivityState(): Unit = runBlocking {
        // Part A: Deterministic clock test for idle timeout reset + hard total cap.
        val clock = FakeInvocationClock()
        val budget = PluginExecutionBudget(processSlots = 4, perPluginSlots = 2, cancellationThreads = 2)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = Files.createTempDirectory("theta-plugin-progress").toFile()
        var agentStore: AndroidAgentStore? = null
        try {
            val stepReady = CompletableDeferred<Unit>()
            val proceedStep = CompletableDeferred<Unit>()
            val hangAfterProgress = CompletableDeferred<Unit>()
            val progressTool = simpleTool("long_scan", timeoutMs = 1_000L) { _, env ->
                env.progress("Phase 1", 0.25f)
                stepReady.complete(Unit)
                proceedStep.await()
                env.progress("Phase 2", 0.75f)
                hangAfterProgress.await()
                ToolResult("never-reached")
            }
            val invocation = PluginInvocation(
                budget = budget,
                pluginId = "com.example.scanner",
                owner = owner,
                tool = progressTool,
                args = buildJsonObject {},
                request = ToolInvocationRequest("run-p", "session-p", "call-p"),
                defaultTimeoutMillis = 1_000L,
                totalTimeoutMillis = 2_200L,
                clock = clock,
            ).start()
            stepReady.await()
            assertEquals(PluginToolProgress("Phase 1", 0.25f), invocation.progress.value)

            // Advance 800ms (< 1000ms idle) -> still running; send second progress to reset idle timer.
            clock.advanceMillis(800L)
            assertFalse(invocation.terminal.isCompleted)
            proceedStep.complete(Unit)
            awaitUntil { invocation.progress.value == PluginToolProgress("Phase 2", 0.75f) }

            // Advance another 800ms (total 1600ms > 1000ms idle, but only 800ms since last progress) -> still running!
            clock.advanceMillis(800L)
            assertFalse("Idle timeout should have been reset by env.progress", invocation.terminal.isCompleted)

            // Advance another 700ms (total 2300ms > 2200ms hard total cap) -> MUST time out!
            clock.advanceMillis(700L)
            val outcome = invocation.terminal.await()
            assertEquals(PluginInvocationStatus.TIMED_OUT, outcome.status)
            hangAfterProgress.complete(Unit)
            invocation.actualExited.await()

            // Part B: End-to-end ChatSessionOwner syncs live env.progress into ChatState.toolProgress.
            val dbDir = File(root, "db").apply { mkdirs() }
            agentStore = AndroidAgentStore.open(dbDir, BundledSQLiteDriver())
            val approvalStore = ChatApprovalSettingsStore(File(root, "no-backup").apply { mkdirs() }).apply { ensureLoaded() }
            val service = HostToolBridgeRoot(budget).initialize(policy = approvalStore.policy()) { agentStore }
            val releaseUiTool = CompletableDeferred<Unit>()
            service.catalog.registerPlugin(
                "com.example.scanner",
                "1",
                simpleTool("scan_screen", risk = ToolRisk.READ) { _, env ->
                    env.progress("Scanning screen...", 0.6f)
                    releaseUiTool.await()
                    ToolResult("scan-done", effect = ToolEffect.NONE)
                },
                "scan_screen",
            )
            var emitted = false
            val fakeProvider = object : ModelProvider {
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    emit(Events.started("resp", request.model))
                    val toolName = request.tools.first { it.name.contains("scan_screen") }.name
                    if (!emitted) {
                        emitted = true
                        emit(Events.toolStart(0, "wire-scan", toolName))
                        emit(Events.toolDelta(0, "{}"))
                        emit(Events.end(0))
                        emit(Events.finished(FinishReason.TOOL_CALLS))
                    } else {
                        emit(Events.text(0, "Finished scan"))
                        emit(Events.end(0))
                        emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            val snapshot = OpenAiRunSnapshot(
                OpenAiProviderSettings("chat", "https://api.example.com/v1", "gpt-test"),
                ApiKeyCredentials("sk-test"),
            )
            val chatOwner = ChatSessionOwner(
                scope = owner,
                service = { service },
                configuration = { snapshot },
                targetForRun = { ModelTarget("chat", fakeProvider, ApiKeyCredentials("sk-test")) },
            )
            chatOwner.open()
            awaitUntil { !chatOwner.state.value.loading && chatOwner.state.value.sessionId != null }
            chatOwner.send("scan now")
            awaitUntil {
                chatOwner.state.value.toolProgress.values.any {
                    it.text == "Scanning screen..." && it.fraction == 0.6f
                }
            }
            releaseUiTool.complete(Unit)
            awaitUntil { !chatOwner.state.value.active }
            assertTrue(chatOwner.state.value.toolProgress.isEmpty())
        } finally {
            owner.cancel()
            budget.close()
            agentStore?.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun toolEnvRequestApprovalPersistsEffectiveRiskBeforeReturningTrueAndRespectsUnattendedGrants(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-plugin-escalation").toFile()
        val budget = PluginExecutionBudget(processSlots = 6, perPluginSlots = 2, cancellationThreads = 2)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var store: AndroidAgentStore? = null
        try {
            store = openTestStore(root)
            val gate = DefinitionGate()
            val validator = Schema202012Validator()
            var mode = ApprovalMode.BALANCED
            val ledger = InvocationLedger(store, gate, validator, policy = ApprovalPolicy(mode = { mode }))
            ledger.recoverColdStart("epoch-esc")
            val catalog = FrozenToolCatalog(gate, budget, owner, validator, store.runCatalogStore())
            val service = HostToolExecutionService(budget, owner, catalog, ledger, validator, sessionStore = store)

            val observedRiskInsideTool = AtomicReference<Risk?>()
            val observedApproved = AtomicReference<Boolean?>()

            val escalatingTool = simpleTool("smart_act", risk = ToolRisk.READ) { _, env ->
                val ok = env.requestApproval("Tap confirm button", ToolRisk.DEVICE_CONTROL)
                observedApproved.set(ok)
                // Verify ledger ALREADY persisted elevated effectiveRisk before requestApproval returned true!
                observedRiskInsideTool.set(ledger.get(env.callId)?.effectiveRisk)
                if (ok) ToolResult("acted", effect = ToolEffect.APPLIED)
                else ToolResult("denied", isError = true, effect = ToolEffect.NONE)
            }
            catalog.registerPlugin("com.example.auto", "1", escalatingTool, "smart_act")
            val frozen = catalog.freeze("run-esc")
            val def = frozen.values.single { it.toolKey == "com.example.auto/smart_act" }

            val callU1 = callUuid("call-u1")
            val callSeed = callUuid("call-seed")
            val callRg = callUuid("call-read-grant")
            val callAllow = callUuid("call-allow")
            val callU2 = callUuid("call-u2")

            // 1. Unattended WITHOUT matching ALLOW_ALWAYS grant -> must return false, effectiveRisk stays READ.
            val unattendedNoGrant = InvocationInput(
                binding(callU1, "w-u1"),
                "chain-u1",
                "session-u1",
                "run-esc",
                "step-u1",
                0,
                "{}",
                def,
                unattended = true,
                initialRisk = def.declaredRisk,
            )
            // First grant READ via ALLOW_ALWAYS so unattended third-party READ tool can start and then request DEVICE_CONTROL.
            val seedInput = InvocationInput(
                binding(callSeed, "w-seed"),
                "chain-seed",
                "session-seed",
                "run-esc",
                "step-seed",
                0,
                "{}",
                def,
                unattended = false,
                initialRisk = def.declaredRisk,
            )
            // Step 1a: Attended call -> requests DEVICE_CONTROL -> user DENIES -> returns false, effectiveRisk stays READ.
            assertTrue(service.prepare(seedInput) is Mutation.Applied)
            assertTrue(service.dispatch(callSeed) is Mutation.Applied)
            awaitUntil { ledger.get(callSeed)?.pending?.internal == true }
            assertEquals(Risk.READ, ledger.get(callSeed)?.effectiveRisk)
            val denyReq = ledger.get(callSeed)!!.pending!!
            assertTrue(service.decide(callSeed, denyReq.id, Decision.DENY) is Mutation.Applied)
            service.awaitObservedExit(callSeed)
            assertEquals(false, observedApproved.get())
            assertEquals(Risk.READ, observedRiskInsideTool.get())
            assertEquals(Risk.READ, ledger.get(callSeed)?.effectiveRisk)

            // Step 1b: Grant READ via ALLOW_ALWAYS in STRICT mode, deny DEVICE_CONTROL,
            // so unattended call starts (READ ALLOW_ALWAYS matches) and env.requestApproval(DEVICE_CONTROL) returns false!
            mode = ApprovalMode.STRICT
            val readGrantInput = InvocationInput(
                binding(callRg, "w-rg"),
                "chain-rg",
                "session-rg",
                "run-esc",
                "step-rg",
                0,
                "{}",
                def,
                unattended = false,
                initialRisk = def.declaredRisk,
            )
            assertTrue(service.prepare(readGrantInput) is Mutation.Applied)
            val rgPending = ledger.get(callRg)!!.pending!!
            assertTrue(service.decide(callRg, rgPending.id, Decision.ALLOW_ALWAYS) is Mutation.Applied)
            assertTrue(service.dispatch(callRg) is Mutation.Applied)
            awaitUntil { ledger.get(callRg)?.pending?.internal == true }
            assertTrue(service.decide(callRg, ledger.get(callRg)!!.pending!!.id, Decision.DENY) is Mutation.Applied)
            service.awaitObservedExit(callRg)

            // Now unattended call starts (READ ALLOW_ALWAYS matches), calls env.requestApproval(DEVICE_CONTROL) -> MUST return false!
            observedApproved.set(null)
            observedRiskInsideTool.set(null)
            assertTrue(service.prepare(unattendedNoGrant) is Mutation.Applied)
            assertTrue(service.dispatch(callU1) is Mutation.Applied)
            service.awaitObservedExit(callU1)
            assertEquals(false, observedApproved.get())
            assertEquals(Risk.READ, observedRiskInsideTool.get())
            assertEquals(Risk.READ, ledger.get(callU1)?.effectiveRisk)
            mode = ApprovalMode.BALANCED

            // Step 2: Attended call approves DEVICE_CONTROL with ALLOW_ALWAYS -> persists effectiveRisk BEFORE returning true!
            observedApproved.set(null)
            observedRiskInsideTool.set(null)
            val attendedAllowInput = InvocationInput(
                binding(callAllow, "w-allow"),
                "chain-allow",
                "session-allow",
                "run-esc",
                "step-allow",
                0,
                "{}",
                def,
                unattended = false,
                initialRisk = def.declaredRisk,
            )
            assertTrue(service.prepare(attendedAllowInput) is Mutation.Applied)
            assertTrue(service.dispatch(callAllow) is Mutation.Applied)
            awaitUntil { ledger.get(callAllow)?.pending?.internal == true }
            val allowReq = ledger.get(callAllow)!!.pending!!
            assertTrue(service.decide(callAllow, allowReq.id, Decision.ALLOW_ALWAYS) is Mutation.Applied)
            service.awaitObservedExit(callAllow)
            assertEquals(true, observedApproved.get())
            assertEquals(Risk.DEVICE_CONTROL, observedRiskInsideTool.get())
            assertEquals(Risk.DEVICE_CONTROL, ledger.get(callAllow)?.effectiveRisk)

            // Step 3: Unattended call WITH matching ALLOW_ALWAYS grant -> returns true AND persists effectiveRisk BEFORE returning true!
            observedApproved.set(null)
            observedRiskInsideTool.set(null)
            val unattendedWithGrant = InvocationInput(
                binding(callU2, "w-u2"),
                "chain-u2",
                "session-u2",
                "run-esc",
                "step-u2",
                0,
                "{}",
                def,
                unattended = true,
                initialRisk = def.declaredRisk,
            )
            assertTrue(service.prepare(unattendedWithGrant) is Mutation.Applied)
            assertTrue(service.dispatch(callU2) is Mutation.Applied)
            service.awaitObservedExit(callU2)
            assertEquals(true, observedApproved.get())
            assertEquals(Risk.DEVICE_CONTROL, observedRiskInsideTool.get())
            assertEquals(Risk.DEVICE_CONTROL, ledger.get(callU2)?.effectiveRisk)
            assertEquals(Status.SUCCEEDED, ledger.get(callU2)?.state)
        } finally {
            owner.cancel()
            budget.close()
            store?.close()
            root.deleteRecursively()
        }
    }

    private class FakeInvocationClock : InvocationClock {
        private data class Timer(val atNanos: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val lock = Any()
        private var currentNanos = 0L
        private val timers = mutableListOf<Timer>()

        override fun nowNanos(): Long = synchronized(lock) { currentNanos }
        override fun elapsedRealtimeNanos(): Long = synchronized(lock) { currentNanos }

        override fun arm(delayMillis: Long, action: () -> Unit): () -> Unit {
            val target = synchronized(lock) {
                val t = Timer(currentNanos + TimeUnit.MILLISECONDS.toNanos(delayMillis.coerceAtLeast(0)), action)
                timers += t
                t
            }
            if (delayMillis <= 0L) fireDue()
            return { synchronized(lock) { target.cancelled = true } }
        }

        fun advanceMillis(millis: Long) {
            synchronized(lock) { currentNanos += TimeUnit.MILLISECONDS.toNanos(millis) }
            fireDue()
        }

        private fun fireDue() {
            while (true) {
                val due = synchronized(lock) {
                    val next = timers.filter { !it.cancelled && it.atNanos <= currentNanos }
                    timers.removeAll(next.toSet())
                    next
                }
                if (due.isEmpty()) return
                due.forEach { it.action() }
            }
        }
    }
}
