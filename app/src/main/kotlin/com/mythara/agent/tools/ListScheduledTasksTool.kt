package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **list_scheduled_tasks** — show every PENDING task with a fire
 * schedule, so the user (and the agent) can see what's queued and
 * decide what to cancel / change.
 *
 * Includes tasks created by `schedule_task` AND `create_reminder`,
 * since both produce TaskEntity rows under the hood. The agent can
 * disambiguate by `kind`: a task with no recurrence and a body that
 * starts with a [reminder] marker is a user-facing reminder; a task
 * with a recurrence or a substantial prompt body is an agent
 * scheduled task. (We don't gate by source; the user might want to
 * cancel either kind.)
 */
@Singleton
class ListScheduledTasksTool @Inject constructor(
    private val taskRepo: TaskRepository,
) : Tool {

    override val name: String = "list_scheduled_tasks"
    override val description: String =
        "List every PENDING task with a future fire time — both user-set reminders and agent-scheduled " +
            "prompts. Returns each task's id, title, scheduled_for, and recurrence (if any). Use this " +
            "before cancel_scheduled_task to identify the right target by id."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val now = System.currentTimeMillis()
        val rows = runCatching { taskRepo.dao.listRecent(limit = 200) }.getOrDefault(emptyList())
        val pending = rows.filter { it.status == TaskStatus.PENDING.name && it.scheduledForMs != null }
            .sortedBy { it.scheduledForMs }
        val arr = buildJsonArray {
            for (r in pending) {
                add(
                    buildJsonObject {
                        put("id", r.id)
                        put("title", r.title)
                        put("scheduled_for_ms", r.scheduledForMs ?: 0L)
                        put(
                            "scheduled_for",
                            HUMAN_FMT.format(Date(r.scheduledForMs ?: now)),
                        )
                        put("recurrence", r.recurrence ?: "")
                        // First 80 chars of the body so the user can
                        // recognise what the task does without dumping
                        // the full prompt into the response.
                        put("preview", r.body.take(80))
                    },
                )
            }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("count", pending.size)
            put("tasks", arr)
        }
        return ToolResult.ok(payload.toString())
    }

    companion object {
        private val HUMAN_FMT = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())
    }
}
