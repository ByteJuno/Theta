package io.github.bytejuno.thetacore.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal const val PLUGIN_STABLE_WINDOW_MS = 30_000L
private const val MAX_JOURNAL_BYTES = 32_768
private const val MAX_SUSPECTS = 128
private val JOURNAL_ID = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}")

/** Immutable cold-process admission decision. Resume never relaxes the running process. */
internal data class PluginRestorePolicy(
    val skipCode: Boolean,
    val skipData: Boolean,
    val suspects: Set<String>,
)

internal interface PluginRecoveryClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}
internal enum class PluginExitReason { CRASH, NATIVE_CRASH, OTHER }
internal data class PluginProcessExit(val pid: Int, val timestamp: Long, val reason: PluginExitReason)
internal fun interface PluginExitHistory { fun recent(): List<PluginProcessExit> }

/** The receipt is separate: an uncaught thread never waits for the normal journal writer. */
internal interface PluginRecoveryStorage {
    fun readState(): ByteArray?
    fun writeState(bytes: ByteArray)
    fun readCrash(): ByteArray?
    fun writeCrash(bytes: ByteArray)
    fun clearCrash()
}

internal class FilePluginRecoveryStorage(directory: File) : PluginRecoveryStorage {
    private val state = File(directory, "state.bin")
    private val crash = File(directory, "crash.bin")
    private fun read(file: File): ByteArray? {
        if (File(file.parentFile, file.name + ".pending").exists()) throw IOException("Interrupted recovery journal write")
        if (!file.exists()) return null
        file.inputStream().use {
            val bytes = it.readNBytes(MAX_JOURNAL_BYTES + 1)
            if (bytes.size > MAX_JOURNAL_BYTES) throw IOException("Recovery journal too large")
            return bytes
        }
    }
    private fun write(file: File, bytes: ByteArray) {
        require(bytes.size <= MAX_JOURNAL_BYTES)
        val directory = checkNotNull(file.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Recovery directory unavailable")
        val pending = File(directory, file.name + ".pending")
        pending.outputStream().use { it.write(bytes); it.fd.sync() }
        try {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    override fun readState() = read(state)
    override fun writeState(bytes: ByteArray) = write(state, bytes)
    override fun readCrash() = read(crash)
    override fun writeCrash(bytes: ByteArray) = write(crash, bytes)
    override fun clearCrash() {
        Files.deleteIfExists(crash.toPath())
        Files.deleteIfExists(File(crash.parentFile, crash.name + ".pending").toPath())
    }
}

private data class RecoverySession(
    val token: String, val pid: Int, val wall: Long, val elapsed: Long,
    val stable: Boolean = false, val page: String? = null,
    val skipCode: Boolean = false, val skipData: Boolean = false,
)
private data class RecoveryState(
    val level: Int = 0, val streak: Int = 0, val resume: Boolean = false,
    val suspects: Set<String> = emptySet(), val session: RecoverySession? = null,
)
private data class CrashReceipt(val token: String, val wall: Long, val elapsed: Long, val page: String?)

/** Pure JVM core. Construct exactly once per Application, never once per Activity/restore. */
internal class PluginCrashRecovery(
    private val storage: PluginRecoveryStorage,
    private val clock: PluginRecoveryClock,
    history: PluginExitHistory = PluginExitHistory { emptyList() },
    pid: Int,
    token: String = UUID.randomUUID().toString(),
) {
    private val journalLock = Any() // Only host primitives/storage under this lock; no plugin objects.
    private val crashRecorded = AtomicBoolean(false)
    @Volatile private var state = RecoveryState()
    @Volatile private var storageFailed = false
    private var policyClaimed = false
    @Volatile var resumeRequested: Boolean = false
        private set

    init {
        require(JOURNAL_ID.matches(token))
        val now = clock.wallMillis()
        var corrupt = false
        val previous = try { storage.readState()?.let(::decodeState) ?: RecoveryState() }
        catch (_: Exception) { corrupt = true; RecoveryState(level = 2) }
        val receipt = try { storage.readCrash()?.let(::decodeCrash) }
        catch (_: Exception) { corrupt = true; null }
        val session = previous.session
        // Exact session identity and monotonic age are authoritative for our receipt.
        // Wall time can move backwards while the process lives (user/NTP adjustment).
        val ownReceipt = receipt?.takeIf {
            session != null && it.token == session.token && it.elapsed >= session.elapsed
        }
        // PID + wall interval + exact reason: a missing process/force-stop/LMK is NOT a crash.
        // History may be absent/unsupported; do not invent evidence when querying it fails.
        val exit = if (session == null) null else try {
            history.recent().filter {
                it.pid == session.pid && it.timestamp >= session.wall && it.timestamp <= now &&
                    it.reason != PluginExitReason.OTHER
            }.maxByOrNull { it.timestamp }
        } catch (_: Exception) { null }
        val crashed = ownReceipt != null || exit != null
        val age = when {
            ownReceipt != null && session != null -> ownReceipt.elapsed - session.elapsed
            exit != null && session != null -> exit.timestamp - session.wall
            else -> Long.MAX_VALUE
        }
        val shortCrash = crashed && session?.stable == false && age in 0 until PLUGIN_STABLE_WINDOW_MS
        var streak = if (shortCrash) (previous.streak + 1).coerceAtMost(2) else 0
        var level = previous.level
        val suspects = previous.suspects.toMutableSet()
        if (crashed) {
            (if (ownReceipt != null) ownReceipt.page else session?.page)?.let { suspects += it }
            if (session?.skipCode == true) level = 2
            else if (streak >= 2) level = maxOf(level, 1)
        }
        // A new crash takes precedence over an earlier resume request.
        if (previous.resume && !crashed && !corrupt) { level = 0; streak = 0 }
        if (corrupt || suspects.size > MAX_SUSPECTS) level = 2
        state = RecoveryState(level, streak, suspects = suspects.take(MAX_SUSPECTS).toSet(),
            session = RecoverySession(token, pid, now, clock.elapsedMillis(), skipCode = level > 0, skipData = level > 1))
        if (persist()) {
            // The new session is durable before retiring an old receipt. A failed delete
            // cannot replay it because receipts are matched to the exact session token.
            try { storage.clearCrash() } catch (_: Exception) { storageFailed = true }
        }
    }

    /** Manual skip is honored only on the first admission decision in this cold process. */
    fun restorePolicy(skipPlugins: Boolean = false): PluginRestorePolicy = synchronized(journalLock) {
        if (!policyClaimed) {
            policyClaimed = true
            if (skipPlugins) {
                state = state.copy(session = checkNotNull(state.session).copy(skipCode = true, skipData = true))
                persist()
            }
        }
        policy
    }

    /** Non-latching preview for host UI; actual admission must call restorePolicy. */
    val policy: PluginRestorePolicy
        get() {
            val snapshot = state
            val session = checkNotNull(snapshot.session)
            return PluginRestorePolicy(storageFailed || session.skipCode, storageFailed || session.skipData, snapshot.suspects.toSet())
        }

    /** Only after a durable, explicit user re-enable; global resume never calls this. */
    fun acknowledgeSuspect(id: String): Boolean = synchronized(journalLock) {
        if (!JOURNAL_ID.matches(id) || storageFailed) return@synchronized false
        val previous = state
        state = state.copy(suspects = state.suspects - id)
        persist().also { if (!it) state = previous }
    }

    /** Called synchronously BEFORE selecting/rendering a plugin page, not in DisposableEffect. */
    fun enterPluginPage(id: String): Boolean = synchronized(journalLock) {
        if (!JOURNAL_ID.matches(id) || storageFailed) return@synchronized false
        if (state.suspects.size >= MAX_SUSPECTS && id !in state.suspects) return@synchronized false
        val session = checkNotNull(state.session)
        if (session.page == id) return@synchronized true
        state = state.copy(session = session.copy(page = id))
        persist()
    }

    fun leavePluginPage() = synchronized(journalLock) {
        val session = checkNotNull(state.session)
        if (session.page != null) {
            state = state.copy(session = session.copy(page = null))
            persist()
        }
        Unit
    }

    fun markStableIfDue() = synchronized(journalLock) {
        val session = checkNotNull(state.session)
        if (!session.stable && clock.elapsedMillis() - session.elapsed >= PLUGIN_STABLE_WINDOW_MS) {
            state = state.copy(streak = 0, session = session.copy(stable = true))
            persist()
        }
        Unit
    }

    fun requestResumeNextColdStart(): Boolean = synchronized(journalLock) {
        if (storageFailed) return@synchronized false
        state = state.copy(resume = true)
        persist().also { if (it) resumeRequested = true }
    }

    /** No Throwable inspection, plugin callbacks, locks, or normal-state writes on crash path. */
    fun recordUncaughtCrash() {
        if (!crashRecorded.compareAndSet(false, true)) return
        val session = state.session ?: return
        try {
            storage.writeCrash(encodeCrash(CrashReceipt(session.token, clock.wallMillis(), clock.elapsedMillis(), session.page)))
        } catch (_: Throwable) { /* Best effort; OS crash history may still supply evidence. */ }
    }

    private fun persist(): Boolean = try {
        storage.writeState(encodeState(state))
        true
    } catch (_: Exception) {
        storageFailed = true
        false
    }
}

/** Captures the original once. Re-entry does not recursively forward to another handler. */
internal class PluginRecoveryUncaughtHandler(
    private val recovery: PluginCrashRecovery,
    private val original: Thread.UncaughtExceptionHandler?,
    private val terminate: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val handling = AtomicBoolean(false)
    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (!handling.compareAndSet(false, true)) { terminate(); return }
        try {
            recovery.recordUncaughtCrash()
            original?.uncaughtException(thread, error)
        } finally {
            terminate() // Android's original handler normally terminates without returning.
        }
    }
}

private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { it.block() }
    bytes.toByteArray()
}
private fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T {
    require(bytes.size <= MAX_JOURNAL_BYTES)
    return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        input.block().also { require(input.available() == 0) }
    }
}
private fun DataOutputStream.optionalId(id: String?) { writeBoolean(id != null); if (id != null) writeUTF(id) }
private fun DataInputStream.id(): String = readUTF().also { require(JOURNAL_ID.matches(it)) }
private fun DataInputStream.optionalId(): String? = if (readBoolean()) id() else null
private fun DataInputStream.time(): Long = readLong().also { require(it >= 0) }
private fun encodeState(value: RecoveryState) = encode {
    writeInt(0x54435231)
    writeInt(value.level); writeInt(value.streak); writeBoolean(value.resume)
    writeInt(value.suspects.size); value.suspects.sorted().forEach { writeUTF(it) }
    writeBoolean(value.session != null)
    value.session?.let {
        writeUTF(it.token); writeInt(it.pid); writeLong(it.wall); writeLong(it.elapsed)
        writeBoolean(it.stable); optionalId(it.page); writeBoolean(it.skipCode); writeBoolean(it.skipData)
    }
}
private fun decodeState(bytes: ByteArray): RecoveryState = decode(bytes) {
    require(readInt() == 0x54435231)
    val level = readInt().also { require(it in 0..2) }
    val streak = readInt().also { require(it in 0..2) }
    val resume = readBoolean()
    val count = readInt().also { require(it in 0..MAX_SUSPECTS) }
    val suspects = List(count) { id() }.toSet().also { require(it.size == count) }
    val session = if (readBoolean()) RecoverySession(id(), readInt().also { require(it > 0) }, time(), time(),
        readBoolean(), optionalId(), readBoolean(), readBoolean()) else null
    require(session?.skipData != true || session?.skipCode == true)
    require(level == 0 || session?.skipCode == true)
    require(level < 2 || session?.skipData == true)
    require(session?.stable != true || streak == 0)
    RecoveryState(level, streak, resume, suspects, session)
}
private fun encodeCrash(value: CrashReceipt) = encode {
    writeInt(0x54434331); writeUTF(value.token); writeLong(value.wall); writeLong(value.elapsed); optionalId(value.page)
}
private fun decodeCrash(bytes: ByteArray): CrashReceipt = decode(bytes) {
    require(readInt() == 0x54434331)
    CrashReceipt(id(), time(), time(), optionalId())
}
