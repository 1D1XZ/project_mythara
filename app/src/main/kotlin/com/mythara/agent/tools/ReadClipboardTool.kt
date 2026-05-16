package com.mythara.agent.tools

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_clipboard` — read the user's most recent clipboard entry.
 *
 * Lets the user say "summarise what I just copied" or "translate
 * this" without having to re-paste into chat.
 *
 * Android 10+ restricts clipboard reads to apps that either (a)
 * have IME / accessibility focus, or (b) are the default input
 * method. Mythara declares an Accessibility service for its
 * phone-control tooling; when it's enabled the clipboard read
 * works. When it's not, the system returns null and we surface
 * an actionable error pointing at the Accessibility settings.
 */
@Singleton
class ReadClipboardTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "read_clipboard"
    override val description =
        "Read the most recent text the user copied to the clipboard. Returns either the text " +
            "or {error:'clipboard_unavailable'} when Android's privacy restrictions block the read."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        return withContext(Dispatchers.Main) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    return@withContext ToolResult.ok("""{"status":"empty"}""")
                }
                val item = clip.getItemAt(0)
                val text = item.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) {
                    return@withContext ToolResult.ok("""{"status":"non_text"}""")
                }
                val truncated = if (text.length > MAX_CHARS) {
                    text.take(MAX_CHARS) + "\n…[truncated, ${text.length - MAX_CHARS} more chars]"
                } else text
                ToolResult.ok(
                    """{"status":"ok","chars":${text.length},"text":${jsonString(truncated)}}""",
                )
            }.getOrElse {
                Log.w(TAG, "clipboard read failed: ${it.message}")
                ToolResult.fail(
                    "clipboard_unavailable: ${it.message ?: it.javaClass.simpleName}. " +
                        "Android may be blocking the read — Mythara's Accessibility service " +
                        "(Settings → Accessibility → Mythara) usually lifts this.",
                )
            }
        }
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    companion object {
        private const val TAG = "Mythara/Clipboard"
        private const val MAX_CHARS = 8_192
    }
}
