package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.OutputStream
import java.io.FilterInputStream
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID

/** A cursor is a snapshot boundary, not an offset into a changing message list. */
data class HistoryCursor internal constructor(val sessionId: String, val revision: Long,
    internal val hash: String, internal val messageCount: Long)
data class HistoryPage(val revision: Long, val totalMessages: Long, val messages: List<Message>,
    val older: HistoryCursor?)
data class HistoryIo(val bytesRead: Long, val bytesWritten: Long, val filesRead: Long, val filesWritten: Long,
    val messagesDecoded: Long)
enum class HistoryWriteStage { BEFORE_WRITE, DURING_WRITE, AFTER_WRITE, AFTER_FILE_SYNC, BEFORE_REPLACE, AFTER_REPLACE, AFTER_DIRECTORY_SYNC }
data class HistoryWritePoint(val file: String, val stage: HistoryWriteStage)
class HistoryStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
data class HistoryRecovery(val recovered: Boolean, val requiresDecision: Boolean, val callIds: List<String>)

/**
 * One directory / session, one exclusive writer. Immutable numbered batches + a small atomic HEAD.
 * HEAD is the commit point. A batch contains only complete protocol transactions; no page splits one.
 * Files beyond HEAD are never promoted. Each batch links its predecessor's digest and message count.
 * Opening checks only HEAD and its last batch; older corruption is rejected when traversed, not hidden
 * by an eager full scan. The call index prevents duplicate IDs without scanning previous messages.
 *
 * Uses the ledger's JDK file-lock / force / atomic-rename primitives (no SQLite in this JDK-only core).
 * PROCESS_CRASH_ONLY is explicit: OS process death is covered, power loss is NOT promised. Directory
 * sync mode fails if unsupported. An Android host must validate its filesystem before using that mode.
 * Snapshot is deliberately full model history; recent/page are bounded browsing APIs, not compaction.
 */
