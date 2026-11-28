package io.github.bytejuno.thetacore.web

import io.github.bytejuno.thetacore.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class WebSettingsStoreTest {
    private val config = WebSearchSettings(SearchBackend.BRAVE, WebSearchSettings.BRAVE_ENDPOINT, "super-secret-fixture")
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun cipher(): SecretCipher = key().let { key -> AesGcmSecretCipher("test-web") { key } }
    private suspend fun opened(root: File, cipher: SecretCipher, block: suspend (WebSearchSettingsStore) -> Unit) {
        val job = SupervisorJob()
        try { block(WebSearchSettingsStore(root, cipher, CoroutineScope(job + Dispatchers.IO))) }
        finally { job.cancelAndJoin() }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected settings failure") }
        catch (e: WebFailure) { assertEquals(WebError.SETTINGS, e.reason) }
    }

    @Test fun existingEncryptedFormatSurvivesColdStartAndUsesFreshIv() = runBlocking<Unit> {
        val root = Files.createTempDirectory("web-legacy-test").toFile()
        try {
            val cipher = cipher(); val file = root.resolve("web-search.bin")
            val legacy = cipher.encrypt("""{"version":1,"backend":"BRAVE","endpoint":"${config.endpoint}","key":"${config.key}"}""".toByteArray())
            file.writeBytes(legacy)
            opened(root, cipher) { store ->
                assertEquals(config.key, store.read().value!!.key)
                assertArrayEquals(legacy, file.readBytes())
                store.save(config)
                assertFalse(legacy.contentEquals(file.readBytes()))
                assertFalse(file.readText().contains(config.key))
            }
            opened(root, cipher) { store -> assertEquals(config.key, store.read().value!!.key) }
            opened(root, cipher()) { store -> assertTrue(store.read().unreadable) }
            assertFalse(config.toString().contains(config.key))
            assertThrows(IllegalArgumentException::class.java) { WebSearchSettings(SearchBackend.BRAVE, "https://evil.test", "key") }
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptAndOversizedRecordsStayUntouchedUntilExplicitReplacement() = runBlocking {
        val root = Files.createTempDirectory("web-corruption-test").toFile()
        try {
            val cipher = cipher(); val file = root.resolve("web-search.bin")
            for (damaged in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), ByteArray(32 * 1024 + 1) { 7 })) {
                file.writeBytes(damaged)
                opened(root, cipher) { store ->
                    assertTrue(store.read().unreadable)
                    rejected { store.save(config) }; rejected { store.save(null) }
                    assertArrayEquals(damaged, file.readBytes())
                    store.save(config, replaceUnreadable = true)
                    assertEquals(config.key, store.read().value!!.key)
                }
                opened(root, cipher) { store -> assertEquals(config.key, store.read().value!!.key) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedEncryptionPreservesPreviousRecordAndCanBeRetried() = runBlocking {
        val root = Files.createTempDirectory("web-write-test").toFile()
        try {
            val cipher = cipher(); val file = root.resolve("web-search.bin")
            opened(root, cipher) { it.save(config) }
            val valid = file.readBytes()
            val failing = object : SecretCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray = throw IOException("synthetic write failure")
                override fun decrypt(ciphertext: ByteArray) = cipher.decrypt(ciphertext)
            }
            opened(root, failing) { store -> rejected { store.save(config) }; assertEquals(config.key, store.read().value!!.key) }
            assertArrayEquals(valid, file.readBytes())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".tmp") })
            opened(root, cipher) { store -> store.save(config); assertEquals(config.key, store.read().value!!.key) }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidKeyCanBeClearedThenReconfiguredAfterColdStartWithoutReadOrClearRecovery() = runBlocking {
        val root = Files.createTempDirectory("web-recovery-test").toFile()
        try {
            var invalid = false; var deletes = 0; var creates = 0; var stored: SecretKey? = key()
            val keys = object : SecretKeySource {
                override fun load(create: Boolean): SecretKey {
                    if (invalid) throw UnusableSecretKeyException("synthetic invalidated key")
                    if (stored == null && create) { stored = key(); creates++ }
                    return checkNotNull(stored)
                }
                override fun delete() { deletes++; stored = null; invalid = false }
            }
            fun recovering() = KeyStoreSecretCipher("test-web", keys, allowKeyRecovery = true, classifyNode = {
                if (it is UnusableSecretKeyException) KeyFailureKind.DEFINITIVE_INVALIDATION else KeyFailureKind.UNKNOWN
            })
            opened(root, recovering()) { it.save(config) }
            invalid = true
            opened(root, recovering()) { store ->
                assertTrue(store.read().unreadable); assertEquals(0, deletes); assertEquals(0, creates)
                rejected { store.save(config) }
                store.save(null, replaceUnreadable = true)
                assertNull(store.read().value); assertFalse(store.read().unreadable)
                assertEquals(0, deletes); assertEquals(0, creates)
            }
            opened(root, recovering()) { store ->
                assertNull(store.read().value); assertFalse(store.read().unreadable)
                assertEquals(0, deletes); assertEquals(0, creates)
                store.save(config)
                assertEquals(1, deletes); assertEquals(1, creates)
                assertEquals(config.key, store.read().value!!.key)
            }
            opened(root, recovering()) { assertEquals(config.key, it.read().value!!.key) }
        } finally { root.deleteRecursively() }
    }

    @Test fun transientKeyFailureNeverRotatesKeyOrOverwritesUnreadableRecord() = runBlocking {
        val root = Files.createTempDirectory("web-transient-test").toFile()
        try {
            val key = key(); var failKey = false; var deletes = 0
            val keys = object : SecretKeySource {
                override fun load(create: Boolean): SecretKey {
                    if (failKey) throw IOException("synthetic transient key failure")
                    return key
                }
                override fun delete() { deletes++ }
            }
            val cipher = KeyStoreSecretCipher("test-web", keys, true, classifyNode = { KeyFailureKind.NON_RECOVERABLE })
            opened(root, cipher) { it.save(config) }
            val before = root.resolve("web-search.bin").readBytes(); failKey = true
            opened(root, cipher) { store ->
                assertTrue(store.read().unreadable)
                rejected { store.save(config, replaceUnreadable = true) }
            }
            assertEquals(0, deletes); assertArrayEquals(before, root.resolve("web-search.bin").readBytes())
        } finally { root.deleteRecursively() }
    }
}
