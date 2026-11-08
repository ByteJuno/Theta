package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Production copy/stage helpers + real stores/filesystem. Sources only, NOT COMPILED/NOT TESTED. */
class PluginImportSourceTest {
    private fun bundle(data: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(if (data) "theta-plugin.json" else "assets/theta-plugin.json"))
            val entry = if (data) "" else ",\"entry\":\"test.Entry\""
            zip.write("""{"schema":1,"type":"${if (data) "data" else "dex"}","id":"test.import","name":"Import","version":"1","versionCode":1,"apiLevel":1$entry}""".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }
    private fun root(action: (File) -> Unit) {
        val root = Files.createTempDirectory("theta-import-regression").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun code(root: File) = PluginStore(root, File(root, "cache"),
        PluginSignatureVerifier { PluginSigners(listOf("a".repeat(64))) })
    private fun assertNoStages(root: File) {
        assertTrue(File(root, "plugin-data-staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "cache/plugin-staging").listFiles().orEmpty().isEmpty())
    }
    private fun await(latch: CountDownLatch) { assertTrue(latch.await(2, TimeUnit.SECONDS)) }
    private fun originalIOException(expected: IOException, actual: IOException): IOException {
        // Coroutine stack recovery may copy the exception at await/stagePluginCopy. Assert the
        // observed type/message AND the original identity in its cause chain, with recovery on.
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        val chain = generateSequence<Throwable>(actual) { it.cause }.take(16).toList()
        assertTrue("Original IOException missing from recovered cause chain", chain.any { it === expected })
        return expected
    }

    @Test fun sharedGateUsableDuringBlockedReadForBothImportKinds() = root { root -> runBlocking {
        for (data in listOf(false, true)) {
            val gate = Mutex()
            val entered = CountDownLatch(1)
            val allow = CountDownLatch(1)
            val bytes = bundle(data)
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown(); allow.await()
                    return super.read(buffer, offset, length)
                }
            }
            val copy = PluginImportSourcePool(1).start(File(root, "sources"), 4096, { input })
            val catalog = PluginDataCatalog(root, 1)
            val store = code(root)
            var staged: Any? = null
            val job = async(Dispatchers.Default) {
                stagePluginCopy<Any>(copy, serialized = { action -> gate.withLock { action() } },
                    stage = { file ->
                        if (data) stagePluginInput({ file.inputStream() }, catalog::stage, catalog::discard)
                        else stagePluginInput({ file.inputStream() }, store::stage, { Files.delete(it.file.toPath()) })
                    },
                    discard = { value ->
                        if (value is StagedPluginData) catalog.discard(value)
                        else Files.delete((value as StagedPlugin).file.toPath())
                    }, publish = { staged = it; true })
            }
            try {
                await(entered)
                // This is the same serialized callback that will perform the actual store mutation.
                withTimeout(2_000) { gate.withLock { assertNull(staged) } }
                assertFalse(job.isCompleted)
            } finally { allow.countDown() }
            withTimeout(2_000) { job.await(); copy.finished.await() }
            gate.withLock {
                val value = checkNotNull(staged)
                if (value is StagedPluginData) catalog.discard(value)
                else Files.delete((value as StagedPlugin).file.toPath())
            }
            assertNoStages(root)
            assertTrue(File(root, "sources").listFiles()!!.isEmpty())
        }
    } }

    @Test fun cancelWaiterReachesActiveCloseWithoutHoldingMutationGate() = root { root -> runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val copy = PluginImportSourcePool(1).start(File(root, "sources"), 32, {
            object : InputStream() {
                override fun read(): Int { reading.countDown(); closed.await(); return -1 }
                override fun close() { closed.countDown() }
            }
        })
        val gate = Mutex()
        // ATOMIC: a cancellation that lands before the waiter is dispatched must still reach
        // stagePluginCopy's cancel path (production creates the copy inside a running coroutine).
        val waiter = launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            stagePluginCopy<Any>(copy, { action -> gate.withLock { action() } },
                { error("cancelled source must never stage") }, {}, { false })
        }
        await(reading)
        waiter.cancel()
        withTimeout(2_000) { waiter.join(); copy.finished.await(); gate.withLock { } }
        await(closed)
        assertTrue(File(root, "sources").listFiles().orEmpty().isEmpty())
    } }

    @Test fun lateOpenIsClosedAndAdmissionStaysBoundedUntilActualExit() = root { root -> runBlocking {
        val pool = PluginImportSourcePool(1)
        val opening = CountDownLatch(1)
        val allowOpen = CountDownLatch(1)
        val closed = AtomicInteger()
        val copy = pool.start(File(root, "sources"), 32, {
            opening.countDown(); allowOpen.await()
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closed.incrementAndGet() }
            }
        })
        await(opening)
        copy.cancel(); copy.release()
        try {
            try { copy.await(); fail("cancel must wake waiter before provider returns") }
            catch (_: CancellationException) { }
            repeat(50) {
                assertThrows(PluginPackageException::class.java) {
                    pool.start(File(root, "sources"), 32, { error("admission should reject") })
                }
            }
            assertEquals(0, closed.get())
        } finally { allowOpen.countDown() }
        withTimeout(2_000) { copy.finished.await() }
        assertEquals(1, closed.get())
        assertTrue(File(root, "sources").listFiles().orEmpty().isEmpty())
        val next = pool.start(File(root, "sources"), 32, { byteArrayOf(2).inputStream() })
        assertArrayEquals(byteArrayOf(2), withTimeout(2_000) { next.await() }.readBytes())
        next.release()
        withTimeout(2_000) { next.finished.await() }
    } }

    @Test fun stuckCloseCannotCreateMoreWorkersOrBlockOtherOperations() = root { root -> runBlocking {
        val pool = PluginImportSourcePool(1)
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val copy = pool.start(File(root, "sources"), 32, {
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closing.countDown(); allowClose.await() }
            }
        })
        try {
            await(closing)
            copy.cancel(); copy.release()
            assertThrows(PluginPackageException::class.java) {
                pool.start(File(root, "sources"), 32, { byteArrayOf().inputStream() })
            }
            withTimeout(2_000) { Mutex().withLock { } }
            assertFalse(copy.finished.isCompleted)
        } finally { allowClose.countDown() }
        withTimeout(2_000) { copy.finished.await() }
    } }

    @Test fun providerCloseFailureCannotReachEitherStore() = root { root -> runBlocking {
        for (data in listOf(false, true)) {
            val failure = IOException("provider close")
            val copy = PluginImportSourcePool(1).start(File(root, "sources"), 4096, {
                object : ByteArrayInputStream(bundle(data)) { override fun close() { throw failure } }
            })
            val catalog = PluginDataCatalog(root, 1)
            val store = code(root)
            try {
                stagePluginCopy<Any>(copy, { it() },
                    { file -> if (data) catalog.stage(file.inputStream()) else store.stage(file.inputStream()) },
                    { error("no stage may exist") }, { error("no publication") })
                fail("close failure lost")
            } catch (error: IOException) {
                val original = originalIOException(failure, error)
                assertTrue(original.suppressed.isEmpty())
            }
            withTimeout(2_000) { copy.finished.await() }
            assertNoStages(root)
            assertTrue(File(root, "sources").listFiles()!!.isEmpty())
        }
    } }

    @Test fun localInputCloseFailureDiscardsRegisteredDataStageAndCodeFile() = root { root ->
        for (data in listOf(false, true)) {
            val catalog = PluginDataCatalog(root, 1)
            val store = code(root)
            val failure = IOException("local close")
            var registered: StagedPluginData? = null
            try {
                stagePluginInput<Any>(
                    { object : ByteArrayInputStream(bundle(data)) { override fun close() { throw failure } } },
                    { if (data) catalog.stage(it).also { value -> registered = value } else store.stage(it) },
                    { if (it is StagedPluginData) catalog.discard(it) else Files.delete((it as StagedPlugin).file.toPath()) },
                )
                fail("close failure lost")
            } catch (error: IOException) { assertSame(failure, error) }
            assertNoStages(root)
            registered?.let { value ->
                assertThrows(PluginPackageException::class.java) { catalog.discard(value) }
            }
        }
    }

    @Test fun closeFailureKeepsCleanupFailureSuppressedUsingProductionStageHelper() = root { root ->
        val catalog = PluginDataCatalog(root, 1)
        val failure = IOException("close")
        val cleanup = IOException("discard reporting")
        try {
            stagePluginInput(
                { object : ByteArrayInputStream(bundle(true)) { override fun close() { throw failure } } },
                catalog::stage,
                { catalog.discard(it); throw cleanup },
            )
            fail("missing close failure")
        } catch (error: IOException) {
            assertSame(failure, error)
            assertArrayEquals(arrayOf<Throwable>(cleanup), error.suppressed)
        }
        assertNoStages(root)
    }

    @Test fun cancelDuringSerializedStageDiscardsBeforeOwnershipTransfer() = root { root -> runBlocking {
        val catalog = PluginDataCatalog(root, 1)
        val gate = Mutex()
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val copy = PluginImportSourcePool(1).start(File(root, "sources"), 4096, { bundle(true).inputStream() })
        val job = async(Dispatchers.Default) {
            stagePluginCopy(copy, { action -> gate.withLock { action() } },
                { file ->
                    val value = stagePluginInput({ file.inputStream() }, catalog::stage, catalog::discard)
                    staged.complete(Unit); resume.await(); value
                }, catalog::discard, { error("closed stage published") })
        }
        try {
            withTimeout(2_000) { staged.await() }
            copy.cancel()
        } finally { resume.complete(Unit) }
        assertNull(withTimeout(2_000) { job.await() })
        withTimeout(2_000) { copy.finished.await() }
        assertNoStages(root)
    } }

    @Test fun callerCancellationDuringHostOwnedStageDoesNotLeakPendingEntry() = root { root -> runBlocking {
        val catalog = PluginDataCatalog(root, 1)
        val gate = Mutex()
        val hostJob = kotlinx.coroutines.SupervisorJob()
        val staged = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val copy = PluginImportSourcePool(1).start(File(root, "sources"), 4096, { bundle(true).inputStream() })
        val job = launch(Dispatchers.Default) {
            stagePluginCopy(copy,
                { action -> withContext(hostJob + Dispatchers.Default) { gate.withLock { action() } } },
                { file ->
                    val value = stagePluginInput({ file.inputStream() }, catalog::stage, catalog::discard)
                    staged.complete(Unit); resume.await(); value
                }, catalog::discard, { error("cancelled caller published a stage") })
        }
        try {
            withTimeout(2_000) { staged.await() }
            job.cancel()
        } finally { resume.complete(Unit) }
        try {
            withTimeout(2_000) { job.join(); copy.finished.await() }
            assertNoStages(root)
            assertTrue(File(root, "sources").listFiles()!!.isEmpty())
        } finally { hostJob.cancel() }
    } }

    @Test fun byteLimitAndReadErrorCleanCacheAndPreserveCloseError() = root { root -> runBlocking {
        val pool = PluginImportSourcePool(1)
        val oversized = pool.start(File(root, "sources"), 2, { byteArrayOf(1, 2, 3).inputStream() })
        try { oversized.await(); fail("limit ignored") } catch (_: PluginPackageException) { }
        oversized.release()
        withTimeout(2_000) { oversized.finished.await() }
        val read = IOException("read")
        val close = IOException("close")
        val broken = pool.start(File(root, "sources"), 32, {
            object : InputStream() {
                override fun read(): Int = throw read
                override fun close() { throw close }
            }
        })
        try { broken.await(); fail("read failure lost") }
        catch (error: IOException) {
            val original = originalIOException(read, error)
            assertArrayEquals(arrayOf<Throwable>(close), original.suppressed)
        }
        broken.release()
        withTimeout(2_000) { broken.finished.await() }
        assertTrue(File(root, "sources").listFiles()!!.isEmpty())
    } }
}
