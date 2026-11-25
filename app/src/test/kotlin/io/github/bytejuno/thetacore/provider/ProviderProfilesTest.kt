package io.github.bytejuno.thetacore.provider

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.run.ModelTarget
import io.github.bytejuno.thetacore.agent.run.RunState
import io.github.bytejuno.thetacore.agent.transport.ModelTransport
import io.github.bytejuno.thetacore.agent.transport.TransportEvent
import io.github.bytejuno.thetacore.chat.ChatSessionOwner
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import io.github.bytejuno.thetacore.plugin.HostToolBridgeRoot
import io.github.bytejuno.thetacore.plugin.PluginExecutionBudget
import io.github.bytejuno.thetacore.storage.*
import java.io.File
import java.nio.file.Files
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderProfilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun generateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private suspend fun tokenOf(snapshot: OpenAiRunSnapshot): String =
        snapshot.target(ModelTransport { _, _, _ -> error("No transport expected") }).credentials.accessToken()

    @Test
    fun legacyVersionOneActiveBinUpgradesLosslesslyAndKeepsImagesBinIndependent(): Unit = runBlocking {
        val root = temp.newFolder()
        val chatKey = generateKey()
        val imageKey = generateKey()
        val chatCipher = AesGcmSecretCipher("chat-aad") { chatKey }
        val imageCipher = AesGcmSecretCipher("image-aad") { imageKey }

        // Hand-craft an exact legacy version=1 active.bin payload (including all Compat & Parameters fields).
        val legacyJson = buildJsonObject {
            put("version", 1)
            put("provider", "chat")
            put("base", "https://legacy.example/v1")
            put("model", "deepseek-reasoner")
            put("key", "sk-legacy-secret-123")
            putJsonObject("parameters") {
                put("maxOutput", 4096)
                put("temperature", 0.3)
                put("topP", 0.95)
                put("reasoning", "high")
                put("parallel", true)
                putJsonArray("stop") { add(kotlinx.serialization.json.JsonPrimitive("END")) }
            }
            putJsonObject("compat") {
                put("maxTokensField", "max_tokens")
                put("reasoningField", "reasoning_content")
                put("replayReasoning", true)
                put("streamUsage", false)
                put("explicitCacheControl", true)
                put("schema", "BASIC")
            }
        }.toString().toByteArray(Charsets.UTF_8)

        val dir = File(root, OpenAiSettingsStore.DIRECTORY).apply { mkdirs() }
        val activeFile = File(dir, "active.bin")
        val legacyCiphertext = chatCipher.encrypt(legacyJson)
        activeFile.writeBytes(legacyCiphertext)

        val imageStore = OpenAiSettingsStore(root, imageCipher, "images.bin")
        imageStore.replace(OpenAiProviderSettings("images", "https://images.example/v1", "gpt-image-1"), "sk-image-key")
        val imageBytesBefore = File(dir, "images.bin").readBytes()

        val store = OpenAiSettingsStore(root, chatCipher)
        val catalog = store.readProfiles()
        // Reading must not mutate active.bin on disk.
        assertArrayEquals(legacyCiphertext, activeFile.readBytes())
        assertEquals(DEFAULT_PROFILE_ID, catalog.activeProfileId)
        assertEquals(1, catalog.profiles.size)
        val migrated = catalog.activeProfile!!
        assertEquals("deepseek-reasoner", migrated.displayName)
        assertEquals("https://legacy.example/v1", migrated.settings.baseUrl)
        assertEquals("deepseek-reasoner", migrated.settings.model)
        assertEquals(4096, migrated.settings.parameters.maxOutputTokens)
        assertEquals("reasoning_content", migrated.settings.compat.reasoningField)
        assertTrue(migrated.settings.compat.replayReasoning)
        assertFalse(migrated.settings.compat.streamUsage)
        assertTrue(migrated.settings.compat.explicitCacheControl)
        assertEquals(SchemaProfile.BASIC, migrated.settings.compat.schemaProfile)
        assertNull(migrated.settings.systemPrompt)
        assertEquals("sk-legacy-secret-123", tokenOf(store.snapshotForNewRun()))

        // Adding a second profile upgrades active.bin cleanly while keeping the migrated profile & key intact.
        val fast = store.saveProfile(
            profileId = "fast",
            displayName = "Fast Flash",
            settings = OpenAiProviderSettings(
                providerId = "chat",
                baseUrl = "https://fast.example/v1",
                model = "glm-4-flash",
                systemPrompt = "Answer concisely.",
            ),
            apiKey = "sk-fast-456",
            activate = true,
        )
        assertEquals("fast", fast.id)
        assertEquals("sk-fast-456", tokenOf(store.snapshotForNewRun()))
        assertEquals("Answer concisely.", store.snapshotForNewRun().request().system)

        // Switch back to migrated default profile: original key and compat remain intact.
        store.selectActiveProfile(DEFAULT_PROFILE_ID)
        val backToLegacy = store.snapshotForNewRun()
        assertEquals("sk-legacy-secret-123", tokenOf(backToLegacy))
        assertEquals("https://legacy.example/v1", backToLegacy.settings.baseUrl)
        assertTrue(backToLegacy.settings.compat.explicitCacheControl)
        assertNull(backToLegacy.request().system)

        // images.bin remained completely untouched.
        assertArrayEquals(imageBytesBefore, File(dir, "images.bin").readBytes())
        assertEquals("sk-image-key", tokenOf(imageStore.snapshotForNewRun()))
    }

    @Test
    fun multiProfileCrudZeroesPlaintextNeverExposesKeysAndEnforcesEndpointIsolation(): Unit = runBlocking {
        val root = temp.newFolder()
        val secretKey = generateKey()
        val aes = AesGcmSecretCipher("crud-aad") { secretKey }
        val capturedPlaintexts = mutableListOf<ByteArray>()
        val trackingCipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                capturedPlaintexts += plaintext
                return aes.encrypt(plaintext)
            }
            override fun encryptForReplacement(plaintext: ByteArray): ByteArray {
                capturedPlaintexts += plaintext
                return aes.encryptForReplacement(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray): ByteArray =
                aes.decrypt(ciphertext).also { capturedPlaintexts += it }
        }

        val store = OpenAiSettingsStore(root, trackingCipher)
        assertTrue(store.readProfiles().profiles.isEmpty())

        val p1 = store.saveProfile(
            profileId = "work",
            displayName = "Work Main",
            settings = OpenAiProviderSettings("chat", "https://work.example/v1", "model-work", systemPrompt = "  System A  "),
            apiKey = "sk-work-secret-alpha",
            activate = true,
        )
        val p2 = store.saveProfile(
            profileId = "coding",
            displayName = "Coding",
            settings = OpenAiProviderSettings("chat", "https://code.example/v1", "model-code", systemPrompt = "   "),
            apiKey = "sk-code-secret-beta",
            activate = false,
        )
        assertEquals("work", store.readProfiles().activeProfileId)
        assertEquals("System A", p1.settings.systemPrompt)
        assertNull(p2.settings.systemPrompt)

        // Summary / toString never leak secret tokens.
        val summaryDump = store.readProfiles().toString()
        assertFalse(summaryDump.contains("sk-work-secret-alpha"))
        assertFalse(summaryDump.contains("sk-code-secret-beta"))

        // Editing same profile with unchanged baseUrl reuses THAT profile's key when apiKey is blank.
        store.saveProfile(
            profileId = "coding",
            displayName = "Coding Pro",
            settings = OpenAiProviderSettings("chat", "https://code.example/v1", "model-code-v2", systemPrompt = "Write tests."),
            apiKey = "",
            activate = true,
        )
        assertEquals("coding", store.readProfiles().activeProfileId)
        assertEquals("sk-code-secret-beta", tokenOf(store.snapshotForNewRun()))
        assertEquals("Write tests.", store.snapshotForNewRun().request().system)

        // Changing baseUrl with blank apiKey MUST fail with ENDPOINT_CHANGED, even if the new baseUrl matches another profile!
        val crossHostAttempt = assertThrows(ProviderSettingsException::class.java) {
            store.saveProfile(
                profileId = "coding",
                displayName = "Coding Pro",
                settings = OpenAiProviderSettings("chat", "https://work.example/v1", "model-code-v2"),
                apiKey = "",
                activate = true,
            )
        }
        assertEquals(ProviderSettingsException.Failure.ENDPOINT_CHANGED, crossHostAttempt.failure)

        // Brand-new profile with blank apiKey (and noAuth=false) is rejected even if baseUrl matches an existing profile.
        val newBlankAttempt = assertThrows(ProviderSettingsException::class.java) {
            store.saveProfile(
                profileId = "stolen",
                displayName = "Stolen",
                settings = OpenAiProviderSettings("chat", "https://work.example/v1", "model-work"),
                apiKey = "",
                activate = true,
            )
        }
        assertEquals(ProviderSettingsException.Failure.NOT_CONFIGURED, newBlankAttempt.failure)

        // Explicit no-auth on changed local endpoint clears key and uses NoAuthCredentials.
        store.saveProfile(
            profileId = "local",
            displayName = "Local Ollama",
            settings = OpenAiProviderSettings("chat", "http://127.0.0.1:11434/v1", "qwen2.5"),
            apiKey = "",
            noAuth = true,
            activate = true,
        )
        assertEquals("", tokenOf(store.snapshotForNewRun()))

        // Rename & delete non-last profiles; deleting active selects remaining; deleting last clears to NOT_CONFIGURED.
        val renamed = store.renameProfile("work", "Work Renamed")
        assertEquals("Work Renamed", renamed.displayName)
        store.deleteProfile("local")
        store.deleteProfile("coding")
        val remaining = store.readProfiles()
        assertEquals(listOf("work"), remaining.profiles.map { it.id })
        assertEquals("work", remaining.activeProfileId)
        assertEquals("sk-work-secret-alpha", tokenOf(store.snapshotForNewRun()))

        val emptyAfterLastDelete = store.deleteProfile("work")
        assertTrue(emptyAfterLastDelete.profiles.isEmpty())
        assertNull(emptyAfterLastDelete.activeProfileId)
        val unconfigured = assertThrows(ProviderSettingsException::class.java) { store.snapshotForNewRun() }
        assertEquals(ProviderSettingsException.Failure.NOT_CONFIGURED, unconfigured.failure)

        // Every plaintext ByteArray touched by encrypt/decrypt was zeroed out.
        assertTrue(capturedPlaintexts.isNotEmpty())
        assertTrue(capturedPlaintexts.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun explicitReplacementRecoversUnusableKeystoreKeyForProfileSave(): Unit = runBlocking {
        val root = temp.newFolder()
        var current: SecretKey? = generateKey()
        var unusable = false
        var deletes = 0
        var creates = 0
        val keySource = object : SecretKeySource {
            override fun load(create: Boolean): SecretKey {
                if (unusable) throw UnusableSecretKeyException("invalidated")
                if (current == null) {
                    if (!create) throw UnrecoverableKeyException("missing")
                    creates++
                    current = generateKey()
                }
                return current!!
            }
            override fun delete() {
                deletes++
                current = null
                unusable = false
            }
        }
        val cipher = KeyStoreSecretCipher("profile-recovery-aad", keySource, allowKeyRecovery = true)
        val store = OpenAiSettingsStore(root, cipher)
        store.saveProfile(
            profileId = "main",
            displayName = "Main",
            settings = OpenAiProviderSettings("chat", "https://example.invalid/v1", "model-a"),
            apiKey = "sk-old",
        )
        unusable = true
        assertThrows(ProviderSettingsException::class.java) { store.readProfiles() }
        assertEquals(0, deletes)

        // Explicit unreadable replacement rebuilds the unusable key and saves the new profile.
        store.saveProfile(
            profileId = "recovered",
            displayName = "Recovered",
            settings = OpenAiProviderSettings("chat", "https://example.invalid/v1", "model-b", systemPrompt = "Fresh"),
            apiKey = "sk-new-recovered",
            replaceUnreadable = true,
        )
        assertEquals(1, deletes)
        assertEquals(1, creates)
        assertEquals("sk-new-recovered", tokenOf(store.snapshotForNewRun()))
        assertEquals("Fresh", store.snapshotForNewRun().request().system)
    }

    @Test
    fun switchingActiveProfileMidRunDoesNotMutateInFlightRunAndAppliesToNextRunWithReplayCheck(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-profile-chat-run").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var storeHandle: AndroidAgentStore? = null
        try {
            val cipher = AesGcmSecretCipher("run-switch-aad") {
                val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                // Stable key across calls in this test:
                key
            }.let {
                val fixed = generateKey()
                AesGcmSecretCipher("run-switch-aad") { fixed }
            }
            val settingsStore = OpenAiSettingsStore(File(root, "no-backup"), cipher)
            settingsStore.saveProfile(
                profileId = "alpha",
                displayName = "Alpha",
                settings = OpenAiProviderSettings(
                    providerId = "chat",
                    baseUrl = "https://alpha.example/v1",
                    model = "alpha-model",
                    compat = OpenAiChatConfig.Compat(reasoningField = "reasoning_content", replayReasoning = true),
                    systemPrompt = "You are Alpha.",
                ),
                apiKey = "sk-alpha",
                activate = true,
            )
            settingsStore.saveProfile(
                profileId = "beta",
                displayName = "Beta",
                settings = OpenAiProviderSettings(
                    providerId = "chat",
                    baseUrl = "https://beta.example/v1",
                    model = "beta-model",
                    systemPrompt = "You are Beta.",
                ),
                apiKey = "sk-beta",
                activate = false,
            )

            val dbDir = File(root, "agent-store").apply { mkdirs() }
            val agentStore = AndroidAgentStore.open(dbDir, BundledSQLiteDriver())
            storeHandle = agentStore
            val service = HostToolBridgeRoot(PluginExecutionBudget()).initialize { agentStore }

            val turn1Started = CompletableDeferred<Unit>()
            val allowTurn1Finish = CompletableDeferred<Unit>()
            val recordedCalls = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, String>>())
            var callIndex = 0

            var emitReasoning = false
            val transport = ModelTransport { endpoint, body, token ->
                flow {
                    val idx = ++callIndex
                    recordedCalls += Triple(endpoint, token, body)
                    emit(TransportEvent.Headers(200, "text/event-stream", null))
                    when {
                        emitReasoning -> {
                            val sse = "data: {\"id\":\"3\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"secret thought\",\"content\":\"Reasoned\"}}]}\n\n" +
                                "data: {\"id\":\"3\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                                "data: [DONE]\n\n"
                            emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                        }
                        idx == 1 -> {
                            turn1Started.complete(Unit)
                            allowTurn1Finish.await()
                            val sse = "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Alpha reply\"}}]}\n\n" +
                                "data: {\"id\":\"1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                                "data: [DONE]\n\n"
                            emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                        }
                        else -> {
                            val sse = "data: {\"id\":\"2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Beta reply\"}}]}\n\n" +
                                "data: {\"id\":\"2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                                "data: [DONE]\n\n"
                            emit(TransportEvent.Bytes(sse.toByteArray(Charsets.UTF_8)))
                        }
                    }
                }
            }

            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val owner = ChatSessionOwner(
                scope = scope,
                service = { service },
                configuration = settingsStore::snapshotForNewRun,
                diagnostic = { failures += it },
                targetForRun = { snapshot -> snapshot.target(transport) },
            )
            owner.open().join()
            owner.send("Hello turn 1").join()

            withTimeout(5_000) { turn1Started.await() }
            // Switch active profile WHILE turn 1 is still streaming!
            settingsStore.selectActiveProfile("beta")
            allowTurn1Finish.complete(Unit)

            withTimeout(5_000) {
                while (owner.state.value.active) delay(25)
            }
            assertEquals(R.string.chat_complete, owner.state.value.notice)
            assertEquals(1, recordedCalls.size)
            val firstCall = recordedCalls[0]
            assertEquals("https://alpha.example/v1/chat/completions", firstCall.first)
            assertEquals("sk-alpha", firstCall.second)
            val firstMessages = Json.parseToJsonElement(firstCall.third).jsonObject.getValue("messages").jsonArray
            assertEquals("system", firstMessages[0].jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals("You are Alpha.", firstMessages[0].jsonObject.getValue("content").jsonPrimitive.content)

            // Turn 2 uses Beta profile, Beta key, and Beta system prompt while preserving Alpha's turn 1 history!
            owner.send("Hello turn 2").join()
            withTimeout(5_000) {
                while (owner.state.value.active) delay(25)
            }
            assertEquals(R.string.chat_complete, owner.state.value.notice)
            assertEquals(2, recordedCalls.size)
            val secondCall = recordedCalls[1]
            assertEquals("https://beta.example/v1/chat/completions", secondCall.first)
            assertEquals("sk-beta", secondCall.second)
            val secondMessages = Json.parseToJsonElement(secondCall.third).jsonObject.getValue("messages").jsonArray
            assertEquals("You are Beta.", secondMessages[0].jsonObject.getValue("content").jsonPrimitive.content)
            assertTrue(secondCall.third.contains("Alpha reply"))

            // Also verify incompatible reasoning replay pauses cleanly for checkpoint summary instead of crashing!
            settingsStore.selectActiveProfile("alpha")
            emitReasoning = true
            owner.newSession().join()
            owner.send("Think first").join()
            withTimeout(5_000) { while (owner.state.value.active) delay(25) }
            assertEquals(RunState.COMPLETED, owner.state.value.result?.state)

            // Switch to Beta (different model/host) after Alpha recorded reasoning replay -> pauses cleanly for checkpoint!
            settingsStore.selectActiveProfile("beta")
            owner.send("Follow up on Beta").join()
            withTimeout(5_000) { while (owner.state.value.active) delay(25) }
            assertEquals(RunState.PAUSED, owner.state.value.result?.state)
            assertTrue(owner.state.value.result?.reason.orEmpty().contains("Replay incompatible"))
            assertTrue(failures.toString(), failures.isEmpty())
        } finally {
            scope.cancel()
            runCatching { storeHandle?.close() }
            root.deleteRecursively()
        }
    }

    @Test
    fun recoverySaveReReadsUnderLockPreservesProfilesWhenReadableAgainAndReloadsEditor(): Unit = runBlocking {
        val root = temp.newFolder()
        val key = generateKey()
        var failReads = false
        val flakyCipher = object : SecretCipher {
            private val delegate = AesGcmSecretCipher("flaky-recovery-aad") { key }
            override fun encrypt(plaintext: ByteArray): ByteArray = delegate.encrypt(plaintext)
            override fun encryptForReplacement(plaintext: ByteArray): ByteArray = delegate.encryptForReplacement(plaintext)
            override fun decrypt(ciphertext: ByteArray): ByteArray {
                if (failReads) throw IllegalStateException("Transient keystore glitch")
                return delegate.decrypt(ciphertext)
            }
        }
        val store = OpenAiSettingsStore(root, flakyCipher)
        store.saveProfile(
            profileId = "p1",
            displayName = "Primary",
            settings = OpenAiProviderSettings("chat", "https://p1.example/v1", "model-1"),
            apiKey = "sk-p1",
            activate = true,
        )
        store.saveProfile(
            profileId = "p2",
            displayName = "Backup",
            settings = OpenAiProviderSettings("chat", "https://p2.example/v1", "model-2"),
            apiKey = "sk-p2",
            activate = false,
        )

        // Simulate transient read failure -> user clicks "Re-enter" (replacingUnreadable = true).
        failReads = true
        val editor = io.github.bytejuno.thetacore.chat.ProviderSettingsEditor()
        editor.load(store, imageProfile = false)
        assertNotNull(editor.loadError)
        editor.beginReplacement()
        assertTrue(editor.replacingUnreadable)

        // Before the user confirms Save, the storage/Keystore becomes readable again!
        failReads = false
        val thrown = assertThrows(ProviderSettingsException::class.java) {
            store.saveProfile(
                profileId = null,
                displayName = "Overwrite Attempt",
                settings = OpenAiProviderSettings("chat", "https://new.example/v1", "new-model"),
                apiKey = "sk-overwrite",
                replaceUnreadable = true,
            )
        }
        assertEquals(ProviderSettingsException.Failure.CATALOG_RECOVERED, thrown.failure)

        // All previously saved profiles and keys MUST remain completely intact!
        val catalog = store.readProfiles()
        assertEquals("p1", catalog.activeProfileId)
        assertEquals(listOf("p1", "p2"), catalog.profiles.map { it.id })
        assertEquals("sk-p1", tokenOf(store.snapshotForNewRun()))
        store.selectActiveProfile("p2")
        assertEquals("sk-p2", tokenOf(store.snapshotForNewRun()))

        // Editor reload restores both profiles cleanly.
        editor.load(store, imageProfile = false)
        assertFalse(editor.replacingUnreadable)
        assertNull(editor.loadError)
        assertEquals(listOf("p1", "p2"), editor.profiles.map { it.id })
    }

    @Test
    fun quickSwitchWaitsForWriteCompletionPreservesSelectionOnFailureAndRetries(): Unit = runBlocking {
        val root = temp.newFolder()
        val key = generateKey()
        var failWrite = false
        val writeStarted = CompletableDeferred<Unit>()
        val allowWriteFinish = CompletableDeferred<Unit>()
        val controlledCipher = object : SecretCipher {
            private val delegate = AesGcmSecretCipher("quick-switch-aad") { key }
            override fun encrypt(plaintext: ByteArray): ByteArray {
                if (failWrite) throw IllegalStateException("Disk full")
                return delegate.encrypt(plaintext)
            }
            override fun encryptForReplacement(plaintext: ByteArray): ByteArray = delegate.encryptForReplacement(plaintext)
            override fun decrypt(ciphertext: ByteArray): ByteArray = delegate.decrypt(ciphertext)
        }
        val store = OpenAiSettingsStore(root, controlledCipher)
        store.saveProfile(
            profileId = "first",
            displayName = "First",
            settings = OpenAiProviderSettings("chat", "https://first.example/v1", "m1"),
            apiKey = "sk-first",
            activate = true,
        )
        store.saveProfile(
            profileId = "second",
            displayName = "Second",
            settings = OpenAiProviderSettings("chat", "https://second.example/v1", "m2"),
            apiKey = "sk-second",
            activate = false,
        )

        val controller = io.github.bytejuno.thetacore.chat.ProfileQuickSwitchController()
        assertFalse(controller.switching)

        // 1. Write failure preserves old active profile and surfaces retryable error state.
        failWrite = true
        val failed = controller.switchTo("second", store::selectActiveProfile)
        assertFalse(failed)
        assertFalse(controller.switching)
        assertEquals(R.string.chat_profile_switch_failed, controller.error)
        assertEquals("second", controller.failedProfileId)
        assertEquals("first", store.readProfiles().activeProfileId)

        // 2. Retry waits for write completion (switching stays true until write finishes).
        failWrite = false
        val retryJob = async(Dispatchers.Default) {
            controller.switchTo(requireNotNull(controller.failedProfileId)) { targetId ->
                writeStarted.complete(Unit)
                allowWriteFinish.await()
                store.selectActiveProfile(targetId)
            }
        }
        withTimeout(5_000) { writeStarted.await() }
        assertTrue("switching must stay true while write is in flight", controller.switching)
        // Concurrent switch attempt while switching is ignored.
        assertFalse(controller.switchTo("first", store::selectActiveProfile))

        allowWriteFinish.complete(Unit)
        assertTrue(retryJob.await())
        assertFalse(controller.switching)
        assertNull(controller.error)
        assertNull(controller.failedProfileId)
        assertEquals("second", store.readProfiles().activeProfileId)
    }

    @Test
    fun legacyV1MigrationGeneratesLegalDisplayNameFallbackWhilePreservingModelParametersAndKey(): Unit = runBlocking {
        val longAndControlModel = "  \torg/very-long-model-" + "x".repeat(140) + "\n"
        val parameters = OpenAiParameters(
            maxOutputTokens = 2048,
            temperature = 0.3,
            topP = 0.9,
            reasoningLevel = "high",
            parallelToolCalls = false,
            stopSequences = listOf("STOP_A", "STOP_B"),
        )
        val compat = OpenAiChatConfig.Compat(
            maxTokensField = "max_tokens",
            reasoningField = "reasoning_content",
            replayReasoning = true,
            streamUsage = false,
            explicitCacheControl = true,
            schemaProfile = SchemaProfile.BASIC,
        )
        val v1Settings = OpenAiProviderSettings(
            providerId = "chat",
            baseUrl = "https://legacy.example/v1",
            model = longAndControlModel,
            parameters = parameters,
            compat = compat,
        )
        val v1Bytes = ProviderSettingsCodec.encode(v1Settings, "sk-legacy-secret-123")
        val migrated = ProviderSettingsCodec.decodeCatalog(v1Bytes)

        assertEquals(DEFAULT_PROFILE_ID, migrated.activeProfileId)
        assertEquals(1, migrated.profiles.size)
        val profile = migrated.active
        assertTrue(validProfileDisplayName(profile.displayName))
        assertTrue(profile.displayName.length <= MAX_PROFILE_NAME_CHARS)
        assertFalse(profile.displayName.contains('\t'))
        assertFalse(profile.displayName.contains('\n'))
        // Original model identifier, parameters, compat, and key MUST be preserved verbatim!
        assertEquals(longAndControlModel, profile.settings.model)
        assertEquals(parameters.maxOutputTokens, profile.settings.parameters.maxOutputTokens)
        assertEquals(parameters.temperature, profile.settings.parameters.temperature)
        assertEquals(parameters.topP, profile.settings.parameters.topP)
        assertEquals(parameters.reasoningLevel, profile.settings.parameters.reasoningLevel)
        assertEquals(parameters.parallelToolCalls, profile.settings.parameters.parallelToolCalls)
        assertEquals(parameters.stopSequences, profile.settings.parameters.stopSequences)
        assertEquals(compat.maxTokensField, profile.settings.compat.maxTokensField)
        assertEquals(compat.reasoningField, profile.settings.compat.reasoningField)
        assertEquals(compat.replayReasoning, profile.settings.compat.replayReasoning)
        assertEquals(compat.streamUsage, profile.settings.compat.streamUsage)
        assertEquals(compat.explicitCacheControl, profile.settings.compat.explicitCacheControl)
        assertEquals(compat.schemaProfile, profile.settings.compat.schemaProfile)
        assertEquals("https://legacy.example/v1", profile.settings.baseUrl)
        assertEquals("sk-legacy-secret-123", profile.key)
        assertFalse(profile.noAuth)

        // Control-char-only / whitespace edge fallback produces DEFAULT_PROFILE_ID safely.
        assertEquals(DEFAULT_PROFILE_ID, fallbackProfileDisplayName("\t\u0007\n"))
    }
}
