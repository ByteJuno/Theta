package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.protocol.ModelEvent
import io.github.bytejuno.thetacore.sdk.SessionEvent

/** The reducer opens consecutive indices, but deltas and PartEnd may interleave. */
internal class EntryTextStream(private val queue: EntryEventQueue) {
    private class Part(val text: StringBuilder = StringBuilder(), var ended: Boolean = false)
    private val later = sortedMapOf<Int, Part>()
    private var current = 0
    private var buffered = 0

    fun accept(event: ModelEvent) {
        if (!queue.accepting) return
        when (event) {
            is ModelEvent.TextDelta -> if (event.index == current) queue.offer(SessionEvent.Text(event.text))
                else if (canBuffer(event.index, event.text.length)) {
                    later.getOrPut(event.index, ::Part).text.append(event.text)
                    buffered += event.text.length
                }
            is ModelEvent.PartEnd -> if (event.index == current) advance()
                else if (canBuffer(event.index, 0)) later.getOrPut(event.index, ::Part).ended = true
            else -> Unit
        }
    }

    private fun canBuffer(index: Int, length: Int): Boolean {
        if (length <= 64_000 - buffered && (index in later || later.size < 256)) return true
        queue.fail("Entry text exceeds the out-of-order buffer")
        reset()
        return false
    }

    private fun advance() {
        do {
            val part = later.remove(++current) ?: break
            buffered -= part.text.length
            if (part.text.isNotEmpty()) queue.offer(SessionEvent.Text(part.text.toString()))
        } while (part.ended)
    }

    fun endAttempt() {
        // A disconnected attempt can have unfinished parts. Deliver its partial in
        // journal order before the consumer receives the discard boundary.
        for (part in later.values) {
            if (part.text.isNotEmpty()) queue.offer(SessionEvent.Text(part.text.toString()))
        }
        reset()
    }

    private fun reset() {
        later.clear()
        current = 0
        buffered = 0
    }
}
