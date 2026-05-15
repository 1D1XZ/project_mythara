package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **cancel_scheduled_task** — cancel a PENDING task by id (full or
 * 8-char prefix) OR by exact title match. Marks the row CANCELED so
 * TaskExecutor never picks it up; the row stays in the DB so the
 * cross-device sync can propagate the cancellation.
 *
 * Disambiguation:
 *   - id is the most reliable handle — list_scheduled_tasks returns
 *     each task's full id. Substring matches (8-char prefix) work too
 *     so the user can paste the short id from logs.
 *   - title matches are exact (case-insensitive). If multiple rows
 *     share a title, only the soonest-scheduled is cancelled and
 *     the response reports how many candidates were found so the
 *     agent can ask the user to pick by id.
 */
@Singleton
class CancelScheduledTaskTool @Inject constructor(
    private val taskRepo: TaskRepository,
) : Tool {

    override val name: String = "cancel_scheduled_task"
    override val description: String =
        "Cancel a PENDING scheduled task by id (full or first 8 chars) OR by exact title. " +
            "If you know the id, pass it; otherwise pass the title. If multiple PENDING tasks share " +
            "the title, only the soonest-fire one is cancelled — call list_scheduled_tasks first to " +
            "pick by id when there's ambiguity."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "id",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Full task id, or the first 8 characters as shown by list_scheduled_tasks. Optional if title is provided.")
                    },
                )
                put(
                    "title",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Exact title (case-insensitive). Optional if id is provided.")
                    },
                )
            },
        )
        put("required", buildJsonArray {})
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val idArg = (args["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val titleArg = (args["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (idArg.isBlank() && titleArg.isBlank()) {
            return ToolResult(false, """{"error":"missing_args","detail":"pass id or title"}""")
        }
        val all = runCatching { taskRepo.dao.listRecent(limit = 200) }.getOrDefault(emptyList())
        val pending = all.filter { it.status == TaskStatus.PENDING.name }

        val byId = pending.firstOrNull {
            idArg.isNotBlank() && (it.id == idArg || it.id.startsWith(idArg))
        }
        val byTitle = if (byId == null && titleArg.isNotBlank()) {
            pending.filter { it.title.equals(titleArg, ignoreCase = true) }
                .minByOrNull { it.scheduledForMs ?: Long.MAX_VALUE }
        } else null
        val target = byId ?: byTitle ?: return ToolResult(
            false,
            """{"error":"not_found","detail":"no PENDING task matches id='$idArg' title='$titleArg'"}""",
        )
        runCatching {
            taskRepo.dao.markTerminal(
                id = target.id,
                newStatus = TaskStatus.CANCELED.name,
                result = "cancelled by user via cancel_scheduled_task",
                nowMs = System.currentTimeMillis(),
            )
        }.onFailure {
            Log.w(TAG, "cancel failed: ${it.message}")
            return ToolResult(false, """{"error":"cancel_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""")
        }

        val ambiguous = if (titleArg.isNotBlank()) {
            pending.count { it.title.equals(titleArg, ignoreCase = true) }
        } else 1
        val payload = buildJsonObject {
            put("ok", true)
            put("cancelled_id", target.id)
            put("cancelled_title", target.title)
            put("matched_by", if (byId != null) "id" else "title")
            if (ambiguous > 1) {
                put("ambiguous", true)
                put(
                    "detail",
                    "Cancelled the soonest-fire match for '$titleArg'; " +
                        "$ambiguous PENDING tasks share that title. Call list_scheduled_tasks to see " +
                        "the rest and cancel by id if needed.",
                )
            } else {
                put("detail", "Cancelled '${target.title}'.")
            }
        }
        return ToolResult.ok(payload.toString())
    }

    companion object {
        private const val TAG = "Mythara/CancelTask"
    }
}
