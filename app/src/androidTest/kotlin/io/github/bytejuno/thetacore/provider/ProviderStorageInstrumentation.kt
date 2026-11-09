package io.github.bytejuno.thetacore.provider

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import io.github.bytejuno.thetacore.agent.provider.openai.OpenAiProviderSettings
import io.github.bytejuno.thetacore.storage.androidSecretCipher
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Dependency-free device probe. Run only on an owned disposable emulator, synthetic keys only. */
class ProviderStorageInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        val alias = "theta.issue18.test.${UUID.randomUUID()}"
        // Instrumentation runs as the target UID, not the test APK UID.
        val root = File(targetContext.noBackupFilesDir, "issue18-${UUID.randomUUID()}")
        var stage = "cipher"
        try {
            val cipher = androidSecretCipher(alias, "issue18-device-test")
            val store = OpenAiSettingsStore(root, cipher)
            val settings = OpenAiProviderSettings("probe", "https://example.invalid/v1", "synthetic-model")
            val secret = "synthetic-device-key-issue18"
            stage = "initial-write"
            store.replace(settings, secret)
            stage = "initial-read"
            val snapshot = store.snapshotForNewRun()
            check(runBlocking { snapshot.target().credentials.accessToken() } == secret)
            val file = File(root, "${OpenAiSettingsStore.DIRECTORY}/active.bin")
            val original = file.readBytes()
            check(!original.toString(Charsets.ISO_8859_1).contains(secret))
            file.writeBytes(original.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            expectDecryptFailure(store)
            file.writeBytes(original)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            expectDecryptFailure(store)
            check(!KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
            store.replace(settings, "synthetic-replacement")
            check(runBlocking { store.snapshotForNewRun().target().credentials.accessToken() } == "synthetic-replacement")
            check(runBlocking { snapshot.target().credentials.accessToken() } == secret)
            // Exercise the production Context factory without opening/replacing any active profile.
            val factoryRoot = File(root, "factory")
            val scopedContext = object : ContextWrapper(targetContext) {
                override fun getNoBackupFilesDir(): File = factoryRoot
                override fun getFilesDir(): File = error("Backed-up directory must not be used")
            }
            val androidStore = OpenAiSettingsStore.android(scopedContext)
            try {
                androidStore.replace(settings, secret)
                check(File(factoryRoot, "${OpenAiSettingsStore.DIRECTORY}/active.bin").isFile)
                check(runBlocking { androidStore.snapshotForNewRun().target().credentials.accessToken() } == secret)
            } finally { androidStore.clear() }
            result.putString("stream", "PASS issue18: AndroidKeyStore roundtrip, tamper, deleted key fail-closed, explicit repair, immutable credentials, noBackup factory\n")
            finish(-1, result)
        } catch (error: Exception) {
            val failure = (error as? ProviderSettingsException)?.failure?.name ?: error.javaClass.simpleName
            result.putString("stream", "FAIL issue18 provider storage device probe: $stage / $failure (sanitized)\n")
            finish(0, result)
        } finally {
            root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
    private fun expectDecryptFailure(store: OpenAiSettingsStore) {
        try { store.snapshotForNewRun(); error("Expected failure") }
        catch (e: ProviderSettingsException) {
            check(e.failure == ProviderSettingsException.Failure.DECRYPT_FAILED)
            check(e.cause == null && e.suppressed.isEmpty())
        }
    }
}
