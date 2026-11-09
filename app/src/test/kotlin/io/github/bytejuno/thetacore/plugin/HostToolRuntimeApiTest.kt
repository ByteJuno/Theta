package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.ledger.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class HostToolRuntimeApiTest {
    private fun request(name: String, id: String = UUID.randomUUID().toString()) = buildJsonObject {
        put("operation", "prepare"); put("callId", id); put("requestId", "request")
        put("taskChainId", "chain"); put("sessionId", "session"); put("runId", "run")
        put("stepId", "step"); put("order", 0); put("name", name)
        put("argsJson", "{}"); put("unattended", true)
    }
    private suspend fun call(api: HostToolRuntimeApi, request: JsonObject) = Json.parseToJsonElement(api.request(request.toString())).jsonObject
    private fun changed(request: JsonObject, key: String, value: JsonElement) = JsonObject(request + (key to value))

    @Test fun lostPrepareResponseRetriesWithStableBindingAndRejectsEveryMismatch() = runBlocking {
        val root = Files.createTempDirectory("host-api-").toFile()
        val budget = PluginExecutionBudget()
        try {
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget).initialize { store }
                val api = HostToolRuntimeApi(service)
                val input = request(service.catalog.freeze("run").values.single { it.toolKey == "host/clock" }.modelName)
                service.catalog.freeze("other")
                val first = call(api, input)
                assertEquals("applied", first.getValue("kind").jsonPrimitive.content)
                val retry = call(api, input)
                assertEquals("alreadyApplied", retry.getValue("kind").jsonPrimitive.content)
                assertEquals(first["record"], retry["record"])
                for ((key, value) in mapOf(
                    "requestId" to JsonPrimitive("other"), "taskChainId" to JsonPrimitive("other"),
                    "sessionId" to JsonPrimitive("other"), "stepId" to JsonPrimitive("other"),
                    "runId" to JsonPrimitive("other"),
                    "order" to JsonPrimitive(1), "argsJson" to JsonPrimitive("{\"extra\":1}"),
                    "unattended" to JsonPrimitive(false), "name" to JsonPrimitive("missing"),
                    "callId" to JsonPrimitive(UUID.randomUUID().toString()),
                )) assertEquals(key, "conflict", call(api, changed(input, key, value))["kind"]!!.jsonPrimitive.content)
                assertEquals(1, store.records().size)
                val id = input["callId"]!!.jsonPrimitive.content
                assertTrue(service.dispatch(id) is Mutation.Applied)
                service.awaitObservedExit(id)
                val done = service.ledger.get(id)!!
                assertEquals(Status.SUCCEEDED, done.state)
                assertEquals("alreadyApplied", call(api, input)["kind"]!!.jsonPrimitive.content)
                assertEquals(done, service.ledger.get(id))
            }
        } finally { budget.close(); root.deleteRecursively() }
    }

    @Test fun heldDefinitionGateNeverBlocksTheSuspendingCallersThread() = runBlocking {
        val root = Files.createTempDirectory("host-api-gate-").toFile()
        val budget = PluginExecutionBudget()
        try {
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget).initialize { store }
                val api = HostToolRuntimeApi(service)
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                    val held = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val holder = thread { service.catalog.gate.withGate { held.countDown(); release.await() } }
                    held.await()
                    try {
                        val freeze = async(caller) { api.request("""{"operation":"freeze","runId":"run"}""") }
                        // A request blocking the single caller thread would leave this queued behind it.
                        withTimeout(3_000) { withContext(caller) { } }
                        assertFalse(freeze.isCompleted)
                        release.countDown()
                        assertTrue(freeze.await().contains("host/clock"))
                    } finally { release.countDown(); holder.join() }
                }
            }
        } finally { budget.close(); root.deleteRecursively() }
    }

    @Test fun readyRestartBlocksWithoutChangingDurableRecordUntilExplicitFreeze() = runBlocking {
        val root = Files.createTempDirectory("host-api-ready-").toFile()
        val budget = PluginExecutionBudget()
        try {
            lateinit var input: JsonObject
            lateinit var ready: InvocationRecord
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget).initialize { store }
                input = request(service.catalog.freeze("run").values.single { it.toolKey == "host/clock" }.modelName)
                call(HostToolRuntimeApi(service), input)
                ready = service.ledger.get(input["callId"]!!.jsonPrimitive.content)!!
                assertEquals(Status.READY, ready.state)
            }
            val disk = root.resolve("${AndroidInvocationStore.DIRECTORY}/${ready.callId}.json").readText()
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget).initialize { store }
                val api = HostToolRuntimeApi(service)
                val dispatch = buildJsonObject { put("operation", "dispatch"); put("callId", ready.callId) }
                repeat(2) {
                    assertEquals("blocked", call(api, dispatch)["kind"]!!.jsonPrimitive.content)
                    assertTrue(service.prepare(ready.input) is Mutation.Blocked)
                    assertEquals(ready, service.ledger.get(ready.callId))
                    assertEquals(disk, root.resolve("${AndroidInvocationStore.DIRECTORY}/${ready.callId}.json").readText())
                    assertNull(service.catalog.frozen("run"))
                }
                call(api, buildJsonObject { put("operation", "freeze"); put("runId", "run") })
                assertEquals("alreadyApplied", call(api, input)["kind"]!!.jsonPrimitive.content)
                assertEquals("applied", call(api, dispatch)["kind"]!!.jsonPrimitive.content)
                service.awaitObservedExit(ready.callId)
                assertEquals(Status.SUCCEEDED, service.ledger.get(ready.callId)!!.state)
            }
        } finally { budget.close(); root.deleteRecursively() }
    }

    @Test fun coldRecoveryExposesUnknownAndNeverClearsItsIsolationThroughRetry() = runBlocking {
        val root = Files.createTempDirectory("host-api-cold-").toFile()
        val budget = PluginExecutionBudget()
        try {
            lateinit var input: JsonObject
            lateinit var id: String
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget).initialize { store }
                input = request(service.catalog.freeze("run").values.single { it.toolKey == "host/clock" }.modelName)
                id = input["callId"]!!.jsonPrimitive.content
                call(HostToolRuntimeApi(service), input)
                val ready = service.ledger.get(id)!!
                // An explicit UNKNOWN outcome must survive a crash before actual-exit evidence,
                // even for a READ tool (ordinary interrupted clock calls infer NONE).
                val claimed = service.ledger.claimExecution(id, ready.revision) as Mutation.Applied
                assertTrue(service.ledger.recordOutcome(id, claimed.ticket!!.attemptId,
                    Status.FAILED, "effect unknown before exit", Effect.UNKNOWN) is Mutation.Applied)
            }
            AndroidInvocationStore.open(root) {}.use { store ->
                val service = HostToolBridgeRoot(budget).initialize { store }
                val api = HostToolRuntimeApi(service)
                service.catalog.freeze("run")
                val recovered = service.ledger.get(id)!!
                assertEquals(Status.FAILED, recovered.state)
                assertEquals(Effect.UNKNOWN, recovered.result!!.effect)
                assertTrue(recovered.recoveryPending)
                assertNotNull(recovered.isolation)
                assertNull(recovered.isolation!!.resolution)
                assertEquals("alreadyApplied", call(api, input)["kind"]!!.jsonPrimitive.content)
                assertTrue(service.dispatch(id) is Mutation.Conflict)
                val next = changed(changed(input, "callId", JsonPrimitive(UUID.randomUUID().toString())), "stepId", JsonPrimitive("next"))
                call(api, next)
                assertTrue(service.dispatch(next["callId"]!!.jsonPrimitive.content) !is Mutation.Applied)
                val view = call(api, buildJsonObject { put("operation", "get"); put("callId", id) })
                assertTrue(view["recoveryPending"]!!.jsonPrimitive.boolean)
                assertEquals(recovered, service.ledger.get(id))
            }
        } finally { budget.close(); root.deleteRecursively() }
    }
}
