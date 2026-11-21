package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.AgentDatabase
import io.github.bytejuno.thetacore.agent.ledger.blobColumn
import io.github.bytejuno.thetacore.agent.ledger.integerColumn
import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import io.github.bytejuno.thetacore.agent.protocol.Message
import java.io.ByteArrayInputStream
import kotlinx.serialization.decodeFromString

data class SessionPreview(val sessionId: String, val text: String?)

internal const val SESSION_PREVIEW_KEY = "session-preview"
internal const val SESSION_PREVIEW_BYTES = 1024
internal const val SESSION_PREVIEW_CHARACTERS = 60
// "0" means no USER text yet; "1" commits even a blank first text. Missing means legacy.
internal const val SESSION_PREVIEW_EMPTY = "0"

/** Only derived metadata is read. No session handle, HEAD or batch is opened/decoded.
 * Old sessions deliberately keep their numbered label, including oversized legacy batches.
 * Bound the SQL projection before Android's CursorWindow or JSON sees damaged metadata.
 */
internal fun AgentDatabase.readSessionPreview(sessionId: String): SessionPreview {
    val text = sessionPreviewValue(sessionId)?.takeIf { it.startsWith("1") }?.substring(1)?.takeIf {
        it.isNotEmpty() && it == compactSessionPreview(sequenceOf(it))
    }
    return SessionPreview(sessionId, text)
}

internal fun AgentDatabase.sessionPreviewValue(sessionId: String): String? = access {
    connection.prepare("SELECT ${integerColumn("ordinal")}, ${blobColumn("bytes", SESSION_PREVIEW_BYTES)} FROM history_chunks WHERE session_id=? AND name=? ORDER BY ordinal LIMIT 2").use { q ->
        q.bindText(1, sessionId); q.bindText(2, SESSION_PREVIEW_KEY)
        if (!q.step() || q.getLong(0) != 0L || q.isNull(1)) return@use null
        val bytes = q.getBlob(1)
        if (bytes.size !in 1..SESSION_PREVIEW_BYTES || q.step()) return@use null
        try {
            HistoryFrameInput(ByteArrayInputStream(bytes)).use { frame ->
                HistoryCodec.json.decodeFromString<String>(frame.readBytes().toString(Charsets.UTF_8))
            }
        } catch (_: IllegalArgumentException) { null }
        catch (_: java.io.IOException) { null }
    }
}

internal fun firstUserText(message: Message): String? {
    val parts = message.parts.filterIsInstance<ContentPart.Text>()
    return if (parts.isEmpty()) null else compactSessionPreview(parts.asSequence().map { it.text })
}

/** Bounded output and no join/regex copy of an arbitrarily long input; preserve code points. */
internal fun compactSessionPreview(parts: Sequence<String>): String {
    val out = StringBuilder()
    var count = 0
    var space = false
    var lastStart = 0
    fun append(codePoint: Int): Boolean {
        if (count == SESSION_PREVIEW_CHARACTERS) {
            out.setLength(lastStart)
            out.append('\u2026')
            return false
        }
        lastStart = out.length
        out.appendCodePoint(codePoint); count++
        return true
    }
    for (part in parts) {
        var i = 0
        while (i < part.length) {
            val cp = part.codePointAt(i); i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt()) {
                if (count > 0) space = true
            } else {
                if (space && !append(' '.code)) return out.toString()
                space = false
                if (!append(if (cp in 0xD800..0xDFFF) 0xFFFD else cp)) return out.toString()
            }
        }
    }
    return out.toString()
}
