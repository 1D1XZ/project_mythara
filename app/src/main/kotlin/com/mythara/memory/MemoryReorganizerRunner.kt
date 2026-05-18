package com.mythara.memory

import android.util.Log
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a one-shot bulk "reorganize memory" run with live progress
 * for the Settings panel — mirrors [com.mythara.lifeline.RecaptionAllRunner]'s
 * pattern so the UI surface is identical.
 *
 * On completion, fires an immediate [HeartbeatSyncer.fireNow] so the
 * retagged rows ship to every paired Mythara device within seconds
 * instead of waiting on the next 5-minute heartbeat. That's what
 * makes "ask the same question on the other device" produce the same
 * memory hits.
 */
@Singleton
class MemoryReorganizerRunner @Inject constructor(
    private val reorganizer: MemoryReorganizer,
    private val heartbeat: Lazy<HeartbeatSyncer>,
) {

    sealed interface State {
        data object Idle : State

        data class Running(
            val attempted: Int,
            val total: Int,
            val retagged: Int,
            val startedMs: Long,
        ) : State

        data class Done(
            val report: MemoryReorganizer.Report,
        ) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun start() {
        if (isRunning()) {
            Log.d(TAG, "reorganize already running; ignoring duplicate start")
            return
        }
        val started = System.currentTimeMillis()
        _state.value = State.Running(0, 0, 0, started)
        job = scope.launch {
            runCatching {
                val report = reorganizer.reorganize { current, total, retagged ->
                    _state.update { State.Running(current, total, retagged, started) }
                }
                _state.value = State.Done(report)
                // Push retagged rows to the cluster immediately — the
                // whole point of reorganize is the other devices
                // pick up the same memory shape on the next ask.
                runCatching { heartbeat.get().fireNow() }
                    .onFailure { Log.w(TAG, "post-reorganize sync kick failed: ${it.message}") }
            }.onFailure { e ->
                Log.w(TAG, "reorganize failed: ${e.message}", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { current ->
            if (current is State.Running) {
                State.Failed("Cancelled by user at ${current.attempted}/${current.total}")
            } else current
        }
    }

    fun acknowledge() {
        if (!isRunning()) _state.value = State.Idle
    }

    companion object {
        private const val TAG = "Mythara/MemoryReorgRun"
    }
}
