package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.ledger.ComposerDocument
import io.github.bytejuno.thetacore.agent.ledger.DocumentLimits
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.UUID

internal fun readDocumentParserResult(stream: DataInputStream): ComposerDocument {
    val status = stream.readUTF()
    if (status != "OK") throw DocumentException(DocumentFailure.valueOf(status))
    val filename = stream.readUTF(); val mime = stream.readUTF(); val size = stream.readLong()
    val length = stream.readInt()
    if (length !in 1..DocumentLimits.CHARACTERS * 4 || filename.length !in 1..160 ||
        mime.length !in 1..128 || size !in 1..DocumentLimits.BYTES) throw DocumentException(DocumentFailure.INVALID)
    val bytes = ByteArray(length)
    stream.readFully(bytes) // A parser death must never admit a truncated success payload.
    if (stream.read() != -1) throw DocumentException(DocumentFailure.INVALID)
    val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    if (text.isBlank() || text.length > DocumentLimits.CHARACTERS) throw DocumentException(DocumentFailure.INVALID)
    return ComposerDocument(UUID.randomUUID().toString(), filename, mime, size, text)
}
