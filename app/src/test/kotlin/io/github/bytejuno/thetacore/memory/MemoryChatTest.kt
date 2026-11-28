package io.github.bytejuno.thetacore.memory

import io.github.bytejuno.thetacore.agent.memory.MemoryPrompt
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.chat.*
import io.github.bytejuno.thetacore.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class MemoryChatTest {
    @Test fun newRunsAndColdSessionsUseMemoryWithoutChangingUserConfiguration() = runBlocking {
        val root = Files.createTempDirectory("memory-chat").toFile()
        val prompts = CopyOnWriteArrayList<String?>()
        val provider = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials) = flow {
                prompts += request.system
                assertTrue(request.tools.any { it.name == "host_memory_save" })
                emit(Events.started("reply-${prompts.size}", "model")); emit(Events.text(0, "Done"))
                emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
            }
        }
        repeat(2) { cold ->
            val store = openTestStore(root)
            val budget = PluginExecutionBudget()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val errors = CopyOnWriteArrayList<Throwable>()
            try {
                if (cold == 0) store.memories.save("Prefers Chinese", pinned = true)
                val host = HostToolBridgeRoot(budget).initialize(memoryTools = {
                    listOf("memory_search", "memory_save", "memory_delete").map { id -> MemoryTool(id, store.memories) { it.name } }
                }) { store }
                val settings = OpenAiProviderSettings("chat", "https://example.invalid/v1", "model", systemPrompt = "User system")
                val owner = ChatSessionOwner(scope, { host }, { OpenAiRunSnapshot(settings, NoAuthCredentials) },
                    diagnostic = { errors += it }, targetForRun = { ModelTarget("chat", provider, NoAuthCredentials) },
                    promptSnapshot = { ChatPromptSnapshot(memorySection = MemoryPrompt.section(store.memories.promptEntries())) })
                owner.open().join()
                suspend fun send() {
                    owner.send("hello").join()
                    withTimeout(10_000) { while (owner.state.value.active) delay(10) }
                    assertTrue(errors.toString(), errors.isEmpty())
                }
                send(); assertTrue(prompts.last()!!.contains("Prefers Chinese"))
                if (cold == 1) {
                    store.memories.setEnabled(false); send(); assertEquals("User system", prompts.last())
                    assertEquals("User system", settings.systemPrompt)
                }
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); budget.close(); store.close() }
        }
    }
}
