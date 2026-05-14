package com.mythara.wear

import android.util.Log
import com.mythara.data.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the latest Mythara agent chat message to the watch face.
 *
 * Observes the conversation history and, whenever the most recent
 * assistant message changes, pushes it to the watch via
 * [WatchInsightPusher]. The Mythara Tactical watch face renders it on
 * its live agent-message line, so the wrist stays in sync with what
 * the agent last said in the app — the watch face is an extended arm
 * of the Mythara app, not a separate notification surface.
 */
@Singleton
class WatchAgentMessageRelay @Inject constructor(
    private val history: HistoryRepository,
    private val pusher: WatchInsightPusher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            history.dao.observeAll()
                .map { rows ->
                    rows.asReversed()
                        .firstOrNull { it.role == "assistant" && !cleanMessage(it.content).isBlank() }
                        ?.let { cleanMessage(it.content) }
                        .orEmpty()
                }
                .distinctUntilChanged()
                .collect { latest ->
                    if (latest.isNotBlank()) {
                        runCatching { pusher.push(latest) }
                            .onFailure { Log.w(TAG, "push failed: ${it.message}") }
                    }
                }
        }
    }

    /**
     * Strip reasoning blocks so the watch shows the spoken reply, not
     * the model's scratchpad — matches what the app's chat UI renders.
     */
    private fun cleanMessage(raw: String?): String =
        raw.orEmpty()
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<think>.*$"), "")
            .trim()

    companion object {
        private const val TAG = "Mythara/AgentMsgRelay"
    }
}
