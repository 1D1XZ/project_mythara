package com.mythara.music

import kotlinx.serialization.Serializable

/**
 * A single tone-phrase that stands for one token in Music Mode's
 * evolving vocabulary. Stored in [com.mythara.data.MusicVocabularyStore]
 * as a JSON value keyed by the lowercased / stripped token.
 *
 *  - [notes] is the ordered list of pitches in Hz. Pitches are drawn
 *    from a pentatonic scale across two octaves (see
 *    [MusicVocabulary.PENTATONIC]) so randomly-generated motifs still
 *    sound musical when played in sequence.
 *  - [hits] / [misses] track the user's decode-tap signal. A motif
 *    that the user reliably identifies (high hits) is "learned" and
 *    stays stable; a motif with low confidence is mutated to a fresh
 *    pitch pattern, giving learnability another chance.
 *  - [generation] increments each time the motif is mutated — useful
 *    for the vocabulary inspector and for debugging "why does this
 *    word sound different today?"
 */
@Serializable
data class Motif(
    val notes: List<Float>,
    val hits: Int = 0,
    val misses: Int = 0,
    val generation: Int = 0,
) {
    /** 0 (always missed) ... 1 (always hit). Returns 0.5 before any
     *  signal has been collected so a brand-new motif isn't treated
     *  as a failure. */
    val confidence: Float
        get() {
            val total = hits + misses
            return if (total == 0) 0.5f else hits.toFloat() / total
        }

    /** Encoded for the JSON blob — short keys keep the DataStore
     *  payload tight even at thousands of tokens. */
    fun toCompactString(): String {
        val n = notes.joinToString(",") { "%.2f".format(it) }
        return "{\"n\":[$n],\"h\":$hits,\"m\":$misses,\"g\":$generation}"
    }
}
