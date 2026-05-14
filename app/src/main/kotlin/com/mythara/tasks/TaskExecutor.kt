package com.mythara.tasks

import android.util.Log
import com.mythara.agent.AgentRunner
import com.mythara.memory.DeviceIdStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heartbeat task pickup + execution.
 *
 * On every [HeartbeatSyncer] tick (~5 min when the process is alive,
 * 15 min from the WorkManager fallback otherwise):
 *
 *   1. List tasks claimable by THIS device — status=PENDING, target
 *      either null ("any device") or this device's id, and the
 *      schedule has elapsed.
 *   2. Try to atomically claim the task ([TaskDao.tryClaim] —
 *      conditional UPDATE that succeeds only if status was still
 *      PENDING). Losing the race to another device on the same null-
 *      target task is fine and expected; we just move on.
 *   3. Execute. Phase 1 implementation is the simplest possible
 *      interpretation: submit the task body as a turn to the agent
 *      via [AgentRunner.submit], same path a typed message would
 *      take. The agent's tool call surface picks up whatever the task
 *      describes ("send mom an SMS reminder about dinner", "check the
 *      battery on dev:xxxx", etc.). Phase 2 can add dedicated
 *      "task kinds" with structured handlers.
 *   4. Mark DONE / FAILED with the agent's final text as the result.
 *
 * Status updates are persisted locally and ship out on the very next
 * sync (the same heartbeat tick fires sync RIGHT BEFORE this), so
 * the requesting device sees the result within one round-trip.
 *
 * Handoff safety: this executor NEVER routes tasks across devices on
 * its own. The user (or the agent acting on the user's request) sets
 * targetDeviceId explicitly via the create_task / handoff_task tool;
 * we just pick up what was explicitly addressed to us OR what's
 * marked "any device". The 5-min cadence + cooperative claiming means
 * the same task isn't run twice unless WorkManager and the in-process
 * timer both fire in the same window — and even then the atomic
 * UPDATE makes the second claim a no-op.
 */
@Singleton
class TaskExecutor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: TaskRepository,
    private val runner: AgentRunner,
    private val deviceIdStore: DeviceIdStore,
) {

    suspend fun tick(maxTasks: Int = 3): Int {
        val myId = runCatching { deviceIdStore.id() }.getOrElse { return 0 }
        val now = System.currentTimeMillis()
        val claimable = runCatching {
            repo.dao.listClaimable(deviceId = myId, nowMs = now, limit = maxTasks)
        }.getOrDefault(emptyList())
        if (claimable.isEmpty()) {
            Log.v(TAG, "tick: no claimable tasks for $myId")
            return 0
        }
        var done = 0
        for (task in claimable) {
            val claimed = runCatching { repo.dao.tryClaim(task.id, myId, now) }.getOrDefault(0)
            if (claimed == 0) {
                // Lost the race to another device — fine, move on.
                continue
            }
            Log.d(TAG, "tick: claimed task ${task.id} '${task.title.take(40)}'")
            runOne(task.copy(claimedByDeviceId = myId, claimedMs = now))
            done++
        }
        return done
    }

    private suspend fun runOne(task: TaskEntity) {
        // Mark RUNNING so other devices viewing the task see the
        // state transition on their next sync (and don't think it's
        // stalled).
        runCatching { repo.dao.markRunning(task.id) }

        // Phase 1: submit the task body to the agent as a normal turn.
        // We DON'T await completion here — the agent's turn lifecycle
        // is process-wide via AgentRunner. Instead we mark the task
        // as DONE optimistically with a "submitted to agent" note;
        // the agent's own audit log + chat history surface what
        // actually happened.
        //
        // Phase 2 can add a TaskExecutor-aware Turn collector that
        // waits for Finished + records the agent's final text into
        // result_text.
        runCatching {
            val prompt = buildPrompt(task)
            runner.submit(text = prompt, fromVoice = false)
            repo.dao.markTerminal(
                task.id,
                TaskStatus.DONE.name,
                "submitted to agent",
                System.currentTimeMillis(),
            )
        }.onFailure { e ->
            Log.w(TAG, "task ${task.id} threw: ${e.message}")
            repo.dao.markTerminal(
                task.id,
                TaskStatus.FAILED.name,
                e.message ?: e.javaClass.simpleName,
                System.currentTimeMillis(),
            )
        }
    }

    private fun buildPrompt(task: TaskEntity): String = buildString {
        append("[handoff-task] ")
        append("requester=").append(task.requesterDeviceId.takeLast(8)).append(' ')
        append("target=").append(task.targetDeviceId?.takeLast(8) ?: "any").append(' ')
        append("id=").append(task.id.take(8)).append('\n')
        append("title: ").append(task.title).append('\n')
        if (task.body.isNotBlank()) append(task.body)
    }

    companion object {
        private const val TAG = "Mythara/TaskExec"
    }
}
