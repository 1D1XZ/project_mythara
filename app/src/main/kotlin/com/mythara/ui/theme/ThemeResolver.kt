package com.mythara.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.mythara.data.BrightnessMode
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Resolve a [BrightnessMode] into a concrete isDark boolean.
 *
 *  - [BrightnessMode.Light] → false
 *  - [BrightnessMode.Dark]  → true
 *  - [BrightnessMode.System] → follows the OS dark-theme setting
 *  - [BrightnessMode.TimeOfDay] → dark between [NIGHT_START_HOUR] (18:00)
 *    and [DAY_START_HOUR] (06:00). Recomputes ONLY at the next day/night
 *    boundary (a single delay, not a per-frame ticker) so the cost is
 *    one recomposition at ~6am and ~6pm — never per frame.
 */
@Composable
fun rememberIsDark(mode: BrightnessMode): Boolean = when (mode) {
    BrightnessMode.Light -> false
    BrightnessMode.Dark -> true
    BrightnessMode.System -> isSystemInDarkTheme()
    BrightnessMode.TimeOfDay -> rememberTimeOfDayDark()
}

@Composable
private fun rememberTimeOfDayDark(): Boolean {
    val isDark by produceState(initialValue = isNightNow()) {
        while (true) {
            value = isNightNow()
            // Sleep until the next 06:00 / 18:00 boundary, then flip.
            delay(millisUntilNextBoundary())
        }
    }
    return isDark
}

private const val DAY_START_HOUR = 6     // 06:00 → light
private const val NIGHT_START_HOUR = 18  // 18:00 → dark

private fun isNightNow(): Boolean {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return hour < DAY_START_HOUR || hour >= NIGHT_START_HOUR
}

/** Milliseconds from now until the next 06:00 or 18:00 local boundary. */
private fun millisUntilNextBoundary(): Long {
    val now = Calendar.getInstance()
    val next = Calendar.getInstance().apply {
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        when {
            hour < DAY_START_HOUR -> set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
            hour < NIGHT_START_HOUR -> set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR)
            else -> {
                // After 18:00 → next boundary is 06:00 tomorrow.
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
            }
        }
    }
    return (next.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
}
