package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.UiText
import io.github.bytejuno.thetacore.uiText
import io.github.bytejuno.thetacore.agent.protocol.ModelErrorCode
import io.github.bytejuno.thetacore.agent.run.RunState

// Reasons can originate in tool exceptions or old persisted records. Never echo arbitrary text,
// even after token-pattern replacement: keys do not have a universal recognizable format.
internal const val MAX_DIAGNOSTIC_REASON_INPUT = 512
internal fun diagnosticReason(reason: String): UiText {
    if (reason.length > MAX_DIAGNOSTIC_REASON_INPUT) return uiText(R.string.chat_diagnostic_omitted)
    val modelCode = ModelErrorCode.entries.firstOrNull { reason == "Model error: ${it.name}" }
    if (modelCode != null) return uiText(R.string.chat_diagnostic_model, modelCode.name)
    // Checkpoint identifiers and exception suffixes are intentionally never copied to the output.
    val base = reason.substringBefore("; checkpoint ")
    val label = when (base) {
        "Model error: CONTEXT_TOO_LONG", "Request budget exceeded" -> R.string.chat_diagnostic_context
        "Request memory budget exceeded",
        "History transaction exceeds runtime memory budget; original history retained" -> R.string.chat_diagnostic_memory
        "Step limit", "Retry limit" -> R.string.chat_diagnostic_budget
        "Repeated tool call limit" -> R.string.chat_diagnostic_repeated
        "Interrupted by user" -> R.string.chat_stopped
        "Model refused response" -> R.string.chat_reply_refused
        "Empty model response" -> R.string.chat_diagnostic_empty
        "Truncated response" -> R.string.chat_pause_truncated
        "Replay incompatible" -> R.string.chat_diagnostic_replay
        "Server execution state unknown", "Server execution requires reconciliation",
        "Admitted response uncertain; host reconciliation required",
        "Summary response uncertain; host reconciliation required" -> R.string.chat_pause_reconcile
        "Local tool execution or effect requires reconciliation", "Tool execution or effect unresolved",
        "Tool execution or resource recovery unresolved" -> R.string.chat_diagnostic_tools
        "Missing server continuation history", "Server continuation limit" -> R.string.chat_diagnostic_continuation
        "Input accepted; awaiting foreground admission", "Run admitted; awaiting durable completion" -> R.string.chat_diagnostic_incomplete
        "Tool step contains an unpaired Unicode surrogate in arguments, name or binding; no local tools registered" -> R.string.chat_diagnostic_unicode
        else -> when {
            reason.startsWith("Tool step interrupted:") -> R.string.chat_diagnostic_tool_interrupted
            reason.startsWith("Persisted checkpoint requires explicit continuation or user review:") -> R.string.chat_diagnostic_checkpoint
            else -> R.string.chat_diagnostic_omitted
        }
    }
    return uiText(label)
}

/** A pure projection of the CURRENT state. No cached failure, journal body or credential access. */
internal fun chatDiagnostic(state: ChatState): UiText? {
    val result = state.result ?: return null
    if (state.sessionId == null || state.loading || state.active || result.state == RunState.COMPLETED) return null
    return uiText(R.string.chat_diagnostic_summary,
        uiText(if (result.state == RunState.FAILED) R.string.chat_diagnostic_failed else R.string.chat_diagnostic_paused),
        diagnosticReason(result.reason), result.steps.coerceAtLeast(0), result.retries.coerceAtLeast(0),
        uiText(if (result.serverExecutionUnknown) R.string.chat_diagnostic_unknown else R.string.chat_diagnostic_not_reported))
}

