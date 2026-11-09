package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProviderSnapshotRunTest {
    @Test fun `active loop retains its snapshot across configuration publication and retry`() = runTest {
        val oldKey = "synthetic-old-key"
        val newKey = "synthetic-new-key"
        fun snapshot(model: String, token: String) = OpenAiRunSnapshot(
            OpenAiProviderSettings("test", "https://example.invalid/$model", model, OpenAiParameters(30, 0.4)),
            ApiKeyCredentials(token),
        )
        var selected = snapshot("old", oldKey)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var calls = 0
        val transport = ModelTransport { url, body, token -> flow {
            assertTrue(url.contains("/old/"))
            assertTrue(body.contains("\"model\":\"old\""))
            assertFalse(body.contains(oldKey))
            assertEquals(oldKey, token)
            calls++
            if (calls == 1) {
                entered.complete(Unit)
                resume.await()
                emit(TransportEvent.Headers(503, "application/json", null))
                emit(TransportEvent.Bytes("""{"error":{"message":"$oldKey","param":"$oldKey"}}""".toByteArray()))
            } else {
                emit(TransportEvent.Headers(200, "text/event-stream", null))
                emit(TransportEvent.Bytes(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n").toByteArray()))
            }
        } }
        val f = RunFixture()
        val loop = AgentRunLoop(RunIdentity("chain", "session", "run", false), selected.target(transport),
            selected.request(), f.history, LedgerToolExecutor(f.ledger, f.gate, f.definitions, f.bridge, RunApprovals { f.approve(it) }), f.journal)
        val running = async { loop.run() }
        entered.await()
        selected = snapshot("new", newKey)
        resume.complete(Unit)
        assertEquals(RunState.COMPLETED, running.await().state)
        assertEquals(2, calls)
        assertEquals("new", selected.request().model)
        assertEquals(newKey, selected.target().credentials.accessToken())
        val persisted = listOf(f.history.snapshot(), f.journal.attempts(), f.journal.submissions(), f.store.records()).toString()
        assertFalse(persisted.contains(oldKey))
        assertFalse(persisted.contains(newKey))
        assertTrue(f.store.records().isEmpty())
    }
}
