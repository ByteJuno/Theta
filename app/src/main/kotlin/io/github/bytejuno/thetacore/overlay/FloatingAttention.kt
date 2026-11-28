package io.github.bytejuno.thetacore.overlay

import io.github.bytejuno.thetacore.chat.ChatState
import io.github.bytejuno.thetacore.chat.OutputTaskProgress
import io.github.bytejuno.thetacore.sdk.TaskPhase

/** Read-only projection: opening an attention entry never admits or retries a tool. */
internal fun floatingAttention(chat: ChatState): OutputTaskProgress? {
    val session = chat.sessionId ?: return null
    val records = chat.records.values.filter { it.input.sessionId == session && !it.input.unattended }
    if (chat.pending) records.firstOrNull { it.pending != null }?.let { record ->
        return OutputTaskProgress("approval:$session:${record.pending!!.id}", TaskPhase.APPROVAL, null)
    }
    // Same-process isolation also needs a decision; recoveryPending alone only
    // describes the cold-recovery barrier. The ledger's request map is authoritative.
    records.firstOrNull { it.callId in chat.recoveries }?.let { record ->
        return OutputTaskProgress("recovery:$session:${chat.recoveries.getValue(record.callId).id}", TaskPhase.RECOVERY, null)
    }
    return null
}
