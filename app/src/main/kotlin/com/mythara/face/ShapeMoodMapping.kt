package com.mythara.face

import com.mythara.ui.face.ParticleShapes
import kotlin.random.Random

/**
 * Maps a detected mood + recent history into:
 *   - a **weighted shape pick** from [ParticleShapes.Kind]
 *   - **rotation rate** + **jitter intensity** modulators for the
 *     spinning 3D shape
 *
 * Per-mood priors:
 *   calm        → torus / octahedron (smooth, balanced, slow)
 *   happy       → icosahedron / torus (complex, lively, mid)
 *   excited     → trefoil knot / icosahedron (chaotic, twisty, fast)
 *   sad         → tetrahedron / octahedron (simple, contracted, slow)
 *   anxious     → cube / tetrahedron (rigid, defensive, jittery)
 *   frustrated  → cube / knot (rigid + chaotic mix)
 *
 * The history-blend ("learning from past expressions"): the
 * dominant-mood weights from the user's recent 7 sessions are mixed
 * in at [HISTORY_BLEND_WEIGHT]. So a calm-leaning week pulls EVERY
 * pick slightly toward calm shapes even when the current emotion
 * detector reads neutral.
 */
internal object ShapeMoodMapping {

    private val CALM = mapOf(
        ParticleShapes.Kind.Torus to 0.45f,
        ParticleShapes.Kind.Octahedron to 0.20f,
        ParticleShapes.Kind.Icosahedron to 0.15f,
        ParticleShapes.Kind.Cube to 0.10f,
        ParticleShapes.Kind.Tetrahedron to 0.05f,
        ParticleShapes.Kind.TrefoilKnot to 0.05f,
    )
    private val HAPPY = mapOf(
        ParticleShapes.Kind.Icosahedron to 0.40f,
        ParticleShapes.Kind.Torus to 0.25f,
        ParticleShapes.Kind.TrefoilKnot to 0.15f,
        ParticleShapes.Kind.Octahedron to 0.10f,
        ParticleShapes.Kind.Cube to 0.05f,
        ParticleShapes.Kind.Tetrahedron to 0.05f,
    )
    private val EXCITED = mapOf(
        ParticleShapes.Kind.TrefoilKnot to 0.40f,
        ParticleShapes.Kind.Icosahedron to 0.30f,
        ParticleShapes.Kind.Torus to 0.10f,
        ParticleShapes.Kind.Octahedron to 0.10f,
        ParticleShapes.Kind.Cube to 0.05f,
        ParticleShapes.Kind.Tetrahedron to 0.05f,
    )
    private val SAD = mapOf(
        ParticleShapes.Kind.Tetrahedron to 0.45f,
        ParticleShapes.Kind.Octahedron to 0.20f,
        ParticleShapes.Kind.Cube to 0.15f,
        ParticleShapes.Kind.Torus to 0.10f,
        ParticleShapes.Kind.Icosahedron to 0.05f,
        ParticleShapes.Kind.TrefoilKnot to 0.05f,
    )
    private val ANXIOUS = mapOf(
        ParticleShapes.Kind.Cube to 0.40f,
        ParticleShapes.Kind.Tetrahedron to 0.25f,
        ParticleShapes.Kind.TrefoilKnot to 0.15f,
        ParticleShapes.Kind.Octahedron to 0.10f,
        ParticleShapes.Kind.Icosahedron to 0.05f,
        ParticleShapes.Kind.Torus to 0.05f,
    )
    private val FRUSTRATED = mapOf(
        ParticleShapes.Kind.Cube to 0.30f,
        ParticleShapes.Kind.TrefoilKnot to 0.30f,
        ParticleShapes.Kind.Tetrahedron to 0.20f,
        ParticleShapes.Kind.Octahedron to 0.10f,
        ParticleShapes.Kind.Icosahedron to 0.05f,
        ParticleShapes.Kind.Torus to 0.05f,
    )
    private val UNIFORM = ParticleShapes.Kind.entries.associateWith {
        1f / ParticleShapes.Kind.entries.size
    }

    /** Pick a shape weighted by [mood] + boosted by [recentHistory]'s
     *  dominant mood. */
    fun pickShape(
        mood: String?,
        recentHistory: List<String>,
        rnd: Random,
    ): ParticleShapes.Kind {
        val base = weightsFor(mood)
        val dominantRecent = recentHistory
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
        val merged = if (dominantRecent != null && dominantRecent != mood) {
            val historyWeights = weightsFor(dominantRecent)
            mergeWithHistoryBoost(base, historyWeights, HISTORY_BLEND_WEIGHT)
        } else base
        return weightedPick(merged, rnd)
    }

    /** Per-second rotation rate (Hz) for the chosen shape, modulated
     *  by mood + intensity. The base rate was previously a hardcoded
     *  0.30 Hz; now it ranges from ~0.15 Hz (sad / calm low intensity)
     *  up to ~0.80 Hz (excited high intensity). */
    fun rotationRateHz(mood: String?, intensity: Float): Float {
        val base = when (mood) {
            "excited" -> 0.60f
            "frustrated" -> 0.50f
            "anxious" -> 0.45f
            "happy" -> 0.35f
            "calm" -> 0.20f
            "sad" -> 0.15f
            else -> 0.30f
        }
        return base * (0.7f + intensity * 0.6f)
    }

    /** Particle-glow + size multiplier per mood + intensity. Calm
     *  states gently dim; excited / frustrated states pop. Returns a
     *  multiplier the FaceMesh applies to per-particle alpha. */
    fun glowMultiplier(mood: String?, intensity: Float): Float {
        val base = when (mood) {
            "excited", "happy" -> 1.15f
            "frustrated" -> 1.05f
            "anxious" -> 1.00f
            "calm" -> 0.85f
            "sad" -> 0.80f
            else -> 1.00f
        }
        return base * (0.85f + intensity * 0.30f)
    }

    private fun weightsFor(mood: String?): Map<ParticleShapes.Kind, Float> = when (mood?.lowercase()) {
        "calm" -> CALM
        "happy" -> HAPPY
        "excited" -> EXCITED
        "sad" -> SAD
        "anxious" -> ANXIOUS
        "frustrated" -> FRUSTRATED
        else -> UNIFORM
    }

    private fun mergeWithHistoryBoost(
        base: Map<ParticleShapes.Kind, Float>,
        history: Map<ParticleShapes.Kind, Float>,
        boost: Float,
    ): Map<ParticleShapes.Kind, Float> =
        ParticleShapes.Kind.entries.associateWith {
            (1f - boost) * (base[it] ?: 0f) + boost * (history[it] ?: 0f)
        }

    private fun weightedPick(
        weights: Map<ParticleShapes.Kind, Float>,
        rnd: Random,
    ): ParticleShapes.Kind {
        val total = weights.values.sum()
        if (total <= 0f) return ParticleShapes.Kind.entries.random(rnd)
        val r = rnd.nextFloat() * total
        var acc = 0f
        for ((k, w) in weights) {
            acc += w
            if (r <= acc) return k
        }
        return weights.keys.last()
    }

    /** Strength of the history influence on the next pick — fraction
     *  of the merged weights that comes from the dominant recent mood.
     *  0.20 felt right: noticeable but not so strong that today's
     *  actual mood gets drowned out by last week's pattern. */
    private const val HISTORY_BLEND_WEIGHT = 0.20f
}
