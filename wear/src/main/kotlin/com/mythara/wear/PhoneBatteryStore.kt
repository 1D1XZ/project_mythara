package com.mythara.wear

import android.content.Context

/**
 * Tiny SharedPreferences cache for the paired phone's battery level,
 * pushed from the phone over the Data Layer. Written by
 * [MytharaWearDataReceiver], read by
 * [com.mythara.wear.complications.PhoneBatteryComplicationService].
 */
object PhoneBatteryStore {
    private const val PREFS = "mythara_phone_battery"
    private const val KEY_PCT = "pct"

    /** Last known phone battery percent, or -1 if never received. */
    fun latest(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PCT, -1)

    fun save(ctx: Context, pct: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PCT, pct.coerceIn(0, 100)).apply()
    }
}
