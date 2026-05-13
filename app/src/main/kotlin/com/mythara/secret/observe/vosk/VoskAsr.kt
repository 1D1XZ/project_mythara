package com.mythara.secret.observe.vosk

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin lifecycle wrapper around the Vosk [Model] + [Recognizer].
 *
 * The [Model] (~150MB resident) is expensive to load and immutable once
 * loaded — keep one app-wide. [Recognizer] is per-session/per-stream
 * and cheap; create one when an [ObserveSession] starts, close it on
 * stop.
 *
 * On API ≥26 we use the recommended `Recognizer(model, sampleRate)`
 * constructor.
 */
@Singleton
class VoskAsr @Inject constructor(private val store: VoskModelStore) {

    @Volatile private var model: Model? = null
    @Volatile private var loadedPath: String? = null

    /**
     * Loads the model for the **currently-active language**. If the active
     * language has changed since the last load (user picked a different
     * one in Secret Settings → languages), this swaps the resident model
     * — closes the old one (~150MB RAM) and loads the new one.
     */
    @Synchronized
    private fun ensureModel(): Model {
        val activePath = store.activePathOrNull()
            ?: error("Vosk model for the active language is not on disk — call VoskModelStore.ensureReady() first")
        val current = model
        if (current != null && loadedPath == activePath) return current

        // Active language changed (or first call) — swap.
        runCatching { current?.close() }
        Log.d(TAG, "loading Vosk model from $activePath")
        val m = Model(activePath)
        model = m
        loadedPath = activePath
        return m
    }

    fun isReady(): Boolean = store.isActiveReady()

    /** Fresh recognizer for one session. Caller must close() when done. */
    fun newRecognizer(sampleRate: Float = 16_000f): Recognizer {
        val m = ensureModel()
        return Recognizer(m, sampleRate)
    }

    /** Parse the JSON Vosk returns to its final-result + partial-result calls. */
    fun parseText(json: String): String =
        runCatching { JSONObject(json).optString("text", "") }.getOrDefault("").trim()

    fun release() {
        model?.close()
        model = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "Mythara/Vosk"
    }
}
