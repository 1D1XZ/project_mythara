package com.mythara.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal JSON-RPC client for Model Context Protocol servers.
 *
 * Mythara speaks the HTTP-streamable MCP variant — every request is a
 * POST containing one JSON-RPC envelope. The two methods we care
 * about for v1:
 *
 *   tools/list  → returns the catalog of tools the server exposes
 *   tools/call  → invokes a named tool, returns its result
 *
 * No SSE / streaming / sessions yet — when an MCP server requires those
 * we'll add an `Sse` variant; for now stateless POSTs cover the
 * 90%-case (HTTP MCP servers like Linear, Notion, etc.).
 *
 * Error policy: every method returns a sealed [Outcome] — never throws.
 * Callers decide what to surface to the agent vs swallow.
 */
@Singleton
class McpClient @Inject constructor() {

    sealed interface Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>
        data class Error(val message: String, val httpStatus: Int? = null) : Outcome<Nothing>
    }

    @Serializable
    data class McpTool(
        val name: String,
        val description: String? = null,
        val inputSchema: JsonObject? = null,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val nextId = AtomicLong(1)

    suspend fun listTools(server: McpServerConfig): Outcome<List<McpTool>> = withContext(Dispatchers.IO) {
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonPrimitive(nextId.getAndIncrement()))
            put("method", "tools/list")
            put("params", buildJsonObject { })
        }
        when (val r = post(server, req)) {
            is Outcome.Error -> r
            is Outcome.Ok -> {
                val result = (r.value as? JsonObject)?.get("result") as? JsonObject
                val tools = (result?.get("tools") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull {
                        runCatching {
                            json.decodeFromJsonElement(McpTool.serializer(), it)
                        }.getOrNull()
                    }
                    ?: emptyList()
                Outcome.Ok(tools)
            }
        }
    }

    suspend fun callTool(
        server: McpServerConfig,
        toolName: String,
        arguments: JsonObject,
    ): Outcome<String> = withContext(Dispatchers.IO) {
        val req = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", JsonPrimitive(nextId.getAndIncrement()))
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                },
            )
        }
        when (val r = post(server, req)) {
            is Outcome.Error -> r
            is Outcome.Ok -> {
                val result = (r.value as? JsonObject)?.get("result") as? JsonObject
                // MCP returns {"content":[{"type":"text","text":"..."}]}
                // We flatten all text parts into one string.
                val content = result?.get("content") as? kotlinx.serialization.json.JsonArray
                val text = content?.joinToString("\n") { part ->
                    (part as? JsonObject)?.get("text")?.let {
                        (it as? JsonPrimitive)?.content
                    } ?: part.toString()
                } ?: result?.toString() ?: r.value.toString()
                Outcome.Ok(text)
            }
        }
    }

    private fun post(
        server: McpServerConfig,
        body: JsonObject,
    ): Outcome<JsonElement> {
        val bodyJson = json.encodeToString(JsonObject.serializer(), body)
        val reqBuilder = Request.Builder()
            .url(server.url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
        if (!server.bearerToken.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer ${server.bearerToken}")
        }
        val req = reqBuilder.build()
        val response = runCatching { http.newCall(req).execute() }
            .getOrElse { return Outcome.Error("network: ${it.message}") }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "MCP HTTP ${resp.code}: ${raw.take(200)}")
                return Outcome.Error("HTTP ${resp.code}: ${raw.take(200)}", httpStatus = resp.code)
            }
            val parsed = runCatching { json.parseToJsonElement(raw) }
                .getOrElse { return Outcome.Error("bad json: ${it.message}") }
            // JSON-RPC error envelope check.
            val err = (parsed as? JsonObject)?.get("error")
            if (err != null) {
                val msg = (err as? JsonObject)?.get("message")?.let {
                    (it as? JsonPrimitive)?.content
                } ?: err.toString()
                return Outcome.Error("rpc: $msg")
            }
            return Outcome.Ok(parsed)
        }
    }

    companion object {
        private const val TAG = "Mythara/MCP"

        /** Default JSON-RPC schema marker the MCP tools/list returns. */
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}
