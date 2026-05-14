package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.HeartbeatSyncer
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `create_task` — drop a task into the cross-device queue. Each
 * Mythara install signed into the same memory repo picks up its
 * share on the 5-minute heartbeat sync; whichever device claims a
 * given task first runs it.
 *
 * Two targeting modes:
 *  - `target_device_id = null` (default) — "any device may pick this
 *    up". Useful for "remind me at 6pm" / "draft a reply" tasks
 *    where it doesn't matter which device runs them.
 *  - `target_device_id = "<id>"` — explicit handoff. Only that
 *    device may claim. The user MUST have explicitly named the
 *    target — never invent a device id without being asked.
 */
@Singleton
class CreateTaskTool @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val taskRepo: TaskRepository,
    /** dagger.Lazy — see SendNoteToDeviceTool for the cycle-break rationale. */
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "create_task"
    override val description: String =
        "Create a task in the cross-device task queue. Any Mythara install signed into the same memory-sync repo can pick it up on the next 5-minute heartbeat (cooperative claiming). " +
            "Set target_device_id to a specific id to HAND OFF the task explicitly — only do this when the user has named the target device (e.g. 'handle this on my tablet'). " +
            "Leave target_device_id blank/null for ordinary 'do this at some point' tasks that any device can grab. " +
            "Use list_mythara_devices to discover device ids."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "title",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "One-line summary of what should be done. Max ~80 chars.")
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional detail / instructions. Free text. Becomes the agent prompt the picker submits.",
                        )
                    },
                )
                put(
                    "target_device_id",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional stable device id of the install that MUST run this task. Omit for any-device. " +
                                "ONLY set this when the user has explicitly named a target.",
                        )
                    },
                )
                put(
                    "scheduled_for_ms",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Optional Unix epoch millis when the task should become eligible to run. " +
                                "Omit for run-asap.",
                        )
                    },
                )
            },
        )
        put(
            "required",
            buildJsonArray { add(JsonPrimitive("title")) },
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = (args["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult(false, """{"error":"missing_title"}""")
        val body = (args["body"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val target = (args["target_device_id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val schedFor = (args["scheduled_for_ms"] as? JsonPrimitive)?.content?.toLongOrNull()

        val myId = deviceIdStore.id()
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val row = TaskEntity(
            id = id,
            title = title,
            body = body,
            requesterDeviceId = myId,
            targetDeviceId = target.ifEmpty { null },
            status = TaskStatus.PENDING.name,
            createdMs = now,
            scheduledForMs = schedFor,
        )
        runCatching { taskRepo.dao.insertIfAbsent(row) }
            .onFailure {
                Log.w(TAG, "task insert failed: ${it.message}")
                return ToolResult(false, """{"error":"insert_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""")
            }
        // Kick a heartbeat so the new task ships to peers within seconds
        // and any local claim happens on this same tick.
        runCatching { heartbeat.get().fireNow() }
        Log.d(TAG, "task $id created (target=${target.ifEmpty { "any" }})")
        return ToolResult(
            true,
            """{"ok":true,"task_id":${JsonPrimitive(id)},"target":${JsonPrimitive(target.ifEmpty { "any" })},"detail":"Task queued. The first eligible device picks it up on its next heartbeat sync (~5 min)."}""",
        )
    }

    companion object {
        private const val TAG = "Mythara/CreateTask"
    }
}
