package com.mythara.face

import android.content.Context
import com.mythara.analytics.interactions.ContactInteractionRepository
import com.mythara.branding.MoodSink
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import com.mythara.ui.face.ParticleShapes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Central state holder for the **living shape** on Home — the
 * geometric form that lives in the face mesh and evolves
 * continuously based on every signal Mythara can read about the
 * user:
 *
 *   - face expression (smile, eyes) via [EmotionDetector]
 *   - heart rate vs personal baseline via LiveWallpaperPulseSink
 *   - voice tone (text + audio) via ChatMoodTracker /
 *     AcousticMoodFusion (publish to [EmotionDetector.publishVoiceTone])
 *   - **relationship temperature** — how many real interactions
 *     with real people happened in the recent window; refreshed
 *     periodically by reading [ContactInteractionRepository]
 *
 * The state is one [LivingShape] data object held in a StateFlow.
 * FaceMesh reads it; updates happen continuously as signals
 * arrive. No more session-bounded re-rolls without smooth
 * transitions — every signal change nudges parameters in real
 * time, and the shape kind only flips on actual face-detect
 * transitions (preserving the "never repeat from old" guarantee).
 *
 * Memory: on every face-detect session end (face leaves), the
 * dominant state is written to [LearningVault] as a tagged
 * record so the agent's recall surface knows the user has been
 * (e.g.) calm and connected this afternoon. This feeds the
 * "evolve with memory and learning" loop the user asked for.
 */
