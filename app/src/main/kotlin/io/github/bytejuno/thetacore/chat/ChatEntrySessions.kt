package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord
import io.github.bytejuno.thetacore.agent.protocol.ModelEvent
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

    suspend fun open(request: EntryRequest, admitted: () -> Boolean = { true }): EntrySession = withContext(Dispatchers.IO) {
        try {
            gate.withLock {
                check(admitted())
                require(request.text.length <= 32_000)
                val store = requireNotNull(host().sessionStore)
                checkIdle()
                val id = request.sessionId?.takeUnless { it.isEmpty() }
                // Opening an unknown ID must fail before changing the currently selected history.
                if (id != null) store.openHistory(id).close()
                synchronized(lock) { check(handles.size < 32) }
                select(id, store.sessionPreviews())
                val handle = Handle(requireNotNull(state().sessionId), admitted)
                synchronized(lock) { handles += handle }
                try {
                    if (request.text.isNotEmpty()) sendLocked(handle, request.text)
                    handle
                } catch (error: Throwable) { handle.close(); throw error }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw EntryUnavailableException() }
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

    fun modelEvent(sessionId: String, event: ModelEvent) {
        if (event is ModelEvent.TextDelta) emit(sessionId, SessionEvent.Text(event.text))
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
                if (handle.tools.put(record.callId, status) != status) handle.offer(object : SessionEvent.ToolActivity {
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

    private inner class Handle(val sessionId: String, val admitted: () -> Boolean) : EntrySession {
        val closed = AtomicBoolean()
        val tools = mutableMapOf<String, String>()
        val approvals = mutableSetOf<String>()
        private val queue = EntryEventQueue(scope, ::finish)
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
