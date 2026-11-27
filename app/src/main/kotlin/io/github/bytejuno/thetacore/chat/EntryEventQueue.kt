package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.sdk.SessionEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Kernel producers only enqueue. No plugin continuation runs on their thread or under
 * their locks. One bounded delivery coroutine belongs to each live handle. */
internal class EntryEventQueue(scope: CoroutineScope, private val detached: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val input = Channel<SessionEvent>(256)
    private val output = Channel<SessionEvent>(Channel.RENDEZVOUS)
    private val closed = AtomicBoolean()
    private val collecting = AtomicBoolean()
    // Even cancellation before dispatch must run the finally block on the delivery thread.
    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private val delivery = scope.launch(dispatcher, start = CoroutineStart.ATOMIC) {
        try { for (event in input) output.send(event) }
        finally { output.close() }
    }

    val events: Flow<SessionEvent> = flow {
        check(collecting.compareAndSet(false, true)) { "Entry events have one consumer" }
        try { for (event in output) emit(event) }
        finally { close() }
    }

    val accepting: Boolean get() = !closed.get()

    fun offer(event: SessionEvent) {
        if (closed.get()) return
        if (input.trySend(event).isFailure) fail("Entry event consumer is too slow")
    }

    fun fail(message: String) {
        if (closed.compareAndSet(false, true)) {
            while (input.tryReceive().isSuccess) { /* incomplete delivery is explicitly failed */ }
            input.trySend(SessionEvent.Failed(message))
            input.close()
            // Keep this handle counted until explicit close or collector completion.
        }
    }

    fun close() {
        closed.set(true)
        detached()
        input.cancel()
        // Only a host continuation consumes input. Plugin collectors terminate via output on
        // the delivery dispatcher; never cancel their Job or invoke their code here.
        delivery.cancel()
    }
}
