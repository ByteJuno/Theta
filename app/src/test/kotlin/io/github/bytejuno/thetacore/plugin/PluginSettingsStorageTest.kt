package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException
import io.github.bytejuno.thetacore.sdk.PluginPrefs
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

class PluginSettingsStorageTest {
    private val id = "test.settings"
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun cipher(pluginId: String = id) = AesGcmPluginSettingsCipher(pluginId) { key }
    private fun store(data: File, cipher: PluginSettingsCipher = cipher()) = PluginSettingsStorage(data, cipher)
    private fun file(data: File, secret: Boolean = false) =
        data.resolve("${PluginSettingsStorage.DIRECTORY}/${if (secret) "secrets.bin" else "prefs.bin"}")
    private fun withRoot(action: (File, File) -> Unit) {
        val root = Files.createTempDirectory("theta-settings-test").toFile()
        try { action(root, root.resolve("plugin-data/$id")) }
        finally { root.deleteRecursively() }
    }

    @Test fun overwriteDeleteEmptyAndNullPersistAcrossOwners() = withRoot { _, data ->
        store(data).use { owner ->
            assertNull(owner.prefs.get("missing"))
            assertNull(owner.secrets.get("missing"))
            owner.prefs.put("key", "old")
            owner.secrets.put("key", "old-secret")
            owner.prefs.put("key", "new")
            owner.secrets.put("key", "new-secret")
            owner.prefs.put("empty", "")
            owner.secrets.put("empty", "")
        }
        store(data).use { owner ->
            assertEquals("new", owner.prefs.get("key"))
            assertEquals("new-secret", owner.secrets.get("key"))
            assertEquals("", owner.prefs.get("empty"))
            assertEquals("", owner.secrets.get("empty"))
            owner.prefs.put("key", null)
            owner.secrets.remove("key")
            owner.prefs.remove("empty")
            owner.secrets.put("empty", null)
            val before = file(data, true).readBytes()
            owner.secrets.remove("absent")
            assertArrayEquals(before, file(data, true).readBytes())
        }
        store(data).use { owner ->
            assertNull(owner.prefs.get("key"))
            assertNull(owner.secrets.get("key"))
            assertNull(owner.prefs.get("empty"))
            assertNull(owner.secrets.get("empty"))
        }
        assertEquals(setOf(PluginSettingsStorage.DIRECTORY), data.list()!!.toSet())
        assertEquals(setOf("prefs.bin", "secrets.bin"), file(data).parentFile.list()!!.toSet())
    }

    @Test fun arbitraryNamesAreDataNeverPathsAndNamespacesStaySeparate() = withRoot { root, data ->
        val names = listOf("../escape", "../../prefs.bin", "/absolute", "C:\\escape", "a/b", "a\\b", ".", "..", "CON", "nul\u0000key", "换行\n🔐")
        store(data).use { owner ->
            names.forEachIndexed { index, name ->
                owner.prefs.put(name, "p$index")
                owner.secrets.put(name, "s$index")
            }
            names.forEachIndexed { index, name ->
                assertEquals("p$index", owner.prefs.get(name))
                assertEquals("s$index", owner.secrets.get(name))
            }
        }
        assertEquals(2, root.walkTopDown().count { it.isFile })
        assertEquals(setOf("prefs.bin", "secrets.bin"), file(data).parentFile.list()!!.toSet())
    }

    @Test fun utf8LimitsRejectBeforeChangingCommittedValue() = withRoot { _, data ->
        store(data).use { owner ->
            val maxKey = "界".repeat(85) + "x" // 256 UTF-8 bytes.
            val maxValue = "🔐".repeat(PluginPrefs.MAX_VALUE_BYTES / 4)
            owner.prefs.put(maxKey, maxValue)
            owner.secrets.put(maxKey, maxValue)
            assertEquals(maxValue, owner.secrets.get(maxKey))
            val oldPrefs = file(data).readBytes()
            val oldSecrets = file(data, true).readBytes()
            for (badKey in listOf("", maxKey + "x", "\uD800")) {
                assertThrows(IllegalArgumentException::class.java) { owner.prefs.get(badKey) }
                assertThrows(IllegalArgumentException::class.java) { owner.secrets.put(badKey, "x") }
                assertThrows(IllegalArgumentException::class.java) { owner.prefs.remove(badKey) }
            }
            for (badValue in listOf(maxValue + "x", "\uDC00")) {
                assertThrows(IllegalArgumentException::class.java) { owner.prefs.put(maxKey, badValue) }
                assertThrows(IllegalArgumentException::class.java) { owner.secrets.put(maxKey, badValue) }
            }
            assertArrayEquals(oldPrefs, file(data).readBytes())
            assertArrayEquals(oldSecrets, file(data, true).readBytes())
        }
    }

