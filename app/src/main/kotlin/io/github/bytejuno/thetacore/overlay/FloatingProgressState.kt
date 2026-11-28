package io.github.bytejuno.thetacore.overlay

import io.github.bytejuno.thetacore.sdk.TaskPhase
import io.github.bytejuno.thetacore.sdk.TaskProgress

internal data class FloatingTask(val taskId: String, val phase: TaskPhase, val toolName: String?) {
    val needsAttention get() = phase == TaskPhase.APPROVAL || phase == TaskPhase.RECOVERY
}

/** Main-thread state. Capture nesting never restores an obsolete or disabled task. */
internal class FloatingProgressState(private val render: (FloatingTask?) -> Unit) {
    var task: FloatingTask? = null; private set
    private var enabled = false
    private var permission = false
    private var foreground = false
    private var toolTask = false
    private var captures = 0

    fun present(progress: TaskProgress?) {
        if (task?.taskId != progress?.taskId) toolTask = false
        task = progress?.takeUnless { it.phase == TaskPhase.COMPLETED }
            ?.let { FloatingTask(it.taskId, it.phase, it.toolName) }
        // Frozen model names may be namespaced or hashed. Keep every tool chain visible,
        // including screen tools, rather than guessing a tool identity from its alias.
        if (task?.phase == TaskPhase.TOOL) toolTask = true
        refresh()
    }
    fun clear(taskId: String) { if (task?.taskId == taskId) { task = null; toolTask = false; refresh() } }
    fun enabled(value: Boolean) { enabled = value; refresh() }
    fun permission(value: Boolean) { permission = value; refresh() }
    fun foreground(value: Boolean) { foreground = value; refresh() }
    fun beforeCapture() { captures++; refresh() }
    fun afterCapture() { if (captures > 0) captures--; refresh() }
    private fun refresh() = render(task?.takeIf {
        enabled && permission && captures == 0 && (!foreground || toolTask || it.needsAttention)
    })
}
