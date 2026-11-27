package io.github.bytejuno.thetacore.sdk

/** An optional projection of the host's task notification. Higher priorities are preferred. */
interface OutputSurface : Extension {
    val displayName: String
    val priority: Int
    fun isAvailable(device: DeviceInfo): Boolean
    fun show(task: TaskProgress)
    fun clear(taskId: String)
}

/** Host-owned snapshot. The identifier remains stable for the lifetime of an execution. */
interface TaskProgress {
    val taskId: String
    val phase: TaskPhase
    val toolName: String?
    val waitingUser: Boolean
}

enum class TaskPhase { MODEL, TOOL, APPROVAL, RECOVERY, COMPLETED }
