package com.mythara.agent.tools

import android.content.Context
import android.hardware.camera2.CameraManager
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `flashlight` — toggle the camera torch.
 *
 * Uses [CameraManager.setTorchMode] against the first camera that
 * reports torch support. No runtime permission required on Android
 * 6+ for the torch (`FLASHLIGHT` was removed; CameraManager allows
 * any app to flip it).
 */
@Singleton
class FlashlightTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "flashlight"
    override val description: String =
        "Turn the phone's flashlight (torch) on or off. " +
            "Use when the user asks 'turn on the flashlight' / 'turn off the torch'."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "on",
                    buildJsonObject {
                        put("type", "boolean")
                        put("description", "true to turn on, false to turn off.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("on"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val on = (args["on"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: return ToolResult(false, """{"error":"missing_on","detail":"Pass an 'on' boolean."}""")
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ToolResult(false, """{"error":"no_camera_service"}""")
        val cameraId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
            ?: return ToolResult(false, """{"error":"no_torch","detail":"No camera with torch support on this device."}""")
        return runCatching {
            cm.setTorchMode(cameraId, on)
            ToolResult(true, """{"ok":true,"on":$on,"camera_id":"$cameraId"}""")
        }.getOrElse {
            ToolResult(false, """{"error":"torch_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""")
        }
    }
}
