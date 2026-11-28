package io.github.bytejuno.thetacore.root

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.plugin.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class RootToolIntegrationTest {
    private class Process(private val text: String = "ok") : RootProcess {
        override val rootConfirmed = true
        override val commandStarted = true
        @Volatile override var actualExit = true
        override val outcome = RootOutcome.COMPLETED
        override val exitCode = 0
        override val outputTruncated = false
        val stopped = CompletableDeferred<Unit>()
        override fun requestStop() { stopped.complete(Unit) }
        override fun output() = text to ""
        override fun close() { check(actualExit) }
    }
    private suspend fun fixture(policy: ApprovalPolicy, backend: RootBackend,
        block: suspend (HostToolExecutionService, FrozenDefinition) -> Unit) {
        val path = Files.createTempDirectory("theta-root-test")
        val budget = PluginExecutionBudget()
        try {
            SqliteInvocationStore.open(path.resolve(AgentDatabase.FILE_NAME),
                androidx.sqlite.driver.bundled.BundledSQLiteDriver()).use { store ->
                val service = HostToolBridgeRoot(budget).initialize(policy = policy,
                    rootTool = HostRootTool(RootBridge(backend))) { store }
                val definition = service.catalog.freeze("run").values.single { it.toolKey == "host/root_shell" }
                block(service, definition)
            }
        } finally { budget.close(); path.toFile().deleteRecursively() }
    }
    private fun input(d: FrozenDefinition, unattended: Boolean = false, step: String = "step") = InvocationInput(
        BindingValue.copyOf(callBinding("test", step, null, 0, "wire")), "chain", "session", "run", step,
        0, """{"command":"id"}""", d, unattended, Risk.ROOT)

    @Test fun rootUsesExistingModesDenyRulesAndUnattendedBoundaryWithoutStartingSu() = runBlocking {
        for (mode in ApprovalMode.entries) {
            val starts = AtomicInteger()
            fixture(ApprovalPolicy(mode = { mode }), RootBackend { starts.incrementAndGet(); Process() }) { service, d ->
                assertEquals(0, starts.get())
                val unattended = input(d, unattended = true, step = "unattended")
                val refused = service.prepare(unattended) as Mutation.Applied
                assertEquals(Status.REJECTED, refused.record.state)
                val i = input(d)
                val record = (service.prepare(i) as Mutation.Applied).record
                if (mode == ApprovalMode.FULL_AUTO) assertEquals(Status.READY, record.state)
                else {
                    assertNotNull(record.pending)
                    assertTrue(service.decide(record.callId, record.pending!!.id, Decision.ALLOW_ALWAYS) is Mutation.Blocked)
                    service.decide(record.callId, record.pending!!.id, Decision.DENY)
                }
                assertEquals(0, starts.get())
            }
            fixture(ApprovalPolicy(mode = { mode }, denied = { true }), RootBackend { error("denied command ran") }) { service, d ->
                assertEquals(Status.REJECTED, (service.prepare(input(d)) as Mutation.Applied).record.state)
            }
        }
    }

    @Test fun explicitRootGrantRunsAndLargeOutputIsStoredAndSessionScoped() = runBlocking {
        val full = "root-output😀".repeat(7000)
        fixture(ApprovalPolicy(), RootBackend { Process(full) }) { service, d ->
            val i = input(d)
            val record = (service.prepare(i) as Mutation.Applied).record
            service.decide(record.callId, record.pending!!.id, Decision.ALLOW_ONCE)
            service.dispatch(record.callId)
            withTimeout(10_000) { service.awaitObservedExit(record.callId) }
            val completed = service.ledger.get(record.callId)!!
            assertEquals(Status.SUCCEEDED, completed.state)
            val result = completed.result!!
            assertTrue(result.text.contains("Truncated"))
            assertTrue(result.text.toByteArray().size <= 32 * 1024)
            val ref = result.output!!.raw
            val bytes = java.io.ByteArrayOutputStream()
            var offset = 0L
            while (offset < ref.sizeBytes) {
                val chunk = service.ledger.readSessionBlob("session", ref.id, offset).second
                bytes.write(chunk); offset += chunk.size
            }
            val raw = Json.decodeFromString(RawToolOutput.serializer(), bytes.toString("UTF-8"))
            assertEquals(full, raw.structured!!.jsonObject.getValue("stdout").jsonPrimitive.content)
            assertThrows(BlobReadException::class.java) { service.ledger.readSessionBlob("other-session", ref.id, 0) }
        }
    }

    @Test fun cancelledRootRetainsUnconfirmedInstanceUntilChildActuallyExits() = runBlocking {
        val process = Process().apply { actualExit = false }
        val entered = CompletableDeferred<Unit>()
        fixture(ApprovalPolicy(mode = { ApprovalMode.FULL_AUTO }), RootBackend { entered.complete(Unit); process }) { service, d ->
            val i = input(d)
            service.prepare(i); service.dispatch(i.binding.callId)
            withTimeout(5_000) { entered.await() }
            service.cancel(i.binding.callId)
            withTimeout(5_000) { process.stopped.await() }
            val cancelled = service.ledger.get(i.binding.callId)!!
            assertEquals(Status.CANCELLED, cancelled.state)
            assertEquals(Effect.UNKNOWN, cancelled.result!!.effect)
            assertEquals(InstanceState.UNCONFIRMED, cancelled.instance!!.state)
            process.actualExit = true
            withTimeout(5_000) { service.awaitObservedExit(i.binding.callId) }
            assertEquals(Status.CANCELLED, service.ledger.get(i.binding.callId)!!.state)
            assertNotEquals(InstanceState.UNCONFIRMED, service.ledger.get(i.binding.callId)!!.instance!!.state)
        }
    }

    @Test fun missingReceiptsRetainUnknownLedgerAndResourceUntilPositiveExitEvidence() = runBlocking {
        val directory = Files.createTempDirectory("theta-root-missing-").toFile()
        val client = object : java.lang.Process() {
            override fun getInputStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun waitFor() = 1
            override fun exitValue() = 1
            override fun destroy() = Unit
        }
        val process = SuRootProcess(client, directory)
        val entered = CompletableDeferred<Unit>()
        try {
            fixture(ApprovalPolicy(mode = { ApprovalMode.FULL_AUTO }), RootBackend { entered.complete(Unit); process }) { service, d ->
                val i = input(d)
                try {
                    service.prepare(i); service.dispatch(i.binding.callId)
                    withTimeout(5_000) { entered.await() }
                    service.cancel(i.binding.callId)
                    val cancelled = service.ledger.get(i.binding.callId)!!
                    assertEquals(Status.CANCELLED, cancelled.state)
                    assertEquals(Effect.UNKNOWN, cancelled.result!!.effect)
                    assertEquals(InstanceState.UNCONFIRMED, cancelled.instance!!.state)
                    assertFalse(process.actualExit)
                } finally {
                    // Simulated backend evidence, never a claim about a real privileged worker.
                    directory.resolve("clean").writeText("clean")
                    withTimeout(5_000) { service.awaitObservedExit(i.binding.callId) }
                }
                assertEquals(Effect.UNKNOWN, service.ledger.get(i.binding.callId)!!.result!!.effect)
            }
        } finally { directory.deleteRecursively() }
    }
}
