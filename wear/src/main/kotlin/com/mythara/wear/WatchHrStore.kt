package com.mythara.wear

import android.content.Context

/**
 * Tiny SharedPreferences cache for the most recent heart-rate sample
 * the watch's [HeartRateService] has captured, plus the wall-clock
 * timestamp of when it landed.
 *
 * Written by [HeartRateService.onDataReceived] (every fresh sample);
 * read by [com.mythara.wear.complications.HeartRateComplicationService]
 * (per-frame for the watch face) and by the wear `MainActivity`
 * `PttScreen` composable (for the in-app HR readout).
 *
 * Mirrors the [InsightStore] / [PhoneBatteryStore] pattern — process-
 * wide singleton object, no DataStore overhead, persists across
 * service restarts so the complication still has a value to show
 * during the brief window between HR-service kill and respawn.
 */
object WatchHrStore {
    private const val PREFS = "mythara_watch_hr"
    private const val KEY_BPM = "bpm"
    private const val KEY_TS = "ts"

    /** Most recent BPM reading. Returns null when nothing has been
     *  captured yet (fresh install / first boot). */
    fun latestBpm(ctx: Context): Int? {
        val v = ctx.prefs().getInt(KEY_BPM, -1)
        return if (v <= 0) null else v
    }

    /** Wall-clock millis when the most recent reading landed. 0 when
     *  no reading has been captured yet. */
    fun latestTs(ctx: Context): Long = ctx.prefs().getLong(KEY_TS, 0L)

    /** True when the most recent reading is younger than [maxStaleMs]
     *  (default 3 min, same as the phone's LiveWallpaperPulseSink so
     *  the visual interpretation stays consistent across surfaces). */
    fun isFresh(ctx: Context, maxStaleMs: Long = 3L * 60 * 1000): Boolean {
        val ts = latestTs(ctx)
        if (ts == 0L) return false
        return System.currentTimeMillis() - ts <= maxStaleMs
    }

    fun save(ctx: Context, bpm: Int) {
        ctx.prefs().edit()
            .putInt(KEY_BPM, bpm)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
