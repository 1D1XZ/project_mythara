package com.mythara.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mythara.branding.MoodSink

/**
 * Per-mood **hue shift in HSV degrees** + saturation/brightness
 * multipliers that the [MytharaTheme] applies to a copy of the
 * resolved [MythPalette] when a mood is active.
 *
 * Net effect: when the user's face / voice / HR signals a mood, every
 * accent colour in the app gently rotates around the colour wheel
 * toward that mood's affective bias:
 *   calm        →  cooler blues / cyans   (hue +30, less saturated)
 *   happy       →  warmer purples / golds (hue −15, brighter)
 *   excited     →  hot magenta / red      (hue −60, brighter, saturated)
 *   sad         →  desaturated blue       (hue +60, less saturated, dimmer)
 *   anxious     →  hot red / orange       (hue −100, saturated, dimmer)
 *   frustrated  →  burnt orange           (hue −80, saturated)
 *
 * The tint magnitude scales with mood intensity (capped at
 * [MAX_HUE_SHIFT_DEG] to never make Charple unrecognisable), and the
 * tint is applied to BRAND accents only (Charple / Bok / Malibu /
 * Mustard / Sriracha / Lavender) — Surface / Bg / Fg are left
 * untouched so legibility stays consistent.
 */
internal object MoodTint {

    data class Shift(val hueDeg: Float, val satMul: Float, val valMul: Float)

    private val CALM = Shift(hueDeg = 30f, satMul = 0.88f, valMul = 1.00f)
    private val HAPPY = Shift(hueDeg = -15f, satMul = 1.05f, valMul = 1.05f)
    private val EXCITED = Shift(hueDeg = -60f, satMul = 1.20f, valMul = 1.05f)
    private val SAD = Shift(hueDeg = 60f, satMul = 0.70f, valMul = 0.85f)
    private val ANXIOUS = Shift(hueDeg = -100f, satMul = 1.15f, valMul = 0.95f)
    private val FRUSTRATED = Shift(hueDeg = -80f, satMul = 1.10f, valMul = 1.00f)
    private val NEUTRAL = Shift(hueDeg = 0f, satMul = 1f, valMul = 1f)

    /** Cap on how far any single mood can rotate the colour wheel.
     *  Without this an excited reading would push Charple → red and
     *  the brand would be unrecognisable. */
    private const val MAX_HUE_SHIFT_DEG = 90f

    /** Resolved at composition time. Subscribes to [MoodSink.moodFlow]
     *  so a fresh detection re-runs every composable that reads the
     *  palette — keep the tint cheap. */
    @Composable
    fun rememberCurrentShift(): Shift {
        val mood by MoodSink.moodFlow.collectAsState()
        return forLabel(mood)
    }

    fun forLabel(label: String?): Shift = when (label?.lowercase()) {
        "calm" -> CALM
        "happy" -> HAPPY
        "excited" -> EXCITED
        "sad" -> SAD
        "anxious" -> ANXIOUS
        "frustrated" -> FRUSTRATED
        else -> NEUTRAL
    }

    /** Apply a mood [shift] to one accent colour. Operates in HSV
     *  space because hue-only rotation in RGB is awful (blue +
     *  green = teal, not blue rotated). Clamps shift magnitude to
     *  [MAX_HUE_SHIFT_DEG]. */
    fun tint(color: Color, shift: Shift): Color {
        if (shift == NEUTRAL) return color
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color.toArgb(), hsv)
        val capped = shift.hueDeg.coerceIn(-MAX_HUE_SHIFT_DEG, MAX_HUE_SHIFT_DEG)
        hsv[0] = ((hsv[0] + capped) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * shift.satMul).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * shift.valMul).coerceIn(0f, 1f)
        val argb = AndroidColor.HSVToColor((color.alpha * 255).toInt(), hsv)
        return Color(argb)
    }

}
