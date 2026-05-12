package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `get_time` — current local time, date, and the user's timezone.
 *
 * Free, zero side-effects, no permission required. Picked as a seed tool
 * because the model demonstrably *cannot* answer "what time is it?" without
 * one, so it makes the multi-turn loop instantly observable: ask the time,
 * see ● get_time → ✓ get_time → spoken reply.
 */
@Singleton
class TimeTool @Inject constructor() : Tool {
    override val name: String = "get_time"
    override val description: String = "Current local date, time, and IANA timezone on the user's phone."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val iso = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val human = now.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss"))
        return ToolResult.ok("""{"iso":"$iso","human":"$human","timezone":"${zone.id}"}""")
    }
}
