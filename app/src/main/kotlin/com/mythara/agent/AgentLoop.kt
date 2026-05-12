package com.mythara.agent

import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.data.SettingsStore
import com.mythara.minimax.ErrorMapper
import com.mythara.minimax.MiniMaxClient
import com.mythara.minimax.StreamingChat
import com.mythara.minimax.models.ChatMessage
import com.mythara.minimax.models.ChatRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-turn agent loop. M2 scope: send the user message + prior history
 * to MiniMax, stream the reply, persist the assistant message. Tool-use
 * loop (function calls + tool execution + re-entry) lands in M5+ when the
 * first real tools come online.
 */
@Singleton
class AgentLoop @Inject constructor(
    private val settings: SettingsStore,
    private val history: HistoryRepository,
) {

    sealed interface Turn {
        data class Delta(val text: String) : Turn
        data class Finished(val finalText: String) : Turn
        data class Error(val message: String, val retryable: Boolean) : Turn
        data object MissingApiKey : Turn
    }

    /**
     * Submit a user message and stream the assistant's reply.
     * The flow emits [Turn.Delta]s as text arrives, then exactly one
     * terminal [Turn.Finished] / [Turn.Error] / [Turn.MissingApiKey].
     */
    fun submit(userText: String): Flow<Turn> = flow {
        val snap = settings.snapshot()
        val apiKey = snap.apiKey
        if (apiKey.isNullOrBlank()) {
            emit(Turn.MissingApiKey)
            return@flow
        }

        val now = System.currentTimeMillis()
        history.dao.insert(MessageRow(tsMillis = now, role = "user", content = userText))

        val prior = history.dao.listAll().map { row ->
            ChatMessage(
                role = row.role,
                content = row.content,
                toolCallId = row.toolCallId,
                name = row.name,
            )
        }

        val client = MiniMaxClient(apiKey = apiKey, region = snap.region)
        val streaming = StreamingChat(client)
        val req = ChatRequest(
            model = snap.model,
            messages = prior,
            stream = true,
        )

        val collected = StringBuilder()
        var finalReason: String? = null
        var failure: ErrorMapper.Mapped? = null

        streaming.stream(snap.region, req).collect { ev ->
            when (ev) {
                is StreamingChat.StreamEvent.Text -> {
                    collected.append(ev.delta)
                    emit(Turn.Delta(ev.delta))
                }
                is StreamingChat.StreamEvent.ToolCallsReady -> {
                    // M5+: dispatch through ConfirmationGate → ToolRegistry.
                    // For M2 we just acknowledge them in-text so the user sees
                    // the agent intended to call tools, even though we don't
                    // execute yet.
                    val names = ev.calls.joinToString { it.function.name }
                    collected.append("\n\n[tool_calls: $names]")
                }
                is StreamingChat.StreamEvent.Done -> finalReason = ev.finishReason
                is StreamingChat.StreamEvent.Failure -> failure = ev.mapped
            }
        }

        if (failure != null) {
            val f = failure!!
            emit(Turn.Error(f.message, retryable = f.isRetryable))
            return@flow
        }

        val finalText = collected.toString()
        history.dao.insert(
            MessageRow(
                tsMillis = System.currentTimeMillis(),
                role = "assistant",
                content = finalText,
            )
        )
        emit(Turn.Finished(finalText))
    }

    /** Empty turn used for "fresh boot" composition before the user types. */
    val empty: Flow<Turn> = flowOf()
}
