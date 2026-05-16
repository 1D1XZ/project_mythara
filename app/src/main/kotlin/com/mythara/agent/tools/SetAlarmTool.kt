package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `set_alarm` — schedule a system alarm via Android's standard
 * AlarmClock intent.
 *
 * Two flavours, picked automatically from the args:
 *
 *   • `at` (HH:mm 24-hour) — sets a one-shot clock alarm. The
 *     system's default Clock app handles the actual ringing.
 *   • `in_minutes` (int) — sets a countdown timer instead. Same
 *     UX, different intent action.
 *
 * No special permissions needed beyond the manifest declaration
 * Android already requires (`com.android.alarm.permission.SET_ALARM`),
 * which is install-time-granted on all Android builds.
 *
 * Returns the actual alarm time + label so the agent can confirm
 * verbally in its next reply.
 */
@Singleton
class SetAlarmTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "set_alarm"
    override val description =
        "Schedule a system alarm or timer via the Clock app. Use 'at' for HH:mm clock alarms " +
            "('07:30'), or 'in_minutes' for countdown timers. 'label' is optional."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("at", buildJsonObject {
                put("type", "string")
                put("description", "Wake-up time as HH:mm (24-hour). e.g. '07:30'. Mutually exclusive with in_minutes.")
            })
            put("in_minutes", buildJsonObject {
                put("type", "integer")
                put("description", "Countdown timer length in minutes. Mutually exclusive with 'at'.")
            })
            put("label", buildJsonObject {
                put("type", "string")
                put("description", "Optional label shown in the Clock app (e.g. 'standup', 'pasta done').")
            })
            put("vibrate", buildJsonObject {
                put("type", "boolean")
                put("description", "Optional. Default true for alarms, false for timers (Android's defaults).")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val at = args["at"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val inMinutes = args["in_minutes"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull()
        val label = args["label"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val vibrate = args["vibrate"]?.jsonPrimitive?.contentOrNull()?.toBoolean()

        if (at.isBlank() && inMinutes == null) {
            return ToolResult.fail("must pass either 'at' (HH:mm) or 'in_minutes' (int)")
        }
        if (at.isNotBlank() && inMinutes != null) {
            return ToolResult.fail("pass only one of 'at' or 'in_minutes', not both")
        }

        return runCatching {
            if (at.isNotBlank()) {
                val (h, m) = parseHhMm(at)
                    ?: return@runCatching ToolResult.fail("invalid time '$at' — expected HH:mm (24-hour)")
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, h)
                    putExtra(AlarmClock.EXTRA_MINUTES, m)
                    if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    if (vibrate != null) putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
                    // Don't pop the Clock UI — fire-and-forget.
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult.ok(
                    """{"status":"ok","kind":"alarm","at":"${"%02d:%02d".format(h, m)}","label":"${label.escape()}"}""",
                )
            } else {
                val mins = inMinutes!!
                if (mins !in 1..24 * 60) {
                    return@runCatching ToolResult.fail("in_minutes must be 1..1440")
                }
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, mins * 60)
                    if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    if (vibrate != null) putExtra(AlarmClock.EXTRA_VIBRATE, vibrate)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, mins) }
                val displayAt = "%02d:%02d".format(
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                )
                ToolResult.ok(
                    """{"status":"ok","kind":"timer","in_minutes":$mins,"fires_at":"$displayAt","label":"${label.escape()}"}""",
                )
            }
        }.getOrElse {
            ToolResult.fail("set_alarm_failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun parseHhMm(s: String): Pair<Int, Int>? {
        val parts = s.split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
