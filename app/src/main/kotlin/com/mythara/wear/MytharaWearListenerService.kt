package com.mythara.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.mythara.agent.AgentRunner
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phone-side companion to the Wear OS push-to-talk app.
 *
 * The watch's SpeechRecognizer runs locally and ships the final
 * transcript to the phone over the Wearable Data Layer under
 * [PTT_SUBMIT_PATH]. This service receives the message and routes
 * the text into [AgentRunner.submit] with `fromVoice=true` so the
 * agent loop produces a voice-friendly short reply (and the TTS
 * path can route back to the watch's BT audio if it's the active
 * sink).
 *
 * Auto-submit (not dictation-to-composer): the user is wearing
 * their watch BECAUSE they don't have the phone in hand. Making
 * them grab the phone to confirm a transcript defeats the point
 * of the watch companion entirely.
 */
@AndroidEntryPoint
class MytharaWearListenerService : WearableListenerService() {

    @Inject lateinit var runner: AgentRunner
    @Inject lateinit var vault: LearningVault

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)
        when (event.path) {
            PTT_SUBMIT_PATH -> {
                val text = runCatching { String(event.data, Charsets.UTF_8).trim() }
                    .getOrElse {
                        Log.w(TAG, "decode failed: ${it.message}")
                        return
                    }
                if (text.isBlank()) {
                    Log.d(TAG, "PTT message arrived empty; skipping")
                    return
                }
                Log.d(TAG, "PTT from watch: \"${text.take(80)}\"")
                scope.launch {
                    runCatching { runner.submit(text = text, fromVoice = true) }
                        .onFailure { Log.w(TAG, "agent submit failed: ${it.message}") }
                }
            }
            HEART_RATE_PATH -> {
                val bpm = runCatching {
                    String(event.data, Charsets.UTF_8).trim().toInt()
                }.getOrNull()
                if (bpm == null || bpm !in 30..240) {
                    Log.d(TAG, "HR payload invalid; skipping")
                    return
                }
                Log.d(TAG, "HR from watch: $bpm bpm")
                scope.launch {
                    runCatching {
                        vault.add(
                            content = "Heart rate $bpm bpm (captured on watch).",
                            tier = Tier.Working,
                            src = "watch:heart-rate",
                            facets = listOf("kind:heart-rate", "topic:health", "source:watch"),
                            conf = 0.95,
                        )
                    }.onFailure { Log.w(TAG, "HR vault write failed: ${it.message}") }
                }
            }
            else -> Log.d(TAG, "ignored message on ${event.path}")
        }
    }

    companion object {
        private const val TAG = "Mythara/WearListener"
        const val PTT_SUBMIT_PATH = "/mythara/ptt/submit"

        /** Watch → phone: a single heart-rate reading (bpm). */
        const val HEART_RATE_PATH = "/mythara/heart_rate"
    }
}
