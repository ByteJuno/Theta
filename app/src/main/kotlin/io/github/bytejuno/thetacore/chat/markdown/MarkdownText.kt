package io.github.bytejuno.thetacore.chat.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** Keep the last completed parse visible while a conflated background parse catches up. */
@Composable
internal fun MarkdownText(text: String) {
    val latest by rememberUpdatedState(text)
    val parsed by produceState<MarkdownDocument?>(null) {
        snapshotFlow { latest }.conflate().collect { source ->
            withFrameNanos { }
            value = withContext(Dispatchers.Default) { MarkdownParser.parse(source) }
        }
    }
    val document = parsed?.takeIf { text.startsWith(it.source) }
        ?: remember(text) { plainMarkdownDocument(text) }
    val strings = uiStrings()
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (parsed?.plainFallback == true && document == parsed) {
                Text(strings.get(R.string.markdown_large_message), style = MaterialTheme.typography.labelSmall)
            }
            MarkdownBlocks(document.blocks)
        }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MarkdownBlock>, depth: Int = 0) {
    blocks.forEach { block -> when (block) {
        is MarkdownBlock.Paragraph -> MarkdownParagraph(block)
        is MarkdownBlock.Code -> MarkdownCode(block)
        is MarkdownBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(if (depth < 4) 8.dp else 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) { MarkdownBlocks(block.blocks, depth + 1) }
        }
        is MarkdownBlock.ListEntry -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (depth < 4) Text(block.marker, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (depth >= 4) Text(block.marker, style = MaterialTheme.typography.labelSmall)
                MarkdownBlocks(block.blocks, depth + 1)
            }
        }
        MarkdownBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    } }
}

@Composable
private fun MarkdownParagraph(block: MarkdownBlock.Paragraph) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val strings = uiStrings()
    val error = strings.get(R.string.markdown_link_failed)
    val content = remember(block.runs, colors, error, context) { buildAnnotatedString {
        block.runs.forEach { run ->
            val style = SpanStyle(
                fontWeight = if (run.style.bold) FontWeight.Bold else null,
                fontStyle = if (run.style.italic) FontStyle.Italic else null,
                fontFamily = if (run.style.code) FontFamily.Monospace else null,
                background = if (run.style.code) colors.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (run.style.strike) TextDecoration.LineThrough else null,
            )
            withStyle(style) {
                val url = run.style.link
                if (url == null) append(run.text)
                else withLink(LinkAnnotation.Url(url,
                    TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = {
                        val safe = safeMarkdownUrl(url)
                        val opened = safe != null && runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))
                                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        if (!opened) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    })) { append(run.text) }
            }
        }
    } }
    Text(content, modifier = if (block.heading > 0) Modifier.semantics { heading() } else Modifier,
        style = when (block.heading) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            3 -> MaterialTheme.typography.titleMedium
            4, 5, 6 -> MaterialTheme.typography.titleSmall
            else -> MaterialTheme.typography.bodyMedium
        })
}

@Composable
private fun MarkdownCode(block: MarkdownBlock.Code) {
    val strings = uiStrings()
    val context = LocalContext.current
    var copied by remember(block.text) { mutableStateOf(false) }
    val copyLabel = strings.get(R.string.markdown_copy_code)
    val copyFailed = strings.get(R.string.markdown_copy_failed)
    val chunks = remember(block.text) { markdownCodeChunks(block.text) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifBlank { strings.get(R.string.markdown_code) },
                    style = MaterialTheme.typography.labelMedium)
                DisableSelection {
                    TextButton(onClick = {
                        copied = runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, block.text))
                        }.isSuccess
                        if (!copied) Toast.makeText(context, copyFailed, Toast.LENGTH_SHORT).show()
                    }) { Text(strings.get(if (copied) R.string.markdown_copied else R.string.markdown_copy_code)) }
                }
            }
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                chunks.forEach { chunk ->
                    Text(chunk, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, softWrap = false)
                }
            }
        }
    }
}
