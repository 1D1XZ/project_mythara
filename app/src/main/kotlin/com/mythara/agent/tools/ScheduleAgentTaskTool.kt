package com.mythara.agent.tools

import android.content.Context
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.HeartbeatSyncer
import com.mythara.reminders.Recurrence
import com.mythara.tasks.TaskEntity
import com.mythara.tasks.TaskRepository
import com.mythara.tasks.TaskStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **schedule_task** — schedule an arbitrary prompt to feed to the
 * agent at a future time, or on a recurring schedule. The reply that
 * the agent produces lands in the chat timeline (and on the watch's
 * insight complication via the existing relay) just as if the user
 * had typed the prompt themselves at the scheduled moment.
 *
 * Use cases:
 *   - "Push a 1-line morning insight to the watch face every day at
 *     7am" → recurring DAILY:07:00 with prompt "Generate a 1-line
 *     morning insight for the watch face: today's calendar, top
 *     follow-ups, anything important about today."
 *   - "Summarise the day for me at 9pm" → DAILY:21:00 with prompt
 *     "Summarise today's notable events / messages / focus."
 *   - "Check if my battery is low at noon and remind me to charge if
 *     so" → DAILY:12:00 with the conditional prompt.
 *
 * Distinct from `create_reminder`:
 *   - create_reminder is for user-facing reminders ("don't forget to
 *     take medication") — fires a notification + TTS announcement +
 *     a chat card.
 *   - schedule_task is for AGENT prompts — the body is a full
 *     instruction that the agent processes and produces output for.
 *     No notification UX; the reply IS the output.
 *
 * Both store a TaskEntity under the hood with `scheduled_for_ms` /
 * `recurrence`. TaskExecutor's heartbeat tick (~5 min granularity)
 * picks up due tasks and submits the body to the agent — same code
 * path that create_reminder uses, just without the notification +
 * TTS layer at the front.
 */
@Singleton
class ScheduleAgentTaskTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val deviceIdStore: DeviceIdStore,
    private val taskRepo: TaskRepository,
    private val heartbeat: dagger.Lazy<HeartbeatSyncer>,
) : Tool {

    override val name: String = "schedule_task"
    override val description: String =
        "Schedule a PROMPT to be fed to the agent at a future time (one-shot OR recurring). " +
            "When the time arrives, the agent processes the prompt and produces a reply, exactly as if " +
            "the user had typed it then. Use this for time-of-day insights, daily summaries, periodic " +
            "checks — anything where the AGENT needs to do something on a schedule. " +
            "For user-facing reminders ('remind me to take medication') use create_reminder instead — " +
            "that fires a notification + TTS, this one just runs the prompt. " +
            "Granularity is ~5 minutes (heartbeat tick); use create_reminder for exact-second timing. " +
            "Provide at_epoch_ms for one-shot OR recurrence for recurring (syntax: " + Recurrence.SYNTAX + "). " +
            "When recurrence is set, the first fire is computed from the recurrence and at_epoch_ms is ignored. " +
            "IMPORTANT: in your CHAT REPLY confirming a schedule, do NOT say 'in N minutes' or any relative " +
            "time — the chat is a permanent log and that text freezes at creation, which becomes wrong as " +
            "time passes. Use the absolute clock time only ('at 14:30', 'tomorrow at 9am') OR just point the " +
            "user at the reminder card / watch face which both show a live countdown that ticks down on " +
            "screen automatically."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "title",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Short label for the schedule — surfaced in list_scheduled_tasks. e.g. 'morning insight', 'evening summary'.")
                    },
                )
                put(
                    "prompt",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The full prompt to feed to the agent at fire time. Write it as if the user " +
                                "is asking it themselves — the agent will process it and reply.",
                        )
                    },
                )
                put(
                    "at_epoch_ms",
                    buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "One-shot fire time as Unix epoch millis (resolve relative times like 'tomorrow 8am' " +
                                "with the time tool first). Required if recurrence is omitted.",
                        )
                    },
                )
                put(
                    "recurrence",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Recurring schedule. Syntax: " + Recurrence.SYNTAX +
                                ". When set, first fire is computed from the recurrence and at_epoch_ms is ignored.",
                        )
                    },
                )
            },
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("title"))
                add(JsonPrimitive("prompt"))
            },
        )
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = (args["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val prompt = (args["prompt"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (title.isBlank() || prompt.isBlank()) {
            return ToolResult(false, """{"error":"missing_args","detail":"title and prompt are required"}""")
        }
        val recurrenceArg = (args["recurrence"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
        val recurrence = recurrenceArg?.let { runCatching { Recurrence.parse(it) }.getOrNull() }
        if (recurrenceArg != null && recurrence == null) {
            return ToolResult(false, """{"error":"bad_recurrence","detail":"unparsable: $recurrenceArg. Syntax: ${Recurrence.SYNTAX}"}""")
        }
        val now = System.currentTimeMillis()
        val fireAt: Long = if (recurrence != null) {
            recurrence.nextAfter(now)
        } else {
            val raw = (args["at_epoch_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (raw == null || raw <= now) {
                return ToolResult(
                    false,
                    """{"error":"missing_when","detail":"Provide either at_epoch_ms (in the future) or recurrence."}""",
                )
            }
            raw
        }

        val myId = deviceIdStore.id()
        val id = UUID.randomUUID().toString()
        val row = TaskEntity(
            id = id,
            title = title,
            body = prompt,
            requesterDeviceId = myId,
            // Pinned to this device — scheduled prompts run where they
            // were created; cross-device handoff isn't needed for the
            // typical use cases.
            targetDeviceId = myId,
            status = TaskStatus.PENDING.name,
            createdMs = now,
            scheduledForMs = fireAt,
            recurrence = recurrence?.encode(),
        )
        runCatching { taskRepo.dao.insertIfAbsent(row) }
            .onFailure {
                Log.w(TAG, "schedule_task insert failed: ${it.message}")
                return ToolResult(false, """{"error":"insert_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""")
            }
        // Verify the row landed with a schedule (defensive — same
        // pattern create_reminder uses).
        val saved = runCatching { taskRepo.dao.byId(id) }.getOrNull()
        if (saved?.scheduledForMs == null) {
            return ToolResult(false, """{"error":"verify_failed","detail":"row didn't persist with a schedule"}""")
        }
        runCatching { heartbeat.get().fireNow() }

        val human = HUMAN_FMT.format(Date(fireAt))
        Log.d(TAG, "schedule_task $id '$title' set for $human (recurs=${recurrence != null})")
        val payload = buildJsonObject {
            put("ok", true)
            put("task_id", id)
            put("title", title)
            put("scheduled_for_ms", fireAt)
            put("scheduled_for", human)
            put("recurring", recurrence != null)
            if (recurrence != null) put("recurrence", recurrence.describe())
            put(
                "detail",
                if (recurrence != null) {
                    "Scheduled '$title' — ${recurrence.describe()}. First fire $human."
                } else {
                    "Scheduled '$title' for $human (one-shot)."
                },
            )
        }
        return ToolResult.ok(payload.toString())
    }

    companion object {
        private const val TAG = "Mythara/ScheduleTask"
        private val HUMAN_FMT = SimpleDateFormat("EEE MMM d, HH:mm", Locale.getDefault())
    }
}
