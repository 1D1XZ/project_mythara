package com.mythara.music

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an agent reply text into a sequence of [Motif]s for
 * [MusicToneEngine] to play. Tokenises on whitespace, filters
 * stopwords (so a long reply doesn't take 30 s of tones — the user
 * can infer "the / a / is" from context once they know the content
 * words), then asks [MusicVocabulary] for a motif per remaining
 * token.
 *
 * Capped at [MAX_MOTIFS_PER_REPLY] motifs so even verbose replies
 * fit in a few seconds of audio. After the cap, encoding silently
 * stops — the underlying text is still shown, just not fully
 * "voiced" in tones.
 */
@Singleton
class MusicReplyEncoder @Inject constructor(
    private val vocabulary: MusicVocabulary,
) {

    /** A motif plus the token it represents — the chat UI uses
     *  [token] for the decode-tap reinforce flow. */
    data class TokenMotif(val token: String, val motif: Motif)

    suspend fun encode(text: String): List<TokenMotif> {
        val raw = text.split(WHITESPACE).asSequence()
            .map { it.lowercase().filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() && it !in STOPWORDS }
            .take(MAX_MOTIFS_PER_REPLY)
            .toList()
        return raw.map { tok ->
            TokenMotif(token = tok, motif = vocabulary.motifFor(tok))
        }
    }

    companion object {
        /** Hard cap on motifs played per reply. Stops a verbose model
         *  reply from monopolising the audio stream — anything beyond
         *  this is still in the text, just not voiced. */
        const val MAX_MOTIFS_PER_REPLY = 8

        private val WHITESPACE = Regex("\\s+")

        /** Very short stopword list — the most common function words.
         *  Filtered out so a 30-word reply doesn't become 30 motifs;
         *  the content words carry the meaning, and the user fills in
         *  function words from grammatical context. Kept tiny so the
         *  language stays "the user's" rather than "tuned by NLP
         *  heuristics." */
        private val STOPWORDS: Set<String> = setOf(
            "a", "an", "the", "and", "or", "but", "if", "of", "on", "in",
            "at", "to", "for", "is", "are", "was", "were", "be", "been",
            "i", "you", "we", "they", "it", "this", "that", "these",
            "those", "my", "your", "our", "their", "with", "as", "by",
            "from", "up", "out", "do", "does", "did", "so", "not",
        )
    }
}
