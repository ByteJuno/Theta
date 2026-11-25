package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.ApiKeyCredentials
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiRunSnapshot
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import io.github.bytejuno.thetacore.plugin.HostToolBridgeRoot
import io.github.bytejuno.thetacore.plugin.PluginExecutionBudget
import io.github.bytejuno.thetacore.sdk.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ChatApprovalModesAppTest {
    private val emptySchema: JsonObject = buildJsonObject { put("type", "object") }

    @Test
    fun settingsStoreDefaultsToBalancedPersistsUpdatesAndFailsClosedOnCorruptDisk() {
        val root = Files.createTempDirectory("theta-approval-settings-test").toFile()
        try {
            val fresh = ChatApprovalSettingsStore(root)
            assertEquals(ChatApprovalSettings(ApprovalMode.BALANCED, false), fresh.ensureLoaded())
            assertEquals(ApprovalMode.BALANCED, fresh.state.value.mode)
            assertFalse(fresh.state.value.allowRootLongTerm)

            fresh.setMode(ApprovalMode.FULL_AUTO)
            fresh.setAllowRootLongTerm(true)
            assertEquals(ChatApprovalSettings(ApprovalMode.FULL_AUTO, true), fresh.state.value)

            val reopened = ChatApprovalSettingsStore(root)
            assertEquals(ChatApprovalSettings(ApprovalMode.FULL_AUTO, true), reopened.ensureLoaded())

            reopened.setMode(ApprovalMode.BALANCED)
            assertEquals(ApprovalMode.BALANCED, ChatApprovalSettingsStore(root).ensureLoaded().mode)

            // Corrupt mode value fails closed to STRICT + allowRootLongTerm=false (never escalates).
            val propFile = File(root, "${ChatApprovalSettingsStore.DIRECTORY}/settings.properties")
            propFile.writeText("mode=UNRECOGNIZED_MODE\nallowRootLongTerm=true\n")
            val corrupted = ChatApprovalSettingsStore(root)
            assertEquals(ChatApprovalSettings(ApprovalMode.STRICT, false), corrupted.ensureLoaded())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun canGrantMultiCallApprovalGatesRootAndUnknownSideEffects() {
        val deviceReq = ApprovalRequest("req-1", "call-1", "dig", "1", 1L, Risk.DEVICE_CONTROL, emptyList(), false)
        val rootReq = ApprovalRequest("req-2", "call-2", "dig", "1", 1L, Risk.ROOT, emptyList(), false)
        val unknownReq = ApprovalRequest("req-3", "call-3", "dig", "1", 1L, Risk.WRITE, listOf("prior-unknown"), false)

        assertTrue(canGrantMultiCallApproval(deviceReq, allowRootLongTerm = false))
        assertFalse(canGrantMultiCallApproval(rootReq, allowRootLongTerm = false))
        assertTrue(canGrantMultiCallApproval(rootReq, allowRootLongTerm = true))
        assertFalse(canGrantMultiCallApproval(unknownReq, allowRootLongTerm = true))
    }

    @Test
    fun balancedModeRunsWriteWithoutPromptPromptsDeviceControlAndSupportsSessionAndAlwaysGrants(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-approval-owner-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var storeHandle: AndroidAgentStore? = null
        try {
            val approvalStore = ChatApprovalSettingsStore(File(root, "no-backup").apply { mkdirs() })
            assertEquals(ApprovalMode.BALANCED, approvalStore.ensureLoaded().mode)

            val writeRuns = AtomicInteger()
            val devRuns = AtomicInteger()
            val rootRuns = AtomicInteger()
            val dynamicRuns = AtomicInteger()

            val writeTool = object : Tool {
                override val id = "write_doc"
                override val spec = ToolSpec(id, "Write document", emptySchema, risk = ToolRisk.WRITE)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    writeRuns.incrementAndGet()
                    return ToolResult("written", effect = ToolEffect.APPLIED)
                }
            }
            var devDescription = "Tap screen v1"
            fun makeDevTool() = object : Tool {
                override val id = "tap_screen"
                override val spec get() = ToolSpec(id, devDescription, emptySchema, risk = ToolRisk.DEVICE_CONTROL)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    devRuns.incrementAndGet()
                    return ToolResult("tapped", effect = ToolEffect.APPLIED)
                }
            }
            val dynamicUpgradeTool = object : Tool {
                override val id = "inspect_then_act"
                override val spec = ToolSpec(id, "Inspect then request device control", emptySchema, risk = ToolRisk.READ)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    val approved = env.requestApproval("Perform tap", ToolRisk.DEVICE_CONTROL)
                    if (!approved) return ToolResult("denied", isError = true, effect = ToolEffect.NONE)
                    dynamicRuns.incrementAndGet()
                    return ToolResult("upgraded-ok", effect = ToolEffect.APPLIED)
                }
            }
            val rootTool = object : Tool {
                override val id = "su_exec"
                override val spec = ToolSpec(id, "Root command", emptySchema, risk = ToolRisk.ROOT)
                override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
                    rootRuns.incrementAndGet()
                    return ToolResult("root-ok", effect = ToolEffect.APPLIED)
                }
            }

            val dbDir = File(root, "db").apply { mkdirs() }
            val agentStore = AndroidAgentStore.open(dbDir, BundledSQLiteDriver())
            storeHandle = agentStore
            val service = HostToolBridgeRoot(PluginExecutionBudget()).initialize(
                policy = approvalStore.policy(),
            ) { agentStore }
            service.catalog.registerHost("host/write_doc", "1", writeTool)
            service.catalog.registerHost("host/tap_screen", "1", makeDevTool())
            service.catalog.registerHost("host/inspect_then_act", "1", dynamicUpgradeTool)
            service.catalog.registerHost("host/su_exec", "1", rootTool)

            var nextToolName: String? = null
            var emittedToolInRun = false
            val fakeProvider = object : ModelProvider {
                override fun chat(request: ChatRequest, auth: Credentials): Flow<ModelEvent> = flow {
                    val toolToCall = nextToolName
                    emit(Events.started("resp", request.model))
                    if (toolToCall != null && !emittedToolInRun) {
                        emittedToolInRun = true
                        emit(Events.toolStart(0, "wire-1", toolToCall))
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
                OpenAiProviderSettings("chat", "https://example.invalid/v1", "test-model"),
                ApiKeyCredentials("sk-test"),
            )
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val owner = ChatSessionOwner(
                scope = scope,
                service = { service },
                configuration = { snapshot },
                diagnostic = { failures += it },
                targetForRun = { ModelTarget("chat", fakeProvider, ApiKeyCredentials("sk-test")) },
            )
            owner.open().join()

            suspend fun waitIdle() = withTimeout(5_000) {
                while (owner.state.value.active) delay(25)
            }
            suspend fun waitPendingApproval(): Pair<String, ApprovalRequest> = withTimeout(5_000) {
                while (true) {
                    val entry = owner.state.value.records.values.firstOrNull { it.pending != null }
                    if (entry != null) return@withTimeout entry.callId to entry.pending!!
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }

            // 1. BALANCED mode: WRITE tool runs to completion with ZERO approval prompts!
            nextToolName = "host_write_doc"; emittedToolInRun = false
            owner.send("Write doc").join()
            waitIdle()
            assertEquals(1, writeRuns.get())
            assertEquals(R.string.chat_complete, owner.state.value.notice)

            // 2. BALANCED mode: READ tool dynamically upgrading via requestApproval(DEVICE_CONTROL) STILL prompts!
            nextToolName = "host_inspect_then_act"; emittedToolInRun = false
            owner.send("Inspect and tap").join()
            val (dynCallId, dynReq) = waitPendingApproval()
            assertEquals(Risk.DEVICE_CONTROL, dynReq.risk)
            assertTrue(dynReq.internal)
            owner.decide(dynCallId, dynReq.id, Decision.ALLOW_ONCE).join()
            waitIdle()
            assertEquals(1, dynamicRuns.get())

            // 3. ALLOW_SESSION on DEVICE_CONTROL: prompts first time, skips prompt on second call in SAME session,
            //    and prompts again in a NEW session!
            nextToolName = "host_tap_screen"; emittedToolInRun = false
            owner.send("Tap 1").join()
            val (tap1Id, tap1Req) = waitPendingApproval()
            owner.decide(tap1Id, tap1Req.id, Decision.ALLOW_SESSION).join()
            waitIdle()
            assertEquals(1, devRuns.get())

            nextToolName = "host_tap_screen"; emittedToolInRun = false
            owner.send("Tap 2 same session").join()
            waitIdle()
            assertEquals(2, devRuns.get())

            owner.newSession().join()
            nextToolName = "host_tap_screen"; emittedToolInRun = false
            owner.send("Tap 3 new session").join()
            val (tap3Id, tap3Req) = waitPendingApproval()
            // Grant ALLOW_ALWAYS now -> works across future sessions until definition digest changes!
            owner.decide(tap3Id, tap3Req.id, Decision.ALLOW_ALWAYS).join()
            waitIdle()
            assertEquals(3, devRuns.get())

            owner.newSession().join()
            nextToolName = "host_tap_screen"; emittedToolInRun = false
            owner.send("Tap 4 across session with ALLOW_ALWAYS").join()
            waitIdle()
            assertEquals(4, devRuns.get())

            // Mutate tool definition description -> digest changes -> ALLOW_ALWAYS is invalidated and re-prompts!
            devDescription = "Tap screen v2 changed description"
            service.catalog.registerHost("host/tap_screen", "2", makeDevTool())
            nextToolName = "host_tap_screen"; emittedToolInRun = false
            owner.send("Tap 5 after definition digest change").join()
            val (tap5Id, tap5Req) = waitPendingApproval()
            owner.decide(tap5Id, tap5Req.id, Decision.DENY).join()
            waitIdle()
            assertEquals(4, devRuns.get())

            // 4. ROOT tool with allowRootLongTerm=false blocks ALLOW_ALWAYS cleanly with R.string.chat_root_long_term_disabled.
            nextToolName = "host_su_exec"; emittedToolInRun = false
            owner.send("Run su").join()
            val (suId, suReq) = waitPendingApproval()
            assertEquals(Risk.ROOT, suReq.risk)
            owner.decide(suId, suReq.id, Decision.ALLOW_ALWAYS).join()
            assertEquals(R.string.chat_root_long_term_disabled, owner.state.value.error)
            // Still pending; ALLOW_ONCE succeeds!
            owner.decide(suId, suReq.id, Decision.ALLOW_ONCE).join()
            waitIdle()
            assertEquals(1, rootRuns.get())

            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            scope.cancel()
            runCatching { storeHandle?.close() }
            root.deleteRecursively()
        }
    }
}
