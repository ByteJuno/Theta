package io.github.bytejuno.thetacore.chat.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentTest {
    @Test fun rendersCommonInlineStylesAndPreservesRawSource() {
        val source = "A **bold** *italic* ~~old~~ `a < b` &amp; 中文 😀"
        val result = MarkdownParser.parse(source)
        assertEquals(source, result.source)
        val runs = (result.blocks.single() as MarkdownBlock.Paragraph).runs
        assertTrue(runs.any { it.text == "bold" && it.style.bold })
        assertTrue(runs.any { it.text == "italic" && it.style.italic })
        assertTrue(runs.any { it.text == "old" && it.style.strike })
        assertTrue(runs.any { it.text == "a < b" && it.style.code })
        assertTrue(runs.joinToString("") { it.text }.contains("& 中文 😀"))
    }

    @Test fun openFenceAndEmphasisAreSafeAtEveryStreamingPrefix() {
        val source = "**start**\n\n```kotlin\nval x = \"**not bold**\"\nprintln(x)"
        for (end in 0..source.length) assertEquals(source.take(end), MarkdownParser.parse(source.take(end)).source)
        val blocks = MarkdownParser.parse(source).blocks
        val code = blocks.last() as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = \"**not bold**\"\nprintln(x)\n", code.text)
        assertFalse(MarkdownParser.parse("**unfinished").plainFallback)
    }

    @Test fun orderedAndNestedListsKeepNumberingAndStructure() {
        val blocks = MarkdownParser.parse("3. first\n   - nested\n     1. deep\n4. second").blocks
        assertEquals(listOf("3.", "4."), blocks.map { (it as MarkdownBlock.ListEntry).marker })
        val nested = (blocks.first() as MarkdownBlock.ListEntry).blocks[1] as MarkdownBlock.ListEntry
        assertEquals("•", nested.marker)
        assertEquals("1.", (nested.blocks[1] as MarkdownBlock.ListEntry).marker)
    }

    @Test fun headingsQuotesRulesAndIndentedCodeHaveDistinctBlocks() {
        val blocks = MarkdownParser.parse("## Title\n\n> quoted\n\n---\n\n    code\n").blocks
        assertEquals(2, (blocks[0] as MarkdownBlock.Paragraph).heading)
        assertTrue(blocks[1] is MarkdownBlock.Quote)
        assertEquals(MarkdownBlock.Rule, blocks[2])
        assertEquals("code\n", (blocks[3] as MarkdownBlock.Code).text)
    }

    @Test fun unsafeSchemesAndAmbiguousDestinationsNeverBecomeLinks() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/html,hi", "intent://example",
            "mailto:a@b.com", "//example.com", "https://", "https://a\\b.com", "https://a.com\n",
            "https://user:pass@example.com", "http:example.com", "https://a.com/a b").forEach {
            assertNull(it, safeMarkdownUrl(it))
        }
        listOf("https://example.com/a?q=1#ok", "HTTP://example.com", "https://127.0.0.1:8080/a").forEach {
            assertEquals(it, safeMarkdownUrl(it))
        }
        val runs = (MarkdownParser.parse("[danger](javascript:alert%281%29) [ok](https://example.com)")
            .blocks.single() as MarkdownBlock.Paragraph).runs
        assertNull(runs.first { it.text == "danger" }.style.link)
        assertEquals("https://example.com", runs.first { it.text == "ok" }.style.link)
    }

    @Test fun htmlStaysLiteralAndImageOnlyContributesAltText() {
        val source = "<script>alert(1)</script>\n\n![a **picture**](https://example.com/track.png)"
        val blocks = MarkdownParser.parse(source).blocks.map { it as MarkdownBlock.Paragraph }
        assertEquals("<script>alert(1)</script>", blocks[0].runs.joinToString("") { it.text })
        assertEquals("a picture", blocks[1].runs.joinToString("") { it.text })
        assertTrue(blocks.flatMap { it.runs }.all { it.style.link == null })
    }

    @Test fun longCodeIsPreservedExactlyForCopyAndChunkedForLayout() {
        val code = "😀".repeat(20000) + "\nlast line\n"
        val block = MarkdownParser.parse("```text\n$code```").blocks.single() as MarkdownBlock.Code
        assertEquals(code, block.text)
        val chunks = markdownTextChunks(block.text)
        assertEquals(code, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= MARKDOWN_TEXT_CHUNK })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun oversizedInputFallsBackWithoutTruncation() {
        val source = "**" + "z".repeat(MAX_MARKDOWN_CHARS) + "**"
        val doc = MarkdownParser.parse(source)
        assertTrue(doc.plainFallback)
        assertEquals(source, doc.blocks.flatMap { (it as MarkdownBlock.Paragraph).runs }.joinToString("") { it.text })
    }

    @Test fun tinyBlockFloodFallsBackWithoutDroppingText() {
        val source = "a\n\n".repeat(3000)
        val doc = MarkdownParser.parse(source)
        assertTrue(doc.plainFallback)
        assertEquals(source, doc.source)
    }

    @Test fun deepNestingAndMalformedUnicodeDoNotCrash() {
        listOf(
            "> ".repeat(10000) + "end",
            "[".repeat(10000) + "x" + "]".repeat(10000),
            "*".repeat(10000),
            "\u0000\uD800 &unknown; &#xD800; \\* literal",
            "![x](file:///tmp/x) <img src=\"https://example.com/x\">",
        ).forEach { assertEquals(it, MarkdownParser.parse(it).source) }
    }

    @Test fun referenceLinksResolveAndEscapedSyntaxStaysText() {
        val doc = MarkdownParser.parse("[site][id] and \\*literal\\*\n\n[id]: https://example.com")
        val runs = (doc.blocks.single() as MarkdownBlock.Paragraph).runs
        assertEquals("https://example.com", runs.first().style.link)
        assertEquals("site and *literal*", runs.joinToString("") { it.text })
    }
}
