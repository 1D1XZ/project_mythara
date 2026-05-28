package com.mythara.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.MytharaColors

/**
 * Lightweight in-house Markdown renderer used everywhere agent-
 * authored prose appears: chat reply bubbles, contact-detail
 * relationship summaries / personality insights / user notes,
 * lifeline captions, memory cards, About copy.
 *
 * No external dependency — we add ~250 lines of Kotlin instead of
 * ~700 KB of compiled commonmark + a renderer DSL. Handles the
 * subset of CommonMark that actually appears in the model's output
 * + the user's hand-typed notes:
 *
 *  Block-level
 *   - blank-line paragraph splits
 *   - `#`, `##`, `###` ATX headings (1–3 levels — anything deeper
 *     drops back to plain bold paragraph)
 *   - `- ` / `* ` / `+ ` unordered bullets
 *   - `1. ` ordered bullets (preserves the model's numbering)
 *   - `> ` block quotes
 *   - ``` fenced code blocks
 *   - `---` / `***` horizontal rules
 *
 *  Inline
 *   - `**bold**` / `__bold__`
 *   - `*italic*` / `_italic_`
 *   - `` `code` `` inline code
 *   - `[label](url)` clickable links
 *   - `~~strike~~` strikethrough
 *
 * Anything we don't recognise renders as plain text, never as a
 * literal markdown sigil — so a partial match (e.g. one lone
 * asterisk) doesn't break the layout.
 *
 * Performance: parsing runs in `remember(text)` per composable
 * invocation; a 2 KB note parses in < 1 ms in dev profiling so we
 * don't bother caching outside the composable's own lifetime.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MytharaColors.Fg,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        lineHeight = TextUnit(20f, TextUnitType.Sp),
    ),
) {
    if (text.isBlank()) return
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier = modifier) {
        for ((i, block) in blocks.withIndex()) {
            if (i > 0) Spacer(Modifier.height(8.dp))
            RenderBlock(block = block, color = color, baseStyle = style)
        }
    }
}

// ────────────────────────────────────────────────────────────── BLOCKS

/** Block-level node returned by [parseBlocks]. */
internal sealed interface MdBlock {
    /** Plain paragraph — may still contain inline formatting. */
    data class Paragraph(val text: String) : MdBlock
    /** ATX heading. [level] is 1–3 (deeper levels coerce to 3). */
    data class Heading(val level: Int, val text: String) : MdBlock
    /** Bullet list — each line is one item. */
    data class BulletList(val items: List<String>) : MdBlock
    /** Numbered list — each line is one item. Marker preserved
     *  verbatim so a model's "1) … 2) …" reads the same way. */
    data class OrderedList(val items: List<Pair<String, String>>) : MdBlock
    /** Block quote — every line in the input started with `> `. */
    data class Quote(val text: String) : MdBlock
    /** Fenced code block. [language] may be empty. */
    data class Code(val text: String, val language: String) : MdBlock
    /** Thematic break — `---` / `***` / `___`. */
    object Rule : MdBlock
}

