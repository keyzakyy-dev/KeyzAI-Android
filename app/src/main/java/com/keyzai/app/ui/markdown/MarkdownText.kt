package com.keyzai.app.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------- model

private sealed interface MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock
    data class Options(val payload: OptionsPayload) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Hr : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

/** Format kartu pilihan KeyzAI: ```keyzai-options {json} ``` */
@Serializable
data class OptionsPayload(val questions: List<OptionQuestion> = emptyList())

@Serializable
data class OptionQuestion(
    val id: String = "",
    val question: String = "",
    val options: List<OptionItem> = emptyList(),
    val multiple: Boolean = false,
)

@Serializable
data class OptionItem(val label: String = "", val description: String = "")

// ---------------------------------------------------------------- parsing

private val FENCE_RE = Regex("```([^\\n`]*)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
private val HEADING_RE = Regex("^(#{1,4})\\s+(.*)$")
private val BULLET_RE = Regex("^\\s*[-*+]\\s+(.*)$")
private val NUMBERED_RE = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val QUOTE_RE = Regex("^>\\s?(.*)$")
private val HR_RE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val TABLE_ROW_RE = Regex("^\\|.*\\|\\s*$")
private val TABLE_SEP_RE = Regex("^\\|[\\s:|-]+\\|\\s*$")
private val INLINE_RE =
    Regex("(`[^`\\n]+`|\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__|\\*[^*\\n]+\\*|~~[^~\\n]+~~|\\[[^\\]]+]\\(https?://[^)\\s]+\\))")

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun parseMarkdown(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var last = 0
    for (m in FENCE_RE.findAll(text)) {
        if (m.range.first > last) blocks += parseTextSegment(text.substring(last, m.range.first))
        val lang = m.groupValues[1].trim().lowercase()
        val code = m.groupValues[2]
        if (lang == "keyzai-options") {
            val payload = runCatching { lenientJson.decodeFromString<OptionsPayload>(code.trim()) }.getOrNull()
            if (payload != null && payload.questions.isNotEmpty()) {
                blocks += MdBlock.Options(payload)
            } else {
                blocks += MdBlock.Code(lang, code)
            }
        } else {
            blocks += MdBlock.Code(lang, code)
        }
        last = m.range.last + 1
    }
    if (last < text.length) blocks += parseTextSegment(text.substring(last))
    return blocks.filterNot { it is MdBlock.Paragraph && it.text.isBlank() }
}

private fun parseTextSegment(segment: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = segment.lines()
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) {
            blocks += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.isEmpty() -> { flushPara(); i++ }
            HR_RE.matches(t) -> { flushPara(); blocks += MdBlock.Hr; i++ }
            HEADING_RE.matches(t) -> {
                flushPara()
                val m = HEADING_RE.find(t)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                i++
            }
            QUOTE_RE.matches(line) -> {
                flushPara()
                val buf = StringBuilder()
                while (i < lines.size && QUOTE_RE.matches(lines[i])) {
                    buf.appendLine(QUOTE_RE.find(lines[i])!!.groupValues[1])
                    i++
                }
                blocks += MdBlock.Quote(buf.toString().trim())
            }
            BULLET_RE.matches(line) -> {
                flushPara()
                val items = mutableListOf<String>()
                while (i < lines.size && BULLET_RE.matches(lines[i])) {
                    items += BULLET_RE.find(lines[i])!!.groupValues[1]
                    i++
                }
                blocks += MdBlock.BulletList(items)
            }
            NUMBERED_RE.matches(line) -> {
                flushPara()
                val items = mutableListOf<String>()
                while (i < lines.size && NUMBERED_RE.matches(lines[i])) {
                    items += NUMBERED_RE.find(lines[i])!!.groupValues[1]
                    i++
                }
                blocks += MdBlock.NumberedList(items)
            }
            TABLE_ROW_RE.matches(t) -> {
                flushPara()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && TABLE_ROW_RE.matches(lines[i].trim())) {
                    val row = lines[i].trim()
                    if (!TABLE_SEP_RE.matches(row)) {
                        rows += row.trim('|').split("|").map { it.trim() }
                    }
                    i++
                }
                if (rows.isNotEmpty()) blocks += MdBlock.Table(rows)
            }
            else -> { para.appendLine(line); i++ }
        }
    }
    flushPara()
    return blocks
}

/** Inline: `code`, **bold**, *italic*, ~~strike~~, [text](url). */
private fun parseInline(
    text: String,
    codeColor: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE_RE.findAll(text)) {
        if (m.range.first > last) append(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("`") -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor))
                append(token.trim('`'))
                pop()
            }
            token.startsWith("**") || token.startsWith("__") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(token.drop(2).dropLast(2))
                pop()
            }
            token.startsWith("*") -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(token.drop(1).dropLast(1))
                pop()
            }
            token.startsWith("~~") -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(token.drop(2).dropLast(2))
                pop()
            }
            token.startsWith("[") -> {
                val label = token.substring(1, token.indexOf("]"))
                val url = token.substring(token.indexOf("](") + 2, token.length - 1)
                withLink(LinkAnnotation.Url(url, styles = null)) {
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    append(label)
                    pop()
                }
            }
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

// ---------------------------------------------------------------- render

/**
 * Renderer markdown ringan untuk bubble chat: code block + tombol salin,
 * heading, list, quote, tabel, dan kartu pilihan interaktif KeyzAI.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    onOptionSelected: (String) -> Unit = {},
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val codeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.secondary
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Code -> CodeBlockUi(block.lang, block.code)
                    is MdBlock.Options -> OptionsCardUi(block.payload, onOptionSelected)
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall
                            2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        }
                        Text(
                            parseInline(block.text, codeColor, linkColor),
                            style = style.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                    is MdBlock.Quote -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                parseInline(block.text, codeColor, linkColor),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = textStyle.copy(fontStyle = FontStyle.Italic),
                            )
                        }
                    }
                    is MdBlock.BulletList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEach { item ->
                                Row {
                                    Text("•  ", style = textStyle)
                                    Text(parseInline(item, codeColor, linkColor), style = textStyle)
                                }
                            }
                        }
                    }
                    is MdBlock.NumberedList -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            block.items.forEachIndexed { idx, item ->
                                Row {
                                    Text("${idx + 1}.  ", style = textStyle)
                                    Text(parseInline(item, codeColor, linkColor), style = textStyle)
                                }
                            }
                        }
                    }
                    is MdBlock.Table -> {
                        val widths = block.rows.fold(listOf<Int>()) { acc, row ->
                            row.mapIndexed { i, cell -> maxOf(acc.getOrElse(i) { 0 }, cell.length) }
                        }
                        val rendered = block.rows.joinToString("\n") { row ->
                            row.mapIndexed { i, cell -> cell.padEnd(widths.getOrElse(i) { cell.length }) }
                                .joinToString(" | ")
                        }
                        Text(
                            rendered,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    MdBlock.Hr -> HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    is MdBlock.Paragraph -> {
                        Text(parseInline(block.text, codeColor, linkColor), style = textStyle)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockUi(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    lang.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(code.trimEnd()))
                    copied = true
                }) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Salin kode",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Text(
                code.trimEnd(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun OptionsCardUi(payload: OptionsPayload, onOptionSelected: (String) -> Unit) {
    val question = payload.questions.firstOrNull() ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(question.question, fontWeight = FontWeight.SemiBold)
            question.options.forEach { opt ->
                OutlinedButton(
                    onClick = { onOptionSelected(opt.label) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(opt.label)
                        if (opt.description.isNotEmpty()) {
                            Text(
                                opt.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
