package io.github.bytejuno.thetacore.provider

import android.content.ContextWrapper
import io.github.bytejuno.thetacore.agent.protocol.ModelEvent
import io.github.bytejuno.thetacore.agent.provider.openai.*
import io.github.bytejuno.thetacore.agent.transport.*
import io.github.bytejuno.thetacore.storage.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

class OpenAiSettingsStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val secret = "synthetic-key-issue18"
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun cipher() = key().let { k -> AesGcmSecretCipher("theta-provider-credentials-v1") { k } }
    private fun settings(n: Int = 1) = OpenAiProviderSettings("provider$n", "https://example.invalid/v$n", "model$n",
        OpenAiParameters(n, 0.5, 0.8, "high", false, listOf("stop$n")),
        OpenAiChatConfig.Compat("max_tokens", "reasoning_content", true, false, SchemaProfile.BASIC))
    private fun record(root: File) = File(root, "${OpenAiSettingsStore.DIRECTORY}/active.bin")
    private fun token(snapshot: OpenAiRunSnapshot) = runBlocking {
        snapshot.target(ModelTransport { _, _, _ -> throw AssertionError("Token lookup must not use transport") })
            .credentials.accessToken()
    }
    private fun failure(expected: ProviderSettingsException.Failure, action: () -> Unit): ProviderSettingsException {
        try { action(); fail("Expected sanitized failure") } catch (error: ProviderSettingsException) {
            assertEquals(expected, error.failure)
            assertNull(error.cause)
            assertEquals(0, error.suppressed.size)
            assertFalse(error.stackTraceToString().contains(secret))
            return error
        }
        throw AssertionError()
    }

    @Test fun cacheCompatSurvivesEncryptedStoreAndControlsWire() = runBlocking {
        val root = temp.newFolder()
        val c = cipher()
        val store = OpenAiSettingsStore(root, c)
        val base = settings()
        val keys = mutableListOf<String>()
        for (enabled in listOf(false, true)) {
            store.replace(OpenAiProviderSettings(base.providerId, base.baseUrl, base.model, base.parameters,
                OpenAiChatConfig.Compat(explicitCacheControl = enabled)), secret)
            val snapshot = OpenAiSettingsStore(root, c).snapshotForNewRun()
            assertEquals(enabled, snapshot.settings.compat.explicitCacheControl)
            var submitted = false
            val target = snapshot.target(ModelTransport { _, body, credential -> flow {
                submitted = true
                assertEquals(secret, credential)
                assertEquals(enabled, body.contains("cache_control"))
                emit(TransportEvent.Headers(200, "text/event-stream", null))
                emit(TransportEvent.Bytes(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n").toByteArray()))
            } })
            keys += target.provider.contextConfigurationKey()
            val request = snapshot.request { messages = listOf(io.github.bytejuno.thetacore.agent.protocol.message(
                io.github.bytejuno.thetacore.agent.protocol.Role.USER,
                io.github.bytejuno.thetacore.agent.protocol.textPart("cached"), cacheBreakpoint = true)) }
            assertTrue(target.provider.chat(request, target.credentials).toList().last() is ModelEvent.Finished)
            assertTrue(submitted)
        }
        assertNotEquals(keys[0], keys[1])
        val encoded = Json.parseToJsonElement(ProviderSettingsCodec.encode(base, secret).toString(Charsets.UTF_8)).jsonObject
        val legacy = JsonObject(encoded + ("compat" to JsonObject(encoded.getValue("compat").jsonObject - "explicitCacheControl")))
        record(root).writeBytes(c.encrypt(legacy.toString().toByteArray()))
        assertFalse(OpenAiSettingsStore(root, c).snapshotForNewRun().settings.compat.explicitCacheControl)
    }

    @Test fun roundTripAndBackupBoundary() {
        val root = temp.newFolder("no_backup")
        val files = temp.newFolder("files")
        val c = cipher()
        val store = OpenAiSettingsStore(root, c)
        store.replace(settings(), secret)
        val before = record(root).readBytes()
        val snapshot = OpenAiSettingsStore(root, c).snapshotForNewRun()
        assertEquals(secret, token(snapshot))
        assertEquals("model1", snapshot.request().model)
        assertEquals(listOf("stop1"), snapshot.request().stopSequences)
        assertEquals("max_tokens", snapshot.settings.compat.maxTokensField)
        assertEquals(SchemaProfile.BASIC, snapshot.settings.compat.schemaProfile)
        assertFalse(before.toString(Charsets.ISO_8859_1).contains(secret))
        assertEquals(0, files.listFiles()!!.size)
        assertEquals(listOf("active.bin"), record(root).parentFile.list()!!.toList())
        assertFalse(store.toString().contains(root.path))
        store.replace(settings(), secret)
        assertFalse(before.contentEquals(record(root).readBytes())) // fresh nonce
        store.clear()
        failure(ProviderSettingsException.Failure.NOT_CONFIGURED) { store.snapshotForNewRun() }
        assertEquals(secret, token(snapshot)) // admitted run survives deletion
    }

    @Test fun tamperTruncationWrongKeyAndNamespaceFailClosed() {
        val root = temp.newFolder()
        val c = cipher()
        val store = OpenAiSettingsStore(root, c)
        store.replace(settings(), secret)
        val original = record(root).readBytes()
        for (bytes in listOf(original.copyOf(10), original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })) {
            record(root).writeBytes(bytes)
            failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { store.snapshotForNewRun() }
        }
        record(root).writeBytes(original)
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { OpenAiSettingsStore(root, cipher()).snapshotForNewRun() }
        val k = key()
        val first = OpenAiSettingsStore(root, AesGcmSecretCipher("first") { k })
        first.replace(settings(), secret)
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) {
            OpenAiSettingsStore(root, AesGcmSecretCipher("second") { k }).snapshotForNewRun()
        }
    }

    @Test fun missingAndInvalidatedKeysNeverRegenerateOnRead() {
        val root = temp.newFolder()
        val k = key()
        var available = true
        val creates = mutableListOf<Boolean>()
        val store = OpenAiSettingsStore(root, AesGcmSecretCipher("test") { create ->
            creates += create
            if (!available) throw java.security.InvalidKeyException(secret)
            k
        })
        store.replace(settings(), secret)
        available = false
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { store.snapshotForNewRun() }
        assertEquals(listOf(true, false), creates)
        val previous = record(root).readBytes()
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(2), "new-key") }
        assertArrayEquals(previous, record(root).readBytes())
        available = true
        assertEquals(secret, token(store.snapshotForNewRun()))
    }

    @Test fun permanentlyUnusableExistingKeyFailsAdmissionAndReplacementEvenAfterClear() {
        val root = temp.newFolder()
        val existingKey = key()
        var invalidated = false
        val lookups = mutableListOf<Boolean>()
        val store = OpenAiSettingsStore(root, AesGcmSecretCipher("test") { create ->
            lookups += create
            // Model an alias that still exists but cannot be used, not a missing key.
            // A write's create=true must not make this permanent failure recoverable.
            if (invalidated) throw java.security.InvalidKeyException("invalidated alias: $secret")
            existingKey
        })
        var transportCalls = 0
        val transport = ModelTransport { _, _, _ ->
            transportCalls++
            throw AssertionError("Failed admission must not invoke transport")
        }
        fun admitAndSubmit() = runBlocking { submit(store.snapshotForNewRun(), transport) }
        store.replace(settings(), secret)
        val before = record(root).readBytes()
        invalidated = true
        val readError = failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { admitAndSubmit() }
        assertEquals("Provider credentials could not be decrypted; credential storage may be unavailable. " +
            "Re-entering the API key may not restore access", readError.message)
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(2), secret) }
        assertArrayEquals(before, record(root).readBytes())
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { admitAndSubmit() }
        store.clear() // Removes the record, not the unusable alias.
        failure(ProviderSettingsException.Failure.NOT_CONFIGURED) { admitAndSubmit() }
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(2), secret) }
        failure(ProviderSettingsException.Failure.NOT_CONFIGURED) { admitAndSubmit() }
        assertFalse(record(root).exists())
        assertEquals(listOf(true, false, true, false, true), lookups)
        assertEquals(0, transportCalls)
    }

    // Real store -> snapshot -> target/request -> provider composition, without an app startup.
    // This is not production UI/model-entry wiring; that integration is still outstanding.
    private suspend fun submit(snapshot: OpenAiRunSnapshot, transport: ModelTransport): List<ModelEvent> {
        val target = snapshot.target(transport)
        return target.provider.chat(snapshot.request(), target.credentials).toList()
    }

    @Test fun persistedUpdatesDuringActiveRequestOnlyAffectNewRunAdmission() = runBlocking {
        withTimeout(10_000) {
            val root = temp.newFolder()
            val c = cipher()
            val store = OpenAiSettingsStore(root, c)
            store.replace(settings(), "key1")
            val admitted = store.snapshotForNewRun()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val observed = mutableListOf<Int>()
            val transport = ModelTransport { url, body, token -> flow {
                val n = when (observed.size) { 0, 1 -> 1; else -> 2 }
                assertEquals("https://example.invalid/v$n/chat/completions", url)
                assertEquals("key$n", token)
                val json = Json.parseToJsonElement(body).jsonObject
                assertEquals("model$n", json.getValue("model").jsonPrimitive.content)
                assertEquals(n, json.getValue("max_tokens").jsonPrimitive.int)
                assertEquals(0.5, json.getValue("temperature").jsonPrimitive.double, 0.0)
                assertEquals(0.8, json.getValue("top_p").jsonPrimitive.double, 0.0)
                assertEquals("high", json.getValue("reasoning_effort").jsonPrimitive.content)
                assertFalse(json.containsKey("parallel_tool_calls")) // No tools: codec omits this field.
                assertEquals("stop$n", json.getValue("stop").jsonArray.single().jsonPrimitive.content)
                assertFalse(body.contains("key1"))
                assertFalse(body.contains("key2"))
                observed += n
                if (observed.size == 1) {
                    entered.complete(Unit)
                    resume.await()
                }
                emit(TransportEvent.Headers(200, "text/event-stream", null))
                emit(TransportEvent.Bytes(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n").toByteArray()))
            } }
            val running = async {
                try { submit(admitted, transport) }
                finally {
                    if (!entered.isCompleted) entered.completeExceptionally(
                        AssertionError("Request ended before reaching the transport suspension"))
                }
            }
            entered.await() // First request is suspended inside the transport.
            assertFalse(running.isCompleted)
            OpenAiSettingsStore(root, c).replace(settings(2), "key2")
            val nextRun = store.snapshotForNewRun()
            assertEquals("provider2", nextRun.target(transport).id)
            assertEquals("model2", nextRun.request().model)
            assertEquals("key2", token(nextRun))
            resume.complete(Unit)
            assertTrue(running.await().last() is ModelEvent.Finished)
            // A subsequent attempt of the admitted run must still use the original snapshot.
            assertEquals("provider1", admitted.target(transport).id)
            assertTrue(submit(admitted, transport).last() is ModelEvent.Finished)
            assertTrue(submit(nextRun, transport).last() is ModelEvent.Finished)
            assertEquals(listOf(1, 1, 2), observed)
        }
    }

    @Test fun failuresRetainNeitherRawCausesNorPlaintextAndFailedWritePreservesRecord() {
        val root = temp.newFolder()
        val good = cipher()
        val store = OpenAiSettingsStore(root, good)
        store.replace(settings(), secret)
        val before = record(root).readBytes()
        val bad = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalStateException(secret)
            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalStateException(secret)
        }
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { OpenAiSettingsStore(root, bad).replace(settings(), secret) }
        failure(ProviderSettingsException.Failure.DECRYPT_FAILED) { OpenAiSettingsStore(root, bad).snapshotForNewRun() }
        assertArrayEquals(before, record(root).readBytes())
        record(root).writeBytes(good.encrypt("{\"key\":\"$secret\"}".toByteArray()))
        failure(ProviderSettingsException.Failure.INVALID_DATA) { store.snapshotForNewRun() }
        assertEquals(listOf("active.bin"), record(root).parentFile.list()!!.toList())
    }

    @Test fun concurrentReadersAndWritersNeverMixConfigurationAndKeys() {
        val root = temp.newFolder()
        val c = cipher()
        val store = OpenAiSettingsStore(root, c)
        store.replace(settings(), "key1")
        val admitted = store.snapshotForNewRun()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0..3).map { worker -> pool.submit {
                gate.await()
                val handle = OpenAiSettingsStore(root, c)
                repeat(30) { index ->
                    if (worker % 2 == 0) {
                        val n = 1 + index % 2
                        handle.replace(settings(n), "key$n")
                    } else {
                        val snapshot = handle.snapshotForNewRun()
                        val n = snapshot.settings.model.removePrefix("model")
                        assertEquals("provider$n", snapshot.settings.providerId)
                        assertEquals("https://example.invalid/v$n", snapshot.settings.baseUrl)
                        assertEquals(n.toInt(), snapshot.request().maxOutputTokens)
                        assertEquals("key$n", token(snapshot))
                    }
                }
            } }
            gate.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals("key1", token(admitted))
        assertEquals("model1", admitted.request().model)
    }

    @Test fun abandonedTempCiphertextIsRemovedByReplaceAndClear() {
        val root = temp.newFolder()
        val store = OpenAiSettingsStore(root, cipher())
        store.replace(settings(), secret)
        val directory = record(root).parentFile
        fun abandon() = File(directory, "abandoned.tmp").also { it.writeBytes(record(root).readBytes()) }
        abandon()
        store.replace(settings(2), "new-key")
        assertEquals(listOf("active.bin"), directory.list()!!.toList())
        abandon()
        store.clear()
        assertEquals(0, directory.list()!!.size)
    }

    @Test fun androidFactoryUsesOnlyNoBackupDirectoryWithoutEagerKeyLookup() {
        val root = temp.newFolder("android-no-backup")
        val context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = root
            override fun getFilesDir(): File = throw AssertionError("Must not use backed-up filesDir")
        }
        val store = OpenAiSettingsStore.android(context)
        failure(ProviderSettingsException.Failure.NOT_CONFIGURED) { store.snapshotForNewRun() }
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun oversizedAndNonRegularRecordsFailWithoutLeakingPaths() {
        val root = temp.newFolder(secret)
        val store = OpenAiSettingsStore(root, cipher())
        record(root).parentFile.mkdirs()
        record(root).mkdir()
        failure(ProviderSettingsException.Failure.READ_FAILED) { store.snapshotForNewRun() }
        failure(ProviderSettingsException.Failure.WRITE_FAILED) { store.replace(settings(), secret) }
        record(root).delete()
        record(root).writeBytes(ByteArray(OpenAiSettingsStore.MAX_BYTES + 1))
        failure(ProviderSettingsException.Failure.READ_FAILED) { store.snapshotForNewRun() }
    }
}
