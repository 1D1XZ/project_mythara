package com.mythara.agent.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar tool pair — list + create.
 *
 * - `list_calendar_events(hoursAhead, max)` reads upcoming events from
 *   the user's default calendars via CalendarContract. Permission:
 *   READ_CALENDAR.
 * - `create_calendar_event(title, startMs, endMs, location?, description?)`
 *   inserts an event into the user's primary writable calendar.
 *   Permission: WRITE_CALENDAR. Returns the inserted event id + system
 *   calendar uri.
 *
 * Read tool is unconfirmed; create tool is technically destructive but
 * inserting an event is forgiving (user can edit / delete in Calendar
 * app trivially) — we ship it without confirmation today. M5 part 4's
 * ConfirmationGate can add per-call prompting if the user wants
 * stricter behaviour.
 */

@Singleton
class ListCalendarEventsTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable
    data class Event(
        val id: Long,
        val title: String? = null,
        val startMs: Long,
        val endMs: Long,
        val location: String? = null,
        val allDay: Boolean = false,
        val calendarName: String? = null,
    )

    @Serializable
    data class Response(val count: Int, val events: List<Event>)

    override val name: String = "list_calendar_events"
    override val description: String =
        "Upcoming events from the user's calendars. " +
            "Defaults to the next 48 hours; pass `hoursAhead` to widen. " +
            "Use when the user asks 'what's on my schedule', 'do I have anything tomorrow', etc."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "hoursAhead",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "How far in the future to look. Default 48. Max 720 (30 days).")
                    },
                )
                put(
                    "max",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of events to return. Default 25, max 100.")
                    },
                )
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"Calendar permission isn't granted. Open Settings → Apps → Mythara → Permissions and allow Calendar."}""",
            )
        }
        val hoursAhead = ((args["hoursAhead"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 48)
            .coerceIn(1, 720)
        val max = ((args["max"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 25)
            .coerceIn(1, 100)
        val now = System.currentTimeMillis()
        val end = now + hoursAhead * 3600L * 1000L
        val events = withContext(Dispatchers.IO) { queryEvents(now, end, max) }
        return ToolResult(
            ok = true,
            output = JSON.encodeToString(
                Response.serializer(),
                Response(count = events.size, events = events),
            ),
        )
    }

    private fun queryEvents(startMs: Long, endMs: Long, max: Int): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<Event>()
        ctx.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC LIMIT $max",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val calIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (c.moveToNext()) {
                out.add(
                    Event(
                        id = c.getLong(idIdx),
                        title = c.getString(titleIdx),
                        startMs = c.getLong(beginIdx),
                        endMs = c.getLong(endIdx),
                        location = c.getString(locIdx),
                        allDay = c.getInt(allDayIdx) == 1,
                        calendarName = c.getString(calIdx),
                    ),
                )
            }
        }
        return out
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}

@Singleton
class CreateCalendarEventTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable
    data class Response(
        val ok: Boolean,
        val eventId: Long?,
        val uri: String?,
        val calendarId: Long?,
    )

    override val name: String = "create_calendar_event"
    override val description: String =
        "Add an event to the user's calendar. Times are epoch millis (UTC). " +
            "Use when the user says 'add a meeting tomorrow at 3pm' or 'put dentist on my calendar Friday at 10'. " +
            "Resolve relative times ('tomorrow', 'next Tuesday') against the user's local time zone before calling."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "Event title.") })
                put("startMs", buildJsonObject { put("type", "integer"); put("description", "Event start time as epoch millis.") })
                put("endMs", buildJsonObject { put("type", "integer"); put("description", "Event end time as epoch millis. Must be > startMs.") })
                put("location", buildJsonObject { put("type", "string"); put("description", "Optional location.") })
                put("description", buildJsonObject { put("type", "string"); put("description", "Optional notes/description.") })
                put("allDay", buildJsonObject { put("type", "boolean"); put("description", "True for an all-day event; startMs/endMs should be midnight UTC of the days.") })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("startMs"), JsonPrimitive("endMs"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"Calendar write permission isn't granted. Open Settings → Apps → Mythara → Permissions and allow Calendar."}""",
            )
        }
        val title = (args["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, """{"error":"missing_title"}""")
        val startMs = (args["startMs"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult(false, """{"error":"missing_or_bad_startMs"}""")
        val endMs = (args["endMs"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult(false, """{"error":"missing_or_bad_endMs"}""")
        if (endMs <= startMs) {
            return ToolResult(false, """{"error":"end_before_start","detail":"endMs must be greater than startMs."}""")
        }
        val location = (args["location"] as? JsonPrimitive)?.content
        val description = (args["description"] as? JsonPrimitive)?.content
        val allDay = (args["allDay"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

        val calId = withContext(Dispatchers.IO) { findPrimaryWritableCalendar() }
            ?: return ToolResult(
                false,
                """{"error":"no_writable_calendar","detail":"Couldn't find a writable calendar on this device. Add a Google account or local calendar in the Calendar app."}""",
            )
        val tz = TimeZone.getDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
        }
        val uri = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
                .getOrNull()
        } ?: return ToolResult(false, """{"error":"insert_failed"}""")
        val eventId = ContentUris.parseId(uri)
        return ToolResult(
            true,
            JSON.encodeToString(
                Response.serializer(),
                Response(ok = true, eventId = eventId, uri = uri.toString(), calendarId = calId),
            ),
        )
    }

    /**
     * Pick the first locally-writable calendar (ACCESS_LEVEL ≥ OWNER).
     * On a typical Pixel that's the user's primary Google calendar.
     */
    private fun findPrimaryWritableCalendar(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} DESC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val accessIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                if (c.getInt(accessIdx) >= CalendarContract.Calendars.CAL_ACCESS_OWNER) {
                    return c.getLong(idIdx)
                }
            }
        }
        return null
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
