package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Reference identity binds MIME metadata; storage remains shared by content hash.
 * Absent contentHash/chunksHash decode the original hash-only format without rewriting it.
 */
@Serializable data class BlobRef(val id: String, val mimeType: String, val sizeBytes: Long,
    val contentHash: String = id, val chunksHash: String? = null) {
    init {
        require(id.matches(Regex("[0-9a-f]{64}")) && contentHash.matches(Regex("[0-9a-f]{64}")))
        require(chunksHash == null || chunksHash.matches(Regex("[0-9a-f]{64}")))
        require(mimeType.length in 1..128 && mimeType.none { it.isISOControl() })
        require(sizeBytes >= 0)
        require(if (chunksHash == null) id == contentHash else
            id == referenceId(contentHash, mimeType, sizeBytes, chunksHash)) { "Invalid blob reference identity" }
    }
}
private fun referenceId(hash: String, mime: String, size: Long, chunks: String) =
    blobDigest("blob-ref-v2\n$hash\n$mime\n$size\n$chunks".toByteArray(Charsets.UTF_8))
internal const val BLOB_CHUNK_SIZE = 8192
internal fun chunkManifest(bytes: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    val result = java.io.ByteArrayOutputStream()
    var offset = 0
    while (offset < bytes.size) {
        digest.update(bytes, offset, minOf(BLOB_CHUNK_SIZE, bytes.size - offset))
        result.write(digest.digest()); offset += minOf(BLOB_CHUNK_SIZE, bytes.size - offset)
    }
    return result.toByteArray()
}
class BlobWrite(bytes: ByteArray, mimeType: String) {
    private val content = bytes.copyOf()
    private val manifest = chunkManifest(content)
    private val hash = blobDigest(content)
    private val chunks = blobDigest(manifest)
    val ref = BlobRef(referenceId(hash, mimeType, content.size.toLong(), chunks), mimeType,
        content.size.toLong(), hash, chunks)
    fun bytes(): ByteArray = content.copyOf()
    internal fun manifest(): ByteArray = manifest.copyOf()
}
internal fun blobDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

@Serializable enum class MediaKind { IMAGE, AUDIO, DOCUMENT, VIDEO }
/** The SDK byte payload is detached on its bounded worker; only the host constructs references. */
sealed interface OutputPart {
    data class Text(val text: String) : OutputPart
    class Media(val kind: MediaKind, val mimeType: String, val bytes: ByteArray) : OutputPart
}
@Serializable data class StoredOutputPart(val text: String? = null, val kind: MediaKind? = null, val blob: BlobRef? = null)
@Serializable data class RawToolOutput(val content: List<StoredOutputPart>, val structured: JsonElement?,
    val isError: Boolean, val effect: Effect?)
/** All children are explicit GC roots: collection never has to guess references from text/JSON. */
@Serializable data class StoredToolOutput(val raw: BlobRef, val media: List<BlobRef>, val warning: String? = null) {
    fun references(): List<BlobRef> = listOf(raw) + media
}
data class NormalizedOutput(val text: String, val isError: Boolean, val stored: StoredToolOutput,
    val writes: List<BlobWrite>)

object ToolOutputNormalizer {
    const val TEXT_LIMIT = 32 * 1024
    private val json = Json { encodeDefaults = true }
    fun normalize(content: List<OutputPart>, structured: JsonElement?, isError: Boolean, effect: Effect?,
        outputSchema: String?, validator: ArgumentsValidator, retrievalName: String): NormalizedOutput {
        require(retrievalName.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val writes = mutableListOf<BlobWrite>()
        val parts = content.map { part -> when (part) {
            is OutputPart.Text -> StoredOutputPart(text = part.text)
            is OutputPart.Media -> {
                val write = BlobWrite(part.bytes, part.mimeType)
                writes += write
                StoredOutputPart(kind = part.kind, blob = write.ref)
            }
        } }
        val raw = BlobWrite(json.encodeToString(RawToolOutput.serializer(),
            RawToolOutput(parts, structured, isError, effect)).toByteArray(Charsets.UTF_8), "application/json")
        writes += raw
        val valid = structured == null || outputSchema == null || validator.valid(outputSchema, structured.toString())
        val warning = if (valid) null else "Tool returned invalid structured output"
        val original = parts.map { part -> part.text ?: "[${part.kind}: ${part.blob!!.mimeType}; blob ${part.blob.id}; " +
            "retrieve with $retrievalName(id=\"${part.blob.id}\", offset=0)]" }
        val empty = original.all { it.isEmpty() }
        val text = buildList {
            if (structured != null && valid) add("[structured JSON]\n$structured")
            addAll(original)
            if (!valid && empty) add(warning!!)
        }.joinToString("\n")
        val notice = "\n[Truncated tool result; full original JSON: blob ${raw.ref.id}. " +
            "Retrieve with $retrievalName(id=\"${raw.ref.id}\", offset=0); follow nextOffset. " +
            "Data is base64-encoded bytes. Truncated JSON above is text, not a complete structured value.]"
        val bounded = ModelResultText.bound(text, notice)
        return NormalizedOutput(bounded, isError || (!valid && empty),
            StoredToolOutput(raw.ref, parts.mapNotNull { it.blob }, warning), writes)
    }
}

/** Do not split a UTF-8 sequence or UTF-16 surrogate pair. */
fun utf8Prefix(text: String, byteLimit: Int): String {
    require(byteLimit >= 0)
    var end = 0
    var size = 0
    while (end < text.length) {
        val c = text[end]
        val pair = c.isHighSurrogate() && end + 1 < text.length && text[end + 1].isLowSurrogate()
        val cost = when {
            pair -> 4
            c.isSurrogate() || c < '\u0080' -> 1
            c < 'ࠀ' -> 2
            else -> 3
        }
        if (cost > byteLimit - size) break
        size += cost; end += if (pair) 2 else 1
    }
    return String(text.substring(0, end).toByteArray(Charsets.UTF_8), Charsets.UTF_8)
}

internal fun InvocationRecord.blobReferences(): List<BlobRef> =
    listOfNotNull(result?.output).flatMap { it.references() } +
        evidence.flatMap { it.outcome?.output?.references().orEmpty() }
