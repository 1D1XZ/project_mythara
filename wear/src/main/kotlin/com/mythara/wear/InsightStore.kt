package com.mythara.wear

import android.content.Context

/**
 * Tiny SharedPreferences cache for the latest insight line the phone
 * has pushed to the watch. Written by [MytharaWearDataReceiver], read
 * by [com.mythara.wear.complications.InsightComplicationService].
 */
object InsightStore {
    private const val PREFS = "mythara_insight"
    private const val KEY_TEXT = "latest"

    fun latest(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEXT, "").orEmpty()

    fun save(ctx: Context, text: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TEXT, text).apply()
    }
}
