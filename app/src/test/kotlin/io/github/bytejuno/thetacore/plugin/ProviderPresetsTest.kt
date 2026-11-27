package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.agent.provider.openai.ApiKeyCredentials
import io.github.bytejuno.thetacore.agent.provider.openai.SchemaProfile
import io.github.bytejuno.thetacore.chat.CHAT_PROVIDER_ID
import io.github.bytejuno.thetacore.chat.ProviderSettingsEditor
import io.github.bytejuno.thetacore.provider.OpenAiSettingsStore
import io.github.bytejuno.thetacore.provider.ProviderSettingsException
import io.github.bytejuno.thetacore.storage.AesGcmSecretCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderPresetsTest {
    private fun dataBundle(
        id: String = "com.example.presets",
        name: String = "Community Presets",
        version: String = "1.0.0",
        versionCode: Long = 1L,
        entries: Map<String, String>,
    ): ByteArray {
        val manifest = """
            {
              "schema": 1,
              "type": "data",
              "id": "$id",
              "name": "$name",
              "version": "$version",
              "versionCode": $versionCode,
              "apiLevel": 1,
              "provides": ["providers"]
            }
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("theta-plugin.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun testCipher(): AesGcmSecretCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmSecretCipher("provider-presets-test-aad") { key }
    }

    @Test
    fun parseProviderTemplateValidatesOpenAiChatFieldsModelsAndCompat() {
        val raw = """
            {
              "id": "deepseek",
              "name": "DeepSeek",
              "protocol": "openai-chat",
              "baseUrl": "https://api.deepseek.com/v1",
              "models": [
                {
                  "id": "deepseek-chat",
                  "displayName": "DeepSeek Chat",
                  "contextWindow": 128000,
                  "maxOutputTokens": 8192,
                  "reasoningLevels": ["low", "high"],
                  "defaultReasoningLevel": "high",
                  "inputKinds": ["text"],
                  "outputKinds": ["text"],
                  "tools": {
                    "supported": true,
                    "parallel": false,
                    "forcedChoice": true,
                    "schemaProfile": "BASIC",
                    "maxTools": 32,
                    "maxNameLength": 64,
                    "mediaInResults": false
                  },
                  "promptCache": true,
                  "structuredOutput": true
                },
                {
                  "id": "deepseek-reasoner"
                }
              ],
              "compat": {
                "maxTokensField": "max_tokens",
                "reasoningField": "reasoning_content",
                "replayReasoning": true,
                "streamUsage": false,
                "schemaProfile": "BASIC",
                "explicitCacheControl": true
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val parsed = parseProviderTemplate(
            pluginId = "com.example.presets",
            pluginName = "Community Presets",
            pluginVersion = "1.0.0",
            entryPath = "providers/deepseek.json",
            bytes = raw,
        )
        assertTrue(parsed.supported)
        assertTrue(parsed.authorUnverified)
        assertEquals("deepseek", parsed.id)
        assertEquals("DeepSeek", parsed.name)
        assertEquals(ProviderTemplateProtocol.OPENAI_CHAT, parsed.protocol)
        assertEquals("https://api.deepseek.com/v1", parsed.baseUrl)
        assertEquals("api.deepseek.com", parsed.host)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), parsed.models.map { it.id })
        assertEquals(128000L, parsed.models[0].contextWindow)
        assertEquals(8192, parsed.models[0].maxOutputTokens)
        assertEquals("high", parsed.models[0].defaultReasoningLevel)

        val compat = requireNotNull(parsed.compat)
        assertEquals("max_tokens", compat.maxTokensField)
        assertEquals("reasoning_content", compat.reasoningField)
        assertTrue(compat.replayReasoning)
        assertFalse(compat.streamUsage)
        assertEquals(SchemaProfile.BASIC, compat.schemaProfile)
        assertTrue(compat.explicitCacheControl)

        // Private localhost / Tailscale cleartext HTTP is allowed just like OpenAiChatConfig.
        val localRaw = """
            {
              "id": "ollama",
              "name": "Local Ollama",
              "protocol": "openai-chat",
              "baseUrl": "http://127.0.0.1:11434/v1"
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val local = parseProviderTemplate("com.example.local", "Local Pack", "1.0", "providers/ollama.json", localRaw)
        assertEquals("127.0.0.1", local.host)
        assertTrue(local.supported)
    }

    @Test
    fun documentedModelMetadataLoadsFromInstalledDataPlugin() {
        val root = Files.createTempDirectory("theta-presets-model-metadata").toFile()
        try {
            val nullableKeys = listOf(
                "contextWindow", "maxOutputTokens", "reasoningLevels", "defaultReasoningLevel",
                "inputKinds", "outputKinds", "tools", "promptCache", "structuredOutput",
            )
            val modelFields = nullableKeys.associateWith { "\"$it\":null" } + mapOf(
                "tool-support" to """"tools":{"supported":true,"parallel":false,"forcedChoice":true,"schemaProfile":"BASIC","maxTools":32,"maxNameLength":64,"mediaInResults":false}""",
                "unknown-tool-details" to """"tools":{"supported":false,"parallel":null,"forcedChoice":null,"schemaProfile":null,"maxTools":null,"maxNameLength":null,"mediaInResults":null}""",
            )
            val entries = modelFields.mapKeys { (id, _) -> "providers/$id.json" }.mapValues { (path, fields) ->
                val id = path.removePrefix("providers/").removeSuffix(".json")
                """{"id":"$id","name":"$id","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"model",$fields}]}"""
            }
            val catalog = PluginDataCatalog(root, 1)
            val stage = catalog.stage(ByteArrayInputStream(dataBundle(entries = entries)))
            val installed = catalog.install(stage, stage.sha256, acknowledged = true)
            val loaded = catalog.readProviderTemplates(installed)
            assertEquals(emptyList<String>(), loaded.errors)
            assertEquals(modelFields.keys, loaded.templates.map { it.id }.toSet())
            loaded.templates.forEach { template ->
                assertTrue(template.supported)
                assertEquals("model", template.models.single().id)
                assertNull(template.models.single().contextWindow)
                assertNull(template.models.single().maxOutputTokens)
                assertEquals(emptyList<String>(), template.models.single().reasoningLevels)
                assertNull(template.models.single().defaultReasoningLevel)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun modelMetadataStillRejectsWrongTypesUnknownFieldsAndInvalidToolSupport() {
        val invalidFields = listOf(
            """"tools":true""",
            """"tools":[]""",
            """"tools":{}""",
            """"tools":{"supported":null}""",
            """"tools":{"supported":"true"}""",
            """"tools":{"supported":true,"parallel":"false"}""",
            """"tools":{"supported":true,"forcedChoice":1}""",
            """"tools":{"supported":true,"schemaProfile":"UNKNOWN"}""",
            """"tools":{"supported":true,"maxTools":-1}""",
            """"tools":{"supported":true,"maxNameLength":1.5}""",
            """"tools":{"supported":true,"maxNameLength":2147483648}""",
            """"tools":{"supported":true,"mediaInResults":[]}""",
            """"tools":{"supported":true,"extra":null}""",
            """"contextWindow":"128000"""",
            """"reasoningLevels":"high"""",
            """"promptCache":"false"""",
            """"unknown":null""",
            """"displayName":null""",
        )
        invalidFields.forEach { fields ->
            val payload = """{"id":"invalid","name":"Invalid","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"model",$fields}]}"""
            assertThrows("Expected invalid model metadata: $fields", PluginPackageException::class.java) {
                parseProviderTemplate("com.example.presets", "Pack", "1.0", "providers/invalid.json", payload.toByteArray())
            }
        }
    }

    @Test
    fun parseProviderTemplateRecognizesNonOpenAiProtocolsAndBlocksMisleadingImport(): Unit = runBlocking {
        val protocols = listOf(
            "anthropic" to ProviderTemplateProtocol.ANTHROPIC,
            "gemini" to ProviderTemplateProtocol.GEMINI,
        )
        val dir = Files.createTempDirectory("theta-presets-unsupported").toFile()
        try {
            val store = OpenAiSettingsStore(dir, testCipher())
            val editor = ProviderSettingsEditor()
            editor.load(store, imageProfile = false)
            assertTrue(editor.editable)

            protocols.forEach { (wire, expected) ->
                val raw = """
                    {
                      "id": "p-$wire",
                      "name": "Vendor $wire",
                      "protocol": "$wire",
                      "baseUrl": "https://api.$wire.example/v1",
                      "models": [{"id": "m1"}]
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8)
                val parsed = parseProviderTemplate("com.example.pack", "Pack", "1.0", "providers/$wire.json", raw)
                assertEquals(expected, parsed.protocol)
                assertEquals(wire == "anthropic", parsed.supported)
                assertNull(parsed.compat)
                assertEquals("api.$wire.example", parsed.host)

                // Must refuse applying non-openai-chat protocol into OpenAI chat profile form.
                assertFalse(editor.applyTemplate(parsed))
                assertEquals("", editor.form.endpoint)
                assertNull(editor.form.templateSourceName)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parseProviderTemplateFailsClosedOnInvalidUrlsUnknownFieldsAndOutOfBoundsValues() {
        val invalidPayloads = listOf(
            // Public cleartext HTTP
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"http://api.public.example/v1"}""",
            // URL with query string
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1?x=1"}""",
            // URL with embedded credentials
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://user:pass@api.example.com/v1"}""",
            // Unknown top-level field
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","extra":true}""",
            // Unknown protocol
            """{"id":"a","name":"A","protocol":"cohere","baseUrl":"https://api.example.com/v1"}""",
            // Unknown compat field
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","compat":{"foo":"bar"}}""",
            // Invalid maxTokensField
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","compat":{"maxTokensField":"bad_field"}}""",
            // Reserved reasoningField
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","compat":{"reasoningField":"content"}}""",
            // replayReasoning=true without reasoningField
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","compat":{"replayReasoning":true}}""",
            // Unknown schemaProfile
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","compat":{"schemaProfile":"UNKNOWN"}}""",
            // String boolean instead of JSON boolean
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","compat":{"streamUsage":"true"}}""",
            // Out-of-bounds contextWindow
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"m1","contextWindow":0}]}""",
            // Out-of-bounds maxOutputTokens
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"m1","maxOutputTokens":99999999}]}""",
            // Unknown model field
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"m1","evil":1}]}""",
            // Duplicate model id
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"m1"},{"id":"m1"}]}""",
            // defaultReasoningLevel not in reasoningLevels
            """{"id":"a","name":"A","protocol":"openai-chat","baseUrl":"https://api.example.com/v1","models":[{"id":"m1","reasoningLevels":["low"],"defaultReasoningLevel":"high"}]}""",
            // Non-empty compat on non-openai-chat protocol
            """{"id":"a","name":"A","protocol":"anthropic","baseUrl":"https://api.anthropic.example/v1","compat":{"streamUsage":true}}""",
        )

        invalidPayloads.forEachIndexed { idx, json ->
            assertThrows("Expected failure for invalid payload #$idx: $json", PluginPackageException::class.java) {
                parseProviderTemplate("com.example.bad", "Bad Pack", "1.0", "providers/bad.json", json.toByteArray(Charsets.UTF_8))
            }
        }
    }

    @Test
    fun savedProfileFromTemplateIsIndependentCopyUnaffectedByPluginUpdateDisableOrUninstall(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-presets-isolation").toFile()
        try {
            val catalog = PluginDataCatalog(root, 1)
            val store = OpenAiSettingsStore(root, testCipher())

            // Pre-existing profile with its own API key to prove template never reuses another profile's key.
            store.saveProfile(
                profileId = "existing",
                displayName = "Existing",
                settings = io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings(
                    CHAT_PROVIDER_ID,
                    "https://existing.example/v1",
                    "existing-model",
                ),
                apiKey = "sk-existing-secret",
                activate = true,
            )

            // Install & enable v1 data plugin with one valid openai-chat template, one anthropic template,
            // and one invalid template (isolated without breaking valid templates).
            val v1Zip = dataBundle(
                version = "1.0.0",
                versionCode = 1L,
                entries = mapOf(
                    "providers/good.json" to """
                        {
                          "id": "good",
                          "name": "Good Provider",
                          "protocol": "openai-chat",
                          "baseUrl": "https://api.good.example/v1",
                          "models": [{"id": "good-pro"}, {"id": "good-mini"}],
                          "compat": {
                            "maxTokensField": "max_tokens",
                            "reasoningField": "reasoning_content",
                            "replayReasoning": true,
                            "schemaProfile": "BASIC"
                          }
                        }
                    """.trimIndent(),
                    "providers/claude.json" to """
                        {
                          "id": "claude",
                          "name": "Anthropic Direct",
                          "protocol": "anthropic",
                          "baseUrl": "https://api.anthropic.example/v1",
                          "models": [{"id": "claude-sonnet"}]
                        }
                    """.trimIndent(),
                    "providers/invalid.json" to """
                        {
                          "id": "broken",
                          "name": "Broken",
                          "protocol": "openai-chat",
                          "baseUrl": "https://api.broken.example/v1",
                          "compat": {"maxTokensField": "invalid_field"}
                        }
                    """.trimIndent(),
                ),
            )
            val stagedV1 = catalog.stage(ByteArrayInputStream(v1Zip))
            val installedV1 = catalog.install(stagedV1, stagedV1.sha256, acknowledged = true)
            val enabledV1 = requireNotNull(catalog.setEnabled("com.example.presets", enabled = true, previous = installedV1))
            val loadedV1 = catalog.readProviderTemplates(enabledV1)
            assertEquals(1, loadedV1.errors.size)
            assertEquals(2, loadedV1.templates.size)

            val goodTemplate = loadedV1.templates.single { it.id == "good" }
            val anthropicTemplate = loadedV1.templates.single { it.id == "claude" }
            assertTrue(goodTemplate.supported)
            assertTrue(anthropicTemplate.supported)

            // Prefill editor from template and verify key isolation & target host preview.
            val editor = ProviderSettingsEditor()
            editor.load(store, imageProfile = false)
            assertTrue(editor.applyTemplate(goodTemplate))
            assertTrue(editor.creatingNew)
            assertNull(editor.form.profileId)
            assertFalse(editor.form.canReuseKey)
            assertEquals("api.good.example", editor.form.targetHost)
            assertEquals("Good Provider", editor.form.profileName)
            assertEquals("good-pro", editor.form.model)
            assertEquals(listOf("good-pro", "good-mini"), editor.form.templateModels)
            assertEquals(SchemaProfile.BASIC, editor.form.schemaProfile)
            assertEquals("reasoning_content", editor.form.reasoningField)
            assertTrue(editor.form.replayReasoning)
            assertTrue(editor.form.templateAuthorUnverified)

            // Attempting to save without entering a key MUST fail (never borrows "existing" profile's key).
            val draftSettings = editor.form.settings(CHAT_PROVIDER_ID, image = false)
            assertThrows(ProviderSettingsException::class.java) {
                store.saveProfile(
                    profileId = editor.form.profileId,
                    displayName = editor.form.profileName,
                    settings = draftSettings,
                    apiKey = "",
                    noAuth = false,
                )
            }

            // Save independent profile copy with user-supplied key.
            val savedSummary = store.saveProfile(
                profileId = editor.form.profileId,
                displayName = editor.form.profileName,
                settings = draftSettings,
                apiKey = "sk-good-secret",
                noAuth = false,
                activate = true,
            )

            // Now update the data plugin to v2 attempting to redirect baseUrl to an attacker domain!
            val v2Zip = dataBundle(
                version = "2.0.0",
                versionCode = 2L,
                entries = mapOf(
                    "providers/good.json" to """
                        {
                          "id": "good",
                          "name": "Good Provider",
                          "protocol": "openai-chat",
                          "baseUrl": "https://evil-redirect.example/v1",
                          "models": [{"id": "good-pro"}]
                        }
                    """.trimIndent(),
                ),
            )
            val stagedV2 = catalog.stage(ByteArrayInputStream(v2Zip))
            val installedV2 = catalog.install(stagedV2, stagedV2.sha256, acknowledged = true)
            val enabledV2 = requireNotNull(catalog.setEnabled("com.example.presets", enabled = true, previous = installedV2))
            assertEquals("evil-redirect.example", catalog.readProviderTemplates(enabledV2).templates.single().host)

            // Disable and uninstall the source data plugin completely!
            catalog.setEnabled("com.example.presets", enabled = false, previous = enabledV2)
            assertThrows(PluginPackageException::class.java) {
                catalog.readProviderTemplates(enabledV2)
            }
            catalog.beginDelete("com.example.presets")
            catalog.finishDelete("com.example.presets")

            // Security invariant: saved host profile's baseUrl, compat, and key remain 100% untouched!
            val snapshot = store.snapshotForNewRun()
            assertEquals("https://api.good.example/v1", snapshot.settings.baseUrl)
            assertEquals("good-pro", snapshot.settings.model)
            assertEquals(SchemaProfile.BASIC, snapshot.settings.compat.schemaProfile)
            assertEquals("reasoning_content", snapshot.settings.compat.reasoningField)
            assertTrue(snapshot.settings.compat.replayReasoning)
            assertEquals(savedSummary.id, store.readProfiles().activeProfileId)
            // Verify API key is still the user's original key and never redirected.
            var capturedEndpoint: String? = null
            var capturedToken: String? = null
            store.fetchModels("https://api.good.example/v1", "", profileId = savedSummary.id, noAuth = false) { ep, creds ->
                capturedEndpoint = ep
                capturedToken = creds.accessToken()
                emptyList()
            }
            assertEquals("https://api.good.example/v1", capturedEndpoint)
            assertEquals("sk-good-secret", capturedToken)
        } finally {
            root.deleteRecursively()
        }
    }
}