@OptIn(ExperimentalSerializationApi::class)
class FileRunHistory private constructor(
    private val directory: Path, private val durability: Durability,
    private val fault: (HistoryWritePoint) -> Unit,
    private val lockChannel: FileChannel, private val lock: FileLock, private val writerKey: Path,
) : RunHistory, AutoCloseable {
    private var closed = false
    private var poisoned = false
    private var bytesRead = 0L
    private var bytesWritten = 0L
    private var filesRead = 0L
    private var filesWritten = 0L
    private var messagesDecoded = 0L
    private var head = read<HistoryHead>(directory.resolve("head.json"))
    init {
        require(head.version == 1 && head.sessionId.isNotBlank() && head.revision >= 0 && head.messageCount >= 0)
        if (head.revision == 0L) require(head.messageCount == 0L && head.hash == EMPTY_HASH)
        else batch(cursor()) // bounded tail validation; never scan all history on open
        discardUncommittedBatch()
        createDirectoryChain(directory.resolve("accounting"), durability)
        discardTemporaryFiles()
        migrateAccounting()
    }

    // Initialized only after the existing writer lock and source validation; no independent owner.
    private val summaryWorkspace = SummaryWorkspace(directory)
    private var summarySpools = 0

    @Synchronized internal fun createSummarySpool(): SummarySpool {
        ready()
        val path = summaryWorkspace.create()
        summarySpools++
        return SummarySpool(path) { releaseSummarySpool(path) }
    }

    @Synchronized private fun releaseSummarySpool(path: Path) {
        try { summaryWorkspace.delete(path) }
        finally { summarySpools-- }
    }

    /** Only our exact temporary names, and only regular files (never follow links). A partial
     * temp is expected after death and is never committed evidence. Unknown names and every final
     * file, including corrupt finals, are retained. This runs only while holding writer.lock. */
    private fun discardTemporaryFiles() {
        val patterns = listOf(directory to Regex("\\.(head|pending|context|view|execution|accounting|reconciliations)\\.json\\.tmp"),
            directory.resolve("batches") to Regex("\\.[1-9][0-9]*\\.json\\.tmp"),
            directory.resolve("calls") to Regex("\\.[0-9a-f]{64}\\.json\\.tmp"),
            directory.resolve("accounting") to Regex("\\.[0-9a-f]{64}\\.json\\.tmp"))
        for ((parent, pattern) in patterns) {
            Files.newDirectoryStream(parent).use { paths ->
                for (path in paths) if (pattern.matches(path.fileName.toString()) &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    point(path, HistoryWriteStage.BEFORE_REPLACE)
                    Files.delete(path)
                    point(path, HistoryWriteStage.AFTER_REPLACE)
                }
            }
            syncDirectory(parent, durability)
        }
    }

    /** At most one speculative revision exists because any failed write poisons its writer. Remove
     * its index entries before allowing that revision to be reused; otherwise a different next batch
     * could turn an abandoned call ID into a false committed duplicate. Cleanup itself is restartable.
     */
    private fun discardUncommittedBatch() {
        val path = batchPath(Math.addExact(head.revision, 1))
        if (!Files.exists(path)) return
        val value = read<HistoryBatch>(path)
        require(value.version == 1 && value.sessionId == sessionId && value.revision == head.revision + 1 &&
            value.previousHash == head.hash && value.previousCount == head.messageCount)
        val hash = hash(value)
        val messages = value.messages.map(HistoryCodec::decodeMessage)
        messagesDecoded += messages.size
        validateHistory(messages)
        messages.flatMap { it.parts.filterIsInstance<ContentPart.ToolCall>() }.forEach { call ->
            val index = callPath(call.binding.callId)
            if (Files.exists(index)) {
                require(read<CallLocation>(index) == CallLocation(call.binding.callId, value.revision, hash))
                Files.delete(index)
            }
        }
        syncDirectory(directory.resolve("calls"), durability)
        Files.delete(path)
        syncDirectory(path.parent, durability)
    }
    val sessionId: String get() = head.sessionId
    @Synchronized fun io() = HistoryIo(bytesRead, bytesWritten, filesRead, filesWritten, messagesDecoded)
    private fun ready() { check(!closed && !poisoned) { "History closed or write uncertain; reopen and recover" } }
    @Synchronized override fun revision(): Long { ready(); return head.revision }
    private fun cursor() = HistoryCursor(head.sessionId, head.revision, head.hash, head.messageCount)
    private fun batchPath(revision: Long) = directory.resolve("batches").resolve("$revision.json")
    private fun callPath(callId: String) = directory.resolve("calls").resolve("${digest(callId.toByteArray(Charsets.UTF_8))}.json")
    private fun pendingPath() = directory.resolve("pending.json")
    private fun checkpointPath() = directory.resolve("context.json")

    private fun accountingPath(taskChainId: String): Path {
        require(taskChainId.isNotBlank())
        HistoryCodec.requireUtf8(taskChainId)
        return directory.resolve("accounting").resolve("${digest(taskChainId.toByteArray(Charsets.UTF_8))}.json")
    }

    /** One bounded atomic counter per chain, independent of history and context-view provenance. */
    @Synchronized internal fun loadAccounting(taskChainId: String): RunAccounting? {
        ready()
        val path = accountingPath(taskChainId)
        return if (Files.exists(path)) read<RunAccounting>(path).also {
            require(it.sessionId == sessionId && it.taskChainId == taskChainId && it.steps >= 0 && it.retries >= 0) {
                "Accounting identity or counters disagree"
            }
        } else null
    }
    @Synchronized internal fun saveAccounting(value: RunAccounting) {
        ready(); require(value.sessionId == sessionId && value.steps >= 0 && value.retries >= 0)
        val prior = loadAccounting(value.taskChainId)
        write(accountingPath(value.taskChainId), value.copy(
            steps = maxOf(value.steps, prior?.steps ?: 0), retries = maxOf(value.retries, prior?.retries ?: 0)))
    }

    /** Copy/force/replace before deleting the legacy slot. A crash at either commit point is
     * restartable: both copies merge monotonically; corrupt legacy data never silently resets. */
    private fun migrateAccounting() {
        val legacy = directory.resolve("accounting.json")
        if (!Files.exists(legacy)) return
        val value = read<RunAccounting>(legacy)
        saveAccounting(value)
        try { Files.delete(legacy); syncDirectory(directory, durability) }
        catch (e: Exception) { poisoned = true; throw HistoryStorageException("Accounting migration uncertain", e) }
    }

    private var runActive = false
    @Synchronized internal fun beginRun() {
        ready(); check(!runActive) { "Session already has an active run" }; runActive = true
    }
    @Synchronized internal fun endRun() { runActive = false }

    private fun retryReceipts(): List<RetryReceipt> {
        val path = directory.resolve("reconciliations.json")
        return if (Files.exists(path)) read<List<RetryReceipt>>(path).also { receipts ->
            require(receipts.all { it.sessionId == sessionId })
        } else emptyList()
    }
    private fun rawBarrier(): ExecutionBarrier? {
        val path = directory.resolve("execution.json")
        return if (Files.exists(path)) read<ExecutionBarrier>(path).also { require(it.sessionId == sessionId) } else null
    }
    @Synchronized internal fun checkpointReconciled(value: ContextCheckpoint): Boolean {
        ready(); return retryReceipts().any { it.checkpoint == value }
    }
    private fun retryEvidence(): Pair<ExecutionBarrier?, ContextCheckpoint?> {
        val barrier = loadBarrier()
        val checkpoint = loadCheckpoint()?.takeIf {
            !checkpointReconciled(it) && (it.serverUnresolved ||
                it.kind == "PAUSED" && it.pauseClass in setOf("ADMITTED", "SUMMARY_RESERVED", "REVIEW"))
        }
        return barrier to checkpoint
    }
    private fun retryIdentifier(barrier: ExecutionBarrier?, checkpoint: ContextCheckpoint?): String =
        digest(HistoryCodec.json.encodeToString(RetryReceipt("", sessionId, "", "", barrier, checkpoint))
            .toByteArray(Charsets.UTF_8))

    /** Inspect after the run stops. The identifier binds the current durable session evidence,
     * independently of appended user messages or changed model configuration. */
    @Synchronized fun retryReconciliation(): RetryReconciliation? {
        ready()
        val (barrier, checkpoint) = retryEvidence()
        if (barrier == null && checkpoint == null) return null
        return RetryReconciliation(retryIdentifier(barrier, checkpoint), sessionId,
            barrier?.reason ?: requireNotNull(checkpoint).reason)
    }

    /** Trusted host call only, after user/host reconciliation. No caller-supplied evidence is
     * authoritative. Rejected/stale/duplicate/unattended decisions make no writes. A single atomic
     * receipt acknowledges both reservations without deleting their forensic records or views.
     * Local execution leases and ledger isolation are deliberately NOT changed by this API. */
    @Synchronized fun confirmRetryAfterReconciliation(id: String, caller: RunIdentity, note: String): Boolean {
        ready()
        if (runActive || caller.unattended || caller.sessionId != sessionId || note.isBlank() ||
            Files.exists(pendingPath())) return false
        val (barrier, checkpoint) = retryEvidence()
        if (barrier == null && checkpoint == null || id != retryIdentifier(barrier, checkpoint)) return false
        val receipt = RetryReceipt(id, sessionId, caller.runId, note, barrier, checkpoint)
        write(directory.resolve("reconciliations.json"), retryReceipts() + receipt)
        return true
    }

    @Synchronized internal fun saveBarrier(value: ExecutionBarrier) {
        ready(); require(value.sessionId == sessionId)
        val previous = loadBarrier()
        require(previous == null || previous.taskChainId == value.taskChainId)
        write(directory.resolve("execution.json"), value)
    }
    @Synchronized internal fun loadBarrier(): ExecutionBarrier? {
        ready()
        val value = rawBarrier() ?: return null
        return value.takeUnless { barrier -> retryReceipts().any { it.barrier == barrier } }
    }
    @Synchronized internal fun completeServerExecution(identity: RunIdentity) {
        ready()
        val barrier = loadBarrier() ?: return
        require(barrier.taskChainId == identity.taskChainId && barrier.runId == identity.runId)
        try { Files.delete(directory.resolve("execution.json")); syncDirectory(directory, durability) }
        catch (e: Exception) { poisoned = true; throw HistoryStorageException("Execution barrier cleanup uncertain", e) }
    }
    @Synchronized internal fun loadView(): ContextCheckpoint? {
        ready()
        return if (Files.exists(directory.resolve("view.json"))) read<ContextCheckpoint>(directory.resolve("view.json")) else null
    }

    /** Independent atomic derived-view commit under the SAME session lock. Never advances HEAD.
     * A stale checkpoint is not replayed; corrupt committed data fails closed and is retained. */
    @Synchronized internal fun saveCheckpoint(value: ContextCheckpoint) {
        ready()
        val source = snapshot() // validates the complete source chain and refuses pending intent
        require(value.sessionId == sessionId && value.sourceRevision == source.revision)
        require(value.matches(source, value.settingsKey))
        require(value.steps >= 0 && value.retries >= 0 && value.goal.isNotBlank())
        require(value.kind in setOf("VIEW", "CONTINUATION", "PAUSED", "COMPLETE"))
        require((value.kind == "CONTINUATION") == (value.nextRunId != null))
        require(value.kind in setOf("PAUSED", "COMPLETE") || value.summary != null)
        value.ledger.forEach { it.validate() }
        // View first: a crash may retain an unused view, never lose admission protection.
        if (value.summary != null) write(directory.resolve("view.json"), value.copy(kind = "VIEW", nextRunId = null))
        write(checkpointPath(), value)
    }

    @Synchronized internal fun loadCheckpoint(): ContextCheckpoint? {
        ready()
        if (!Files.exists(checkpointPath())) return null
        return read<ContextCheckpoint>(checkpointPath()).also {
            require(it.version == 1 && it.sessionId == sessionId && it.steps >= 0 && it.retries >= 0)
            require(it.kind in setOf("VIEW", "CONTINUATION", "PAUSED", "COMPLETE"))
            require((it.kind == "CONTINUATION") == (it.nextRunId != null))
            require(it.kind in setOf("PAUSED", "COMPLETE") || it.summary != null)
            it.ledger.forEach { record -> record.validate() }
        }
    }

    /** Null means absent/stale, not that an uncertain checkpoint can be safely resumed. */
    @Synchronized fun checkpointInfo(): CheckpointInfo? = loadCheckpoint()?.takeIf {
        it.matches(snapshot(), it.settingsKey)
    }?.info()

    private inline fun <reified T> read(path: Path): T {
        try {
            Files.newInputStream(path).use { raw ->
                val counted = object : FilterInputStream(raw) {
                    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        `in`.read(b, off, len).also { if (it > 0) bytesRead += it }
                }
                val input = BufferedInputStream(counted)
                filesRead++
                input.mark(HISTORY_STREAM_MAGIC.size)
                val prefix = ByteArray(HISTORY_STREAM_MAGIC.size)
                val count = input.read(prefix)
                input.reset()
                if (count == prefix.size && prefix.contentEquals(HISTORY_STREAM_MAGIC)) {
                    val framed = HistoryFrameInput(input)
                    val value = HistoryCodec.json.decodeFromStream<T>(framed)
                    require(framed.read() == -1) { "Trailing history payload" }
                    return value
                }
                // Existing v1 checked-JSON sessions remain readable, with their original bound.
                require(Files.size(path) in 1..MAX_FILE_BYTES) { "Invalid legacy history file size" }
                val bytes = input.readBytes()
                val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                val checked = HistoryCodec.json.decodeFromString<CheckedHistoryValue>(text)
                require(digest(checked.payload.toByteArray(Charsets.UTF_8)) == checked.sha256) { "Checksum mismatch" }
                return HistoryCodec.json.decodeFromString(checked.payload)
            }
        } catch (e: Exception) { throw HistoryStorageException("Invalid history file $path", e) }
    }
    private fun batch(c: HistoryCursor): Pair<HistoryBatch, List<Message>> {
        require(c.sessionId == sessionId && c.revision in 1..head.revision && c.messageCount > 0)
        val value = read<HistoryBatch>(batchPath(c.revision))
        require(value.version == 1 && value.sessionId == sessionId && value.revision == c.revision)
        require(hash(value) == c.hash) { "History chain mismatch" }
        val messages = freezeMessages(value.messages.map(HistoryCodec::decodeMessage))
        messagesDecoded += messages.size
        validateHistory(messages)
        messages.flatMap { it.parts.filterIsInstance<ContentPart.ToolCall>() }.forEach { call ->
            val location = read<CallLocation>(callPath(call.binding.callId))
            require(location == CallLocation(call.binding.callId, c.revision, c.hash)) { "Call index disagrees with history" }
        }
        require(messages.isNotEmpty() && value.previousCount >= 0 && value.previousCount + messages.size == c.messageCount)
        if (c.revision == 1L) require(value.previousCount == 0L && value.previousHash == EMPTY_HASH)
        else require(value.previousCount > 0 && value.previousHash.length == 64)
        return value to messages
    }
    @Synchronized fun recent(limit: Int = 50): HistoryPage = page(null, limit)
    /** limit is a soft message limit: retain a whole append transaction, even at a page boundary. */
    @Synchronized fun page(before: HistoryCursor?, limit: Int = 50): HistoryPage {
        ready(); require(limit in 1..10_000)
        var c = before ?: cursor()
        require(c.sessionId == sessionId && c.revision in 0..head.revision)
        val revision = c.revision
        val total = c.messageCount
        val chunks = mutableListOf<List<Message>>()
        var count = 0
        while (c.revision > 0 && count < limit) {
            val (value, messages) = batch(c)
            chunks += messages; count += messages.size
            c = HistoryCursor(sessionId, c.revision - 1, value.previousHash, value.previousCount)
        }
        return HistoryPage(revision, total, frozen(chunks.asReversed().flatten()), c.takeIf { it.revision > 0 })
    }
    @Synchronized override fun snapshot(): HistorySnapshot {
        ready()
        check(!Files.exists(pendingPath())) { "Pending tool step requires cold reconciliation before model replay" }
        var c: HistoryCursor? = null
        val chunks = mutableListOf<List<Message>>()
        do {
            val p = page(c, 10_000)
            chunks += p.messages; c = p.older
        } while (c != null)
        val messages = frozen(chunks.asReversed().flatten())
        validateHistory(messages)
        return HistorySnapshot(head.revision, messages)
    }

    @Synchronized override fun prepareStep(expectedRevision: Long, identity: RunIdentity, stepId: String, assistant: Message) {
        ready(); check(expectedRevision == head.revision)
        require(identity.sessionId == sessionId)
        check(!Files.exists(pendingPath())) { "Pending tool step must be recovered first" }
        // Freeze and round-trip the exact value that recovery will read BEFORE persisting intent.
        val stored = HistoryCodec.encodeMessage(assistant)
        HistoryCodec.requireUtf8(stored)
        listOf(identity.taskChainId, identity.sessionId, identity.runId, stepId).forEach(HistoryCodec::requireUtf8)
        val value = StoredPending(revision = expectedRevision, taskChainId = identity.taskChainId,
            sessionId = sessionId, runId = identity.runId, unattended = identity.unattended, stepId = stepId,
            assistant = stored)
        val decoded = validatePending(value)
        require(HistoryCodec.encodeMessage(decoded) == stored)
        decoded.parts.filterIsInstance<ContentPart.ToolCall>().forEach { call ->
            require(!Files.exists(callPath(call.binding.callId))) { "Duplicate call identity" }
        }
        write(pendingPath(), value)
    }

    private fun validatePending(p: StoredPending): Message {
        require(p.version == 1 && p.sessionId == sessionId && p.revision in 0..head.revision)
        val assistant = HistoryCodec.decodeMessage(p.assistant)
        require(assistant.role == Role.ASSISTANT && assistant.parts.none { it is ContentPart.ToolResult })
        val calls = assistant.parts.filterIsInstance<ContentPart.ToolCall>()
        require(calls.isNotEmpty() && calls.map { it.binding.callId }.distinct().size == calls.size)
        calls.forEachIndexed { order, call ->
            // Malformed/blank/null arguments are intentionally accepted by InvocationInput:
            // the ledger records and rejects them (or cancels truncated calls), preserving evidence.
            InvocationInput(BindingValue.copyOf(call.binding), p.taskChainId, p.sessionId, p.runId,
                p.stepId, order, call.argsJson, null, p.unattended, Risk.READ, true, call.name)
        }
        return assistant
    }

    @Synchronized override fun append(expectedRevision: Long, messages: List<Message>) {
        ready(); check(expectedRevision == head.revision) { "History changed during run" }
        val next = freezeMessages(messages)
        require(next.isNotEmpty()); validateHistory(next) // only the new, complete transaction
        val pending = if (Files.exists(pendingPath())) read<StoredPending>(pendingPath()) else null
        if (pending != null) {
            require(pending.revision == head.revision && pending.sessionId == sessionId && next.size == 2 &&
                HistoryCodec.encodeMessage(next.first()) == pending.assistant) { "Pending step differs from append" }
        }
        val value = HistoryBatch(sessionId = sessionId, revision = Math.addExact(head.revision, 1),
            previousHash = head.hash, previousCount = head.messageCount, messages = next.map(HistoryCodec::encodeMessage))
        val hash = hash(value)
        val calls = next.flatMap { it.parts.filterIsInstance<ContentPart.ToolCall>() }.map { it.binding.callId }
        for (id in calls) {
            val path = callPath(id)
            if (Files.exists(path)) {
                val location = read<CallLocation>(path)
                require(location.callId == id && location.revision > 0)
                require(location.revision > head.revision) { "Duplicate call identity" }
            }
        }
        // Index entries ahead of HEAD are speculative, just like the uncommitted batch. Both are
        // forced before HEAD. No commit is acknowledged until every required file is durable.
        write(batchPath(value.revision), value)
        for (id in calls) write(callPath(id), CallLocation(id, value.revision, hash))
        val newHead = HistoryHead(sessionId = sessionId, revision = value.revision,
            messageCount = Math.addExact(head.messageCount, next.size.toLong()), hash = hash)
        write(directory.resolve("head.json"), newHead)
        head = newHead
        if (pending != null) clearPending()
    }

    /** Call only after InvocationLedger.recoverColdStart. Never execute or re-submit a tool here.
     * Missing registrations are known unsubmitted because AgentRunLoop registers the entire frozen
     * step before execution. Existing terminal truth wins, including UNKNOWN and late-exit evidence.
     * A stale pending file after HEAD commit is verified and removed without a second history append.
     */
    @Synchronized fun recoverPending(ledger: InvocationLedger): HistoryRecovery {
        ready()
        if (!Files.exists(pendingPath())) return HistoryRecovery(false, false, emptyList())
        val p = read<StoredPending>(pendingPath())
        val assistant = validatePending(p)
        val calls = assistant.parts.filterIsInstance<ContentPart.ToolCall>()
        val alreadyCommitted = p.revision < head.revision
        // No new writes may bypass pending, so only one commit may have happened.
        if (alreadyCommitted) require(p.revision + 1 == head.revision)
        calls.forEachIndexed { order, call ->
            var r = ledger.get(call.binding.callId)
            if (r == null) {
                check(!alreadyCommitted) { "Committed step missing ledger record" }
                applyRecovery(ledger.recordInvocation(InvocationInput(BindingValue.copyOf(call.binding),
                    p.taskChainId, sessionId, p.runId, p.stepId, order, call.argsJson, null, p.unattended,
                    Risk.READ, true, call.name)))
                r = requireNotNull(ledger.get(call.binding.callId))
            }
            require(r.input.binding == BindingValue.copyOf(call.binding) && r.input.sessionId == sessionId &&
                r.input.runId == p.runId && r.input.taskChainId == p.taskChainId && r.input.stepId == p.stepId &&
                r.input.callOrder == order && r.input.argsJson == call.argsJson && r.input.modelName == call.name)
            check(r.state != Status.RUNNING && r.instance?.state != InstanceState.UNCONFIRMED) { "Cold-recover ledger first" }
            if (!r.state.terminal) {
                check(r.instance == null && !alreadyCommitted)
                applyRecovery(ledger.cancel(r.callId))
            }
        }
        val ids = calls.map { it.binding.callId }
        val results = requireNotNull(ledger.resultsForStep(sessionId, p.runId, p.stepId, ids)) { "Incomplete ledger step" }
        if (alreadyCommitted) {
            // After a restart the ledger's text has been through UTF-8, but this batch kept the original.
            val committed = batch(cursor()).second.map { HistoryCodec.atLedgerPrecision(HistoryCodec.encodeMessage(it)) }
            require(committed == listOf(p.assistant, HistoryCodec.encodeMessage(results)).map(HistoryCodec::atLedgerPrecision)) {
                "Ledger/history disagreement"
            }
            clearPending()
        } else append(head.revision, listOf(assistant, results))
        val unresolved = ids.any { id ->
            val r = requireNotNull(ledger.get(id))
            r.recoveryPending || r.result?.effect == Effect.UNKNOWN || r.restartRequired ||
                r.isolation?.let { it.resolution != RecoveryResolution.VERIFIED_CLEAR } == true
        }
        return HistoryRecovery(true, unresolved, ids)
    }
    private fun applyRecovery(m: Mutation) {
        check(m is Mutation.Applied || m is Mutation.AlreadyApplied) { "Ledger recovery failed: $m" }
    }
    private fun clearPending() {
        try { Files.delete(pendingPath()); syncDirectory(directory, durability) }
        catch (e: Exception) { poisoned = true; throw HistoryStorageException("Pending cleanup uncertain", e) }
    }
    private inline fun <reified T> write(path: Path, value: T) {
        val temp = path.resolveSibling(".${path.fileName}.tmp")
        try {
            point(path, HistoryWriteStage.BEFORE_WRITE)
            FileChannel.open(temp, CREATE_NEW, WRITE).use { channel ->
                var signalled = false
                var written = 0L
                val counted = object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val buffer = ByteBuffer.wrap(b, off, len)
                        while (buffer.hasRemaining()) bytesWritten += channel.write(buffer)
                        written += len
                        if (!signalled && written > HISTORY_STREAM_MAGIC.size + 4) {
                            signalled = true
                            point(path, HistoryWriteStage.DURING_WRITE)
                        }
                    }
                }
                val stream = HistoryFrameOutput(counted)
                HistoryCodec.writeValue(value as Any, stream)
                stream.finish()
                filesWritten++
                point(path, HistoryWriteStage.AFTER_WRITE)
                channel.force(true)
                point(path, HistoryWriteStage.AFTER_FILE_SYNC)
            }
            point(path, HistoryWriteStage.BEFORE_REPLACE)
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            point(path, HistoryWriteStage.AFTER_REPLACE)
            syncDirectory(path.parent, durability)
            point(path, HistoryWriteStage.AFTER_DIRECTORY_SYNC)
        } catch (e: Exception) {
            // Even a pre-HEAD error halts this instance: it may have a partially prepared tool step.
            poisoned = true
            throw HistoryStorageException("History write uncertain: ${path.fileName}; reopen and recover", e)
        } finally {
            // Preserve the primary exception; startup retries cleanup after death or delete failure.
            runCatching {
                if (Files.deleteIfExists(temp)) syncDirectory(path.parent, durability)
            }
        }
    }
    private fun point(path: Path, stage: HistoryWriteStage) = fault(HistoryWritePoint(
        directory.relativize(path).toString().replace('\\', '/'), stage))
    @Synchronized override fun close() {
        if (!closed) {
            check(!runActive && summarySpools == 0) { "Cannot close history with an active run or summary spool" }
            closed = true
            try { lock.release() } finally {
                // Only after the channel is closed: a later open must not overlap this descriptor.
                try { lockChannel.close() } finally { activeWriters.remove(writerKey) }
            }
        }
    }
    companion object {
        private const val EMPTY_HASH = "ROOT"
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        /** POSIX drops a process's lock when ANY descriptor for that file closes. A same-process
         * duplicate must therefore be rejected before it opens, and then closes, writer.lock. */
        private val activeWriters = java.util.concurrent.ConcurrentHashMap.newKeySet<Path>()
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun hash(batch: HistoryBatch): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() {
                override fun write(b: Int) { digest.update(b.toByte()) }
                override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
            }
            HistoryCodec.writeValue(batch, sink)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        /** Make each new directory reachable durably before creating children inside it. Sync
         * only the missing chain and its direct containing parents, not pre-existing ancestors
         * above that boundary (which may allow traversal but not directory reads). An existing
         * session directory and its direct parent are synced too: callers may have just made it. */
        internal fun createDirectoryChain(directory: Path, durability: Durability,
                                          sync: (Path, Durability) -> Unit = ::syncDirectory) {
            val absolute = directory.toAbsolutePath().normalize()
            val missing = mutableListOf<Path>()
            var parent = absolute
            while (true) {
                try {
                    require(Files.readAttributes(parent, java.nio.file.attribute.BasicFileAttributes::class.java).isDirectory)
                    break
                } catch (_: NoSuchFileException) {
                    missing.add(parent)
                    parent = parent.parent ?: throw NoSuchFileException(parent.toString())
                }
            }
            if (missing.isEmpty()) {
                absolute.parent?.let { sync(it, durability) }
                sync(absolute, durability)
                return
            }
            for (path in missing.asReversed()) {
                try { Files.createDirectory(path) }
                catch (e: FileAlreadyExistsException) {
                    // Another creator may have filled this gap since the stat; never accept a file.
                    if (!Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).isDirectory) throw e
                }
                path.parent?.let { sync(it, durability) }
                sync(path, durability)
            }
        }
        private fun syncDirectory(path: Path, durability: Durability) {
            if (durability == Durability.REQUIRE_DIRECTORY_SYNC) FileChannel.open(path, READ).use { it.force(true) }
        }
        /** Explicit creation only; open never treats missing or damaged storage as a new session. */
        fun create(directory: Path, sessionId: String = UUID.randomUUID().toString(),
                   durability: Durability, fault: (HistoryWritePoint) -> Unit = {}): FileRunHistory {
            require(sessionId.isNotBlank())
            val session = checkedSessionDirectory(directory, allowMissing = true)
            createDirectoryChain(session, durability)
            Files.newDirectoryStream(session).use { require(!it.iterator().hasNext()) { "Session directory is not empty" } }
            Files.createDirectory(session.resolve("batches")); Files.createDirectory(session.resolve("calls"))
            syncDirectory(session.resolve("batches"), durability)
            syncDirectory(session.resolve("calls"), durability)
            val payload = HistoryCodec.json.encodeToString(HistoryHead(sessionId = sessionId, revision = 0, messageCount = 0, hash = EMPTY_HASH))
            val bytes = HistoryCodec.json.encodeToString(CheckedHistoryValue(payload, digest(payload.toByteArray(Charsets.UTF_8))))
                .toByteArray(Charsets.UTF_8)
            FileChannel.open(session.resolve("head.json"), CREATE_NEW, WRITE).use {
                val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) it.write(buffer); it.force(true)
            }
            syncDirectory(session, durability)
            return open(session, durability, fault)
        }
        fun open(directory: Path, durability: Durability, fault: (HistoryWritePoint) -> Unit = {}): FileRunHistory {
            // Before opening writer.lock or performing any recovery/migration writes, reject raw
            // ambiguous paths and linked directories. Cleanup still happens only under the lock.
            val session = try {
                checkedSessionDirectory(directory).also { path ->
                    checkedSessionDirectory(path.resolve("batches"))
                    checkedSessionDirectory(path.resolve("calls"))
                    checkedSessionDirectory(path.resolve("accounting"), allowMissing = true)
                    checkedSessionDirectory(path.resolve("summary-tmp"), allowMissing = true)
                }
            } catch (e: Exception) { throw HistoryStorageException("Cannot open session $directory", e) }
            syncDirectory(session, durability)
            val writerKey = session.toRealPath()
            if (!activeWriters.add(writerKey)) throw HistoryStorageException("Cannot open session $directory",
                IllegalStateException("Session already has a writer in this process"))
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                channel = FileChannel.open(session.resolve("writer.lock"), CREATE, WRITE)
                lock = channel.tryLock() ?: error("Session already has a writer")
                return FileRunHistory(session, durability, fault, channel, lock, writerKey)
            } catch (e: Throwable) {
                // Errors too: decoding a large tail batch can exhaust the heap, and a leaked slot
                // would make this session unopenable until the process dies. Close before removing.
                try { try { lock?.release() } finally { channel?.close() } }
                catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                finally { activeWriters.remove(writerKey) }
                throw if (e is Exception) HistoryStorageException("Cannot open session $directory", e) else e
            }
        }
    }
}
