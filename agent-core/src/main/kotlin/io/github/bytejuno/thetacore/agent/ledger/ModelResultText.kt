package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The one model envelope encoder. Budgets are UTF-8 bytes AFTER JSON escaping. */
object ModelResultText {
    const val LIMIT = 32 * 1024
    fun encode(status: Status, effect: Effect, text: String): String = buildJsonObject {
        put("status", status.name.lowercase()); put("effect", effect.name.lowercase()); put("message", text)
    }.toString()
    private fun budget(status: Status, effect: Effect) = LIMIT - encode(status, effect, "").length
    internal class Work { var codeUnits = 0; var prefixCodeUnits = 0 }
    // kotlinx JSON escapes quotes, backslashes and C0 controls; other characters are emitted
    // literally. JVM UTF-8 replaces an unpaired surrogate with one '?' byte, not U+FFFD.
    private fun scan(text: String, budget: Int, work: Work?, boundary: (Int, Int) -> Unit = { _, _ -> }): Int {
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val c = text[index]
            val pair = c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
            val width = if (pair) 2 else 1
            work?.let { it.codeUnits += width }
            val cost = when {
                c == '"' || c == '\\' || c in "\b\t\n\u000c\r" -> 2
                c < ' ' -> 6
                pair -> 4
                c.isSurrogate() || c < '\u0080' -> 1
                c < 'ࠀ' -> 2
                else -> 3
            }
            if (cost > budget - bytes) return -1
            bytes += cost; index += width
            boundary(index, bytes)
        }
        return bytes
    }
    // Longest terminal status and effect. Stops after at most LIMIT+1 code points.
    fun fits(text: String): Boolean = scan(text, budget(Status.TIMED_OUT, Effect.UNKNOWN), null) >= 0
    fun bound(text: String, notice: String, status: Status = Status.TIMED_OUT, effect: Effect = Effect.UNKNOWN): String =
        boundMeasured(text, notice, status, effect, null)
    internal fun boundMeasured(text: String, notice: String, status: Status = Status.TIMED_OUT,
        effect: Effect = Effect.UNKNOWN, work: Work?): String {
        val budget = budget(status, effect)
        // Only bounded prefix boundaries are retained, never a second scan of the huge input.
        val ends = IntArray(budget + 1)
        var count = 0
        val costs = IntArray(budget + 1)
        if (scan(text, budget, work) { end, cost -> ends[++count] = end; costs[count] = cost } >= 0) return text
        val noticeBytes = scan(notice, budget, work)
        require(noticeBytes >= 0) { "Truncation notice exceeds model budget" }
        while (costs[count] > budget - noticeBytes) count--
        val end = ends[count]
        work?.let { it.prefixCodeUnits += end }
        // Preserve utf8Prefix's established replacement of lone surrogates on truncation only.
        return String(text.substring(0, end).toByteArray(Charsets.UTF_8), Charsets.UTF_8) + notice
    }
    fun project(result: LogicalResult): String {
        // Legacy/direct outcome and host rejection paths retain the full original in the ledger,
        // not a retrievable tool-output blob. Do not invent a blob reference for those paths.
        val text = bound(result.text,
            "\n[Truncated ledger message; full original retained in the invocation ledger.]",
            result.status, result.effect)
        return encode(result.status, result.effect, text)
    }
}
