package com.mythara.branding

/**
 * Maps a mood label to the (top, bottom) RGB stops for the live
 * wallpaper's gradient. Read by [WallpaperRenderer] every frame and
 * smoothly lerp'd into the currently-rendered colours so a mood
 * shift fades in over ~5-10 s rather than popping.
 *
 * Labels match the strings emitted by
 * [com.mythara.agent.mood.LexicalMoodScorer]:
 *   anxious / sad / frustrated / excited / happy / null
 *
 * Each palette keeps the wallpaper recognisably "Mythara" — same
 * dark deep-space character — but biases the hue + saturation:
 *   anxious    : red-tinged, like a thin sunset before storm
 *   sad        : cool blue, somber
 *   frustrated : warmer red, orange highlights
 *   excited    : magenta + warm purple, energetic
 *   happy      : warm purple + violet, the brand baseline shifted
 *                slightly brighter
 *   neutral    : the original purple-black gradient (#06040C →
 *                #2A1740) — used when no mood is currently known
 *                or when the mood is too stale (>30 min)
 *
 * RGB triples are kept as IntArray rather than Color ints so the
 * renderer can lerp per-channel without packing/unpacking on every
 * frame.
 */
internal object MoodPalette {
    data class Stops(val top: IntArray, val bot: IntArray)

    private val NEUTRAL = Stops(intArrayOf(0x06, 0x04, 0x0C), intArrayOf(0x2A, 0x17, 0x40))
    private val ANXIOUS = Stops(intArrayOf(0x0E, 0x04, 0x08), intArrayOf(0x40, 0x18, 0x22))
    private val SAD = Stops(intArrayOf(0x04, 0x06, 0x10), intArrayOf(0x18, 0x22, 0x40))
    private val FRUSTRATED = Stops(intArrayOf(0x12, 0x06, 0x04), intArrayOf(0x40, 0x22, 0x16))
    private val EXCITED = Stops(intArrayOf(0x10, 0x04, 0x12), intArrayOf(0x52, 0x18, 0x48))
    private val HAPPY = Stops(intArrayOf(0x08, 0x04, 0x10), intArrayOf(0x36, 0x1F, 0x4F))

    fun forLabel(mood: String?): Stops = when (mood?.lowercase()) {
        "anxious" -> ANXIOUS
        "sad" -> SAD
        "frustrated" -> FRUSTRATED
        "excited" -> EXCITED
        "happy" -> HAPPY
        else -> NEUTRAL
    }
}
