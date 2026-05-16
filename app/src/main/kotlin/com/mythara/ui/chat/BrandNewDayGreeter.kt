package com.mythara.ui.chat

import com.mythara.ai.ModelRouter
import com.mythara.me.MeProfileStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generates the "it's a brand new day" greeting shown at the top
 * of the chat transcript when the user has no chat activity for
 * today yet.
 *
 * Behaviour:
 *   - One greeting per ISO date. Cached in-memory; same date
 *     returns the same string until process death (or user resets
 *     the cache via [clear]).
 *   - Lazy generation: first call for today returns a random
 *     curated fallback IMMEDIATELY, then kicks off an async
 *     LLM call via ModelRouter.summarise (light path = local
 *     Gemma) to swap in a more personal greeting. The composable
 *     re-renders when the async result lands.
 *   - The LLM prompt includes the user's display name (from
 *     [MeProfileStore]) when set, so greetings feel personal
 *     ("Morning, Ankur — ...") instead of generic.
 *
 * Why this lives in a singleton: the ChatViewModel could own it,
 * but multiple chat surfaces (compact, two-pane right) might
 * mount the BrandNewDayBubble — caching at process scope means
 * the LLM call runs once total, not per-mount.
 */
@Singleton
class BrandNewDayGreeter @Inject constructor(
    private val router: ModelRouter,
    private val meProfile: MeProfileStore,
) {
    // Two-tier cache: a fast "what to show RIGHT NOW" value
    // (initially the fallback, replaced by the LLM result when
    // it lands), and a flag to ensure only one LLM call is
    // in-flight per day.
    private val cached = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /** Current greeting for [isoDay]. Returns the fallback
     *  immediately on first call; kicks off the LLM swap async. */
    fun current(isoDay: String): String =
        cached[isoDay] ?: fallbackFor(isoDay).also { cached[isoDay] = it }

    /** Suspend variant — kicks the LLM if not already cached,
     *  awaits the result, returns either the LLM greeting or
     *  the fallback. Used by the BubbleHost composable's
     *  produceState block. */
    suspend fun fetch(isoDay: String): String = withContext(Dispatchers.IO) {
        cached[isoDay]?.let { existing ->
            // If existing is the curated fallback (still un-
            // upgraded), try once to upgrade it.
            if (!existing.startsWith(CURATED_PREFIX)) return@withContext existing
        }
        // Mark in-flight + spin.
        if (inFlight.putIfAbsent(isoDay, true) != null) {
            return@withContext cached[isoDay] ?: fallbackFor(isoDay)
        }
        try {
            val name = runCatching { meProfile.snapshot().displayName.takeIf { it.isNotBlank() } }
                .getOrNull()
            val prompt = buildPrompt(isoDay, name)
            val llm = runCatching { router.summarise(prompt, maxLen = 220, heavy = false) }
                .getOrNull()
                ?.trim()
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it.length in 8..200 }
            val final = llm ?: fallbackFor(isoDay)
            cached[isoDay] = final
            final
        } finally {
            inFlight.remove(isoDay)
        }
    }

    /** Reset (e.g. on midnight rollover or user request). */
    fun clear() {
        cached.clear()
        inFlight.clear()
    }

    private fun buildPrompt(isoDay: String, userName: String?): String = buildString {
        append("Compose ONE short, warm, conversational morning greeting for a user starting ")
        append("a brand new day in a personal-AI assistant called Mythara. ")
        append("Today is $isoDay. ")
        if (userName != null) append("Address them by name: $userName. ")
        append("\n\nRules:\n")
        append("  - Under 25 words. ONE sentence ideally.\n")
        append("  - No questions, no calls to action, no emoji.\n")
        append("  - Feel-good but not saccharine — something a thoughtful friend would say.\n")
        append("  - DO NOT mention the AI nature, no \"as an AI\".\n")
        append("  - Return ONLY the greeting text, no preamble, no quotes.\n")
    }

    /** Pick one of the curated fallbacks deterministically for
     *  the day (so a re-paint before the LLM lands doesn't
     *  flicker between different lines). Prefixed with a marker
     *  so [fetch] knows this slot is upgrade-eligible. */
    private fun fallbackFor(isoDay: String): String {
        val seed = LocalDate.parse(isoDay).toEpochDay()
        val idx = (Random(seed).nextInt(FALLBACKS.size))
        return CURATED_PREFIX + FALLBACKS[idx]
    }

    companion object {
        private const val CURATED_PREFIX = "​"  // zero-width marker for upgrade detection

        /** A small set of "ok if the model is offline" greetings.
         *  Quiet + warm — nothing fakely chipper. */
        private val FALLBACKS: List<String> = listOf(
            "Clean slate. Today gets to be whatever you make of it.",
            "New day, same you — but a little more rested, hopefully.",
            "Whatever's on your mind, this is a good time to start.",
            "Take a breath. The day's not in a rush.",
            "Hello again — your move when you're ready.",
            "Quiet morning. I'm here whenever you want to dig in.",
            "Fresh page. Nothing on it yet.",
            "Today's first thought is whatever you decide it is.",
        )

        /** Hilt accessor for callers that aren't in the graph
         *  (e.g. raw composables that want the greeter without
         *  building a ViewModel just for it). */
        @EntryPoint
        @InstallIn(SingletonComponent::class)
        interface BrandNewDayEntryPoint {
            fun greeter(): BrandNewDayGreeter
        }

        fun from(ctx: android.content.Context): BrandNewDayGreeter =
            EntryPointAccessors.fromApplication(
                ctx.applicationContext,
                BrandNewDayEntryPoint::class.java,
            ).greeter()
    }
}
