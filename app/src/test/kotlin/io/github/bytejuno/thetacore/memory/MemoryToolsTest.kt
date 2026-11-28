package io.github.bytejuno.thetacore.memory

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.memory.*
import io.github.bytejuno.thetacore.chat.ChatPromptSnapshot
import io.github.bytejuno.thetacore.plugin.DataPersona
import io.github.bytejuno.thetacore.plugin.HostToolBridgeRoot
import io.github.bytejuno.thetacore.plugin.PluginExecutionBudget
import io.github.bytejuno.thetacore.plugin.openTestStore
import io.github.bytejuno.thetacore.agent.protocol.callBinding
import io.github.bytejuno.thetacore.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MemoryToolsTest {
    private val env = ToolEnv("run", "source-session", "call", false, { _, _ -> })
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun hostLedgerRequiresWriteApprovalAndRechecksDisabledBeforeExecution() = runBlocking {
        val budget = PluginExecutionBudget()
        val store = openTestStore(Files.createTempDirectory("memory-approval").toFile())
        try {
            val host = HostToolBridgeRoot(budget).initialize(memoryTools = {
                listOf("memory_search", "memory_save", "memory_delete").map { id -> MemoryTool(id, store.memories) { it.name } }
            }) { store }
            val definition = host.catalog.freeze("run").values.single { it.toolKey == "host/memory_save" }
            assertEquals(Risk.WRITE, definition.declaredRisk)
            fun input(step: String, json: String = """{"content":"approved fact"}""") = InvocationInput(
                BindingValue.copyOf(callBinding("test", step, null, 0, "wire")), "chain", "session", "run", step,
                0, json, definition, false, Risk.WRITE)
            val invalid = input("invalid", """{"content":true}""")
            host.prepare(invalid)
            assertEquals(Status.REJECTED, host.ledger.get(invalid.binding.callId)!!.state)
            val pending = input("pending")
            host.prepare(pending)
            val approval = requireNotNull(host.ledger.get(pending.binding.callId)!!.pending)
            assertEquals(0, store.memories.usage().count)
            store.memories.setEnabled(false)
            host.decide(pending.binding.callId, approval.id, Decision.ALLOW_ONCE)
            host.dispatch(pending.binding.callId)
            withTimeout(10_000) { host.awaitObservedExit(pending.binding.callId) }
            assertEquals(0, store.memories.usage().count)
            assertEquals(Effect.NONE, host.ledger.get(pending.binding.callId)!!.result!!.effect)
            store.memories.setEnabled(true)
            val accepted = input("accepted"); host.prepare(accepted)
            host.decide(accepted.binding.callId, host.ledger.get(accepted.binding.callId)!!.pending!!.id, Decision.ALLOW_ONCE)
            host.dispatch(accepted.binding.callId)
            withTimeout(10_000) { host.awaitObservedExit(accepted.binding.callId) }
            assertEquals(Status.SUCCEEDED, host.ledger.get(accepted.binding.callId)!!.state)
            assertEquals(1, store.memories.usage().count)
        } finally { budget.close(); store.close() }
    }
    @Test fun schemasRiskValidationEffectsAndSourceUseTheSharedStore() = runBlocking {
        AgentDatabase.open(Files.createTempDirectory("memory-tools").resolve(AgentDatabase.FILE_NAME), BundledSQLiteDriver()).use { db ->
            val store = MemoryStore(db)
            val search = MemoryTool("memory_search", store) { it.name }
            val save = MemoryTool("memory_save", store) { it.name }
            val delete = MemoryTool("memory_delete", store) { it.name }
            assertEquals(ToolRisk.READ, search.spec.risk)
            assertEquals(ToolRisk.WRITE, save.spec.risk); assertEquals(ToolRisk.WRITE, delete.spec.risk)
            assertEquals("host/memory", save.spec.exclusiveResource)
            for (bad in listOf("{}", """{"content":false}""", """{"content":"ok","pinned":"true"}""",
                """{"content":"ok","extra":1}""", """{"content":"ok","category":null}""")) {
                val result = save.call(args(bad), env)
                assertTrue(bad, result.isError); assertEquals(ToolEffect.NONE, result.effect)
            }
            val result = save.call(args("""{"content":"用户偏好中文","pinned":true}"""), env)
            assertFalse(result.isError); assertEquals(ToolEffect.APPLIED, result.effect)
            val id = result.structured!!.jsonObject["id"]!!.jsonPrimitive.content
            assertEquals("source-session", store.get(id)!!.sourceSessionId)
            save.call(args("""{"content":"用户偏好中文"}"""), env)
            assertEquals(1, store.usage().count); assertTrue(store.get(id)!!.pinned)
            assertEquals(1, search.call(args("""{"query":"中文"}"""), env).structured!!.jsonObject["memories"]!!.jsonArray.size)
            for (bad in listOf("""{"query":"","limit":"5"}""", """{"query":"","limit":101}"""))
                assertTrue(search.call(args(bad), env).isError)
            store.setEnabled(false)
            for ((tool, input) in listOf(search to """{"query":""}""", save to """{"content":"blocked"}""",
                delete to """{"id":"$id"}""")) assertEquals("DISABLED", tool.call(args(input), env).text)
            store.setEnabled(true)
            assertFalse(delete.call(args("""{"id":"$id"}"""), env).isError)
            assertEquals(0, store.usage().count)
        }
    }

    @Test fun memoryIsIndependentFromPersonaAndUserSystemText() {
        val persona = DataPersona("test/guide", "Guide", "Original persona")
        val prompt = ChatPromptSnapshot(persona = persona, memorySection = "Remembered facts")
        assertEquals("Original persona\n\nUser system\n\nRemembered facts", systemWithMemory(prompt, "User system", null))
        assertEquals("Original persona", persona.systemPrompt)
        assertEquals("Original persona\n\nUser system", systemWithMemory(prompt.copy(memorySection = ""), "User system", null))
    }
}