/** Split the raw markdown into a list of blocks. */
internal fun parseBlocks(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val out = ArrayList<MdBlock>()
    var i = 0
    val n = lines.size
    while (i < n) {
        val line = lines[i]
        val trimmed = line.trim()

        // Skip pure blank lines — they're paragraph separators handled
        // implicitly by the iteration shape below.
        if (trimmed.isEmpty()) { i++; continue }

        // Fenced code block. Walk until the closing fence or EOF.
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = ArrayList<String>()
            i++
            while (i < n && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < n) i++ // consume closing fence
            out += MdBlock.Code(text = codeLines.joinToString("\n"), language = lang)
            continue
        }

        // ATX heading.
        if (trimmed.startsWith("#")) {
            var level = 0
            while (level < trimmed.length && trimmed[level] == '#') level++
            if (level in 1..6 && level < trimmed.length && trimmed[level] == ' ') {
                val body = trimmed.substring(level + 1).trim()
                out += MdBlock.Heading(level = level.coerceAtMost(3), text = body)
                i++
                continue
            }
        }

        // Thematic break: a line of three or more `-` / `*` / `_`.
        if (HR_REGEX.matches(trimmed)) {
            out += MdBlock.Rule
            i++
            continue
        }

        // Block quote — consume consecutive `> …` lines into one block.
        if (trimmed.startsWith("> ") || trimmed == ">") {
            val quoteLines = ArrayList<String>()
            while (i < n) {
                val l = lines[i].trim()
                if (l.startsWith(">")) {
                    quoteLines.add(l.removePrefix(">").trimStart())
                    i++
                } else break
            }
            out += MdBlock.Quote(text = quoteLines.joinToString("\n").trim())
            continue
        }

        // Bullet list — consume consecutive `- ` / `* ` / `+ ` lines.
        if (BULLET_REGEX.matches(line)) {
            val items = ArrayList<String>()
            while (i < n && BULLET_REGEX.matches(lines[i])) {
                items.add(lines[i].trimStart().removePrefix(BULLET_REGEX.find(lines[i])!!.value).trimStart())
                i++
            }
            out += MdBlock.BulletList(items = items)
            continue
        }

        // Ordered list — consume consecutive `<n>. ` lines. Capture
        // both the marker (`1.`, `2.`) and the body separately so we
        // can render numbering the user expects.
        val ordMatch = ORDERED_REGEX.matchEntire(line)
        if (ordMatch != null) {
            val items = ArrayList<Pair<String, String>>()
            while (i < n) {
                val m = ORDERED_REGEX.matchEntire(lines[i]) ?: break
                items.add(m.groupValues[1] to m.groupValues[2])
                i++
            }
            out += MdBlock.OrderedList(items = items)
            continue
        }

        // Paragraph — consume up to the next block-break / heading /
        // list / blank line. Wrapping text on the same line breaks
        // doesn't matter because Text wraps anyway.
        val para = StringBuilder()
        while (i < n) {
            val l = lines[i]
            val lt = l.trim()
            if (lt.isEmpty()) break
            if (lt.startsWith("#") || lt.startsWith(">") ||
                lt.startsWith("```") || HR_REGEX.matches(lt) ||
                BULLET_REGEX.matches(l) ||
                ORDERED_REGEX.matchEntire(l) != null
            ) break
            if (para.isNotEmpty()) para.append(' ')
            para.append(lt)
            i++
        }
        if (para.isNotEmpty()) out += MdBlock.Paragraph(text = para.toString())
    }
    return out
}

// ──────────────────────────────────────────────────────── BLOCK RENDER

@Composable
private fun RenderBlock(block: MdBlock, color: Color, baseStyle: TextStyle) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            text = parseInline(block.text, color),
            style = baseStyle,
            color = color,
        )
        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> 22f to FontWeight.Bold
                2 -> 19f to FontWeight.Bold
                else -> 16f to FontWeight.SemiBold
            }
            Text(
                text = parseInline(block.text, color),
                style = baseStyle.copy(
                    fontSize = TextUnit(size, TextUnitType.Sp),
                    lineHeight = TextUnit(size + 6f, TextUnitType.Sp),
                    fontWeight = weight,
                ),
                color = color,
            )
        }
        is MdBlock.BulletList -> Column {
            for (item in block.items) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "•",
                        color = MytharaColors.Charple,
                        style = baseStyle,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        text = parseInline(item, color),
                        style = baseStyle,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is MdBlock.OrderedList -> Column {
            for ((marker, body) in block.items) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = marker,
                        color = MytharaColors.Charple,
                        style = baseStyle,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = parseInline(body, color),
                        style = baseStyle,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .background(MytharaColors.Charple.copy(alpha = 0.5f))
                    .padding(vertical = 2.dp),
            ) {
                // Stretch to the height of the text. Compose will
                // resolve this automatically when the Row laid out.
                Text(" ", style = baseStyle)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = parseInline(block.text, color),
                style = baseStyle.copy(fontStyle = FontStyle.Italic),
                color = MytharaColors.FgMute,
            )
        }
        is MdBlock.Code -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MytharaColors.SurfaceHigh.copy(alpha = 0.6f))
                .padding(PaddingValues(horizontal = 10.dp, vertical = 8.dp)),
        ) {
            Text(
                text = block.text,
                style = baseStyle.copy(fontFamily = FontFamily.Monospace),
                color = MytharaColors.Bok,
            )
        }
        MdBlock.Rule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MytharaColors.SurfaceHigh),
        )
    }
}

// ─────────────────────────────────────────────────────── INLINE SPANS

/**
 * Parse inline formatting in [text] into an [AnnotatedString].
 * Single pass — looks for the next sigil ahead, slices off the
 * unstyled prefix, applies the span to the matched range, and
 * recurses into the tail. Order of precedence:
 *
 *   1. fenced inline code  `…`   (suppresses other sigils inside)
 *   2. links               [a](b)
 *   3. strikethrough       ~~…~~
 *   4. bold                **…** / __…__
 *   5. italic              *…* / _…_
 *
 * The "next-sigil" walk uses simple index hunts, not a real parser
 * — fast enough for paragraph-scale prose and forgiving when the
 * input is a partial match (no exception, just renders as plain
 * text).
 *
 * @Composable because MytharaColors fields are CompositionLocal-
 * backed getters since the theme engine landed. We snapshot the
 * needed palette values here so the non-composable [appendInline]
 * recursion never needs to read the palette directly.
 */