    @Test fun entryAndAggregateByteLimitsPreserveExistingFile() = withRoot { _, data ->
        store(data).use { owner ->
            repeat(PluginPrefs.MAX_ENTRIES) { owner.prefs.put("k$it", "") }
            val before = file(data).readBytes()
            assertThrows(IOException::class.java) { owner.prefs.put("extra", "") }
            assertArrayEquals(before, file(data).readBytes())
            owner.prefs.put("k0", "overwrite-at-capacity")
            owner.prefs.remove("k1")
            owner.prefs.put("extra", "now fits")
            val big = "x".repeat(PluginPrefs.MAX_VALUE_BYTES)
            repeat(15) { owner.secrets.put("k$it", big) }
            val secrets = file(data, true).readBytes()
            assertThrows(IOException::class.java) { owner.secrets.put("overflow", big) }
            assertArrayEquals(secrets, file(data, true).readBytes())
            assertTrue(secrets.size <= PluginPrefs.MAX_STORED_BYTES)
        }
    }

    @Test fun actualCiphertextSizeIncludingOverheadIsCheckedOnWriteAndDelete() = withRoot { _, data ->
        val oversized = AtomicBoolean(false)
        val delegate = cipher()
        val injected = object : PluginSettingsCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = if (oversized.get())
                ByteArray(PluginPrefs.MAX_STORED_BYTES + 1) else delegate.encrypt(plaintext)
            override fun decrypt(ciphertext: ByteArray) = delegate.decrypt(ciphertext)
        }
        store(data, injected).use { owner ->
            owner.secrets.put("keep", "old")
            val before = file(data, true).readBytes()
            oversized.set(true)
            assertThrows(IOException::class.java) { owner.secrets.put("keep", "new") }
            assertThrows(IOException::class.java) { owner.secrets.remove("keep") }
            assertArrayEquals(before, file(data, true).readBytes())
            assertEquals("old", owner.secrets.get("keep"))
        }
    }

    @Test fun malformedTruncatedAndOversizedFilesFailClosedForAllOperations() = withRoot { _, data ->
        store(data).use { owner ->
            owner.prefs.put("k", "v")
            owner.secrets.put("k", "secret")
            val prefs = file(data).readBytes()
            val secrets = file(data, true).readBytes()
            val malformedPrefs = listOf(
                byteArrayOf(), prefs.copyOf(7), prefs.copyOf(prefs.size - 1), prefs + 0.toByte(),
                prefs.clone().also { it[0] = 0 },
                prefs.clone().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) },
                prefs.clone().also { it[it.lastIndex] = 0xff.toByte() },
                prefs.clone().also { ByteBuffer.wrap(it).putInt(4, 2) } + prefs.copyOfRange(8, prefs.size),
                ByteArray(PluginPrefs.MAX_STORED_BYTES + 1),
            )
            for (bad in malformedPrefs) {
                file(data).writeBytes(bad)
                assertThrows(IOException::class.java) { owner.prefs.get("k") }
                assertThrows(IOException::class.java) { owner.prefs.put("k", "new") }
                assertThrows(IOException::class.java) { owner.prefs.remove("k") }
                assertArrayEquals(bad, file(data).readBytes())
            }
            val malformedSecrets = listOf(
                byteArrayOf(), secrets.copyOf(16), secrets.copyOf(secrets.size - 1), secrets + 0.toByte(),
                secrets.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                prefs, // Plaintext is not a migration/fallback format.
                cipher().encrypt(prefs.copyOf(7)), // Authentication passes; framing still fails.
                ByteArray(PluginPrefs.MAX_STORED_BYTES + 1),
            )
            for (bad in malformedSecrets) {
                file(data, true).writeBytes(bad)
                assertThrows(IOException::class.java) { owner.secrets.get("k") }
                assertThrows(IOException::class.java) { owner.secrets.put("k", "new") }
                assertThrows(IOException::class.java) { owner.secrets.remove("k") }
                assertArrayEquals(bad, file(data, true).readBytes())
            }
        }
    }

    @Test fun encryptionFailurePreservesPriorSecretAndLeavesNoTemporaryPlaintext() = withRoot { _, data ->
        val fail = AtomicBoolean(false)
        val delegate = cipher()
        val injected = object : PluginSettingsCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                if (fail.get()) throw GeneralSecurityException("injected")
                return delegate.encrypt(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray) = delegate.decrypt(ciphertext)
        }
        store(data, injected).use { owner ->
            owner.secrets.put("k", "old")
            val before = file(data, true).readBytes()
            fail.set(true)
            assertThrows(IOException::class.java) { owner.secrets.put("k", "new") }
            assertThrows(IOException::class.java) { owner.secrets.remove("k") }
            assertArrayEquals(before, file(data, true).readBytes())
            assertEquals("old", owner.secrets.get("k"))
            assertEquals(listOf("secrets.bin"), file(data, true).parentFile.list()!!.toList())
        }
    }

    @Test fun encryptionIsNondeterministicAndDiskContainsNoRawSecretOrSecretKeyName() = withRoot { _, data ->
        val secret = "unique-secret-value-不是明文-0123456789"
        val name = "private-secret-key-name-0123456789"
        store(data).use { owner ->
            owner.secrets.put(name, secret)
            val first = file(data, true).readBytes()
            owner.secrets.put(name, secret)
            val second = file(data, true).readBytes()
            assertFalse(first.contentEquals(second))
            assertFalse(first.copyOfRange(4, 16).contentEquals(second.copyOfRange(4, 16)))
            assertEquals(secret, owner.secrets.get(name))
            data.walkTopDown().filter { it.isFile }.forEach { stored ->
                val bytes = stored.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains(secret.toByteArray().toString(Charsets.ISO_8859_1)))
                assertFalse(bytes.contains(name))
            }
        }
    }

    @Test fun oldCiphertextSurvivesSnapshotRollbackAndClearDataClearsBothStores() = withRoot { root, data ->
        val snapshot = root.resolve("snapshot")
        val first = store(data)
        first.prefs.put("k", "before")
        first.secrets.put("k", "before-secret")
        first.close()
        copyPluginTree(data, snapshot) // Same production copy helper as PluginUpdates.
        val oldCiphertext = file(snapshot, true).readBytes()
        store(data).use { owner ->
            owner.prefs.put("k", "after")
            owner.secrets.put("k", "after-secret")
            owner.secrets.put("new", "discard-on-rollback")
        }
        deletePluginTree(data.parentFile, data.name)
        Files.move(snapshot.toPath(), data.toPath())
        assertArrayEquals(oldCiphertext, file(data, true).readBytes())
        store(data).use { restored ->
            assertEquals("before", restored.prefs.get("k"))
            assertEquals("before-secret", restored.secrets.get("k"))
            assertNull(restored.secrets.get("new"))
        }
        assertThrows(PluginClosedException::class.java) { first.secrets.put("k", "stale-owner") }
        deletePluginTree(data.parentFile, data.name)
        store(data).use { cleared ->
            assertNull(cleared.prefs.get("k"))
            assertNull(cleared.secrets.get("k"))
            cleared.secrets.put("fresh", "same-key-new-data")
            assertEquals("same-key-new-data", cleared.secrets.get("fresh"))
        }
    }

    @Test fun wrongIdWrongKeyAndMissingKeyNeverDecryptOrReplaceOldData() = withRoot { _, data ->
        store(data).use { it.secrets.put("k", "old") }
        val before = file(data, true).readBytes()
        val wrongKey = SecretKeySpec(ByteArray(32) { 99.toByte() }, "AES")
        val creates = AtomicInteger()
        val missing = AesGcmPluginSettingsCipher(id) { create ->
            if (create) creates.incrementAndGet()
            throw GeneralSecurityException("missing key")
        }
        for (wrong in listOf(cipher("test.other"), AesGcmPluginSettingsCipher(id) { wrongKey }, missing)) {
            store(data, wrong).use { owner ->
                assertThrows(IOException::class.java) { owner.secrets.get("k") }
                assertThrows(IOException::class.java) { owner.secrets.put("k", "new") }
                assertThrows(IOException::class.java) { owner.secrets.remove("k") }
            }
            assertArrayEquals(before, file(data, true).readBytes())
        }
        assertEquals(0, creates.get())
        assertEquals(pluginSettingsKeyAlias(id), pluginSettingsKeyAlias(id))
        assertNotEquals(pluginSettingsKeyAlias(id), pluginSettingsKeyAlias("test.other"))
        assertThrows(Exception::class.java) { pluginSettingsKeyAlias("../escape") }
    }

    @Test fun closeRevokesAllRetainedHandlesIncludingAbsentRemoves() = withRoot { _, data ->
        val owner = store(data)
        owner.prefs.put("k", "v")
        owner.secrets.put("k", "s")
        val prefs = owner.prefs
        val secrets = owner.secrets
        owner.close()
        owner.close()
        assertThrows(PluginClosedException::class.java) { prefs.get("k") }
        assertThrows(PluginClosedException::class.java) { prefs.put("k", "new") }
        assertThrows(PluginClosedException::class.java) { prefs.remove("absent") }
        assertThrows(PluginClosedException::class.java) { secrets.get("k") }
        assertThrows(PluginClosedException::class.java) { secrets.put("k", null) }
        assertThrows(PluginClosedException::class.java) { secrets.remove("absent") }
        store(data).use { reopened ->
            assertEquals("v", reopened.prefs.get("k"))
            assertEquals("s", reopened.secrets.get("k"))
        }
    }

    @Test fun concurrentOwnersNeverLoseDistinctWritesOrObservePartialValues() = withRoot { _, data ->
        val owners = List(4) { store(data) }
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val values = List(4) { "value-$it:" + it.toString().repeat(4096) }
        try {
            val jobs = owners.mapIndexed { index, owner -> pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(12) { iteration ->
                    owner.prefs.put("p$index-$iteration", values[index])
                    owner.secrets.put("s$index-$iteration", values[index])
                    owner.prefs.put("shared", values[index])
                    owner.secrets.put("shared", values[index])
                    assertTrue(owner.prefs.get("shared") in values)
                    assertTrue(owner.secrets.get("shared") in values)
                }
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            repeat(4) { index -> repeat(12) { iteration ->
                assertEquals(values[index], owners[0].prefs.get("p$index-$iteration"))
                assertEquals(values[index], owners[0].secrets.get("s$index-$iteration"))
            } }
        } finally {
            pool.shutdownNow()
            owners.forEach { it.close() }
        }
    }

    @Test fun closeWaitsForAdmittedWriteThenPreventsFurtherWrites() = withRoot { _, data ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = cipher()
        val injected = object : PluginSettingsCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return delegate.encrypt(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray) = delegate.decrypt(ciphertext)
        }
        val owner = store(data, injected)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writing = pool.submit { owner.secrets.put("k", "committed") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = pool.submit { owner.close() }
            release.countDown()
            writing.get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)
            assertThrows(PluginClosedException::class.java) { owner.secrets.put("k", "late") }
            store(data).use { assertEquals("committed", it.secrets.get("k")) }
        } finally {
            release.countDown()
            pool.shutdownNow()
            owner.close()
        }
    }

    @Test fun admissionCloseIsNonblockingQueuedCallsRejectAndWorkerCloseDrains() = withRoot { _, data ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delegate = cipher()
        val injected = object : PluginSettingsCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
                return delegate.encrypt(plaintext)
            }
            override fun decrypt(ciphertext: ByteArray) = delegate.decrypt(ciphertext)
        }
        val owner = store(data, injected)
        val pool = Executors.newFixedThreadPool(2)
        val queued = FutureTask<Unit> {
            assertThrows(PluginClosedException::class.java) { owner.prefs.put("queued", "must-not-write") }
            Unit
        }
        val queuedThread = Thread(queued, "settings-queued-operation")
        val draining = FutureTask<Unit> { owner.close() }
        val drainThread = Thread(draining, "settings-off-main-drain")
        fun awaitBlocked(thread: Thread) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (thread.state != Thread.State.BLOCKED && thread.isAlive && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("Expected storage monitor contention", Thread.State.BLOCKED, thread.state)
        }
        try {
            val writing = pool.submit { owner.secrets.put("admitted", "committed") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queuedThread.start()
            awaitBlocked(queuedThread) // It passed the first check, but has not acquired the lock.
            val admission = pool.submit {
                owner.closeAdmission()
                owner.closeAdmission() // Idempotent, no wait for the cipher latch.
                assertThrows(PluginClosedException::class.java) { owner.prefs.get("absent") }
                assertThrows(PluginClosedException::class.java) { owner.prefs.put("late", "v") }
                assertThrows(PluginClosedException::class.java) { owner.prefs.remove("absent") }
                assertThrows(PluginClosedException::class.java) { owner.secrets.get("admitted") }
                assertThrows(PluginClosedException::class.java) { owner.secrets.put("late", "v") }
                assertThrows(PluginClosedException::class.java) { owner.secrets.remove("absent") }
            }
            admission.get(2, TimeUnit.SECONDS) // Must finish while the admitted cipher still holds the lock.
            assertFalse(writing.isDone)
            drainThread.start()
            awaitBlocked(drainThread)
            assertFalse(draining.isDone)
            release.countDown()
            writing.get(5, TimeUnit.SECONDS)
            queued.get(5, TimeUnit.SECONDS)
            draining.get(5, TimeUnit.SECONDS)
            store(data).use { restored ->
                assertEquals("committed", restored.secrets.get("admitted"))
                assertNull(restored.prefs.get("queued"))
                assertNull(restored.prefs.get("late"))
                assertNull(restored.secrets.get("late"))
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
            queuedThread.join(5000)
            drainThread.join(5000)
            owner.close()
        }
    }

    @Test fun nonDirectoryReservedPathAndNonFileStoreFailClosed() = withRoot { _, data ->
        data.mkdirs()
        val reserved = data.resolve(PluginSettingsStorage.DIRECTORY)
        reserved.writeText("collision")
        store(data).use { owner ->
            assertThrows(IOException::class.java) { owner.prefs.get("k") }
            assertThrows(IOException::class.java) { owner.secrets.put("k", "v") }
            assertEquals("collision", reserved.readText())
            reserved.delete()
            file(data).mkdirs()
            assertThrows(IOException::class.java) { owner.prefs.get("k") }
            assertThrows(IOException::class.java) { owner.prefs.remove("k") }
        }
    }

    @Test fun linkedReservedDirectoryIsRejectedWithoutTouchingOutsideData() = withRoot { root, data ->
        data.mkdirs()
        val outside = root.resolve("outside").apply { mkdirs() }
        val untouched = outside.resolve("prefs.bin").apply { writeText("untouched") }
        val link = data.resolve(PluginSettingsStorage.DIRECTORY).toPath()
        try { Files.createSymbolicLink(link, outside.toPath()) }
        catch (error: Exception) { assumeNoException("Platform cannot create symbolic links", error); return@withRoot }
        try {
            store(data).use { owner ->
                assertThrows(IOException::class.java) { owner.prefs.get("k") }
                assertThrows(IOException::class.java) { owner.secrets.put("k", "v") }
                assertThrows(IOException::class.java) { owner.prefs.remove("k") }
            }
            assertEquals("untouched", untouched.readText())
        } finally { Files.deleteIfExists(link) }
    }
}
