package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * `send_sms` — open the system SMS composer with the recipient and
 * body pre-filled. The user taps Send themselves; we don't send
 * silently in the background.
 *
 * Today's implementation uses `ACTION_SENDTO` with the `smsto:` URI
 * and the `sms_body` extra. This requires NO runtime permission
 * (despite SEND_SMS being declared in the manifest) because we're
 * launching the user's chosen messaging app rather than calling
 * SmsManager.sendTextMessage directly. That's by design — until
 * M5 part 4 ships ConfirmationGate, silent SMS would be too risky.
 *
 * Direct-send (no system composer in-between) lands in M6 alongside
 * the ConfirmationGate UI that prompts before each send.
 */
@Singleton
class SmsComposerTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "send_sms"
    override val description: String =
        "Open the SMS composer pre-filled with a recipient + message body. " +
            "The user reviews and taps Send themselves; you don't send silently. " +
            "Use when the user asks 'text Mom that I'm running late' — after resolving 'Mom' to a phone number via read_contact."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Recipient phone number (E.164 preferred — e.g. +14155551234).")
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Message body. Keep it short and natural — the user can edit before sending.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("to"), JsonPrimitive("body"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        if (to.isEmpty()) return ToolResult(false, """{"error":"missing_to"}""")
        // smsto: URI accepts a phone number or comma-separated numbers.
        val uri = Uri.parse("smsto:$to")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = runCatching { ctx.startActivity(intent) }
        return if (result.isSuccess) {
            ToolResult(
                ok = true,
                output = """{"ok":true,"opened":"sms_composer","to":${JsonPrimitive(to)},"body_len":${body.length}}""",
            )
        } else {
            ToolResult(
                ok = false,
                output = """{"error":"launch_failed","detail":${JsonPrimitive(result.exceptionOrNull()?.message ?: "no SMS app")}}""",
            )
        }
    }
}
