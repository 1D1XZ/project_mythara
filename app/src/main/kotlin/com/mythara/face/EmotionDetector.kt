package com.mythara.face

import com.mythara.branding.LiveWallpaperPulseSink
import com.mythara.branding.MoodSink
import com.mythara.camera.FaceTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Fuses face expression (smile + eye-open) with HR delta vs personal
 * baseline + optional voice-tone valence into one of six mood labels.
 *
 * Inputs:
 *   - [FaceTracker.Pose.smile]        — ML Kit `smilingProbability` (EMA-smoothed)
 *   - [FaceTracker.Pose.leftEyeOpen]  — same; used as a squint signal
 *     (closed eyes + high HR = high-arousal negative state like
 *     anxious / frustrated)
 *   - HR from [LiveWallpaperPulseSink] vs its rolling baselineMean;
 *     ratio of (bpm − baseline)/baseline gives an arousal proxy
 *     scaled to the user's own resting rate, not an absolute
 *     threshold
 *   - Voice tone (when available via [voiceTone]) — same valence
 *     signal that LexicalMoodScorer + AcousticMoodFusion emit
 *
 * Output: a [Reading] with a mood label compatible with the existing
 * MoodPalette ("calm", "happy", "excited", "sad", "anxious",
 * "frustrated") plus an intensity in [0, 1] that the
 * FaceMesh + theme tint use to scale rotation speed / hue boost.
 *
 * The reading is published to [MoodSink] so the live wallpaper, amulet,
 * and any other component already reading the mood label react too.
 * Mythara's mood signal is now multi-modal: text from chat, audio from
 * voice samples, AND face + HR from the front camera.
 */
@Singleton
class EmotionDetector @Inject constructor() {

    data class Reading(
        val mood: String,
        /** [0, 1] — how confident the detector is that this mood is
         *  active. Used to scale the shape's rotation rate, the
         *  particle jitter, and the mood tint strength. */
        val intensity: Float,
        val ts: Long,
    )

    private val _reading = MutableStateFlow<Reading?>(null)
    val reading: StateFlow<Reading?> = _reading.asStateFlow()

    @Volatile private var voiceTone: Float = 0f
    @Volatile private var voiceToneTs: Long = 0L

    /** Voice tone valence signal: ≈ +1 = positive, ≈ −1 = negative.
     *  Called by [com.mythara.agent.mood.ChatMoodTracker] (lexical) or
     *  [com.mythara.agent.mood.AcousticMoodFusion] (audio) whenever
     *  they have a fresh reading. Stale-after [VOICE_STALE_AFTER_MS];
     *  beyond that the face signal alone drives the detector. */
    fun publishVoiceTone(valence: Float) {
        voiceTone = valence.coerceIn(-1f, 1f)
        voiceToneTs = System.currentTimeMillis()
    }

    /**
     * Push a fresh face-pose reading. Combines with the current HR +
     * voice-tone snapshots into a mood label and publishes it.
     *
     * Called from [com.mythara.ui.face.FaceMesh]'s pose-collection
     * loop — typically every 16-33 ms while the face is in view (the
     * Face-ID-style throttle drops idle frames before this point).
     */
    fun pushFacePose(pose: FaceTracker.Pose) {
        if (!pose.present) return
        val smile = pose.smile.coerceIn(0f, 1f)
        val eyeOpen = ((pose.leftEyeOpen + pose.rightEyeOpen) * 0.5f).coerceIn(0f, 1f)
        val hr = LiveWallpaperPulseSink.bpm() ?: 0
        val baseline = LiveWallpaperPulseSink.baseline()
        val hrDelta = if (hr > 0 && baseline > 0f) (hr - baseline) / baseline else 0f
        val voice = if (System.currentTimeMillis() - voiceToneTs <= VOICE_STALE_AFTER_MS) voiceTone else 0f

        val reading = classify(smile = smile, eyeOpen = eyeOpen, hrDelta = hrDelta, voiceTone = voice)
        _reading.value = reading
        MoodSink.update(reading.mood)
    }

    /** Two-axis affective model — arousal × valence — bucketed into
     *  the six mood labels MoodPalette already understands. Arousal
     *  comes from HR delta + how squinted/closed the eyes are (those
     *  often correlate with high-arousal negative states). Valence
     *  comes from smile + voice tone. */
    private fun classify(smile: Float, eyeOpen: Float, hrDelta: Float, voiceTone: Float): Reading {
        // Arousal: HR + eye-closed proxy. Scaled into [-1, 1] with
        // the eye contribution capped so a calm blink doesn't read
        // as anxiety.
        val eyeClosedSignal = (1f - eyeOpen) * 0.4f
        val arousal = (hrDelta * 1.6f + eyeClosedSignal).coerceIn(-1f, 1f)
        // Valence: smile (−0.5..+0.5) + voice tone weighted half (since
        // it's often stale). Stretched to roughly [-1, 1].
        val valence = ((smile - 0.5f) * 2f + voiceTone * 0.5f).coerceIn(-1f, 1f)

        val (mood, baseIntensity) = when {
            valence > 0.20f && arousal > 0.20f -> "excited" to ((valence + arousal) * 0.5f)
            valence > 0.20f && arousal <= 0.20f -> "happy" to valence
            valence < -0.20f && arousal > 0.20f -> "anxious" to ((-valence + arousal) * 0.5f)
            valence < -0.20f && arousal <= 0.20f -> "sad" to (-valence)
            arousal > 0.30f -> "frustrated" to arousal
            arousal < -0.05f -> "calm" to (-arousal).coerceAtLeast(0.3f)
            else -> "calm" to 0.4f
        }
        val intensity = baseIntensity.coerceIn(0f, 1f)
        return Reading(mood = mood, intensity = intensity, ts = System.currentTimeMillis())
    }

    companion object {
        private const val VOICE_STALE_AFTER_MS = 60_000L
    }
}
