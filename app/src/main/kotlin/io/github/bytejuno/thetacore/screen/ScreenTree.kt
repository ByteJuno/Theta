package io.github.bytejuno.thetacore.screen

import kotlinx.serialization.json.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal const val SCREEN_NODE_LIMIT = 160
internal const val SCREEN_TREE_BYTES = 24 * 1024
internal const val SCREEN_SCAN_LIMIT = 2000

internal data class ScreenNode(val packageName: String, val className: String, val text: String,
    val description: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val visible: Boolean, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean,
    val password: Boolean = false)

internal fun shortScreenText(text: String, max: Int = 180): String {
    val end = text.offsetByCodePoints(0, minOf(text.codePointCount(0, text.length), max))
    return text.substring(0, end)
}

internal fun compactScreenTree(nodes: List<ScreenNode>, sourceTruncated: Boolean = false): JsonObject {
    val kept = mutableListOf<JsonElement>(); var bytes = 128; var truncated = sourceTruncated
    for (n in nodes) {
        if (!n.visible || n.right <= n.left || n.bottom <= n.top ||
            (n.text.isBlank() && n.description.isBlank() && !n.clickable && !n.editable && !n.scrollable)) continue
        val item = buildJsonObject {
            put("class", shortScreenText(n.className.substringAfterLast('.'), 80))
            if (!n.password && n.text.isNotBlank()) put("text", shortScreenText(n.text))
            if (!n.password && n.description.isNotBlank()) put("description", shortScreenText(n.description))
            putJsonArray("center") { add(n.left + (n.right - n.left) / 2); add(n.top + (n.bottom - n.top) / 2) }
            putJsonArray("bounds") { add(n.left); add(n.top); add(n.right); add(n.bottom) }
            if (n.clickable) put("clickable", true)
            if (n.editable) put("editable", true)
            if (n.scrollable) put("scrollable", true)
            if (n.password) put("password", true)
        }
        val size = item.toString().toByteArray().size + 1
        if (kept.size == SCREEN_NODE_LIMIT || bytes + size > SCREEN_TREE_BYTES - 512) { truncated = true; break }
        kept += item; bytes += size
    }
    return buildJsonObject {
        put("package", shortScreenText(nodes.firstOrNull { it.visible && it.packageName.isNotBlank() }?.packageName.orEmpty(), 180))
        put("nodes", JsonArray(kept)); put("truncated", truncated)
    }
}

internal fun parseScreenXml(xml: String): JsonObject {
    require(xml.toByteArray().size <= 1024 * 1024 && !xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(xml.reader())
    val nodes = mutableListOf<ScreenNode>(); var scanned = 0
    val bounds = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG || parser.name != "node") continue
        if (++scanned > SCREEN_SCAN_LIMIT) break
        fun attr(name: String) = parser.getAttributeValue(null, name).orEmpty()
        val b = bounds.matchEntire(attr("bounds"))?.groupValues?.drop(1)?.map { it.toIntOrNull() } ?: continue
        if (b.any { it == null || it !in 0..32767 }) continue
        nodes += ScreenNode(attr("package"), attr("class"), attr("text"), attr("content-desc"),
            b[0]!!, b[1]!!, b[2]!!, b[3]!!, attr("visible-to-user") != "false",
            attr("clickable") == "true", attr("editable") == "true" || attr("class").endsWith("EditText"),
            attr("scrollable") == "true", attr("password") == "true")
    }
    return compactScreenTree(nodes, scanned > SCREEN_SCAN_LIMIT)
}
