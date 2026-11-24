package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.execution.Schema202012Validator
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class FrozenToolCatalogPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    private class TaggedTool(
        private val toolName: String,
        private val tag: String,
        val calls: AtomicInteger = AtomicInteger(),
    ) : Tool {
        override val id: String = toolName
        override val spec = ToolSpec(
            name = toolName,
            description = "tool $tag",
            inputSchema = buildJsonObject { put("type", "object") },
            risk = ToolRisk.READ,
        )
        override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
            calls.incrementAndGet()
            return ToolResult(text = tag, effect = ToolEffect.NONE)
        }
    }

    private fun openService(
        root: File,
        register: suspend (FrozenToolCatalog) -> Unit,
    ): Triple<AndroidAgentStore, CoroutineScope, HostToolExecutionService> = runBlocking {
        val store = openTestStore(root)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val budget = PluginExecutionBudget(processSlots = 2, perPluginSlots = 2, cancellationThreads = 1)
        val gate = DefinitionGate()
        val validator = Schema202012Validator()
        val ledger = InvocationLedger(store, gate, validator)
        assertTrue(ledger.recoverColdStart(UUID.randomUUID().toString()) !is Mutation.StorageFailure)
        val catalog = FrozenToolCatalog(gate, budget, owner, validator, store.runCatalogStore())
        register(catalog)
        Triple(store, owner, HostToolExecutionService(budget, owner, catalog, ledger, validator, sessionStore = store))
    }

    private suspend fun runSingleCall(
        service: HostToolExecutionService,
        runId: String,
        modelName: String,
        stepId: String,
    ): InvocationRecord {
        val definition = service.catalog.definition(runId, modelName)
        val callId = UUID.randomUUID().toString()
        val input = InvocationInput(
            BindingValue("test", "req-$stepId", null, 0, null, callId),
            "chain-1", "session-1", runId, stepId, 0, "{}",
            definition, false, definition?.declaredRisk ?: Risk.READ, false, modelName,
        )
        val prepared = service.prepare(input)
        assertTrue("Expected Applied, got $prepared", prepared is Mutation.Applied)
        val record = (prepared as Mutation.Applied).record
        if (record.state == Status.READY) {
            val dispatched = service.dispatch(callId)
            assertTrue("Expected Applied on dispatch, got $dispatched", dispatched is Mutation.Applied)
            service.awaitObservedExit(callId)
        }
        return requireNotNull(service.ledger.get(callId))
    }

    @Test fun restartedRunPreservesOriginalAliasMappingEvenWhenNewToolClaimsCollidingName() = runBlocking {
        val root = temp.newFolder()
        val hijackedAlias = "host_a_b_02c2b0e6aca1"

        // Process 1: freeze host/a/b and host/a_b for run-1.
        val firstFrozen: Map<String, FrozenDefinition>
        val (store1, owner1, service1) = openService(root) { catalog ->
            catalog.registerHost("host/a/b", "1", TaggedTool("ab_slash", "original-slash"))
            catalog.registerHost("host/a_b", "1", TaggedTool("ab_underscore", "original-underscore"))
        }
        try {
            firstFrozen = service1.catalog.freeze("run-1")
            assertEquals("host/a/b", firstFrozen.getValue(hijackedAlias).toolKey)
            val preRestart = runSingleCall(service1, "run-1", hijackedAlias, "step-1")
            assertEquals(Status.SUCCEEDED, preRestart.state)
            assertEquals("original-slash", preRestart.result?.text)
        } finally {
            owner1.cancel()
            store1.close()
        }

        // Process 2: register host/a/b, host/a_b, AND new hijacking tool host/a_b_02c2b0e6aca1.
        val originalSlash = TaggedTool("ab_slash", "original-slash")
        val hijacker = TaggedTool("ab_hijacker", "HIJACKED")
        val (store2, owner2, service2) = openService(root) { catalog ->
            catalog.registerHost("host/a/b", "1", originalSlash)
            catalog.registerHost("host/a_b", "1", TaggedTool("ab_underscore", "original-underscore"))
            catalog.registerHost("host/a_b_02c2b0e6aca1", "1", hijacker)
        }
        try {
            // Calling restore OR freeze on existing run-1 must return the original persisted mapping.
            val restored = service2.catalog.restore("run-1")
            assertNotNull(restored)
            assertEquals(firstFrozen, restored)
            assertEquals(firstFrozen, service2.catalog.freeze("run-1"))
            assertEquals("host/a/b", service2.catalog.definition("run-1", hijackedAlias)?.toolKey)

            // Executing hijackedAlias inside run-1 must invoke original host/a/b, NEVER hijacker.
            val afterRestart = runSingleCall(service2, "run-1", hijackedAlias, "step-2")
            assertEquals(Status.SUCCEEDED, afterRestart.state)
            assertEquals("original-slash", afterRestart.result?.text)
            assertEquals(1, originalSlash.calls.get())
            assertEquals(0, hijacker.calls.get())

            // A brand-new run-2 frozen after restart allocates against the current tool directory.
            val freshRun = service2.catalog.freeze("run-2")
            assertEquals("host/a_b_02c2b0e6aca1", freshRun.getValue(hijackedAlias).toolKey)
            assertEquals("host/a/b", service2.catalog.definition("run-1", hijackedAlias)?.toolKey)
        } finally {
            owner2.cancel()
            store2.close()
        }
    }

    @Test fun restartedRunRejectsCallWhenOriginalToolIsMissingOrChangedAndNeverRunsHijacker() = runBlocking {
        val root = temp.newFolder()
        val hijackedAlias = "host_a_b_02c2b0e6aca1"

        val (store1, owner1, service1) = openService(root) { catalog ->
            catalog.registerHost("host/a/b", "1", TaggedTool("ab_slash", "original-slash"))
            catalog.registerHost("host/a_b", "1", TaggedTool("ab_underscore", "original-underscore"))
        }
        try {
            val frozen = service1.catalog.freeze("run-1")
            assertEquals("host/a/b", frozen.getValue(hijackedAlias).toolKey)
        } finally {
            owner1.cancel()
            store1.close()
        }

        // Restart 1: original host/a/b is missing; only hijacker host/a_b_02c2b0e6aca1 is registered.
        val hijacker1 = TaggedTool("ab_hijacker", "HIJACKED")
        val (store2, owner2, service2) = openService(root) { catalog ->
            catalog.registerHost("host/a_b", "1", TaggedTool("ab_underscore", "original-underscore"))
            catalog.registerHost("host/a_b_02c2b0e6aca1", "1", hijacker1)
        }
        try {
            // Even calling freeze("run-1") directly must load persisted run-1 instead of re-allocating.
            val restoredViaFreeze = service2.catalog.freeze("run-1")
            assertEquals("host/a/b", restoredViaFreeze.getValue(hijackedAlias).toolKey)

            val rejected = runSingleCall(service2, "run-1", hijackedAlias, "step-missing")
            assertEquals(Status.REJECTED, rejected.state)
            assertEquals("STALE definition", rejected.result?.text)
            assertEquals(0, hijacker1.calls.get())
        } finally {
            owner2.cancel()
            store2.close()
        }

        // Restart 2: original host/a/b exists with a DIFFERENT version; hijacker is also registered.
        val changedOriginal = TaggedTool("ab_slash", "changed-v2")
        val hijacker2 = TaggedTool("ab_hijacker", "HIJACKED")
        val (store3, owner3, service3) = openService(root) { catalog ->
            catalog.registerHost("host/a/b", "2", changedOriginal)
            catalog.registerHost("host/a_b", "1", TaggedTool("ab_underscore", "original-underscore"))
            catalog.registerHost("host/a_b_02c2b0e6aca1", "1", hijacker2)
        }
        try {
            val api = HostToolRuntimeApi(service3)
            val restoredJson = api.request("""{"operation":"restore","runId":"run-1"}""")
            val restoredMap = Json.parseToJsonElement(restoredJson).jsonObject
            assertEquals("host/a/b", restoredMap.getValue(hijackedAlias).jsonObject.getValue("toolKey").jsonPrimitive.content)

            val rejected = runSingleCall(service3, "run-1", hijackedAlias, "step-changed")
            assertEquals(Status.REJECTED, rejected.state)
            assertEquals("STALE definition", rejected.result?.text)
            assertEquals(0, changedOriginal.calls.get())
            assertEquals(0, hijacker2.calls.get())
        } finally {
            owner3.cancel()
            store3.close()
        }
    }
}
