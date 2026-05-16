package com.mythara.agent.tools

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `search_photos` — query MediaStore for images matching basic
 * filters (folder name, mime type, date range, count).
 *
 * Useful for "show me my last 5 screenshots", "find WhatsApp images
 * from yesterday", "how many photos did I take this week".
 *
 * Returns a JSON array of `{id, name, uri, taken_ms, size_bytes,
 * width, height, mime}` entries — the agent can pass URIs into
 * other tools (e.g. render_canvas via `<img src='content://…'>`)
 * or just summarise the result.
 *
 * Permission: needs READ_MEDIA_IMAGES on Android 13+ (already
 * declared in the manifest). User grants at first use.
 */
@Singleton
class SearchPhotosTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "search_photos"
    override val description =
        "Search the device's photo library by folder, mime, date range, and recency. " +
            "Returns up to 'limit' matches (default 20). Useful for 'show me my last screenshots', " +
            "'find images from yesterday', 'count my photos from this week'."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("folder", buildJsonObject {
                put("type", "string")
                put("description", "Match images whose bucket-display-name contains this (case-insensitive). " +
                    "e.g. 'Screenshots', 'WhatsApp', 'Camera'.")
            })
            put("mime", buildJsonObject {
                put("type", "string")
                put("description", "MIME-type filter: 'image/png', 'image/jpeg', 'image/webp', or just 'image' for any.")
            })
            put("since_days", buildJsonObject {
                put("type", "integer")
                put("description", "Only photos taken in the last N days. Mutually exclusive with since_ms.")
            })
            put("since_ms", buildJsonObject {
                put("type", "integer")
                put("description", "Only photos with date_taken >= this epoch-ms. Mutually exclusive with since_days.")
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "Max rows to return. Default 20, max 100.")
            })
        })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val folder = args["folder"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val mime = args["mime"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val sinceDays = args["since_days"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
        val sinceMsRaw = args["since_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 20).coerceIn(1, 100)

        val sinceMs = when {
            sinceMsRaw != null -> sinceMsRaw
            sinceDays != null -> System.currentTimeMillis() - TimeUnit.DAYS.toMillis(sinceDays)
            else -> 0L
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.MIME_TYPE,
                )

                val selectionClauses = mutableListOf<String>()
                val selectionArgs = mutableListOf<String>()
                if (folder.isNotBlank()) {
                    selectionClauses += "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
                    selectionArgs += "%${folder}%"
                }
                if (mime.isNotBlank()) {
                    selectionClauses += if (mime == "image") {
                        "${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/%'"
                    } else {
                        "${MediaStore.Images.Media.MIME_TYPE} = ?".also { selectionArgs += mime }
                    }
                }
                if (sinceMs > 0L) {
                    // DATE_TAKEN is in ms; some entries have null DATE_TAKEN and
                    // we'd rather over-include than miss those, so include
                    // null-DATE_TAKEN rows when caller asked for "recent".
                    selectionClauses += "(${MediaStore.Images.Media.DATE_TAKEN} >= ? OR ${MediaStore.Images.Media.DATE_TAKEN} IS NULL)"
                    selectionArgs += sinceMs.toString()
                }
                val selection = selectionClauses.joinToString(" AND ").ifBlank { null }
                val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

                val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val out = StringBuilder("""{"matches":[""")
                var first = true
                var matchedCount = 0
                context.contentResolver.query(
                    collection, projection, selection,
                    selectionArgs.toTypedArray().takeIf { it.isNotEmpty() },
                    order,
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val bucketIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val dateIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val wIdx = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val hIdx = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val mimeIdx = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    while (cursor.moveToNext() && matchedCount < limit) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: ""
                        val bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx).orEmpty() else ""
                        val takenMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        val w = if (wIdx >= 0) cursor.getInt(wIdx) else 0
                        val h = if (hIdx >= 0) cursor.getInt(hIdx) else 0
                        val m = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        val uri = Uri.withAppendedPath(collection, id.toString())

                        if (!first) out.append(',')
                        first = false
                        out.append('{')
                        out.append("\"id\":$id,")
                        out.append("\"name\":\"${name.escape()}\",")
                        out.append("\"folder\":\"${bucket.escape()}\",")
                        out.append("\"uri\":\"${uri.toString().escape()}\",")
                        out.append("\"taken_ms\":$takenMs,")
                        out.append("\"size_bytes\":$size,")
                        out.append("\"width\":$w,")
                        out.append("\"height\":$h,")
                        out.append("\"mime\":\"${m.escape()}\"")
                        out.append('}')
                        matchedCount++
                    }
                }
                out.append("],\"count\":$matchedCount}")
                ToolResult.ok(out.toString())
            }.getOrElse {
                ToolResult.fail(
                    "search_photos_failed: ${it.message ?: it.javaClass.simpleName}. " +
                        "Ensure READ_MEDIA_IMAGES is granted (Settings → Apps → Mythara → Permissions).",
                )
            }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
}
