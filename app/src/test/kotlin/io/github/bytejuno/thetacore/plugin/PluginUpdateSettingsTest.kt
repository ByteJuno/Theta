package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Production settings + update transaction, with an injected JVM key (no AndroidKeyStore). */
class PluginUpdateSettingsTest {
    private val id = "test.update_settings"
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val signers = PluginSigners(listOf("a".repeat(64)))

    private inner class Fixture(val root: File) {
        val data = root.resolve("plugin-data/$id")
        val snapshot = root.resolve("plugin-snapshots/$id/1")
        val store = fixturePluginStore(root, root.resolve("cache"), object : PluginSignatureVerifier {
            override fun verify(file: File) = signers
        })
        val updates = PluginUpdates(root, store, { 1_000L })
        fun settings() = PluginSettingsStorage(data, AesGcmPluginSettingsCipher(id) { key })
        fun secretBytes(directory: File = data) = directory.resolve("${PluginSettingsStorage.DIRECTORY}/secrets.bin").readBytes()
        fun publish(version: Long) = store.install(store.stage(ByteArrayInputStream(apk(version))))
        fun recover(interrupted: Boolean = false) = updates.recover(id, interrupted, false) {}
        fun initial(): PluginSettingsStorage {
            publish(1)
            return settings().also {
                it.prefs.put("theme", "before")
                it.secrets.put("token", "before-secret")
                it.close()
            }
        }
        fun activate(codeOnly: Boolean = false) {
            val intent = updates.prepareInstall(id, 1, 2, codeOnly)
            publish(2)
            updates.commitInstall(intent)
            assertFalse(recover())
        }
        fun changed() = settings().use {
            it.prefs.put("theme", "after")
            it.secrets.put("token", "after-secret")
            it.prefs.put("new", "new-pref")
            it.secrets.put("new", "new-secret")
        }
        fun assertBefore() = settings().use {
            assertEquals("before", it.prefs.get("theme"))
            assertEquals("before-secret", it.secrets.get("token"))
            assertNull(it.prefs.get("new"))
            assertNull(it.secrets.get("new"))
        }
        fun assertAfter() = settings().use {
            assertEquals("after", it.prefs.get("theme"))
            assertEquals("after-secret", it.secrets.get("token"))
            assertEquals("new-pref", it.prefs.get("new"))
            assertEquals("new-secret", it.secrets.get("new"))
        }
    }

    private fun fixture(block: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("theta-update-settings").toFile()
        try { Fixture(root).block() } finally { deleteFixtureRoot(root) }
    }

    private fun apk(version: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("assets/theta-plugin.json"))
            zip.write(
                """{"schema":1,"type":"dex","id":"$id","name":"Settings","version":"$version","versionCode":$version,"apiLevel":1,"entry":"test.Entry"}""".toByteArray(),
            )
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test fun snapshotManualRollbackRestoresPrefsAndDecryptableOriginalCiphertext() = fixture {
        val closedOwner = initial()
        val original = secretBytes()
        activate()
        assertArrayEquals(original, secretBytes(snapshot))
        changed()
        updates.started(id, 2)
        updates.requestRollback(id)
        assertTrue(recover())
        assertArrayEquals(original, secretBytes())
        assertBefore()
        assertThrows(PluginClosedException::class.java) { closedOwner.secrets.put("token", "stale") }
        updates.acknowledgeRecovery(id)
        assertBefore()
    }

    @Test fun failedFirstStartRestoresBothSettingsStores() = fixture {
        initial()
        activate()
        changed()
        assertTrue(updates.failed(id, 2))
        assertAfter() // No restoration in the failed plugin's process.
        assertTrue(recover())
        assertBefore()
        updates.acknowledgeRecovery(id)
        assertBefore()
    }

    @Test fun codeOnlyManualRollbackPreservesPrefsAndExactChangedCiphertext() = fixture {
        initial()
        activate(codeOnly = true)
        // Emulate a leftover snapshot from an interrupted older transaction.
        copyPluginTree(data, snapshot)
        changed()
        val changedCiphertext = secretBytes()
        updates.started(id, 2)
        updates.requestRollback(id)
        assertTrue(recover())
        assertAfter()
        assertArrayEquals(changedCiphertext, secretBytes())
        updates.acknowledgeRecovery(id)
        assertFalse(snapshot.exists())
        assertAfter()
    }

    @Test fun codeOnlyInterruptedFirstStartAndReceiptReplayPreserveSettings() = fixture {
        initial()
        activate(codeOnly = true)
        copyPluginTree(data, snapshot)
        changed()
        val changedCiphertext = secretBytes()
        assertTrue(recover(interrupted = true))
        assertAfter()
        assertArrayEquals(changedCiphertext, secretBytes())
        settings().use { it.secrets.put("token", "after-recovery") }
        assertTrue(PluginUpdates(root, store).recover(id, true, false) {})
        settings().use { assertEquals("after-recovery", it.secrets.get("token")) }
        updates.acknowledgeRecovery(id)
        settings().use { assertEquals("after-recovery", it.secrets.get("token")) }
    }

    @Test fun clearDataRecoveryRemovesSettingsAndPolicyWithoutNeedingValidPolicy() = fixture {
        initial()
        activate(codeOnly = true)
        changed()
        val policy = root.resolve("plugin-updates/install-intents/$id.properties")
        policy.parentFile.mkdirs()
        policy.writeText("dataRestorable=invalid\n")
        store.delete(id, clearData = true)
        updates.forget(id)
        assertFalse(data.exists())
        assertFalse(policy.exists())
        assertNull(updates.read(id))
        settings().use {
            assertNull(it.prefs.get("theme"))
            assertNull(it.secrets.get("token"))
            it.secrets.put("token", "fresh-data-same-key")
            assertEquals("fresh-data-same-key", it.secrets.get("token"))
        }
    }
}
