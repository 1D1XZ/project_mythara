package com.mythara.mic

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide coordinator for the single Android `AudioRecord`
 * resource. The app has multiple mic clients (Observe, Lumi-listen
 * wake-word, continuous voice chat) and Android only allows one to
 * hold the mic at a time. Before this broker existed, conflicts
 * surfaced as cryptic init failures after the user toggled — the
 * broker catches them upstream so the UI can disable conflicting
 * affordances and explain *which* mode is currently using the mic.
 *
 * V1 is cooperative — no preemption. The currently-active client
 * keeps the mic until it releases voluntarily; later acquires fail
 * fast. A future v2 could add priority preemption (e.g.
 * push-to-talk briefly bumps Observe) if real usage demands it.
 *
 * Push-to-talk in [MicButton] is intentionally NOT brokered: it's a
 * user-initiated one-shot scoped to a single utterance, and the
 * SpeechRecognizer's session is short enough (< 30s) that adding
 * broker friction would make the UX worse for the most common voice
 * flow.
 */
@Singleton
class MicBroker @Inject constructor() {

    /** Identifies the kind of long-lived mic owner. */
    enum class Client {
        /** ObserveForegroundService — passive learning loop. */
        OBSERVE,
        /** LumiListenerService — always-on "Hey Lumi" wake-word. */
        LUMI_LISTEN,
        /** ChatScreen continuous voice mode (Pixel Soda). */
        CONTINUOUS_CHAT,
    }

    private val _owner = MutableStateFlow<Client?>(null)
    /** Currently-acquired client, or null if the mic is free. */
    val owner: StateFlow<Client?> = _owner.asStateFlow()

    /**
     * Try to acquire the mic for [client]. Returns true if acquired
     * (or already held by this client — idempotent). Returns false
     * if another client holds it.
     */
    @Synchronized
    fun acquire(client: Client): Boolean {
        val current = _owner.value
        return if (current == null || current == client) {
            if (current == null) Log.d(TAG, "acquire by $client")
            _owner.value = client
            true
        } else {
            Log.d(TAG, "acquire by $client refused — owned by $current")
            false
        }
    }

    /**
     * Release the mic, but only if [client] is the current owner —
     * a stale release call from a previously-stopped client doesn't
     * accidentally yank the mic out from under a fresh acquirer.
     */
    @Synchronized
    fun release(client: Client) {
        if (_owner.value == client) {
            Log.d(TAG, "release by $client")
            _owner.value = null
        } else {
            Log.d(TAG, "release by $client ignored — current owner is ${_owner.value}")
        }
    }

    /** Human-readable label for use in UI error messages. */
    fun describe(client: Client): String = when (client) {
        Client.OBSERVE -> "Observe mode"
        Client.LUMI_LISTEN -> "Lumi wake-word listener"
        Client.CONTINUOUS_CHAT -> "continuous voice chat"
    }

    companion object {
        private const val TAG = "Mythara/MicBroker"
    }
}
