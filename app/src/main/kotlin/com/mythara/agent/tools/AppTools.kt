package com.mythara.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `open_app(pkg)` — launch an installed app by package name.
 *
 * The launcher activity is resolved via PackageManager — no
 * explicit activity name needed. We pull the QUERY_ALL_PACKAGES
 * permission already (declared in M0 manifest) so we can see all
 * apps the user has installed.
 *
 * Permission story:
 *  - On Android 11+ QUERY_ALL_PACKAGES gates the visibility of
 *    other apps to ours. Already declared.
 *  - Launching an Activity from a singleton context requires
 *    FLAG_ACTIVITY_NEW_TASK — set unconditionally here.
 */
@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    override val name: String = "open_app"
    override val description: String =
        "Launch an installed app by its package name (e.g. 'com.google.android.gm' for Gmail). " +
            "Use `list_apps` first if you're not sure of the exact package name."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "pkg",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Full package name like 'com.spotify.music' or 'com.uber.driver'.")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("pkg"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pkg = (args["pkg"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (pkg.isEmpty()) return ToolResult(false, """{"error":"missing_pkg"}""")
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult(false, """{"error":"not_found","detail":"No launcher activity for $pkg. Is the app installed?"}""")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(intent)
            ToolResult(true, """{"ok":true,"opened":${JsonPrimitive(pkg)}}""")
        }.getOrElse {
            ToolResult(false, """{"error":"launch_failed","detail":${JsonPrimitive(it.message ?: "unknown")}}""")
        }
    }
}

/**
 * `list_apps` — enumerate the user's installed apps with their
 * package names + visible labels.
 *
 * Filters to apps that have a launcher activity (= things the user
 * can actually open from Home) so the model doesn't see system
 * services / providers it can't launch.
 *
 * Result is capped at [MAX_RESULTS] entries — model doesn't need
 * the full 300-app inventory; it picks a target after a coarse name
 * filter the model can apply over the response.
 */
@Singleton
class ListAppsTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable data class App(val pkg: String, val label: String)
    @Serializable data class Response(val count: Int, val apps: List<App>)

    override val name: String = "list_apps"
    override val description: String =
        "List user-launchable apps installed on the phone. " +
            "Returns package names + visible labels. Use to find the exact package name before calling `open_app`."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "filter",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Optional case-insensitive substring filter on app label or package.")
                    },
                )
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val filter = (args["filter"] as? JsonPrimitive)?.content?.lowercase()?.trim().orEmpty()
        val apps = withContext(Dispatchers.IO) { enumerateApps(filter) }
        return ToolResult(
            ok = true,
            output = JSON.encodeToString(
                Response.serializer(),
                Response(count = apps.size, apps = apps),
            ),
        )
    }

    private fun enumerateApps(filter: String): List<App> {
        val pm = ctx.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolved = pm.queryIntentActivities(mainIntent, 0)
        return resolved.asSequence()
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                App(
                    pkg = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                )
            }
            .filter {
                filter.isEmpty() ||
                    it.label.lowercase().contains(filter) ||
                    it.pkg.lowercase().contains(filter)
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .take(MAX_RESULTS)
            .toList()
    }

    companion object {
        private const val MAX_RESULTS = 80
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
