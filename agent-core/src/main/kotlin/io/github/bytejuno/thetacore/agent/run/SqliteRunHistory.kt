package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.OutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID

/** A cursor is a snapshot boundary, not an offset into a changing message list. */
data class HistoryCursor internal constructor(val sessionId: String, val revision: Long,
    internal val hash: String, internal val messageCount: Long)
data class HistoryPage(val revision: Long, val totalMessages: Long, val messages: List<Message>,
    val older: HistoryCursor?)
/** Logical serialized-value I/O counters (not SQLite page or physical disk traffic). */
data class HistoryIo(val bytesRead: Long, val bytesWritten: Long, val filesRead: Long, val filesWritten: Long,
    val messagesDecoded: Long)
enum class HistoryWriteStage { AFTER_BEGIN, AFTER_CHUNK, AFTER_VALUE, BEFORE_COMMIT, AFTER_COMMIT }
data class HistoryWritePoint(val file: String, val stage: HistoryWriteStage)
class HistoryStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
data class HistoryRecovery(val recovered: Boolean, val requiresDecision: Boolean, val callIds: List<String>)

/** Session handle borrowing the host's single AgentDatabase. Close releases only this session.
 * Indexed immutable batches and chunked values keep append/open/page work independent of total
 * history length. A page retains whole protocol transactions. snapshot intentionally materializes
 * full model history. SQL chunks share the database monitor; decoding holds only the session
 * lock, so other sessions and ledger writes can progress. All share the uncertainty barrier.
 */
