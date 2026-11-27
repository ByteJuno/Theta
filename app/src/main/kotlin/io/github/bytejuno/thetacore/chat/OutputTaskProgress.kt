package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.sdk.TaskPhase
import io.github.bytejuno.thetacore.sdk.TaskProgress

internal data class OutputTaskProgress(
    override val taskId: String,
    override val phase: TaskPhase,
    override val toolName: String?,
) : TaskProgress {
    override val waitingUser get() = phase == TaskPhase.APPROVAL || phase == TaskPhase.RECOVERY
}

internal fun ChatTaskProgress.outputTask(id: String) = OutputTaskProgress(id, when (this) {
    ChatTaskProgress.Model -> TaskPhase.MODEL
    is ChatTaskProgress.Tool -> TaskPhase.TOOL
    ChatTaskProgress.Approval -> TaskPhase.APPROVAL
    ChatTaskProgress.Recovery -> TaskPhase.RECOVERY
}, (this as? ChatTaskProgress.Tool)?.name)
