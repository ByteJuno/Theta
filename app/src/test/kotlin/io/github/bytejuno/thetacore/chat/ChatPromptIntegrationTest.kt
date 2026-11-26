package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.plugin.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class ChatPromptIntegrationTest {
    @Test fun newRunsUseStableSkillPrefixAndPersonaThenFallBackAfterDisableAndDelete() = runBlocking {
        val root = Files.createTempDirectory("theta-chat-prompts").toFile()
        val budget = PluginExecutionBudget()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = openTestStore(root.resolve("agent").apply { mkdirs() })
        try {
            val catalog = PluginDataCatalog(root, 1)
            val selected = installPromptBundle(catalog, mapOf(
                "skills/read/SKILL.md" to "---\ndescription: Read a document.\n---\nFull body must not enter the prefix.",
                "personas/guide.json" to """{"id":"guide","name":"Guide","systemPrompt":"Explain in small steps."}"""))
            val preference = PersonaSettings(root.resolve("settings"))
            preference.select("test.prompts/guide")
            val host = HostToolBridgeRoot(budget).initialize(skillReader = { id, path -> catalog.readSkill(selected, id, path) }) { store }
            val prompts = CopyOnWriteArrayList<String?>()
            val failures = CopyOnWriteArrayList<Throwable>()
            val provider = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials) = flow {
                    prompts += request.system
                    assertTrue(request.tools.any { it.name == "host_skill_read" })
                    emit(Events.started("reply-${prompts.size}", "model"))
                    emit(Events.text(0, "Done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                }
            }
            val owner = ChatSessionOwner(scope, { host }, {
                OpenAiRunSnapshot(OpenAiProviderSettings("chat", "https://example.invalid/v1", "model",
                    systemPrompt = "User system prompt."), NoAuthCredentials)
            }, diagnostic = { failures += it }, targetForRun = { ModelTarget("chat", provider, NoAuthCredentials) },
                promptSnapshot = {
                    val data = catalog.restore { fail(it) }.filter { it.active(false) }
                        .map(catalog::promptData)
                    preference.resolve(PluginPromptData(data.flatMap { it.skills }, data.flatMap { it.personas }))
                })
            owner.open().join()
            suspend fun turn(text: String) {
                owner.send(text).join()
                withTimeout(10_000) { while (owner.state.value.active) delay(10) }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals(R.string.chat_complete, owner.state.value.notice)
            }
            turn("first"); turn("second")
            assertEquals(prompts[0], prompts[1])
            assertTrue(prompts[0]!!.startsWith("Available skills"))
            assertTrue(prompts[0]!!.contains("Explain in small steps."))
            assertTrue(prompts[0]!!.endsWith("User system prompt."))
            assertFalse(prompts[0]!!.contains("Full body must not"))
            assertFalse(owner.state.value.personaUnavailable)
            catalog.setEnabled("test.prompts", false, selected)
            turn("disabled")
            assertEquals("User system prompt.", prompts.last())
            assertTrue(owner.state.value.personaUnavailable)
            assertNull(preference.current().id)
            catalog.setEnabled("test.prompts", true)
            preference.select("test.prompts/guide")
            catalog.beginDelete("test.prompts"); catalog.finishDelete("test.prompts")
            turn("deleted")
            assertEquals("User system prompt.", prompts.last())
            assertTrue(owner.state.value.personaUnavailable)
            preference.select(null)
            turn("acknowledged")
            assertFalse(owner.state.value.personaUnavailable)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close(); store.close(); root.deleteRecursively()
        }
    }
}
