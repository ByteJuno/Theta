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
data class NormalizedOutput(
    val text: String,
    val isError: Boolean,
    val stored: StoredToolOutput,
    val writes: List<BlobWrite>,
    val truncationNotice: String,
    internal val rebindWithPrefix: ((String, Status, Effect) -> String)? = null,
)

object ToolOutputNormalizer {
    const val TEXT_LIMIT = 32 * 1024
    private val json = Json { encodeDefaults = true }
    fun normalize(content: List<OutputPart>, structured: JsonElement?, isError: Boolean, effect: Effect?,
        outputSchema: String?, validator: ArgumentsValidator, retrievalName: String?): NormalizedOutput {
        require(retrievalName == null || retrievalName.matches(Regex("[A-Za-z0-9_-]{1,64}")))
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
        // Older pending records may have no frozen retrieval alias. Keep the original blob
        // and its reference without advertising a guessed callable name.
        fun retrieval(id: String) = retrievalName?.let { "Retrieve with $it(id=\"$id\", offset=0)" }
            ?: "No retrieval alias recorded for this run"
        val original = parts.map { part -> part.text ?: "[${part.kind}: ${part.blob!!.mimeType}; blob ${part.blob.id}; " +
            "${retrieval(part.blob.id)}]" }
        val empty = original.all { it.isEmpty() }
        val fullText = buildList {
            if (structured != null && valid) add("[structured JSON]\n$structured")
            addAll(original)
            if (!valid && empty) add(warning!!)
        }.joinToString("\n")
        val mediaPlaceholders = parts.mapIndexedNotNull { index, part ->
            if (part.blob != null) original[index] else null
        }
        val textOnly = buildList {
            if (structured != null && valid) add("[structured JSON]\n$structured")
            parts.forEach { part -> if (part.blob == null) add(part.text!!) }
            if (!valid && empty) add(warning!!)
        }.joinToString("\n")
        val notice = "\n[Truncated tool result; full original JSON: blob ${raw.ref.id}. " +
            "${retrieval(raw.ref.id)}. " + (if (retrievalName != null) "Follow nextOffset. " else "") +
            "Data is base64-encoded bytes. Truncated JSON above is text, not a complete structured value.]"
        val bounded = boundPreservingMedia(
            fullText = fullText,
            textOnly = textOnly,
            mediaPlaceholders = mediaPlaceholders,
            rawId = raw.ref.id,
            notice = notice,
            prefix = "",
            status = Status.TIMED_OUT,
            effect = Effect.UNKNOWN,
        )
        return NormalizedOutput(
            text = bounded,
            isError = isError || (!valid && empty),
            stored = StoredToolOutput(raw.ref, parts.mapNotNull { it.blob }, warning),
            writes = writes,
            truncationNotice = notice,
            rebindWithPrefix = { prefix, status, eff ->
                boundPreservingMedia(fullText, textOnly, mediaPlaceholders, raw.ref.id, notice, prefix, status, eff)
            },
        )
    }

    private fun boundPreservingMedia(
        fullText: String,
        textOnly: String,
        mediaPlaceholders: List<String>,
        rawId: String,
        notice: String,
        prefix: String,
        status: Status,
        effect: Effect,
    ): String {
        val totalBudget = ModelResultText.budgetFor(status, effect)
        val combinedFull = if (prefix.isEmpty()) fullText else prefix + fullText
        if (ModelResultText.encodedBytes(combinedFull, totalBudget) >= 0) return combinedFull
        if (mediaPlaceholders.isEmpty()) {
            return ModelResultText.bound(combinedFull, notice, status, effect)
        }
        val noticeBytes = ModelResultText.encodedBytes(notice, totalBudget)
        require(noticeBytes >= 0) { "Truncation notice exceeds model budget" }
        val prefixBytes = if (prefix.isEmpty()) 0 else ModelResultText.encodedBytes(prefix, totalBudget - noticeBytes)
        if (prefixBytes < 0) {
            return ModelResultText.bound(combinedFull, notice, status, effect)
        }
        val available = totalBudget - noticeBytes - prefixBytes
        val allMedia = mediaPlaceholders.joinToString("\n")
        val allMediaBytes = ModelResultText.encodedBytes(allMedia, available)
        if (allMediaBytes >= 0) {
            val remainingForText = available - allMediaBytes
            val newlineBytes = 2 // JSON-escaped "\n"
            if (textOnly.isNotEmpty() && remainingForText > newlineBytes) {
                val truncatedText = ModelResultText.prefixToBudget(textOnly, remainingForText - newlineBytes)
                if (truncatedText.isNotEmpty()) {
                    return prefix + truncatedText + "\n" + allMedia + notice
                }
            }
            return prefix + allMedia + notice
        }
        // Media placeholders alone exceed the remaining budget: retain as many placeholders as fit
        // and emit an explicit omission notice before the complete truncation notice.
        val totalMedia = mediaPlaceholders.size
        fun omission(omitted: Int, hasShown: Boolean): String =
            (if (hasShown) "\n" else "") +
                "[$omitted of $totalMedia media attachment(s) omitted due to 32 KiB limit; " +
                "full media references are in original JSON: blob $rawId.]"
        val kept = mutableListOf<String>()
        var keptBytes = 0
        for (index in mediaPlaceholders.indices) {
            val placeholder = mediaPlaceholders[index]
            val sepBytes = if (kept.isEmpty()) 0 else 2
            val placeholderBytes = ModelResultText.encodedBytes(placeholder, available)
            if (placeholderBytes < 0) break
            val omittedAfter = totalMedia - (index + 1)
            val omissionBytes = ModelResultText.encodedBytes(omission(omittedAfter, hasShown = true), available)
            if (omissionBytes < 0 || keptBytes + sepBytes + placeholderBytes + omissionBytes > available) break
            kept += placeholder
            keptBytes += sepBytes + placeholderBytes
        }
        val omittedCount = totalMedia - kept.size
        val omissionText = omission(omittedCount, hasShown = kept.isNotEmpty())
        val omissionBytes = ModelResultText.encodedBytes(omissionText, available)
        if (omissionBytes < 0 || keptBytes + omissionBytes > available) {
            return ModelResultText.bound(prefix + omissionText, notice, status, effect)
        }
        val mediaPart = if (kept.isEmpty()) "" else kept.joinToString("\n")
        return prefix + mediaPart + omissionText + notice
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
