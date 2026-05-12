package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `web_fetch` — fetch a URL and return the text body (truncated to ~6KB).
 *
 * No HTML→Markdown conversion in this minimal version — the model is good
 * at parsing rendered HTML directly, and shipping a stripping library would
 * inflate the APK. A second seed tool that demonstrates network-side
 * effects flowing through the agentic loop, plus exercises the OkHttp
 * stack outside the MiniMax client.
 */
@Singleton
class WebFetchTool @Inject constructor() : Tool {
    override val name: String = "web_fetch"
    override val description: String = "Fetch a URL and return its text body (truncated to 6KB)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("description", "Absolute URL to fetch. Must start with http:// or https://.")
            })
        })
        put("required", kotlinx.serialization.json.JsonArray(
            listOf(kotlinx.serialization.json.JsonPrimitive("url")),
        ))
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args["url"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return ToolResult.fail("url must be an absolute http(s) URL")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mythara/0.0.1 (Android)")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use ToolResult.fail("http ${resp.code} ${resp.message}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val truncated = if (body.length > MAX_BYTES) body.take(MAX_BYTES) + "\n…[truncated]" else body
                    ToolResult.ok(truncated)
                }
            }.getOrElse { ToolResult.fail("fetch failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        runCatching { this.content }.getOrNull()

    companion object {
        private const val MAX_BYTES = 6_144
    }
}
