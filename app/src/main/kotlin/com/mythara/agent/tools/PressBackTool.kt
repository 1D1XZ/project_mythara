package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.PhoneControlAccessibilityService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `press_back` — fire the system back action via Accessibility's
 * GLOBAL_ACTION_BACK.
 *
 * Used heavily by the auto-reply flow: after the agent taps an image
 * thumbnail to inspect it full-screen, this gets it back to the
 * conversation so the next screenshot / tool call lands in the right
 * place. Also useful in any "open something → look → return"
 * navigation pattern.
 *
 * Equivalent to the user pressing the system back gesture / button.
 * Has no real "destructive" failure mode — at worst the OS closes
 * the foreground activity, which the model can recover from on the
 * next read step.
 */
@Singleton
class PressBackTool @Inject constructor() : Tool {

    override val name: String = "press_back"
    override val description: String =
        "Send a system back action — equivalent to the user pressing the back gesture / button. " +
            "Use to return to the previous screen after expanding an image, opening a media viewer, or " +
            "navigating into a sub-page. Required step in auto-reply flows where you tap on a chat image " +
            "to see it full-size and then need to get back to the conversation. " +
            "Reliable: even when the foreground app doesn't have a back button it responds to the system back."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(false, """{"error":"accessibility_not_granted","detail":"Enable Mythara in Settings → Accessibility."}""")
        val ok = service.pressBack()
        return if (ok) ToolResult(true, """{"ok":true}""")
        else ToolResult(false, """{"error":"action_rejected","detail":"OS refused the back action. Foreground may have intercepted it."}""")
    }
}
