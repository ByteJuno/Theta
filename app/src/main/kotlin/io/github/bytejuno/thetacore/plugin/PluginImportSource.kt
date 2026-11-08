package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** Process-wide, fail-fast admission: no waiting queue and at most two copy + two close threads.
 * A stuck open/read/close retains its permit, even after cancellation. Never use the mutation gate
 * or Dispatchers.IO for provider calls. Interrupt/close cannot guarantee cancellation of provider
 * or kernel IO; exhaustion rejects imports until workers finish (possibly until process restart).
 */
internal class PluginImportSourcePool(private val capacity: Int = 2) {
    private val permits = Semaphore(capacity)
    private val directories = mutableSetOf<File>()
    init { require(capacity > 0) }

    fun start(directory: File, maxBytes: Long, open: () -> InputStream,
        report: (Throwable) -> Unit = {}): PluginImportCopy {
        require(maxBytes > 0)
        if (!permits.tryAcquire()) throw PluginPackageException("Import sources busy; close imports or restart")
        val copy = PluginImportCopy(maxBytes, open, report, releasePermit = { permits.release() }, createFile = {
            // Once per process/root, before creating any current-process file. This is NOT the
            // code/data mutation mutex. Only host cache files are touched, never store stages.
            synchronized(directories) {
                val root = pluginPath(directory.parentFile.canonicalFile, directory.name)
                pluginPath(root, ".")
                Files.createDirectories(root.toPath()) // Android may reclaim an empty cache root.
                if (root !in directories) {
                    Files.newDirectoryStream(root.toPath()).use { paths ->
                        paths.forEach { Files.delete(it) }
                    }
                    directories += root
                }
                Files.createTempFile(root.toPath(), "source-", ".tmp").toFile()
            }
        })
        try { copy.start() } catch (error: Throwable) { permits.release(); throw error }
        return copy
    }
}

// Shared by every manager instance, APK and data alike.
internal val pluginImportSources = PluginImportSourcePool()

internal class PluginImportCopy internal constructor(
    private val maxBytes: Long,
    private val open: () -> InputStream,
    private val report: (Throwable) -> Unit,
    private val releasePermit: () -> Unit,
    private val createFile: () -> File,
) {
    private val cancelled = AtomicBoolean()
    private val result = CompletableDeferred<File>()
    private val closeRequested = CountDownLatch(1)
    private val released = CountDownLatch(1)
    // Also useful for source-level JVM regressions to await actual cleanup, not just cancellation.
    internal val finished = CompletableDeferred<Unit>()
    val isCancelled: Boolean get() = cancelled.get()

    internal fun start() { thread(name = "theta-import-copy", isDaemon = true) { run() } }
    fun cancel() {
        cancelled.set(true)
        closeRequested.countDown() // reaches the closer even when open() returns after cancellation
        result.completeExceptionally(CancellationException("Import closed"))
    }
    suspend fun await(): File = result.await()
    // No provider callback, filesystem IO, join or coroutine launch on the disposing UI thread.
    fun release() { released.countDown() }
    private fun checkOpen() { if (isCancelled) throw CancellationException("Import closed") }

    private fun run() {
        var file: File? = null
        var failure: Throwable? = null
        try {
            checkOpen()
            val input = open() // may never return; permit stays held and waiter can still cancel
            val closeDone = CountDownLatch(1)
            val closeError = AtomicReference<Throwable?>()
            try {
                thread(name = "theta-import-close", isDaemon = true) {
                    try { closeRequested.await(); input.close() }
                    catch (error: Throwable) { closeError.set(error) }
                    finally { closeDone.countDown() }
                }
            } catch (error: Throwable) {
                // Thread creation failure still owns the newly opened resource; close on this
                // bounded worker, never on Main or under the mutation mutex.
                withPluginCleanup(cleanup = { input.close() }) { throw error }
            }
            try {
                checkOpen()
                val target = createFile().also { file = it }
                target.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        checkOpen()
                        val count = input.read(buffer)
                        checkOpen()
                        if (count < 0) break
                        dataRequire(count > 0 && count.toLong() <= maxBytes - total, "Import exceeds byte limit or stalled stream")
                        out.write(buffer, 0, count)
                        total += count
                    }
                }
            } catch (error: Throwable) { failure = error }
            finally {
                closeRequested.countDown()
                closeDone.await() // uncooperative close retains this slot, not the shared gate
                closeError.get()?.let { error ->
                    val original = failure
                    if (original == null) failure = error else if (original !== error) original.addSuppressed(error)
                }
            }
            failure?.let { throw it }
            checkOpen()
            result.complete(checkNotNull(file))
            // The consumer owns local-file access until release, including serialized staging.
            // cancel() MUST NOT delete a file being consumed by a durable host transition.
            released.await()
        } catch (error: Throwable) { failure = error }
        finally {
            var cleaned = true
            try { file?.let { Files.deleteIfExists(it.toPath()) } }
            catch (cleanup: Throwable) {
                cleaned = false
                val original = failure
                if (original == null) failure = cleanup else if (original !== cleanup) original.addSuppressed(cleanup)
            }
            val error = failure
            if (error != null && !result.completeExceptionally(error)) {
                try { report(error) } catch (_: Throwable) { }
            }
            // Failed deletion quarantines capacity too: repeated failures cannot accumulate
            // unbounded cache files. A later process retries the dedicated cache sweep.
            if (cleaned) releasePermit()
            finished.complete(Unit)
        }
    }
}

