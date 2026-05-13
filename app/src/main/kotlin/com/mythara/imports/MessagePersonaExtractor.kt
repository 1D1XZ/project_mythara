package com.mythara.imports

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar

/**
 * Reduces a batch of imported [MessageRecord]s (SMS provider scan,
 * WhatsApp `.txt` export, future Signal / Telegram backups) into a
 * handful of persona-trait vault records.
 *
 * Privacy posture: we don't persist raw messages. Only the
 * extracted patterns — "top texting contact is Mom", "most active
 * texting hour is 9-10pm", "common phrases: lol / thanks / on my
 * way" — land in the vault. Those records sync to GitHub like the
 * usage-stats persona records.
 *
 * v1 is heuristic only:
 *  - top-K contacts by message count
 *  - peak-hour bucket (when does the user text most)
 *  - communication style classification (casual / formal /
 *    abbreviation-heavy) via simple feature counts
 *  - count totals for transparency ("imported N messages")
 *
 * v2 (future) could run a Gemma summarisation pass over chunks to
 * extract higher-order traits ("relationship dynamics with X",
 * "common topics with Y") — same pattern as PersonaBuilder's
 * usage-stats path, just over message bodies.
 */
@Singleton
class MessagePersonaExtractor @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
) {
    data class Report(
        val ok: Boolean,
        val recordsWritten: Int,
        val messagesAnalyzed: Int,
        val message: String? = null,
    )

    suspend fun extractAndPersist(
        source: String,
        messages: List<MessageRecord>,
    ): Report {
        if (messages.isEmpty()) {
            return Report(false, 0, 0, "no messages to analyse")
        }
        val now = System.currentTimeMillis()
        var written = 0

        // 1) Top contacts by user-sent message count. "Mostly texts
        //    Mom and Sam" is one of the most useful persona facts.
        val userMessages = messages.filter { it.isFromUser && !it.contact.isNullOrBlank() }
        val byContact = userMessages
            .groupingBy { it.contact!! }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        if (byContact.isNotEmpty()) {
            val top = byContact.take(TOP_CONTACTS).joinToString(", ") { (name, count) -> "$name ($count)" }
            addPersonaFact(
                content = "Top $source contacts the user messages: $top.",
                traits = listOf("trait:top-contacts", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 2) Peak hour band — which 4h window dominates outgoing
        //    message timestamps. Tells the agent about the user's
        //    rhythm ("you're a 9-11pm texter").
        val hourCounts = IntArray(24)
        val cal = Calendar.getInstance()
        for (m in userMessages) {
            cal.timeInMillis = m.tsMillis
            hourCounts[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        val (bandLabel, bandCount) = peakBand(hourCounts)
        if (bandCount > 0) {
            addPersonaFact(
                content = "Peak $source texting time for the user: $bandLabel.",
                traits = listOf("trait:texting-rhythm", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 3) Communication style — quick heuristic scan over the
        //    user's outbound text. Counts emoji density, abbrev
        //    presence ("lol", "btw", "rn", "u", "ur"), avg length.
        val style = classifyStyle(userMessages)
        if (style != null) {
            addPersonaFact(
                content = "User's $source communication style: $style.",
                traits = listOf("trait:style", "source:$source"),
                now = now,
            )?.let { written++ }
        }

        // 4) Totals for transparency.
        val inboundCount = messages.count { !it.isFromUser }
        addPersonaFact(
            content = "Imported $source history: ${messages.size} messages (${userMessages.size} sent, $inboundCount received).",
            traits = listOf("trait:import-summary", "source:$source"),
            now = now,
        )?.let { written++ }

        return Report(
            ok = true,
            recordsWritten = written,
            messagesAnalyzed = messages.size,
        )
    }

    private suspend fun addPersonaFact(
        content: String,
        traits: List<String>,
        now: Long,
    ): Boolean {
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = buildList {
            add("kind:persona")
            add("source:message-import")
            addAll(traits)
        }
        return runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "persona:message-import",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.85,
                now = now,
            )
        }.getOrElse {
            Log.w(TAG, "vault.add (persona/message-import) failed: ${it.message}")
            false
        }
    }

    private fun peakBand(hourCounts: IntArray): Pair<String, Int> {
        // Slide a 4-hour window across the 24-hour cycle (wrap-around)
        // and pick the one with the most messages.
        var bestStart = 0
        var bestSum = 0
        for (start in 0..23) {
            var sum = 0
            for (i in 0 until BAND_WIDTH) sum += hourCounts[(start + i) % 24]
            if (sum > bestSum) {
                bestSum = sum
                bestStart = start
            }
        }
        val endHour = (bestStart + BAND_WIDTH) % 24
        return "${formatHour(bestStart)}–${formatHour(endHour)}" to bestSum
    }

    private fun formatHour(h: Int): String = when (h) {
        0 -> "12am"
        12 -> "12pm"
        in 1..11 -> "${h}am"
        else -> "${h - 12}pm"
    }

    /** Quick style classification. Returns null if too few messages to classify. */
    private fun classifyStyle(userMessages: List<MessageRecord>): String? {
        if (userMessages.size < STYLE_MIN_MESSAGES) return null
        var emojiCount = 0
        var abbrevCount = 0
        var totalLen = 0
        for (m in userMessages) {
            totalLen += m.text.length
            // Emoji: any codepoint in the common emoji ranges
            var i = 0
            while (i < m.text.length) {
                val cp = m.text.codePointAt(i)
                if (cp in 0x1F000..0x1FFFF || cp in 0x2600..0x27BF) emojiCount++
                i += Character.charCount(cp)
            }
            // Abbreviations: token-bound match against a small set
            for (abbrev in ABBREVIATIONS) {
                if (TOKEN.matcher(m.text).results()
                        .anyMatch { it.group().equals(abbrev, ignoreCase = true) }
                ) abbrevCount++
            }
        }
        val avgLen = totalLen.toFloat() / userMessages.size
        val emojiRate = emojiCount.toFloat() / userMessages.size
        val abbrevRate = abbrevCount.toFloat() / userMessages.size
        return when {
            emojiRate >= 0.5f && abbrevRate >= 0.3f -> "casual, emoji + abbreviation heavy (avg ${avgLen.toInt()} chars/msg)"
            emojiRate >= 0.5f -> "casual, emoji-rich (avg ${avgLen.toInt()} chars/msg)"
            abbrevRate >= 0.3f -> "casual, abbreviation-heavy (avg ${avgLen.toInt()} chars/msg)"
            avgLen >= 100f -> "long-form, formal (avg ${avgLen.toInt()} chars/msg)"
            avgLen >= 50f -> "moderate length, conversational (avg ${avgLen.toInt()} chars/msg)"
            else -> "terse, brief (avg ${avgLen.toInt()} chars/msg)"
        }
    }

    companion object {
        private const val TAG = "Mythara/MsgImport"
        private const val TOP_CONTACTS = 5
        private const val BAND_WIDTH = 4
        private const val STYLE_MIN_MESSAGES = 50
        private val ABBREVIATIONS = setOf(
            "lol", "btw", "rn", "u", "ur", "tbh", "imo", "imho",
            "omw", "thx", "thnx", "k", "kk", "np", "ttyl", "brb",
        )
        // Lazy Java regex — Kotlin Regex doesn't have a streaming
        // results() API. Pattern is "word-character runs".
        private val TOKEN = java.util.regex.Pattern.compile("""\b[\w']+\b""")
    }
}
