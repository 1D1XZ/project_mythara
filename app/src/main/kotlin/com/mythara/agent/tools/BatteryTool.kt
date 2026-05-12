package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `get_battery` — phone battery level (0–100) + charging state.
 *
 * Sticky-broadcast read, no runtime permission needed (`BATTERY_CHANGED`
 * is exempt). Picked as the second seed tool because it demonstrates
 * a phone-state observation flowing back through the agent loop — the
 * pattern every M5+ phone-control tool will follow.
 */
@Singleton
class BatteryTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {
    override val name: String = "get_battery"
    override val description: String = "Phone battery percentage (0..100) and charging state (charging|full|discharging)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = ctx.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        return ToolResult.ok("""{"percent":$pct,"state":"$state"}""")
    }
}
