package com.mythara.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.PhoneControlAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * `screenshot` — capture the current foreground screen and save
 * the PNG to Mythara's filesDir so the agent can reason about it
 * (or render it on the Canvas).
 *
 * Sister to `take_photo` (camera-based) and `read_screen` (text-only
 * accessibility dump). Use this when you need pixels — e.g. the
 * user asks "what's on my screen", "describe what I'm looking at",
 * or "save a screenshot for me".
 *
 * Implementation note: Android restricts cross-app screenshot APIs.
 * The clean path is AccessibilityService.takeScreenshot() which is
 * granted-by-the-user via the existing Phone Control Accessibility
 * service. When that service isn't enabled, returns an actionable
 * error pointing at the toggle.
 */
@Singleton
class ScreenshotTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "screenshot"
    override val description =
        "Capture the current foreground screen as a PNG. Returns the file path so you can pass it " +
            "to render_canvas via <img src='file://...'> or hand it to a vision model. " +
            "Requires Mythara's Accessibility service to be enabled."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult.fail(
                "accessibility_not_granted: enable Mythara in Settings → Accessibility, then retry.",
            )

        return withContext(Dispatchers.IO) {
            runCatching {
                val bmp = takeScreenshotViaAccessibility(service)
                    ?: return@withContext ToolResult.fail(
                        "screenshot_failed: AccessibilityService returned no bitmap (recent foreground change?)",
                    )
                val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
                val file = File(dir, "${UUID.randomUUID()}.png")
                FileOutputStream(file).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                ToolResult.ok(
                    """{"status":"ok","path":"${file.absolutePath.escape()}","width":${bmp.width},"height":${bmp.height}}""",
                )
            }.getOrElse {
                Log.w(TAG, "screenshot failed: ${it.message}")
                ToolResult.fail("screenshot_failed: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private suspend fun takeScreenshotViaAccessibility(
        service: android.accessibilityservice.AccessibilityService,
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                context.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val hardware = result.hardwareBuffer
                        val bmp = runCatching {
                            Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { hardware.close() }
                        cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot onFailure code=$errorCode")
                        cont.resume(null)
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot threw: ${t.message}")
            cont.resume(null)
        }
    }

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "Mythara/Screenshot"
    }
}
