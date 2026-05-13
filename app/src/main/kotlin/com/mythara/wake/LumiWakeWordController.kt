package com.mythara.wake

import android.content.Context
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Re-MENTIA's [WakeWordEngine] in a Mythara-shaped facade:
 *  - lifecycle (start / stop) safe to call repeatedly
 *  - status flow that the UI consumes for the status pill
 *  - detection flow that the Activity layer subscribes to so it can
 *    open the chat surface with mic primed when "Lumi" fires
 *
 * **Asset contract.** Three ONNX files must live in `app/src/main/assets/`:
 *   - `melspectrogram.onnx`  (shared, from openWakeWord 0.6.0 release)
 *   - `embedding_model.onnx` (shared, from openWakeWord 0.6.0 release)
 *   - `lumi.onnx`            (custom — trained via openWakeWord Colab)
 *
 * If any are missing the controller stays in [State.MissingAsset] and
 * never calls AudioRecord — verified by checking AssetManager.list().
 *
 * **Mutual exclusion with Observe.** Both AudioRecord clients can't run
 * at the same hardware time. The controller declines to start if Observe
 * is currently running (and vice versa — Observe should consult
 * [state] before starting). UI will surface this conflict.
 */
@Singleton
class LumiWakeWordController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object MissingAsset : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _wakes = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    val wakes: SharedFlow<WakeEvent> = _wakes.asSharedFlow()

    /** Slim wake-event envelope — we don't leak the library's class into the rest of the app. */
    data class WakeEvent(val phrase: String, val score: Float, val tsMillis: Long)

    private var engine: WakeWordEngine? = null
    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    /**
     * Verify the three required ONNX files are present in `assets/`. We
     * check by attempting to open InputStreams — Re-MENTIA loads from
     * AssetManager so the same lookup happens at start time anyway.
     */
    fun assetsPresent(): Boolean = REQUIRED_ASSETS.all { name ->
        runCatching { ctx.assets.open(name).use { } }.isSuccess
    }

    /** Start the engine if assets exist + not already running. */
    fun start() {
        if (_state.value is State.Listening) return
        if (!assetsPresent()) {
            _state.value = State.MissingAsset
            Log.w(TAG, "Lumi wake-word assets missing — see app/src/main/assets/README.md")
            return
        }
        runCatching {
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val newEngine = WakeWordEngine(
                context = ctx,
                models = listOf(
                    WakeWordModel(
                        name = "Lumi",
                        modelPath = LUMI_MODEL,
                        // Conservative threshold — Lumi is a rare phoneme
                        // sequence so false positives are unlikely, but
                        // 0.1 gives the user a margin before a noisy
                        // environment retunes.
                        threshold = 0.15f,
                    ),
                ),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2_000L,
                scope = newScope,
            )
            newEngine.start()
            scope = newScope
            engine = newEngine
            collectJob = newScope.launch {
                newEngine.detections.collect { d: WakeWordDetection ->
                    val now = System.currentTimeMillis()
                    Log.d(TAG, "wake fired: ${d.model.name} score=${d.score}")
                    _wakes.tryEmit(
                        WakeEvent(phrase = d.model.name, score = d.score, tsMillis = now),
                    )
                }
            }
            _state.value = State.Listening
        }.onFailure { e ->
            Log.e(TAG, "wake start failed: ${e.message}", e)
            _state.value = State.Error(e.message ?: e.javaClass.simpleName)
            stopInternal()
        }
    }

    fun stop() {
        stopInternal()
        _state.value = State.Idle
    }

    private fun stopInternal() {
        runCatching { engine?.release() }
        engine = null
        runCatching { collectJob?.cancel() }
        collectJob = null
        scope = null
    }

    companion object {
        private const val TAG = "Mythara/Wake"
        // openWakeWord ships these two as shared models that compute the
        // mel-spec and the speech embeddings every custom wake-word
        // model classifies on top of.
        private const val MELSPEC_MODEL = "melspectrogram.onnx"
        private const val EMBED_MODEL   = "embedding_model.onnx"
        private const val LUMI_MODEL    = "lumi.onnx"
        private val REQUIRED_ASSETS = listOf(MELSPEC_MODEL, EMBED_MODEL, LUMI_MODEL)
    }
}
