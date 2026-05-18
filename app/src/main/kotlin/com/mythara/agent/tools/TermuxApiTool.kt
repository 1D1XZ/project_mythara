package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.TermuxAvailability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `termux_api` — invoke Termux:API companion binaries
 * (clipboard / battery / location / camera / TTS / vibrate / toast /
 * notification / sensor) for direct Android-platform access.
 *
 * Architecturally a thin wrapper over [TermuxExecTool]: every Termux:API
 * surface is a `termux-<name>` binary on Termux's `$PATH`, and they're
 * already designed to be CLI-driven. So `termux_api(api="battery-status")`
 * becomes `termux_exec(command="termux-battery-status")` under the hood
 * with a small allowlist gate to keep the agent from poking at binaries
 * we haven't reviewed.
 *
 * The Termux:API companion is a SEPARATE APK from Termux itself —
 * `com.termux.api` on F-Droid. When it's missing, the underlying
 * `termux-*` binary returns command-not-found; the wrapped result
 * surfaces that to the agent with a hint about installing the companion.
 *
 * Why an allowlist when Termux already gates this:
 *   - The model can't introspect what binaries are safe to call. A
 *     short, hand-picked list keeps it focused on the high-value
 *     Android-platform surfaces and avoids `termux-call-log`-style
 *     PII paths until the user explicitly opts in.
 *   - When the agent picks a non-allowlisted api, the tool responds
 *     with the full allowed list — a discoverability mechanism.
 */
@Singleton
class TermuxApiTool @Inject constructor(
    private val exec: TermuxExecTool,
    private val availability: TermuxAvailability,
) : Tool {
    override val name = "termux_api"
    override val description =
        "Read Android platform state via Termux:API. Wraps termux-<api> binaries: clipboard-get, " +
            "clipboard-set, battery-status, location, camera-photo, camera-info, tts-speak, " +
            "vibrate, toast, notification, sensor, wifi-connectioninfo, share, torch. Returns the " +
            "binary's stdout (most return JSON natively). Requires the Termux:API companion APK " +
            "in addition to Termux itself."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("api", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Termux:API name (without the 'termux-' prefix). Allowed: " +
                        API_ALLOWLIST.joinToString(", "),
                )
            })
            put("args", buildJsonObject {
                put("type", "array")
                put(
                    "description",
                    "CLI arguments to pass to the termux-<api> binary. E.g. for tts-speak: " +
                        "['-l','en','Hello'].",
                )
                put("items", buildJsonObject { put("type", "string") })
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Milliseconds before kill. Default 15000, max 60000.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("api"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!availability.isInstalled()) {
            return ToolResult.ok(
                """{"status":"not_installed","hint":"install Termux + Termux:API from F-Droid"}""",
            )
        }

        val api = args["api"]?.jsonPrimitive?.contentOrNull()?.trim()?.lowercase().orEmpty()
            .removePrefix("termux-")
        if (api.isBlank()) return ToolResult.fail("api must be non-empty")
        if (api !in API_ALLOWLIST) {
            return ToolResult.ok(
                """{"status":"blocked","api":${jsonString(api)},""" +
                    """"reason":"not allowlisted","allowed":""" +
                    API_ALLOWLIST.joinToString(",", "[", "]") { jsonString(it) } +
                    "}",
            )
        }

        val cliArgs = args["args"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull() }
            .orEmpty()
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 15_000L)
            .coerceIn(500L, 60_000L)

        Log.d(TAG, "api=$api args=$cliArgs timeout=${timeoutMs}ms")

        val execArgs = buildJsonObject {
            put("command", "termux-$api")
            put("args", JsonArray(cliArgs.map { JsonPrimitive(it) }))
            put("background", true)
            put("timeout_ms", timeoutMs)
        }
        return exec.execute(execArgs)
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String) = "\"" + s.escape() + "\""

    companion object {
        private const val TAG = "Mythara/TermuxApi"

        /** Curated Termux:API surface. Read-leaning by default; the
         *  write-heavy entries (clipboard-set, tts-speak, vibrate,
         *  toast, notification, share, torch) are useful enough to
         *  include but the agent is told via system prompt to confirm
         *  before firing them on the user's behalf. */
        private val API_ALLOWLIST: Set<String> = linkedSetOf(
            // Clipboard
            "clipboard-get", "clipboard-set",
            // Device state
            "battery-status", "wifi-connectioninfo", "wifi-scaninfo",
            "telephony-cellinfo", "telephony-deviceinfo",
            // Sensors + location
            "location", "sensor",
            // Camera
            "camera-info", "camera-photo",
            // Speak / vibrate / toast / notify
            "tts-speak", "vibrate", "toast",
            "notification", "notification-list", "notification-remove",
            // Other
            "share", "torch", "fingerprint",
        )
    }
}
