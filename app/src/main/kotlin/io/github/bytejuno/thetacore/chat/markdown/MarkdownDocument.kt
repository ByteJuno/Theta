package io.github.bytejuno.thetacore.chat.markdown

import java.net.URI
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class MarkdownStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

internal data class MarkdownRun(val text: String, val style: MarkdownStyle = MarkdownStyle())

internal sealed interface MarkdownBlock {
    data class Paragraph(val runs: List<MarkdownRun>, val heading: Int = 0) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class ListEntry(val marker: String, val blocks: List<MarkdownBlock>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

internal data class MarkdownDocument(
    val source: String,
    val blocks: List<MarkdownBlock>,
    val plainFallback: Boolean = false,
)

// Bound parser work on untrusted model text. Larger messages remain selectable in full.
internal const val MAX_MARKDOWN_CHARS = 256 * 1024
internal const val MARKDOWN_TEXT_CHUNK = 4096
private const val MAX_NESTING = 24

/** Validate again at launch time; no intent:, file:, javascript:, credentials or relative URLs. */
internal fun safeMarkdownUrl(destination: String): String? {
    if (destination.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val uri = try { URI(destination) } catch (_: Exception) { return null }
    return destination.takeIf {
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
    }
}

/** Avoid splitting a surrogate pair, and prefer original line boundaries for code blocks. */
internal fun markdownTextChunks(text: String, code: Boolean = false): List<String> {
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + MARKDOWN_TEXT_CHUNK, text.length)
        if (end < text.length) {
            // Search only this chunk; rescanning earlier newline-free text is quadratic.
            var newline = end - 1
            while (newline >= start && text[newline] != '\n') newline--
            if (newline >= start) end = newline + 1
            // A boundary newline terminates this code row, not a new empty Text row.
            else if (code && text[end] == '\n') end++
            else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}

internal fun plainMarkdownDocument(source: String) = MarkdownDocument(
    source, markdownTextChunks(source).map { MarkdownBlock.Paragraph(listOf(MarkdownRun(it))) }, true,
)

internal fun codeChunkDisplay(chunk: String): String = chunk.removeSuffix("\n")

internal fun markdownCodeChunks(text: String): List<String> =
    markdownTextChunks(text, code = true).map(::codeChunkDisplay)

/** AST only: no HTML renderer, image loader, transport or persistence side effects. */
internal object MarkdownParser {
    private val parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .enabledBlockTypes(setOf(Heading::class.java, ThematicBreak::class.java, FencedCodeBlock::class.java,
            IndentedCodeBlock::class.java, BlockQuote::class.java, ListBlock::class.java))
        .maxOpenBlockParsers(MAX_NESTING)
        .maxInlineNesting(MAX_NESTING)
        .build()

    fun parse(source: String): MarkdownDocument {
        if (source.length > MAX_MARKDOWN_CHARS) return plainMarkdownDocument(source)
        return try {
            val root = parser.parse(source)
            // A short input can still contain thousands of tiny layout nodes. Keep the full
            // original text instead of building an unbounded Compose tree in one chat item.
            var count = 0
            root.accept(object : AbstractVisitor() {
                override fun visitChildren(parent: Node) {
                    count++
                    if (count <= 2000) super.visitChildren(parent)
                }
            })
            if (count > 2000) plainMarkdownDocument(source) else MarkdownDocument(source, blocks(root))
        }
        catch (_: IllegalArgumentException) { plainMarkdownDocument(source) }
    }

    private fun blocks(parent: Node): List<MarkdownBlock> = buildList {
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Paragraph -> add(MarkdownBlock.Paragraph(inlines(node)))
                is Heading -> add(MarkdownBlock.Paragraph(inlines(node), node.level))
                is FencedCodeBlock -> add(MarkdownBlock.Code(node.literal, node.info.substringBefore(' ').take(80)))
                is IndentedCodeBlock -> add(MarkdownBlock.Code(node.literal, ""))
                is BlockQuote -> add(MarkdownBlock.Quote(blocks(node)))
                is ListBlock -> {
                    var item = node.firstChild
                    var number = (node as? OrderedList)?.markerStartNumber ?: 1
                    while (item != null) {
                        add(MarkdownBlock.ListEntry(if (node is OrderedList) "${number++}." else "•", blocks(item)))
                        item = item.next
                    }
                }
                is ThematicBreak -> add(MarkdownBlock.Rule)
                else -> addAll(blocks(node))
            }
            node = node.next
        }
    }

    private fun inlines(parent: Node, style: MarkdownStyle = MarkdownStyle()): List<MarkdownRun> = buildList {
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Text -> add(MarkdownRun(node.literal, style))
                is Code -> add(MarkdownRun(node.literal, style.copy(code = true)))
                is HtmlInline -> add(MarkdownRun(node.literal, style)) // tags stay literal, never interpreted
                is SoftLineBreak, is HardLineBreak -> add(MarkdownRun("\n", style))
                is StrongEmphasis -> addAll(inlines(node, style.copy(bold = true)))
                is Emphasis -> addAll(inlines(node, style.copy(italic = true)))
                is Strikethrough -> addAll(inlines(node, style.copy(strike = true)))
                is Link -> addAll(inlines(node, style.copy(link = safeMarkdownUrl(node.destination))))
                is Image -> addAll(inlines(node, style.copy(link = null))) // alt text only, including remote images
                else -> addAll(inlines(node, style))
            }
            node = node.next
        }
    }
}
