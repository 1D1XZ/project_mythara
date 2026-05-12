package com.mythara.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.AgentLoop
import com.mythara.data.HistoryRepository
import com.mythara.data.MessageRow
import com.mythara.mic.Tts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the chat surface. Owns:
 *  - the persisted message list materialised as composite [ChatItem]s
 *    (user text, assistant text, tool invocations as paired calls+results)
 *  - a transient buffer of in-flight tool calls so the Crush-style
 *    ● running indicator can render before the result lands
 *  - the streaming assistant text being typed into the latest bubble
 *  - thinking / error / missing-key flags
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agent: AgentLoop,
    private val history: HistoryRepository,
    private val tts: Tts,
) : ViewModel() {
    init { tts.init() }

    /**
     * One renderable row in the timeline. The view composes a list of
     * these instead of raw MessageRow entries because tool calls + their
     * results are paired visually — a single composite block, Crush-style.
     */
    sealed interface ChatItem {
        val key: String
        data class UserText(override val key: String, val text: String) : ChatItem
        data class AssistantText(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
        data class Tool(
            override val key: String,
            val name: String,
            val args: String,
            val state: ToolState,
            val output: String? = null,
            val durationMs: Long? = null,
        ) : ChatItem
    }

    enum class ToolState { Running, Success, Failure }

    data class UiState(
        val items: List<ChatItem> = emptyList(),
        val streaming: String? = null,
        val thinking: Boolean = false,
        val needsApiKey: Boolean = false,
        val errorBanner: String? = null,
        /** Names of the tools currently registered — surfaced for debug + Settings later. */
        val registeredTools: List<String> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val inflightTools = mutableMapOf<String, ChatItem.Tool>()

    init {
        // Observe persisted history; recompose ChatItems each time the table changes.
        viewModelScope.launch {
            history.dao.observeAll().collect { rows -> rebuildItems(rows) }
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(thinking = true, streaming = "", needsApiKey = false, errorBanner = null) }
        viewModelScope.launch {
            agent.submit(text).collect { turn ->
                when (turn) {
                    is AgentLoop.Turn.Delta -> _ui.update {
                        it.copy(streaming = (it.streaming ?: "") + turn.text)
                    }
                    is AgentLoop.Turn.ToolStart -> {
                        // Render the bubble in ● running state. The persisted
                        // tool MessageRow doesn't exist yet — registry hasn't
                        // executed — so we shadow-track via inflightTools.
                        val item = ChatItem.Tool(
                            key = "tool:${turn.callId}",
                            name = turn.name,
                            args = turn.args,
                            state = ToolState.Running,
                        )
                        inflightTools[turn.callId] = item
                        // Force a recompose so the running bubble appears.
                        viewModelScope.launch { rebuildItems(history.dao.listAll()) }
                    }
                    is AgentLoop.Turn.ToolEnd -> {
                        inflightTools.remove(turn.callId)
                        // The persisted `role:tool` row will arrive via the
                        // history flow and replace the inflight stub. Flush
                        // streaming buffer here too — the assistant might
                        // have emitted text *before* calling the tool.
                        _ui.update { it.copy(streaming = "") }
                    }
                    is AgentLoop.Turn.Finished -> {
                        _ui.update { it.copy(streaming = null, thinking = false) }
                        tts.speak(turn.finalText.removeSuffix(" [hit max iterations]"))
                    }
                    is AgentLoop.Turn.Error -> _ui.update {
                        it.copy(streaming = null, thinking = false, errorBanner = turn.message)
                    }
                    is AgentLoop.Turn.MissingApiKey -> _ui.update {
                        it.copy(streaming = null, thinking = false, needsApiKey = true)
                    }
                }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(errorBanner = null) }
    fun dismissMissingKey() = _ui.update { it.copy(needsApiKey = false) }

    private fun rebuildItems(rows: List<MessageRow>) {
        val items = mutableListOf<ChatItem>()
        for (row in rows) {
            when (row.role) {
                "user" -> items.add(ChatItem.UserText(key = "u:${row.id}", text = row.content.orEmpty()))
                "assistant" -> {
                    if (!row.content.isNullOrEmpty()) {
                        items.add(ChatItem.AssistantText(key = "a:${row.id}", text = row.content))
                    }
                    // Assistant turns with tool_calls produce paired Tool rows
                    // when their results arrive (role=tool below). We don't
                    // render anything extra for the assistant row itself
                    // beyond text — the tool bubble is the visual peer.
                }
                "tool" -> {
                    val callId = row.toolCallId.orEmpty()
                    val toolName = row.name.orEmpty()
                    // Determine ok by best-effort — registry packs JSON; assume
                    // ok unless content is empty or starts with "fetch failed"/etc.
                    val isFailure = row.content.isNullOrBlank() ||
                        row.content.startsWith("fetch failed") ||
                        row.content.startsWith("unknown tool") ||
                        row.content.startsWith("http ")
                    items.add(
                        ChatItem.Tool(
                            key = "tool:$callId",
                            name = toolName,
                            args = "",
                            state = if (isFailure) ToolState.Failure else ToolState.Success,
                            output = row.content,
                        ),
                    )
                }
            }
        }
        // Append still-inflight tools at the bottom (they haven't persisted yet).
        items.addAll(inflightTools.values)
        _ui.update { it.copy(items = items) }
    }
}