/** A resource created inside use is owned BEFORE close runs. Used by both production store
 * paths, including local-file close failures. Never leave a registered data stage unowned.
 */
internal fun <T : Any> stagePluginInput(
    open: () -> InputStream,
    stage: (InputStream) -> T,
    discard: (T) -> Unit,
    validate: (T) -> Unit = {},
): T {
    var owned: T? = null
    try {
        open().use { owned = stage(it) }
        return checkNotNull(owned).also(validate)
    } catch (error: Throwable) {
        withPluginCleanup(cleanup = { owned?.let(discard) }) { throw error }
    }
}

/** The shared production two-phase path. Source await is caller-cancellable and outside the
 * serialized callback. Only a fully copied AND successfully closed local file reaches stage.
 * Caller/close cancellation during the durable phase prevents publication, not local cleanup.
 */
internal suspend fun <T : Any> stagePluginCopy(
    copy: PluginImportCopy,
    serialized: suspend (suspend () -> T?) -> T?,
    stage: suspend (File) -> T,
    discard: suspend (T) -> Unit,
    publish: (T) -> Boolean,
): T? {
    val caller = coroutineContext[Job]
    try {
        val file = copy.await()
        return serialized {
            if (copy.isCancelled || caller?.isActive == false) null
            else {
                val value = stage(file)
                var transferred = false
                withPluginCleanup(cleanup = { if (!transferred) discard(value) }) {
                    if (!copy.isCancelled && caller?.isActive != false && publish(value)) {
                        transferred = true
                        value
                    } else null
                }
            }
        }
    } catch (error: Throwable) {
        copy.cancel()
        throw error
    } finally { copy.release() }
}

/** Called on Main, including the host-to-caller cancellation boundary. Empty cancellation
 * never queues behind the mutation gate. Store tickets retain cleanup ownership on failure.
 * Detach only this source's stage before enqueueing; the job cannot see a replacement session.
 */
internal fun transferImportFailure(
    session: PluginImportSession, copy: PluginImportCopy, error: Throwable,
    enqueue: (suspend () -> Unit) -> Unit,
    discard: suspend (PluginImportSession) -> Unit,
    report: (Throwable) -> Unit,
) {
    if (session.source !== copy) return
    val owned = session.detachStages() ?: return
    enqueue {
        try { discard(owned) } catch (cleanup: Throwable) {
            if (cleanup !== error) error.addSuppressed(cleanup)
            report(error)
        }
    }
}

internal fun PluginImportSession.detachStages(): PluginImportSession? {
    if (code == null && data == null) return null
    return PluginImportSession().also {
        it.code = code; it.data = data
        code = null; data = null
    }
}

/** Stage fields are serialized on Main; source cancellation is immediate and thread-safe. */
internal class PluginImportSession {
    internal var closed = false
    internal var source: PluginImportCopy? = null
    internal var code: StagedPlugin? = null
    internal var data: StagedPluginData? = null
}
