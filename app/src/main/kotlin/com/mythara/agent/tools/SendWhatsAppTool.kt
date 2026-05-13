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
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `send_whatsapp` — open WhatsApp with a recipient + message body
 * pre-filled. The user taps Send themselves; we don't drive
 * WhatsApp's UI silently in v1.
 *
 * Why deep-link rather than direct automation:
 *  - WhatsApp has no public API for third-party apps to send
 *    messages on the user's behalf (Business API is gated to
 *    verified businesses, paid, and unsuited to personal use).
 *  - Driving WhatsApp's UI via [com.mythara.services.PhoneControlAccessibilityService]
 *    (tap contact, focus input, type, tap send) would work but
 *    breaks the moment WhatsApp ships a redesign. The deep-link
 *    is the WhatsApp-team-blessed integration point.
 *  - The user is one tap away from Send anyway. The intent path
 *    matches our `send_sms` tool's safety stance: Mythara
 *    prepares the message, the user fires it.
 *
 * Two URI variants tried in order:
 *  1. `whatsapp://send?phone=<E.164>&text=<urlencoded>` — direct
 *     to the installed WhatsApp app on the device, fastest.
 *  2. `https://wa.me/<phone-no-plus>?text=<urlencoded>` — official
 *     fallback that Android's intent picker still resolves to
 *     WhatsApp when it's installed; falls back to a browser
 *     "open in WhatsApp / install WhatsApp" page when not.
 *
 * No runtime permission required — we're launching an existing
 * app via implicit intent, not sending the message ourselves.
 *
 * ConfirmationGate gate: NOT required for v1 since the system UI
 * (WhatsApp composer) provides the user-tap gate. A future
 * `send_whatsapp_direct` variant using Accessibility automation
 * would gate the same way SmsDirectTool does.
 */
@Singleton
class SendWhatsAppTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "send_whatsapp"
    override val description: String =
        "Open WhatsApp with a recipient + message pre-filled. The user taps Send themselves. " +
            "Use when the user says 'whatsapp mom that I'm running late' or 'send a whatsapp message to John' — " +
            "after resolving the name to a phone number via read_contact. " +
            "Numbers should be E.164 (e.g. +14155551234)."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Recipient phone number in E.164 (e.g. +14155551234). Country code is required — WhatsApp can't resolve local numbers.",
                        )
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Message body. The user reviews and edits before sending if they want.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("to"), JsonPrimitive("body"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawTo = (args["to"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val body = (args["body"] as? JsonPrimitive)?.content.orEmpty()
        if (rawTo.isEmpty()) return ToolResult(false, """{"error":"missing_to"}""")
        val normalised = normaliseNumber(rawTo)
        val encodedBody = URLEncoder.encode(body, "UTF-8")

        // Try the whatsapp:// scheme first (fastest — direct to the
        // app, no intent disambiguation). If WhatsApp isn't installed
        // (or this user's profile doesn't have it) the activity start
        // throws, and we fall back to the wa.me HTTPS URL which
        // Android offers in the system app picker.
        val direct = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("whatsapp://send?phone=$normalised&text=$encodedBody")
            setPackage(WHATSAPP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val directOk = runCatching { ctx.startActivity(direct) }.isSuccess
        if (directOk) {
            return ToolResult(true, """{"ok":true,"opened":"whatsapp","via":"direct","to":${JsonPrimitive(normalised)},"body_len":${body.length}}""")
        }

        // wa.me path. Strip the leading + because the URL spec wants
        // just digits ("https://wa.me/14155551234?text=hi").
        val waMe = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://wa.me/${normalised.removePrefix("+")}?text=$encodedBody")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackOk = runCatching { ctx.startActivity(waMe) }.isSuccess
        return if (fallbackOk) {
            ToolResult(true, """{"ok":true,"opened":"whatsapp","via":"wa.me","to":${JsonPrimitive(normalised)},"body_len":${body.length}}""")
        } else {
            ToolResult(
                false,
                """{"error":"whatsapp_not_available","detail":"Couldn't open WhatsApp. Make sure it's installed and the number is in E.164 (with country code)."}""",
            )
        }
    }

    /**
     * WhatsApp accepts both `+14155551234` (whatsapp:// scheme) and
     * `14155551234` (wa.me URL). We normalise to keep a leading `+`
     * for the direct path, then strip it on the fallback. Drop any
     * spaces / dashes / parens the model might pass through.
     */
    private fun normaliseNumber(raw: String): String {
        val cleaned = raw.filter { it.isDigit() || it == '+' }
        return if (cleaned.startsWith("+")) cleaned else "+$cleaned"
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
    }
}
