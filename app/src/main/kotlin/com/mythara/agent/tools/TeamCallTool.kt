package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.audit.AuditRepository
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.HeartbeatSyncer
import com.mythara.memory.MemorySettings
import com.mythara.memory.MemorySync
import com.mythara.memory.github.GitHubClient
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
 * `team_call` — fans the same task out to EVERY known Mythara device
 * in the cluster. Each device claims its own copy on the next
 * heartbeat tick, runs it, and writes the result back via task sync.
 * The requesting device sees N independent results — one per peer —
 * in the tasks list.
 *
 * Use cases:
 *  - "ask all my devices what their battery level is right now"
 *  - "make every device confirm it's reachable"
 *  - "pull the most recent screenshot from each device"
 *
 * Each created task is bound to a SPECIFIC target device (the fan-out
 * is deliberate). Unlike `create_task` with a null target, here we
 * want EVERY device to run it, not just the first to claim.
 *
 * Discovery source: device registry — same list `list_mythara_devices`
 * reads (device_messages/devices/&lt;id&gt;.json). Self is included so the
 * requester also runs the task locally.
 */
@Singleton
class TeamCallTool @Inject constructor(
    private val deviceIdStore: DeviceIdStore,
    private val memorySettings: MemorySettings,
    private val auditRepo: AuditRepository,
    private val taskRepo: TaskRepository,
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "team_call"
    override val description: String =
        "Fan a task out to EVERY known Mythara device in the cluster. " +
            "Each device runs its own copy and writes the result back via task sync. " +
            "Use when the user asks something like 'ask all my devices to X', 'team call', " +
            "'check on every device', 'broadcast to my devices'. Unlike create_task with a null " +
            "target (first device to claim wins), team_call binds one task per device — every " +
            "device runs it independently. Self is included."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "title",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "One-line summary every device will see (e.g. 'report your battery level').",
                        )
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional detail — becomes the agent prompt on each receiving device.",
                        )
                    },
                )
                put(
                    "include_self",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Default true. Set false to fan out ONLY to peer devices, skipping this one.",
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
        val includeSelf = (args["include_self"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true

        val myId = deviceIdStore.id()
        val cfg = memorySettings.snapshot()
        if (!cfg.configured) {
            return ToolResult(false, """{"error":"sync_not_configured","detail":"Set memory sync PAT + repo first."}""")
        }

        val client = GitHubClient(cfg.pat!!)
        val deviceIds = mutableSetOf<String>()
        if (includeSelf) deviceIds.add(myId)

        // Discover peers from device_messages/devices/<id>.json — same
        // canonical source list_mythara_devices uses. Audit log
        // distinct is the local fallback if the network listing fails.
        when (val r = client.listDirectory(cfg.owner, cfg.repo, "device_messages/devices")) {
            is GitHubClient.Outcome.Ok -> {
                for (f in r.value.filter { it.type == "file" && it.name.endsWith(".json") }) {
                    deviceIds.add(f.name.removeSuffix(".json"))
                }
            }
            else -> {
                // Fall back to local audit-log distinct so we still
                // know about peers we've seen.
                val audit = runCatching { auditRepo.dao.listDistinctDevices() }.getOrDefault(emptyList())
                audit.forEach { deviceIds.add(it.deviceId) }
            }
        }
        if (!includeSelf) deviceIds.remove(myId)

        if (deviceIds.isEmpty()) {
            return ToolResult(false, """{"error":"no_devices_found","detail":"No peer devices visible in the memory repo. Sync at least once on the other devices first."}""")
        }

        val now = System.currentTimeMillis()
        val ids = mutableListOf<String>()
        for (devId in deviceIds) {
            val taskId = UUID.randomUUID().toString()
            val row = TaskEntity(
                id = taskId,
                title = title,
                body = body,
                requesterDeviceId = myId,
                targetDeviceId = devId,
                status = TaskStatus.PENDING.name,
                createdMs = now,
            )
            runCatching { taskRepo.dao.insertIfAbsent(row) }
            ids.add(taskId)
        }
        // Single heartbeat kick — ships all N tasks in one sync round.
        runCatching { heartbeat.get().fireNow() }
        Log.d(TAG, "team_call: fanned $title → ${deviceIds.size} device(s)")
        val resultJson = buildJsonObject {
            put("ok", true)
            put("fanned_out_count", deviceIds.size)
            put("target_devices", buildJsonArray { deviceIds.forEach { add(JsonPrimitive(it)) } })
            put("task_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
            put(
                "detail",
                "Tasks queued. Each device picks up its copy on the next 5-min heartbeat. " +
                    "Watch the tasks list for results.",
            )
        }
        return ToolResult(true, resultJson.toString())
    }

    companion object {
        private const val TAG = "Mythara/TeamCall"
    }
}
