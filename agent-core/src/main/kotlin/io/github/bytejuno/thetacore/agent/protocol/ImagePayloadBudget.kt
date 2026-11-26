package io.github.bytejuno.thetacore.agent.protocol

/** Independent of model tokens/windows and reference-only history storage limits.
 * Reserve raw reads plus twelve bytes per base64 character for simultaneous encoder buffers,
 * UTF-16 base64/data URLs, JSON builder growth + final String, and the transport UTF-8 copy.
 * The 64 MiB ceiling leaves headroom in a 256 MiB heap for history, UI and other runtime state.
 * This is a conservative allocation bound, not a guarantee about total process free memory.
 */
internal object ImagePayloadBudget {
    const val MAX_PEAK_BYTES = 64L * 1024 * 1024

    fun admits(media: Sequence<ContentPart.Media>): Boolean {
        var remaining = MAX_PEAK_BYTES
        for (part in media) {
            val raw = part.blob.sizeBytes
            // Bound before arithmetic, including hostile metadata; never overflow into admission.
            if (raw <= 0 || raw > MAX_PEAK_BYTES) return false
            val base64 = ((raw + 2) / 3) * 4
            val peak = raw + base64 * 12 + 1024
            if (peak > remaining) return false
            remaining -= peak
        }
        return true
    }

    fun admitsMessages(messages: List<Message>): Boolean = admits(messages.asSequence()
        .flatMap { it.parts.asSequence() }.filterIsInstance<ContentPart.Media>())
}
