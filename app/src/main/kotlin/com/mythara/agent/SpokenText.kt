package com.mythara.agent

/**
 * Markdown → plain spoken text. The chat surface renders the model's
 * markdown as-is (bold, bullets, code, links) because that's how the
 * model wants to format itself; but Android TextToSpeech will literally
 * read "asterisk asterisk yes asterisk asterisk", which is grating.
 *
 * This transformer:
 *  - Drops fenced code blocks entirely (you don't listen to code; you
 *    read it).
 *  - Unwraps inline code, bold, italic, headings, blockquotes — keeps
 *    the inner text but strips the decoration.
 *  - Resolves `[label](url)` → `label` so URLs aren't spelled out.
 *  - Flattens bullets and numbered lists to plain sentences with
 *    trailing periods so the TTS engine inserts natural pauses.
 *  - Collapses tables into commas (best effort — tables don't speak
 *    well no matter what; the user can read those on screen).
 *  - Collapses multiple blank lines and runs of whitespace.
 *
 * Strip [Thinks.strip] first if the input may contain reasoning blocks —
 * this function does not handle `<think>…</think>`.
 */
object SpokenText {

    // Multi-line regex flags so `^`/`$` anchor per line, not per string.
    private val CODE_BLOCK     = Regex("""```[^\n]*\n.*?```""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE    = Regex("""`([^`\n]+)`""")
    private val IMAGE          = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val LINK           = Regex("""\[([^\]]+)\]\([^)]*\)""")
    private val BOLD_STAR      = Regex("""\*\*([^*\n]+)\*\*""")
    private val ITALIC_STAR    = Regex("""\*([^*\n]+)\*""")
    private val BOLD_UNDER     = Regex("""__([^_\n]+)__""")
    private val ITALIC_UNDER   = Regex("""(?<![A-Za-z0-9_])_([^_\n]+)_(?![A-Za-z0-9_])""")
    private val HEADING_MARK   = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val BLOCKQUOTE_MARK = Regex("""^\s*>+\s*""", RegexOption.MULTILINE)
    private val HRULE          = Regex("""^\s*[-*_]{3,}\s*$""", RegexOption.MULTILINE)
    private val TABLE_SEP_ROW  = Regex("""^\s*\|?[\s:|-]+\|[\s:|-]+\|?\s*$""", RegexOption.MULTILINE)
    private val BULLET_MARK    = Regex("""^\s*[-*+•]\s+""", RegexOption.MULTILINE)
    private val NUMBERED_MARK  = Regex("""^\s*\d+[.)]\s+""", RegexOption.MULTILINE)
    private val PIPE           = Regex("""\s*\|\s*""")
    private val MULTI_NL       = Regex("""\n{2,}""")
    private val MULTI_SPACE    = Regex("""[ \t]{2,}""")
    private val SOFT_TERMINATORS = setOf('.', '!', '?', ',', ':', ';')

    fun forSpeech(input: String): String {
        if (input.isBlank()) return ""
        var s = input

        // 1. Drop fenced code blocks wholesale — meaningless to listen to.
        s = CODE_BLOCK.replace(s, " ")

        // 2. Unwrap inline media + links + emphasis. Order matters: bold-pair
        //    before italic-single so `**x**` doesn't get eaten by the italic rule.
        s = INLINE_CODE.replace(s) { it.groupValues[1] }
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { it.groupValues[1] }
        s = BOLD_STAR.replace(s) { it.groupValues[1] }
        s = BOLD_UNDER.replace(s) { it.groupValues[1] }
        s = ITALIC_STAR.replace(s) { it.groupValues[1] }
        s = ITALIC_UNDER.replace(s) { it.groupValues[1] }

        // 3. Strip line-prefix decorations.
        s = HEADING_MARK.replace(s, "")
        s = BLOCKQUOTE_MARK.replace(s, "")
        s = HRULE.replace(s, "")
        s = TABLE_SEP_ROW.replace(s, "")
        s = BULLET_MARK.replace(s, "")
        s = NUMBERED_MARK.replace(s, "")

        // 4. Tables → commas. Crude but better than reading "pipe pipe pipe".
        s = PIPE.replace(s, ", ")

        // 5. Per-line: append a soft period if there isn't already terminal
        //    punctuation, so TTS inserts a natural pause between list items.
        s = s.split('\n').joinToString("\n") { line ->
            val t = line.trimEnd()
            if (t.isBlank()) ""
            else if (t.last() in SOFT_TERMINATORS) t
            else "$t."
        }

        // 6. Compact whitespace.
        s = MULTI_NL.replace(s, " ")
        s = s.replace('\n', ' ')
        s = MULTI_SPACE.replace(s, " ")

        return s.trim()
    }
}
