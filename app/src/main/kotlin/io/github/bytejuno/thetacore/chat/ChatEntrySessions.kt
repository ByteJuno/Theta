package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord
import io.github.bytejuno.thetacore.agent.protocol.HistoryDisposition
import io.github.bytejuno.thetacore.agent.protocol.ModelEvent
import io.github.bytejuno.thetacore.agent.run.ModelAttempt
import io.github.bytejuno.thetacore.agent.run.RunJournal
import io.github.bytejuno.thetacore.agent.run.SessionPreview
import io.github.bytejuno.thetacore.agent.run.ToolExecutionBridge
import io.github.bytejuno.thetacore.plugin.HostToolExecutionService
import io.github.bytejuno.thetacore.sdk.EntryRequest
import io.github.bytejuno.thetacore.sdk.EntrySession
import io.github.bytejuno.thetacore.sdk.EntryUnavailableException
import io.github.bytejuno.thetacore.sdk.SessionEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Borrows the application's one chat writer; never creates a second history/run owner.
 * The chat mutex serializes session selection and send admission with UI operations.
 * Event queues are bounded and never suspend the kernel on a plugin consumer. */
internal class ChatEntrySessions(
    private val gate: Mutex,
    private val scope: CoroutineScope,
    private val host: suspend () -> HostToolExecutionService,
    private val state: () -> ChatState,
    private val select: suspend (String?, List<SessionPreview>) -> Unit,
    private val send: suspend (String) -> Unit,
) {
    private val lock = Any()
    private val handles = mutableSetOf<Handle>()
    internal val handleCount: Int get() = synchronized(lock) { handles.size }

    suspend fun open(request: EntryRequest, admitted: () -> Boolean = { true }): EntrySession {
        // The caller may be cancelled while withContext dispatches the result back.
        var undelivered: Handle? = null
        try {
            return withContext(Dispatchers.IO) {
                gate.withLock {
                    check(admitted())
                    require(request.text.length <= 32_000)
                    val store = requireNotNull(host().sessionStore)
                    checkIdle()
                    val id = request.sessionId?.takeUnless { it.isEmpty() }
                    // Validate a different history before releasing the owner's current binding.
                    if (id != null && id != state().sessionId) store.openHistory(id).close()
                    synchronized(lock) { check(handles.size < 32) }
                    select(id, store.sessionPreviews())
                    val handle = Handle(requireNotNull(state().sessionId), admitted)
                    undelivered = handle
                    synchronized(lock) { handles += handle }
                    if (request.text.isNotEmpty()) sendLocked(handle, request.text)
                    handle
                }
            }.also { undelivered = null }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw EntryUnavailableException() }
        finally { undelivered?.close() }
    }

    private fun checkIdle() {
        val current = state()
        check(!current.active && !current.pending && current.records.values.none { it.pending != null })
    }

    private suspend fun sendLocked(handle: Handle, text: String) {
        check(!handle.closed.get() && handle.accepting && handle.admitted())
        checkIdle()
        require(text.isNotBlank() && text.length <= 32_000)
        if (state().sessionId != handle.sessionId) {
            val store = requireNotNull(host().sessionStore)
            store.openHistory(handle.sessionId).close()
            select(handle.sessionId, store.sessionPreviews())
            checkIdle()
        }
        check(!handle.closed.get() && handle.accepting && handle.admitted())
        send(text)
    }

    fun modelEvent(sessionId: String, event: ModelEvent) = synchronized(lock) {
        handles.filter { it.sessionId == sessionId }.forEach { it.text.accept(event) }
    }

    fun observeAttempts(sessionId: String, journal: RunJournal): RunJournal = object : RunJournal by journal {
        override fun finished(attempt: ModelAttempt) {
            journal.finished(attempt)
            val boundary = object : SessionEvent.AttemptEnded {
                override val discardText = attempt.cancelled || attempt.disposition == HistoryDisposition.DISCARD
            }
            synchronized(lock) {
                handles.filter { it.sessionId == sessionId }.forEach {
                    it.text.endAttempt()
                    it.offer(boundary)
                }
            }
        }
    }

    fun observeTools(delegate: ToolExecutionBridge, host: HostToolExecutionService,
        sessionId: String): ToolExecutionBridge = object : ToolExecutionBridge by delegate {
        override suspend fun awaitTerminal(callId: String) {
            records(sessionId, listOfNotNull(host.ledger.get(callId)))
            delegate.awaitTerminal(callId)
            records(sessionId, listOfNotNull(host.ledger.get(callId)))
        }
    }

    fun records(sessionId: String, records: Collection<InvocationRecord>) = synchronized(lock) {
        handles.filter { it.sessionId == sessionId }.forEach { handle ->
            records.sortedBy { it.input.callOrder }.forEach recordLoop@ { record ->
                val name = record.input.definition?.modelName ?: return@recordLoop
                val status = record.state.name
                val previous = handle.tools[record.callId]
                if (previous != null && previous.revision >= record.revision) return@recordLoop
                handle.tools[record.callId] = ToolSnapshot(record.revision, status)
                if (previous?.status != status) handle.offer(object : SessionEvent.ToolActivity {
                    override val toolName = name
                    override val state = status
                })
                record.pending?.let { pending ->
                    if (handle.approvals.add(pending.id)) handle.offer(object : SessionEvent.NeedsApproval {
                        override val summary = pending.summary.take(4096)
                    })
                }
            }
        }
    }

    fun finished(current: ChatState) {
        val id = current.sessionId ?: return
        val event = if (current.error == null && current.notice == R.string.chat_complete && !current.pending)
            SessionEvent.Finished else SessionEvent.Failed("Chat stopped or needs attention in Theta")
        emit(id, event)
        synchronized(lock) { handles.filter { it.sessionId == id }.forEach { it.tools.clear(); it.approvals.clear() } }
    }

    private fun emit(sessionId: String, event: SessionEvent) = synchronized(lock) {
        handles.filter { it.sessionId == sessionId }.forEach { it.offer(event) }
    }

    private class ToolSnapshot(val revision: Long, val status: String)

    private inner class Handle(val sessionId: String, val admitted: () -> Boolean) : EntrySession {
        val closed = AtomicBoolean()
        val tools = mutableMapOf<String, ToolSnapshot>()
        val approvals = mutableSetOf<String>()
        private val queue = EntryEventQueue(scope, ::finish)
        val text = EntryTextStream(queue)
        val accepting: Boolean get() = queue.accepting
        override val events: Flow<SessionEvent> = queue.events

        fun offer(event: SessionEvent) {
            if (closed.get()) return
            queue.offer(event)
        }

        override suspend fun send(text: String) = withContext(Dispatchers.IO) {
            try { gate.withLock { sendLocked(this@Handle, text) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw EntryUnavailableException() }
        }

        private fun finish() = synchronized(lock) {
            if (closed.compareAndSet(false, true)) {
                handles.remove(this)
                tools.clear(); approvals.clear()
            }
        }

        override fun close() { finish(); queue.close() }
    }
}
