package com.mythara.mcp

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live snapshot of every tool MCP servers currently expose. ToolRegistry
 * reads from here on every apiSchema() build + execute() lookup, so MCP
 * tools surface to the agent without a process restart whenever the
 * user adds a server or a server's tool catalog changes.
 *
 * Refresh policy:
 *  - On boot + on every config change (DataStore flow collector): re-list
 *    every enabled server.
 *  - On any `tools/list` failure: keep stale tools for that server so the
 *    agent doesn't suddenly lose a tool because of a transient blip.
 *
 * Tool naming convention: `mcp__<serverId>__<toolName>`. The prefix
 * makes server-of-origin obvious in the audit log, and keeps the tool
 * namespace clean against native Mythara tools (which never start with
 * `mcp__`).
 */
@Singleton
class McpRegistry @Inject constructor(
    private val configStore: McpConfigStore,
    private val client: McpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tools = MutableStateFlow<List<McpToolHandle>>(emptyList())
    val tools: StateFlow<List<McpToolHandle>> = _tools.asStateFlow()

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            // First-run seed: if the user has zero configured MCP
            // servers, seed the known-working free ones from the
            // [KnownMcpServers] catalog. Idempotent — never overwrites
            // existing user config, only fires when the list is empty.
            runCatching { seedIfEmpty() }
                .onFailure { Log.w(TAG, "seed failed: ${it.message}") }
        }
        // Observe config changes — every edit (add/remove/enable) re-discovers.
        scope.launch {
            configStore.serversFlow().collect { configs ->
                refresh(configs)
            }
        }
        Log.d(TAG, "McpRegistry started")
    }

    private suspend fun seedIfEmpty() {
        val current = configStore.list()
        if (current.isNotEmpty()) return
        val seedables = KnownMcpServers.catalog.filter { it.autoSeed }
        for (entry in seedables) {
            configStore.upsert(
                McpServerConfig(
                    id = KnownMcpServers.idFor(entry.url),
                    name = entry.name,
                    url = entry.url,
                    bearerToken = null,
                    enabled = true,
                ),
            )
        }
        if (seedables.isNotEmpty()) {
            Log.d(TAG, "seeded ${seedables.size} free MCP server(s) on first run")
        }
    }

    /** Force a re-list for every enabled server. Settings panel uses this. */
    fun refreshNow() {
        scope.launch {
            val configs = runCatching { configStore.list() }.getOrDefault(emptyList())
            refresh(configs)
        }
    }

    private suspend fun refresh(configs: List<McpServerConfig>) {
        val enabled = configs.filter { it.enabled }
        val newTools = mutableListOf<McpToolHandle>()
        for (server in enabled) {
            when (val r = client.listTools(server)) {
                is McpClient.Outcome.Ok -> {
                    for (t in r.value) {
                        newTools.add(
                            McpToolHandle(
                                serverId = server.id,
                                serverName = server.name,
                                originalName = t.name,
                                description = t.description ?: "${t.name} (from ${server.name})",
                                inputSchema = t.inputSchema ?: buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject { })
                                },
                                qualifiedName = "${TOOL_PREFIX}${server.id}__${t.name}",
                            ),
                        )
                    }
                    Log.d(TAG, "discovered ${r.value.size} tool(s) from ${server.name}")
                }
                is McpClient.Outcome.Error -> {
                    Log.w(TAG, "list failed for ${server.name}: ${r.message}")
                    // Keep any previously-known tools from this server in
                    // place so a transient failure doesn't strip them.
                    newTools.addAll(_tools.value.filter { it.serverId == server.id })
                }
            }
        }
        _tools.value = newTools
    }

    /** Current snapshot wrapped as Mythara [Tool] instances. */
    fun asMytharaTools(): List<Tool> = _tools.value.map { McpToolWrapper(it, client, configStore) }

    /** Lookup by qualified name (the model uses these). */
    fun findByName(name: String): McpToolHandle? =
        _tools.value.firstOrNull { it.qualifiedName == name }

    companion object {
        private const val TAG = "Mythara/MCP"
        const val TOOL_PREFIX = "mcp__"
    }
}

/** What McpRegistry tracks for each discovered tool. */
data class McpToolHandle(
    val serverId: String,
    val serverName: String,
    val originalName: String,
    val description: String,
    val inputSchema: JsonObject,
    val qualifiedName: String,
)

/**
 * Adapts an [McpToolHandle] to Mythara's [Tool] interface so the
 * agent loop / ToolRegistry can treat it identically to a native tool.
 */
private class McpToolWrapper(
    private val handle: McpToolHandle,
    private val client: McpClient,
    private val configStore: McpConfigStore,
) : Tool {
    override val name: String = handle.qualifiedName
    override val description: String = handle.description
    override val parameters: JsonElement = handle.inputSchema

    override suspend fun execute(args: JsonObject): ToolResult {
        val server = runCatching { configStore.list() }.getOrDefault(emptyList())
            .firstOrNull { it.id == handle.serverId }
            ?: return ToolResult.fail("mcp server '${handle.serverId}' not configured")
        return when (val r = client.callTool(server, handle.originalName, args)) {
            is McpClient.Outcome.Ok -> ToolResult.ok(r.value)
            is McpClient.Outcome.Error -> ToolResult.fail("mcp error: ${r.message}")
        }
    }
}
