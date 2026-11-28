package io.github.bytejuno.thetacore.overlay

import io.github.bytejuno.thetacore.chat.ChatState

/** A stale window must never cancel a newer execution or another session's saved step. */
internal fun stopFloatingTask(task: FloatingTask, sessionId: String?, state: ChatState, stop: (String?, String?) -> Unit) {
    if (state.executionId == task.taskId) stop(task.taskId, null)
    else if (task.needsAttention && sessionId != null && state.sessionId == sessionId &&
        !state.active && state.pending) stop(null, sessionId)
}
