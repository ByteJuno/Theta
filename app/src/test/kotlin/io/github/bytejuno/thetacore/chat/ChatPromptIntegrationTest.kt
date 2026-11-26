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
    @Test fun unchangedPackageStillContinuesSavedSummaryAfterColdOpen() = checkpointRecovery(null)
    @Test fun updatedSkillHidesStaleSummaryAndNewInputContinuesAfterColdOpen() = checkpointRecovery("skill")
    @Test fun updatedPersonaHidesStaleSummaryAndNewInputContinuesAfterColdOpen() = checkpointRecovery("persona")

    private fun checkpointRecovery(update: String?) = runBlocking {
        val root = Files.createTempDirectory("theta-prompt-checkpoint").toFile()
        val catalog = PluginDataCatalog(root, 1)
        fun entries(skill: Int, persona: Int) = mapOf(
            "skills/read/SKILL.md" to "---\ndescription: INDEX-$skill\n---\nDOCUMENT-$skill",
            "personas/guide.json" to """{"id":"guide","name":"Guide","systemPrompt":"PERSONA-$persona"}""")
        installPromptBundle(catalog, entries(1, 1))
        val preference = PersonaSettings(root.resolve("settings"))
        preference.select("test.prompts/guide")
        val requests = CopyOnWriteArrayList<ChatRequest>()
        val provider = object : ModelProvider {
            override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
            override fun chat(request: ChatRequest, auth: Credentials) = flow {
                requests += request
                emit(Events.started("reply-${requests.size}", "model"))
                emit(Events.text(0, "Saved explanation")); emit(Events.end(0))
                emit(Events.finished(if (requests.size == 1) FinishReason.CONTEXT_EXCEEDED else FinishReason.STOP))
            }
        }
        suspend fun fixture(): Triple<ChatSessionOwner, suspend () -> Unit, List<Throwable>> {
            val budget = PluginExecutionBudget()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = openTestStore(root.resolve("agent").apply { mkdirs() })
            val host = HostToolBridgeRoot(budget).initialize(skillReader = { selection, id, path ->
                catalog.readSkill(selection, id, path)
            }) { store }
            val failures = CopyOnWriteArrayList<Throwable>()
            val owner = ChatSessionOwner(scope, { host }, {
                OpenAiRunSnapshot(OpenAiProviderSettings("chat", "https://example.invalid/v1", "model"), NoAuthCredentials)
            }, diagnostic = { failures += it }, targetForRun = { ModelTarget("chat", provider, NoAuthCredentials) },
                promptSnapshot = {
                    val data = catalog.restore { fail(it) }.filter { it.active(false) }.map(catalog::promptData)
                    preference.resolve(PluginPromptData(data.flatMap { it.skills }, data.flatMap { it.personas },
                        selections = data.flatMap { it.selections.entries }.associate { it.toPair() }))
                })
            return Triple(owner, { scope.coroutineContext[Job]!!.cancelAndJoin(); budget.close(); store.close() }, failures)
        }
        suspend fun idle(owner: ChatSessionOwner) {
            withTimeout(10_000) { while (owner.state.value.active) delay(10) }
        }
        try {
            val first = fixture()
            val checkpoint = try {
                first.first.open().join(); first.first.send("unfinished goal").join(); idle(first.first)
                first.first.resume(summaryWindowTokens = 8192).join(); idle(first.first)
                assertTrue(first.third.toString(), first.third.isEmpty())
                requireNotNull(first.first.state.value.checkpoint).also { assertTrue(it.canContinue) }
            } finally { first.second() }
            assertEquals(2, requests.size)
            if (update != null) installPromptBundle(catalog,
                entries(if (update == "skill") 2 else 1, if (update == "persona") 2 else 1), version = 2)
            val cold = fixture()
            try {
                val owner = cold.first
                owner.open().join()
                assertEquals(checkpoint.id, owner.state.value.checkpoint!!.id)
                val token = requireNotNull(owner.state.value.recovery)
                if (update == null) {
                    assertFalse(owner.state.value.checkpointSettingsChanged)
                    assertEquals(setOf(ChatRecoveryAction.CONTINUE_SUMMARY), recoveryActions(owner.state.value))
                    owner.recover(token, ChatRecoveryAction.CONTINUE_SUMMARY, confirmed = true).join(); idle(owner)
                } else {
                    assertTrue(owner.state.value.checkpointSettingsChanged)
                    assertTrue(recoveryActions(owner.state.value).isEmpty())
                    owner.resume().join(); idle(owner) // Direct calls also refuse before model submission.
                    assertEquals(R.string.checkpoint_settings_changed, owner.state.value.error)
                    assertEquals(token, owner.state.value.recovery)
                    assertEquals(2, requests.size)
                    owner.send("Continue the unfinished goal with the current prompts").join(); idle(owner)
                    assertTrue(requests.last().messages.flatMap { it.parts }.filterIsInstance<ContentPart.Text>()
                        .any { it.text == "unfinished goal" })
                    assertTrue(requests.last().system!!.contains(if (update == "skill") "INDEX-2" else "PERSONA-2"))
                }
                assertEquals(cold.third.toString(), R.string.chat_complete, owner.state.value.notice)
                assertTrue(cold.third.toString(), cold.third.isEmpty())
                assertEquals(3, requests.size)
                assertTrue(owner.state.value.result!!.steps > checkpoint.steps)
                assertFalse(owner.state.value.checkpointSettingsChanged)
            } finally { cold.second() }
        } finally { root.deleteRecursively() }
    }

    @Test fun runningChatKeepsOldSkillBytesNextRunUsesUpdateAndDamagedPreferenceDoesNotBlockChat() = runBlocking {
        val root = Files.createTempDirectory("theta-chat-skill-update").toFile()
        val budget = PluginExecutionBudget()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = openTestStore(root.resolve("agent").apply { mkdirs() })
        try {
            val catalog = PluginDataCatalog(root, 1)
            fun entries(n: Int) = mapOf("skills/read/SKILL.md" to
                "---\ndescription: INDEX-$n\n---\nDOCUMENT-$n")
            installPromptBundle(catalog, entries(1))
            val preferences = root.resolve("settings").apply { mkdirs() }
            preferences.resolve("chat-persona.json").writeText("""{"id":"test.prompts/guide?"}""")
            val settings = PersonaSettings(preferences)
            val host = HostToolBridgeRoot(budget).initialize(skillReader = { selection, id, path ->
                catalog.readSkill(selection, id, path)
            }) { store }
            var requests = 0
            val failures = CopyOnWriteArrayList<Throwable>()
            val provider = object : ModelProvider {
                override fun executionMode(request: ChatRequest) = ModelExecutionMode.GENERATION_ONLY
                override fun chat(request: ChatRequest, auth: Credentials) = flow {
                    val call = ++requests
                    assertTrue(request.system!!.contains(if (call <= 2) "INDEX-1" else "INDEX-2"))
                    emit(Events.started("reply-$call", "model"))
                    if (call % 2 == 1) {
                        if (call == 1) installPromptBundle(catalog, entries(2), version = 2)
                        emit(Events.toolStart(0, "wire-$call", "host_skill_read"))
                        emit(Events.toolDelta(0, """{"id":"test.prompts/read"}"""))
                        emit(Events.end(0)); emit(Events.finished(FinishReason.TOOL_CALLS))
                    } else {
                        val result = request.messages.flatMap { it.parts }.filterIsInstance<ContentPart.ToolResult>().last()
                        assertFalse(result.isError)
                        assertTrue(result.text.contains(if (call == 2) "DOCUMENT-1" else "DOCUMENT-2"))
                        emit(Events.text(0, "Done")); emit(Events.end(0)); emit(Events.finished(FinishReason.STOP))
                    }
                }
            }
            val owner = ChatSessionOwner(scope, { host }, {
                OpenAiRunSnapshot(OpenAiProviderSettings("chat", "https://example.invalid/v1", "model"), NoAuthCredentials)
            }, diagnostic = { failures += it }, targetForRun = { ModelTarget("chat", provider, NoAuthCredentials) },
                promptSnapshot = {
                    val data = catalog.restore { fail(it) }.filter { it.active(false) }.map(catalog::promptData)
                    settings.resolve(PluginPromptData(data.flatMap { it.skills }, data.flatMap { it.personas },
                        selections = data.flatMap { it.selections.entries }.associate { it.toPair() }))
                })
            owner.open().join()
            for (message in listOf("first", "next")) {
                owner.send(message).join()
                withTimeout(10_000) { while (owner.state.value.active) delay(10) }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals(R.string.chat_complete, owner.state.value.notice)
                assertTrue(owner.state.value.personaRecovered)
            }
            assertEquals(4, requests)
            assertEquals(2L, owner.state.value.acceptedMessages)
            settings.select(null)
            assertFalse(settings.current().recovered)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            budget.close(); store.close(); root.deleteRecursively()
        }
    }

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
            val host = HostToolBridgeRoot(budget).initialize(skillReader = { selection, id, path -> catalog.readSkill(selection, id, path) }) { store }
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
                    preference.resolve(PluginPromptData(data.flatMap { it.skills }, data.flatMap { it.personas },
                        selections = data.flatMap { it.selections.entries }.associate { it.toPair() }))
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