@Composable
internal fun parseInline(text: String, defaultColor: Color): AnnotatedString {
    val linkColor = MytharaColors.Bok
    val codeBg = MytharaColors.SurfaceHigh.copy(alpha = 0.5f)
    return buildAnnotatedString {
        appendInline(this, text, defaultColor, linkColor = linkColor, codeBg = codeBg)
    }
}

private fun appendInline(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    text: String,
    color: Color,
    linkColor: Color,
    codeBg: Color,
) {
    var i = 0
    val n = text.length
    while (i < n) {
        val ch = text[i]
        when {
            // Inline code `…` — never recurse, render as-is.
            ch == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close < 0) {
                    builder.append(ch); i++
                } else {
                    builder.withStyle(
                        SpanStyle(
                            color = linkColor,
                            fontFamily = FontFamily.Monospace,
                            background = codeBg,
                        ),
                    ) { append(text.substring(i + 1, close)) }
                    i = close + 1
                }
            }
            // Link [label](url) — render label, store url as a tag.
            ch == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < n && text[close + 1] == '(') {
                    val urlEnd = text.indexOf(')', close + 2)
                    if (urlEnd > close + 1) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, urlEnd)
                        builder.pushStringAnnotation(tag = "URL", annotation = url)
                        builder.withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) { appendInline(this, label, linkColor, linkColor, codeBg) }
                        builder.pop()
                        i = urlEnd + 1
                        continue
                    }
                }
                builder.append(ch); i++
            }
            // Strikethrough ~~…~~.
            ch == '~' && i + 1 < n && text[i + 1] == '~' -> {
                val close = text.indexOf("~~", i + 2)
                if (close < 0) {
                    builder.append(ch); i++
                } else {
                    builder.withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough, color = color),
                    ) { appendInline(this, text.substring(i + 2, close), color, linkColor, codeBg) }
                    i = close + 2
                }
            }
            // Bold **…** or __…__. The double sigil takes precedence
            // over the single (italic) one so `**bold**` parses
            // correctly even though `*` would also match alone.
            (ch == '*' && i + 1 < n && text[i + 1] == '*') ||
                (ch == '_' && i + 1 < n && text[i + 1] == '_') -> {
                val open = text.substring(i, i + 2)
                val close = text.indexOf(open, i + 2)
                if (close < 0) {
                    builder.append(ch); i++
                } else {
                    builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) {
                        appendInline(this, text.substring(i + 2, close), color, linkColor, codeBg)
                    }
                    i = close + 2
                }
            }
            // Italic *…* or _…_.
            ch == '*' || ch == '_' -> {
                val close = text.indexOf(ch, i + 1)
                // Defend against false positives like "snake_case_name"
                // by requiring the sigil to be the start of a word
                // (preceded by space/punct or BOL) AND closer to be at
                // word boundary (followed by space/punct or EOL).
                val priorIsWord = i > 0 && text[i - 1].isLetterOrDigit()
                if (close < 0 || priorIsWord) {
                    builder.append(ch); i++
                } else {
                    val nextIsWord = close + 1 < n && text[close + 1].isLetterOrDigit()
                    if (nextIsWord) {
                        builder.append(ch); i++
                    } else {
                        builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = color)) {
                            appendInline(this, text.substring(i + 1, close), color, linkColor, codeBg)
                        }
                        i = close + 1
                    }
                }
            }
            else -> {
                builder.append(ch); i++
            }
        }
    }
}

// ─────────────────────────────────────────────────────── REGEXES (top-level for reuse)

private val HR_REGEX = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
private val BULLET_REGEX = Regex("^\\s*[-*+]\\s+.*$")
private val ORDERED_REGEX = Regex("^\\s*(\\d+[.)])\\s+(.*)$")

// ─────────────────────────────────────────────────────── HELPERS (unused but kept for callers)

/** Unused — retained so a caller wanting the unstyled link handler
 *  has somewhere to look. The real link click handling lives in
 *  callers via [LocalUriHandler] + ClickableText. */
@Suppress("unused")
private fun handleUrl(uri: androidx.compose.ui.platform.UriHandler, url: String) {
    runCatching { uri.openUri(url) }
}