@OptIn(ExperimentalSerializationApi::class)
open class SqliteRunHistory internal constructor(
    private val database: AgentDatabase,
    val sessionId: String,
    private val fault: (HistoryWritePoint) -> Unit,
) : RunHistory, AutoCloseable {
    private val db = database.connection
    private var closed = false
    private var poisoned = false
    private var bytesRead = 0L
    private var bytesWritten = 0L
    private var filesRead = 0L
    private var filesWritten = 0L
    private var messagesDecoded = 0L
    /** Test scheduling seam after releasing the SQL monitor, before frame/JSON decoding. */
    internal var afterReadChunk: () -> Unit = {}
    private var head = read<HistoryHead>("head")
    init {
        require(head.version == 1 && head.sessionId == sessionId && head.revision >= 0 && head.messageCount >= 0)
        require(head.revision == databaseRevision())
        if (head.revision == 0L) require(head.messageCount == 0L && head.hash == EMPTY_HASH)
        else batch(cursor())
    }
    private fun databaseRevision(): Long = database.access { db.prepare("SELECT ${integerColumn("revision")} FROM sessions WHERE session_id=?").use {
        it.bindText(1, sessionId); require(it.step()); it.getLong(0)
    } }
    internal fun hasValue(name: String): Boolean = database.access {
        db.prepare("SELECT 1 FROM history_values WHERE session_id=? AND name=?").use {
            it.bindText(1, sessionId); it.bindText(2, name); it.step()
        }
    }
    @Synchronized fun io() = HistoryIo(bytesRead, bytesWritten, filesRead, filesWritten, messagesDecoded)
    private fun ready() {
        check(!closed && !poisoned) { "History closed or write uncertain; reopen and recover" }
        try { database.ready() }
        catch (error: StoreException) { throw HistoryStorageException("Database requires reopen and recovery", error) }
    }
    @Synchronized override fun revision(): Long { ready(); return head.revision }
    private fun cursor() = HistoryCursor(head.sessionId, head.revision, head.hash, head.messageCount)
    private fun batchPath(revision: Long) = "batches/$revision"
    private fun callPath(callId: String) = "calls/${digest(callId.toByteArray(Charsets.UTF_8))}"
    private fun pendingPath() = "pending"
    private fun checkpointPath() = "context"

    private fun accountingPath(taskChainId: String): String {
        require(taskChainId.isNotBlank())
        HistoryCodec.requireUtf8(taskChainId)
        return "accounting/${digest(taskChainId.toByteArray(Charsets.UTF_8))}"
    }

    /** One bounded atomic counter per chain, independent of history and context-view provenance. */
    @Synchronized internal fun loadAccounting(taskChainId: String): RunAccounting? {
        ready()
        val path = accountingPath(taskChainId)
        return if (hasValue(path)) read<RunAccounting>(path).also {
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

    private var runActive = false
    @Synchronized internal fun beginRun() {
        ready(); check(!runActive) { "Session already has an active run" }; runActive = true
    }
    @Synchronized internal fun endRun() { runActive = false }

    private fun receiptKey(value: Any): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(b: Int) { digest.update(b.toByte()) }
            override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
        }
        HistoryCodec.writeValue(value, sink)
        return "ack/" + digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun reconciled(value: Any): Boolean {
        val key = receiptKey(value)
        if (!hasValue(key)) return false
        val receipt = read<RetryReceipt>(key)
        require(receipt.sessionId == sessionId && (receipt.barrier == value || receipt.checkpoint == value))
        return true
    }
    private fun rawBarrier(): ExecutionBarrier? {
        val path = "execution"
        return if (hasValue(path)) read<ExecutionBarrier>(path).also { require(it.sessionId == sessionId) } else null
    }
    @Synchronized internal fun checkpointReconciled(value: ContextCheckpoint): Boolean {
        ready(); return reconciled(value)
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
            hasValue(pendingPath())) return false
        val (barrier, checkpoint) = retryEvidence()
        if (barrier == null && checkpoint == null || id != retryIdentifier(barrier, checkpoint)) return false
        val receipt = RetryReceipt(id, sessionId, caller.runId, note, barrier, checkpoint)
        transaction("reconciliations") {
            writeValue("reconciliations/$id", receipt)
            barrier?.let { writeValue(receiptKey(it), receipt) }
            checkpoint?.let { writeValue(receiptKey(it), receipt) }
        }
        return true
    }

    @Synchronized internal fun saveBarrier(value: ExecutionBarrier) {
        ready(); require(value.sessionId == sessionId)
        val previous = loadBarrier()
        require(previous == null || previous.taskChainId == value.taskChainId)
        write("execution", value)
    }
    @Synchronized internal fun loadBarrier(): ExecutionBarrier? {
        ready()
        val value = rawBarrier() ?: return null
        return value.takeUnless { barrier -> reconciled(barrier) }
    }
    @Synchronized internal fun completeServerExecution(identity: RunIdentity) {
        ready()
        val barrier = loadBarrier() ?: return
        require(barrier.taskChainId == identity.taskChainId && barrier.runId == identity.runId)
        try { remove("execution") }
        catch (e: Exception) { poisoned = true; throw HistoryStorageException("Execution barrier cleanup uncertain", e) }
    }
    @Synchronized internal fun loadView(): ContextCheckpoint? {
        ready()
        return if (hasValue("view")) read<ContextCheckpoint>("view") else null
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
        if (value.summary != null) write("view", value.copy(kind = "VIEW", nextRunId = null))
        write(checkpointPath(), value)
    }

    @Synchronized internal fun loadCheckpoint(): ContextCheckpoint? {
        ready()
        if (!hasValue(checkpointPath())) return null
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

    private inline fun <reified T> read(name: String): T {
        try {
            val input = valueInput(name)
            input.use {
                val framed = HistoryFrameInput(it)
                val result = HistoryCodec.json.decodeFromStream<T>(framed)
                require(framed.read() == -1)
                return result
            }
        } catch (error: Exception) { throw HistoryStorageException("Invalid history value $name", error) }
    }
    private fun valueInput(name: String): InputStream {
        require(hasValue(name)) { "Missing history value $name" }
        filesRead++
        return object : InputStream() {
            var ordinal = 0L
            var chunk = ByteArrayInputStream(byteArrayOf())
            var ended = false
            private fun next(): Boolean {
                if (chunk.available() > 0) return true
                if (ended) return false
                // Immutable values cannot change under this session's lock. Seek the next row
                // by the composite primary key; no cursor/transaction survives decoding or yield.
                val bytes = database.access {
                    val seek = if (ordinal == 0L) "" else " AND ordinal>=?"
                    db.prepare("SELECT ${integerColumn("ordinal")}, ${blobColumn("bytes", HISTORY_FRAME_BYTES)} FROM history_chunks WHERE session_id=? AND name=?$seek ORDER BY ordinal LIMIT 1").use { statement ->
                        statement.bindText(1, sessionId); statement.bindText(2, name)
                        if (ordinal != 0L) statement.bindLong(3, ordinal)
                        if (!statement.step()) null else {
                            require(statement.getLong(0) == ordinal++ && !statement.isNull(1)) { "Invalid history chunk" }
                            statement.getBlob(1).also { require(it.size in 1..HISTORY_FRAME_BYTES) { "Invalid history chunk size" } }
                        }
                    }
                }
                if (bytes == null) { ended = true; return false }
                bytesRead += bytes.size
                chunk = ByteArrayInputStream(bytes)
                afterReadChunk()
                return true
            }
            override fun read(): Int = if (next()) chunk.read() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else if (next()) chunk.read(b, off, len) else -1
        }
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
        check(!hasValue(pendingPath())) { "Pending tool step requires cold reconciliation before model replay" }
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
        check(!hasValue(pendingPath())) { "Pending tool step must be recovered first" }
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
            require(!hasValue(callPath(call.binding.callId))) { "Duplicate call identity" }
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
        val pending = if (hasValue(pendingPath())) read<StoredPending>(pendingPath()) else null
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
            if (hasValue(path)) {
                val location = read<CallLocation>(path)
                require(location.callId == id && location.revision > 0)
                throw IllegalArgumentException("Duplicate call identity")
            }
        }
        val newHead = HistoryHead(sessionId = sessionId, revision = value.revision,
            messageCount = Math.addExact(head.messageCount, next.size.toLong()), hash = hash)
        transaction("append") {
            db.prepare("UPDATE sessions SET revision=? WHERE session_id=? AND revision=?").use {
                it.bindLong(1, value.revision); it.bindText(2, sessionId); it.bindLong(3, expectedRevision); it.step()
            }
            require(db.longValue("SELECT changes()") == 1L) { "Revision conflict" }
            writeValue(batchPath(value.revision), value)
            for (id in calls) writeValue(callPath(id), CallLocation(id, value.revision, hash))
            writeValue("head", newHead)
        }
        head = newHead
        if (pending != null) clearPending()
    }

    /** Call only after InvocationLedger.recoverColdStart. Never execute or re-submit a tool here.
     * Missing registrations are known unsubmitted because AgentRunLoop registers the entire frozen
     * step before execution. Existing terminal truth wins, including UNKNOWN and late-exit evidence.
     * A stale pending value after HEAD commit is verified and removed without a second history append.
     */
    @Synchronized fun recoverPending(ledger: InvocationLedger): HistoryRecovery {
        ready()
        if (!hasValue(pendingPath())) return HistoryRecovery(false, false, emptyList())
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
    private fun clearPending() = remove(pendingPath())
    private fun remove(name: String) = transaction(name) {
        db.prepare("DELETE FROM history_values WHERE session_id=? AND name=?").use {
            it.bindText(1, sessionId); it.bindText(2, name); it.step()
        }
    }
    private fun <T> transaction(name: String, block: () -> T): T {
        try {
            val result = database.transaction {
                point(name, HistoryWriteStage.AFTER_BEGIN)
                val result = block()
                if (name == "reconciliations") {
                    point(name, HistoryWriteStage.AFTER_CHUNK)
                    point(name, HistoryWriteStage.AFTER_VALUE)
                }
                point(name, HistoryWriteStage.BEFORE_COMMIT)
                result
            }
            point(name, HistoryWriteStage.AFTER_COMMIT)
            return result
        } catch (error: Throwable) {
            poisoned = true; database.poison()
            if (error is Exception) throw HistoryStorageException("History transaction failed: $name; reopen and recover", error)
            throw error
        }
    }
    private fun write(name: String, value: Any) = transaction(name) { writeValue(name, value) }
    private fun writeValue(name: String, value: Any) {
        db.prepare("DELETE FROM history_values WHERE session_id=? AND name=?").use {
            it.bindText(1, sessionId); it.bindText(2, name); it.step()
        }
        db.prepare("INSERT INTO history_values(session_id,name) VALUES(?,?)").use {
            it.bindText(1, sessionId); it.bindText(2, name); it.step()
        }
        db.prepare("INSERT INTO history_chunks(session_id,name,ordinal,bytes) VALUES(?,?,?,?)").use { statement ->
            val output = object : OutputStream() {
                val buffer = ByteArray(HISTORY_FRAME_BYTES)
                var used = 0
                var ordinal = 0L
                override fun write(b: Int) { buffer[used++] = b.toByte(); if (used == buffer.size) flush() }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    var offset = off; var remaining = len
                    while (remaining > 0) {
                        val n = minOf(remaining, buffer.size - used)
                        b.copyInto(buffer, used, offset, offset + n); used += n; offset += n; remaining -= n
                        if (used == buffer.size) flush()
                    }
                }
                override fun flush() {
                    if (used == 0) return
                    statement.bindText(1, sessionId); statement.bindText(2, name); statement.bindLong(3, ordinal++)
                    statement.bindBlob(4, buffer.copyOf(used)); statement.step(); statement.reset()
                    bytesWritten += used; used = 0
                    point(name, HistoryWriteStage.AFTER_CHUNK)
                }
            }
            val framed = HistoryFrameOutput(output)
            HistoryCodec.writeValue(value, framed); framed.finish(); output.flush()
        }
        filesWritten++
        point(name, HistoryWriteStage.AFTER_VALUE)
    }
    private fun point(name: String, stage: HistoryWriteStage) = fault(HistoryWritePoint(name, stage))
    @Synchronized override fun close() {
        if (!closed) { closed = true; database.releaseSession(sessionId) }
    }
    companion object {
        private const val EMPTY_HASH = "ROOT"
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
        /** Idempotent creation: a crash before COMMIT leaves no session; after it, retry opens
         * the existing session without resetting its revision, pending work, or checkpoints. */
        fun create(database: AgentDatabase, sessionId: String = UUID.randomUUID().toString(),
                   fault: (HistoryWritePoint) -> Unit = {}): SqliteRunHistory {
            require(sessionId.isNotBlank()); HistoryCodec.requireUtf8(sessionId)
            database.bindSession(sessionId)
            try {
                database.access {
                    val exists = database.connection.prepare("SELECT 1 FROM sessions WHERE session_id=?").use {
                        it.bindText(1, sessionId); it.step()
                    }
                    if (!exists) {
                        // Bootstrap uses the same streaming writer as later updates, without reading HEAD.
                        database.transaction {
                            fault(HistoryWritePoint("create", HistoryWriteStage.AFTER_BEGIN))
                            database.connection.prepare("INSERT INTO sessions(session_id,revision) VALUES(?,0)").use {
                                it.bindText(1, sessionId); it.step()
                            }
                            val head = HistoryHead(sessionId = sessionId, revision = 0, messageCount = 0, hash = EMPTY_HASH)
                            val out = java.io.ByteArrayOutputStream()
                            HistoryFrameOutput(out).apply { HistoryCodec.writeValue(head, this); finish() }
                            database.connection.prepare("INSERT INTO history_values(session_id,name) VALUES(?,'head')").use {
                                it.bindText(1, sessionId); it.step()
                            }
                            database.connection.prepare("INSERT INTO history_chunks(session_id,name,ordinal,bytes) VALUES(?,'head',?,?)").use {
                                val bytes = out.toByteArray()
                                var offset = 0
                                while (offset < bytes.size) {
                                    val end = minOf(offset + HISTORY_FRAME_BYTES, bytes.size)
                                    it.bindText(1, sessionId); it.bindLong(2, (offset / HISTORY_FRAME_BYTES).toLong())
                                    it.bindBlob(3, bytes.copyOfRange(offset, end)); it.step(); it.reset(); offset = end
                                    fault(HistoryWritePoint("create", HistoryWriteStage.AFTER_CHUNK))
                                }
                            }
                            fault(HistoryWritePoint("create", HistoryWriteStage.AFTER_VALUE))
                            fault(HistoryWritePoint("create", HistoryWriteStage.BEFORE_COMMIT))
                        }
                        try { fault(HistoryWritePoint("create", HistoryWriteStage.AFTER_COMMIT)) }
                        catch (error: Throwable) { database.poison(); throw error }
                    }
                }
                return SqliteRunHistory(database, sessionId, fault)
            } catch (error: Throwable) { database.releaseSession(sessionId); throw error }
        }
        fun open(database: AgentDatabase, sessionId: String,
                 fault: (HistoryWritePoint) -> Unit = {}): SqliteRunHistory {
            database.bindSession(sessionId)
            try { return SqliteRunHistory(database, sessionId, fault) }
            catch (error: Throwable) { database.releaseSession(sessionId); throw error }
        }
    }
}