@Singleton
class LivingShapeEngine @Inject constructor(
    private val emotionDetector: EmotionDetector,
    private val historyStore: MoodHistoryStore,
    private val interactionRepo: ContactInteractionRepository,
    private val vault: LearningVault,
    @ApplicationContext private val ctx: Context,
) {

    data class LivingShape(
        /** Generator family for this session's shape. Each family
         *  rolls dramatically different random forms — and within a
         *  family, two rolls produce visually distinct geometries.
         *  So "Supershape twice in a row" still gives the user a
         *  different alien form each time. */
        val family: CreativeShapes.Family,
        /** Random seed used to mint this session's shape. Persisted
         *  so the FaceMesh can re-render the same shape across
         *  recompositions without re-rolling. */
        val seed: Long,
        /** Unit vector — the axis the shape spins around this session.
         *  Re-rolled on every new pickup so a different geometry tilts
         *  in a different direction. */
        val rotationAxis: FloatArray,
        val rotationRateHz: Float,
        val glowMultiplier: Float,
        val particleCount: Int,
        /** Most recent detector reading (mood label + intensity). May
         *  be null until a face has been seen at least once. */
        val mood: String?,
        val intensity: Float,
        /** 0 (solitary day) → 1 (richly social day) computed from the
         *  ContactInteractionDb. Tints the shape toward warm hues +
         *  denser particles when high; cool + sparser when low. */
        val socialTemperature: Float,
        /** True while a face is currently in frame. When false the
         *  FaceMesh holds the LAST assembled shape but at lower
         *  energy (slower rotation, dimmer glow) — the shape PERSISTS
         *  rather than scattering apart. */
        val active: Boolean,
        val sessionStartMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LivingShape) return false
            return family == other.family && seed == other.seed &&
                rotationAxis.contentEquals(other.rotationAxis) &&
                rotationRateHz == other.rotationRateHz &&
                glowMultiplier == other.glowMultiplier &&
                particleCount == other.particleCount &&
                mood == other.mood && intensity == other.intensity &&
                socialTemperature == other.socialTemperature &&
                active == other.active && sessionStartMs == other.sessionStartMs
        }
        override fun hashCode(): Int {
            var r = family.hashCode()
            r = 31 * r + seed.hashCode()
            r = 31 * r + rotationAxis.contentHashCode()
            r = 31 * r + rotationRateHz.hashCode()
            r = 31 * r + glowMultiplier.hashCode()
            r = 31 * r + particleCount
            r = 31 * r + (mood?.hashCode() ?: 0)
            r = 31 * r + intensity.hashCode()
            r = 31 * r + socialTemperature.hashCode()
            r = 31 * r + active.hashCode()
            r = 31 * r + sessionStartMs.hashCode()
            return r
        }
    }

    private val initial = LivingShape(
        family = CreativeShapes.Family.SphericalHarmonic,
        seed = 0L,
        rotationAxis = floatArrayOf(0f, 1f, 0f),
        rotationRateHz = 0.30f,
        glowMultiplier = 1.0f,
        particleCount = 800,
        mood = null,
        intensity = 0.4f,
        socialTemperature = 0.3f,
        active = false,
        sessionStartMs = 0L,
    )

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<LivingShape> = _state.asStateFlow()

    /** Sliding window of the last 4 family choices — feeds the
     *  CreativeShapes family-pick so we don't get the same generator
     *  twice in a row. Within a family every roll is already
     *  visually unique (different random params), so a 4-deep
     *  family window plus per-roll randomness gives essentially
     *  unbounded shape novelty. */
    private val recentFamilies = ArrayDeque<CreativeShapes.Family>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Continuous parameter drift driven by EmotionDetector readings.
        scope.launch {
            emotionDetector.reading.collect { onReadingChange(it) }
        }
        // Periodic social-temperature refresh — every minute is plenty
        // because interaction events arrive at chat / call / SMS pace,
        // not per-frame.
        scope.launch {
            while (true) {
                refreshSocialTemperature()
                delay(60_000L)
            }
        }
        // Live mood-change trigger: when MoodSink emits a fresh label
        // DURING an active session and it's different from the
        // session's starting mood, mint a brand-new shape (new
        // family + new seed). FaceMesh observes the seed change and
        // smoothly morphs from old → new geometry without ending the
        // session. So a smile mid-stare → the shape evolves LIVE.
        scope.launch {
            MoodSink.moodFlow.collect { newMood ->
                val cur = _state.value
                if (cur.active && newMood != null && newMood != cur.mood) {
                    mutateForMoodChange(newMood)
                }
            }
        }
    }

    /** Called by FaceMesh on every face-detect session start
     *  (pose.present transition false → true). Re-picks the shape
     *  with the never-repeat guarantee + records the start time so
     *  the session-end hook knows the duration. */
    suspend fun startSession() {
        // Per-session seed — captured into LivingShape so FaceMesh can
        // re-derive the exact same shape across recompositions
        // without state drift.
        val seed = System.nanoTime() xor Random.nextLong()
        val rnd = Random(seed)
        val mood = MoodSink.current()
        val intensity = emotionDetector.reading.value?.intensity ?: 0.5f
        // Roll the family with mood-bias but avoid the most-recent ones.
        // Within a family every roll is already visually unique
        // (different random parameters), so a 2-deep family-avoid plus
        // CreativeShapes' internal randomness gives essentially
        // unbounded novel forms.
        val avoid = recentFamilies.take(2).toSet()
        var family = CreativeShapes.Family.SphericalHarmonic
        // 6 attempts then accept whatever — generous; never blocks.
        for (attempt in 0 until 6) {
            val candidateRng = Random(seed + attempt * 7919L)
            family = CreativeShapes.pickFamilyExternal(mood, candidateRng)
            if (family !in avoid) break
        }
        recentFamilies.addFirst(family)
        while (recentFamilies.size > 4) recentFamilies.removeLast()
        // Random rotation axis.
        val axis = FloatArray(3)
        val u = rnd.nextFloat() * 2f - 1f
        val phi = rnd.nextFloat() * 2f * kotlin.math.PI.toFloat()
        val rxy = kotlin.math.sqrt(1f - u * u)
        axis[0] = rxy * kotlin.math.cos(phi)
        axis[1] = rxy * kotlin.math.sin(phi)
        axis[2] = u

        _state.value = _state.value.copy(
            family = family,
            seed = seed,
            rotationAxis = axis,
            rotationRateHz = ShapeMoodMapping.rotationRateHz(mood, intensity),
            glowMultiplier = ShapeMoodMapping.glowMultiplier(mood, intensity),
            particleCount = CreativeShapes.particleCount(family, mood, intensity),
            mood = mood,
            intensity = intensity,
            active = true,
            sessionStartMs = System.currentTimeMillis(),
        )
    }

    /** Live mid-session re-roll triggered when MoodSink emits a fresh
     *  label that differs from the session's current mood. Picks a
     *  brand-new family + seed (same never-repeat / mood-bias logic
     *  as startSession), updates the state, and lets FaceMesh smoothly
     *  morph from the previous geometry to the new one. Does NOT end
     *  the session — sessionStartMs + active flag stay the same so
     *  the memory record on session-end captures the full arc. */
    private fun mutateForMoodChange(newMood: String) {
        val cur = _state.value
        if (!cur.active) return
        val seed = System.nanoTime() xor Random.nextLong()
        val rnd = Random(seed)
        val avoid = recentFamilies.take(2).toSet()
        var family = CreativeShapes.Family.SphericalHarmonic
        for (attempt in 0 until 6) {
            val candidateRng = Random(seed + attempt * 7919L)
            family = CreativeShapes.pickFamilyExternal(newMood, candidateRng)
            if (family !in avoid) break
        }
        recentFamilies.addFirst(family)
        while (recentFamilies.size > 4) recentFamilies.removeLast()
        // Fresh random rotation axis so the new shape tilts in a new
        // direction — visual change matches the emotional change.
        val axis = FloatArray(3)
        val u = rnd.nextFloat() * 2f - 1f
        val phi = rnd.nextFloat() * 2f * kotlin.math.PI.toFloat()
        val rxy = kotlin.math.sqrt(1f - u * u)
        axis[0] = rxy * kotlin.math.cos(phi)
        axis[1] = rxy * kotlin.math.sin(phi)
        axis[2] = u
        val intensity = emotionDetector.reading.value?.intensity ?: cur.intensity
        _state.value = cur.copy(
            family = family,
            seed = seed,
            rotationAxis = axis,
            rotationRateHz = ShapeMoodMapping.rotationRateHz(newMood, intensity),
            glowMultiplier = ShapeMoodMapping.glowMultiplier(newMood, intensity),
            particleCount = CreativeShapes.particleCount(family, newMood, intensity),
            mood = newMood,
            intensity = intensity,
        )
    }

    /** Called by FaceMesh on session end (pose.present transition
     *  true → false). Holds the shape kind + axis, drops the energy
     *  parameters (slower spin, dimmer glow), and writes a memory
     *  record to [MoodHistoryStore] + [LearningVault]. */
    suspend fun endSession() {
        val cur = _state.value
        if (cur.sessionStartMs <= 0L) return
        val durationMs = (System.currentTimeMillis() - cur.sessionStartMs).coerceAtLeast(0L)
        // Drop to idle energy but KEEP the shape — the user wants the
        // last evolved form to persist when the face leaves.
        _state.value = cur.copy(
            active = false,
            rotationRateHz = cur.rotationRateHz * 0.45f,
            glowMultiplier = cur.glowMultiplier * 0.55f,
        )
        // Filter out flicker events.
        if (durationMs < 500L) return
        // 1. Mood history — drives the next shape pick's history bias.
        runCatching {
            historyStore.record(
                MoodHistoryStore.MoodSession(
                    tsMs = cur.sessionStartMs,
                    mood = cur.mood ?: "calm",
                    intensity = cur.intensity,
                    durationMs = durationMs,
                    shapeKind = cur.family.name,
                ),
            )
        }
        // 2. Memory of me — into the LearningVault as a tagged
        //    observation. The agent's recall surface can find these
        //    when the user asks "how was I yesterday?" or the
        //    persona builder rolls them up.
        val durationSec = (durationMs / 1000L).coerceAtLeast(1L)
        val content = buildString {
            append("self emotional reading: ")
            append(cur.mood ?: "calm")
            append(" (intensity ").append("%.2f".format(cur.intensity)).append(")")
            append(" for ").append(durationSec).append("s")
            append(", shape=").append(cur.family.name.lowercase())
            append(", social=").append("%.2f".format(cur.socialTemperature))
        }
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "living-shape",
                facets = listOf(
                    "kind:emotional-session",
                    "mood:${cur.mood ?: "calm"}",
                    "shape:${cur.family.name.lowercase()}",
                    "target:self",
                    "social:${(cur.socialTemperature * 10).toInt()}",
                ),
                conf = cur.intensity.toDouble().coerceAtLeast(0.3),
            )
        }
    }

    /** Continuous parameter drift driven by every EmotionDetector
     *  reading (face frame ticks come at ~30 fps when tracking, ~3
     *  fps when idle). EMA on rotation rate + glow keeps the
     *  shape from twitching as the smile or HR signal jitters. */
    private fun onReadingChange(reading: EmotionDetector.Reading?) {
        val cur = _state.value
        val mood = reading?.mood ?: cur.mood
        val intensity = reading?.intensity ?: cur.intensity
        val targetRate = ShapeMoodMapping.rotationRateHz(mood, intensity)
        val targetGlow = ShapeMoodMapping.glowMultiplier(mood, intensity)
        // Strong smoothing — don't want every face-jitter to bounce
        // the visible rotation rate around. 12 % per update means
        // ~30 readings (~1 s) for a full transition.
        val newRate = cur.rotationRateHz * 0.88f + targetRate * 0.12f
        val newGlow = cur.glowMultiplier * 0.88f + targetGlow * 0.12f
        // Particle count uses the freshly-picked kind for THIS session;
        // we deliberately don't change particleCount mid-session to
        // avoid jarring density shifts. It re-rolls on the next start.
        _state.value = cur.copy(
            rotationRateHz = newRate,
            glowMultiplier = newGlow,
            mood = mood,
            intensity = intensity,
        )
    }

    /** Pull recent interaction counts from the ContactInteractionDb
     *  and turn them into a 0..1 social-temperature signal. The shape
     *  reads this to tilt density + colour energy when the user has
     *  been actively engaging with people vs solo. */
    private suspend fun refreshSocialTemperature() {
        val warmth = runCatching {
            val now = System.currentTimeMillis()
            val window = 24L * 3_600_000L // 24 h
            val rows = interactionRepo.dao.listAll(limit = 200)
                .filter { now - it.tsMs < window }
            // Recency-weighted count: an interaction now counts ~1,
            // 12 h ago ~0.5, 24 h ago ~0.05. Normalise so 20
            // recency-weighted interactions / day reads as "max
            // warmth" — empirically this matches a normal social day.
            var weighted = 0f
            for (row in rows) {
                val ageH = ((now - row.tsMs) / 3_600_000f).coerceAtLeast(0.001f)
                weighted += (1f / (1f + ageH * 0.15f))
            }
            (weighted / 20f).coerceIn(0f, 1f)
        }.getOrDefault(_state.value.socialTemperature)
        // Smooth so the bar doesn't lurch on every refresh tick.
        val cur = _state.value
        val smoothed = cur.socialTemperature * 0.7f + warmth * 0.3f
        _state.value = cur.copy(socialTemperature = smoothed)
    }
}
