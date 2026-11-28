package io.github.bytejuno.thetacore.chat

/** Keep the wire/storage UTF-16 bound without splitting a supplementary character. */
internal fun documentFilename(name: String): String {
    val clean = name.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), "")
    var end = minOf(clean.length, 160)
    if (end < clean.length && end > 0 && clean[end - 1].isHighSurrogate() && clean[end].isLowSurrogate()) end--
    return clean.substring(0, end).ifBlank { "document" }
}
