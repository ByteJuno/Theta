package io.github.bytejuno.thetacore.sample.blockedprovider

import java.io.IOException

internal class SourceReadAborted(val reason: String) : IOException("Fixture source aborted: $reason")

/** onRead is serialized by the descriptor Handler; abort/close may come from another thread. */
internal class PrefixReader(
    private val bytes: ByteArray,
    private val interruptAfterPrefix: Boolean,
    private val gate: ReadGate,
    private val event: (String) -> Unit = {},
) {
    private var positiveReads = 0
    private var returnedBytes = 0L
    private var gateEntered = false
    private var abortLogged = false
    private var eofLogged = false

    init { require(bytes.size > 1) }

    fun read(offset: Long, size: Int, data: ByteArray): Int {
        require(offset >= 0 && size >= 0) { "Invalid source range" }
        checkFailure()
        // Empty probes do not consume the first-positive-read allowance or enter the gate.
        if (size == 0 || data.isEmpty()) return 0
        if (interruptAfterPrefix && positiveReads > 0 && !gateEntered) {
            gateEntered = true
            event("PREFIX_WAIT offset=$offset size=$size reads=$positiveReads returned=$returnedBytes total=${bytes.size}")
            val outcome = gate.await()
            event("PREFIX_GATE_EXIT outcome=$outcome")
            checkFailure()
            // The interrupting source must NEVER turn a released gate into a successful copy.
            gate.abort("UNEXPECTED_GATE_SUCCESS")
            checkFailure()
        }
        if (offset >= bytes.size) {
            if (!eofLogged) {
                eofLogged = true
                event("PREFIX_EOF returned=$returnedBytes reads=$positiveReads")
            }
            return 0
        }
        val count = minOf(size, data.size, bytes.size - offset.toInt(),
            if (positiveReads == 0) bytes.size - 1 else bytes.size)
        bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + count)
        checkFailure()
        positiveReads++
        returnedBytes += count
        // Bounded per-session read logs, in addition to Runtime's bounded UI ring.
        if (positiveReads <= 12) {
            val marker = if (positiveReads == 1) "PREFIX_FIRST_RETURN" else "PREFIX_READ_RETURN"
            event("$marker offset=$offset bytes=$count returned=$returnedBytes total=${bytes.size}")
        } else if (positiveReads == 13) event("PREFIX_READ_LOG_LIMIT limit=12")
        return count
    }

    /** Only actual FD_RELEASE calls this in production; Reset never frees a session slot. */
    fun close() { gate.abort("CLOSED") }

    private fun checkFailure() {
        gate.failureWithDeadline()?.let { reason ->
            if (!abortLogged) {
                abortLogged = true
                event("PREFIX_READ_ABORT reason=$reason reads=$positiveReads returned=$returnedBytes errno=EIO")
            }
            throw SourceReadAborted(reason)
        }
    }
}
