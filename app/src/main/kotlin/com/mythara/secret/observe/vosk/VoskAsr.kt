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

    /** Loads the model from disk if not already loaded. Throws if not extracted. */
    @Synchronized
    private fun ensureModel(): Model {
        model?.let { return it }
        val path = store.pathOrNull() ?: error("Vosk model not on disk — call VoskModelStore.ensureReady() first")
        Log.d(TAG, "loading Vosk model from $path")
        val m = Model(path)
        model = m
        return m
    }

    fun isReady(): Boolean = store.isExtracted()

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
    }

    companion object {
        private const val TAG = "Mythara/Vosk"
    }
}
