package com.mythara.secret.observe.extract

/**
 * Detects explicit "talk to Lumi" prefixes in Observe transcripts and
 * extracts the tail as a deliberate user note.
 *
 *   "Lumi, remember that the new wifi password is xyz"
 *     → note: "remember that the new wifi password is xyz"
 *
 *   "Hey Lumi note this down — meeting moved to 4pm"
 *     → note: "note this down meeting moved to 4pm"
 *
 * The match is anchored at start-of-utterance only (we don't want
 * "I told Lumi about the bug" to fire). Vosk transcripts are
 * lowercase by default, but the regex is case-insensitive anyway.
 *
 * False-positive surface area is low: Mythara is "Lumi" specifically
 * because the syllable count + vowel pattern is rare in English
 * conversation. If the user starts a sentence with "Lumi" they almost
 * certainly mean the assistant.
 *
 * Pairs with M8.3a's wake-word listener: same trigger phrase, different
 * mode. The wake word opens the chat surface; the note detector lets
 * Observe-mode quietly capture asks-while-passing without needing the
 * user to switch contexts.
 */
object LumiNoteDetector {

    /**
     * Returns the note text (with the "Lumi" prefix stripped) if the
     * transcript begins with a Lumi address, else null.
     *
     * Recognised prefixes:
     *   - "Lumi[,/:] <note>"                  (bare address)
     *   - "Hey Lumi[,/:] <note>"
     *   - "Hi Lumi[,/:] <note>"
     *   - "Okay Lumi[,/:] <note>"
     *   - "Hello Lumi[,/:] <note>"
     *   - Same forms with no comma/colon, just whitespace
     *   - "Lumi please <note>" / "Lumi remember <note>" (imperatives)
     *
     * Also tolerates common Vosk mishears for the proper noun
     * ("loomi", "lumy", "loomie") since "Lumi" is OOV for the en-us
     * small model and likely to get transcribed phonetically.
     */
    fun detect(transcript: String): String? {
        val s = transcript.trim()
        if (s.isEmpty()) return null
        val m = PREFIX_RE.find(s) ?: return null
        return s.substring(m.range.last + 1).trim().ifBlank { null }
    }

    /**
     * Pattern, in order:
     *   - optional address opener (hey/hi/hello/okay/yo)
     *   - the Lumi token (or one of its likely-mishears)
     *   - optional connector word (please / can you / remember / note)
     *   - optional `,` `:` `-` or whitespace
     */
    private val PREFIX_RE = Regex(
        pattern = """^\s*(?:hey\s+|hi\s+|hello\s+|okay\s+|ok\s+|yo\s+)?(?:lumi|loomi|lumy|loomie|lumie)\b[\s,:\-]*(?:please\s+|can\s+you\s+|could\s+you\s+|remember\s+|note\s+|jot\s+down\s+)?""",
        options = setOf(RegexOption.IGNORE_CASE),
    )
}
