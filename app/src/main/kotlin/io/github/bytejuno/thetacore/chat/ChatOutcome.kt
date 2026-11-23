package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.run.RunResult
import io.github.bytejuno.thetacore.agent.run.RunState
import io.github.bytejuno.thetacore.agent.run.CheckpointInfo

internal fun needsBudgetReadmission(checkpoint: CheckpointInfo?): Boolean = checkpoint?.pauseClass == "BUDGET"

internal fun chatOutcome(result: RunResult): Int = when {
    result.state == RunState.COMPLETED -> R.string.chat_complete
    result.serverExecutionUnknown || result.reason.contains("reconciliation", true) || result.reason.contains("uncertain", true) -> R.string.chat_pause_reconcile
    result.reason.contains("Step limit") || result.reason.contains("Retry limit") -> R.string.chat_pause_budget
    result.reason.contains("memory budget", true) || result.reason.contains("Request budget", true) || result.reason.contains("CONTEXT_TOO_LONG") -> R.string.chat_pause_context
    result.reason.contains("Truncated") -> R.string.chat_pause_truncated
    result.reason == "Interrupted by user" -> R.string.chat_stopped
    result.reason.contains("Repeated tool") -> R.string.chat_pause_repeated
    result.reason.contains("Tool", true) || result.reason.contains("Local", true) -> R.string.chat_pause_tools
    result.reason.contains("refused") -> R.string.chat_reply_refused
    result.state == RunState.FAILED -> R.string.chat_model_failed
    else -> R.string.chat_paused
}

internal fun replyReason(reason: String): Int = when (reason) {
    "REFUSAL" -> R.string.chat_reply_refused
    "CONTENT_FILTER", "CONTENT_BLOCKED" -> R.string.chat_reply_filtered
    "LENGTH" -> R.string.chat_reply_length
    "CONTEXT_EXCEEDED" -> R.string.chat_reply_context
    "EMPTY_RESPONSE" -> R.string.chat_diagnostic_empty
    else -> R.string.chat_reply_interrupted
}
