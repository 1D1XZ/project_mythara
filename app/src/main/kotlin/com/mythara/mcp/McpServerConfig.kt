package com.mythara.mcp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One configured MCP (Model Context Protocol) server. Mythara connects
 * over HTTP-streamable JSON-RPC, discovers its tools, and surfaces them
 * to the agent transparently — the model sees them in the same `tools`
 * array as native Mythara tools and can call them by name.
 *
 * Auth: optional bearer token. Stored cleartext in DataStore because
 * MCP server tokens are typically per-app scoped and the user supplies
 * them themselves — we don't ship them anywhere.
 */
@Serializable
data class McpServerConfig(
    /** Stable id used in the tool-routing prefix. Generated from URL. */
    val id: String,
    /** Display name in Settings. */
    val name: String,
    /** Base URL of the MCP server's JSON-RPC endpoint. */
    val url: String,
    /** Optional bearer token; appended as `Authorization: Bearer <token>`. */
    val bearerToken: String? = null,
    /** True if the user has enabled this server. Disabled = tools hidden from agent. */
    val enabled: Boolean = true,
)

/**
 * DataStore-backed list of MCP servers. Mirrors the FavoritesStore /
 * UserAliasesStore pattern — one JSON-encoded list under a single
 * preference key.
 */
@Singleton
class McpConfigStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_mcp_servers")

    private val keyServers = stringPreferencesKey("mcp.servers.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Live flow — UI + McpRegistry both observe to refresh on edit. */
    fun serversFlow(): Flow<List<McpServerConfig>> =
        ctx.dataStore.data.map { prefs ->
            val raw = prefs[keyServers].orEmpty()
            if (raw.isBlank()) emptyList()
            else runCatching {
                json.decodeFromString(ListSerializer(McpServerConfig.serializer()), raw)
            }.getOrDefault(emptyList())
        }

    suspend fun list(): List<McpServerConfig> = serversFlow().first()

    suspend fun upsert(server: McpServerConfig) {
        val current = list().filterNot { it.id == server.id }
        val updated = current + server
        write(updated)
    }

    suspend fun remove(id: String) {
        val updated = list().filterNot { it.id == id }
        write(updated)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val updated = list().map { if (it.id == id) it.copy(enabled = enabled) else it }
        write(updated)
    }

    private suspend fun write(list: List<McpServerConfig>) {
        val raw = json.encodeToString(ListSerializer(McpServerConfig.serializer()), list)
        ctx.dataStore.edit { it[keyServers] = raw }
    }
}
