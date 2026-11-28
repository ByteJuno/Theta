package io.github.bytejuno.thetacore.overlay

import io.github.bytejuno.thetacore.chat.ChatState
import io.github.bytejuno.thetacore.sdk.TaskPhase
import io.github.bytejuno.thetacore.sdk.TaskProgress

internal data class FloatingAttention(override val taskId: String, override val phase: TaskPhase,
    val requestId: String) : TaskProgress {
    override val toolName: String? = null
    override val waitingUser = true
}

/** Read-only projection: opening an attention entry never admits or retries a tool. */
internal fun floatingAttention(chat: ChatState): FloatingAttention? {
    val session = chat.sessionId ?: return null
    val records = chat.records.values.filter { it.input.sessionId == session && !it.input.unattended }
    if (chat.pending) records.firstOrNull { it.pending != null }?.let { record ->
        val request = record.pending!!.id
        return FloatingAttention("approval:$session:$request", TaskPhase.APPROVAL, request)
    }
    // Same-process isolation also needs a decision; recoveryPending alone only
    // describes the cold-recovery barrier. The ledger's request map is authoritative.
    records.firstOrNull { it.callId in chat.recoveries }?.let { record ->
        val request = chat.recoveries.getValue(record.callId).id
        return FloatingAttention("recovery:$session:$request", TaskPhase.RECOVERY, request)
    }
    return null
}
