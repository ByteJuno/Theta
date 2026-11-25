package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.protocol.*

/** Independent safety ceiling for materialized request views. Provider token admission still runs.
 * Oversized input is never truncated; old history may be summarized, otherwise the run pauses.
 * Atomic legacy transactions above 8 MiB stay on disk and require host action before runtime use.
 */
internal const val MAX_RUNTIME_BATCH_BYTES = 8L * 1024 * 1024
internal class HistoryViewLimitException : IllegalStateException("History transaction exceeds runtime memory budget; original history retained")

data class RequestViewLimit(val maxMessages: Int = 4096, val maxChars: Long = 2L * 1024 * 1024) {
    init { require(maxMessages > 0 && maxChars > 0) }
    internal fun admits(messages: List<Message>): Boolean {
        if (messages.size > maxMessages) return false
        var remaining = maxChars
        for (m in messages) {
            // Charge structure as well as strings (including opaque replay and tool bindings).
            remaining -= 64
            for (p in m.parts) {
                remaining -= 64 + (p.replay?.let { it.protocol.length.toLong() + it.model.length + it.payload.length } ?: 0)
                remaining -= when (p) {
                    is ContentPart.Text -> p.text.length.toLong()
                    is ContentPart.Media -> p.blob.id.length.toLong() + p.blob.mimeType.length
                    is ContentPart.Reasoning -> (p.summary?.length ?: 0).toLong()
                    is ContentPart.ToolResult -> p.text.length.toLong() + p.callId.length
                    is ContentPart.ToolCall -> with(p.binding) { p.name.length.toLong() + p.argsJson.length +
                        provider.length + requestId.length + (responseId?.length ?: 0) + (wireId?.length ?: 0) + callId.length }
                }
                if (remaining < 0) return false
            }
        }
        return remaining >= 0
    }
}
