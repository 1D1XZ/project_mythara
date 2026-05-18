package com.mythara.agent.tools

import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.lifeline.LifelineCaptionStatus
import com.mythara.lifeline.LifelineCaptioner
import com.mythara.lifeline.LifelineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `caption_lifeline_photo` — (re-)caption a single photo from the
 * Lifeline (the device's photo timeline). The agent calls this when
 * the user says "caption that photo again", "tell me what's in this
 * picture", or asks to add context to an existing caption.
 *
 * Wraps the same [LifelineCaptioner.captionOne] the photo-arrival
 * worker uses, so the model gets the production captioning pipeline
 * (Gemma vision → Gemini cloud → MiniMax-VL cascade, EXIF-derived
 * prompt, face-detection metadata, the works) for free.
 *
 * Why this exists: without a dedicated tool, the model improvises
 * with `render_canvas` / `generate_image` / `spawn_agent` (literally
 * what it did the first time the user asked) because none of those
 * names match "caption a photo" semantically — but a `caption_*`
 * tool is unambiguous.
 *
 * Args:
 *   - `lifeline_id` (preferred): the integer row id from a previous
 *     `search_photos` result. Passing this is the canonical path.
 *   - `latest` (boolean): if true and `lifeline_id` is omitted,
 *     captions the single most-recently-added lifeline row. Useful
 *     for "caption the photo I just took".
 *   - `context` (string, optional): extra user-supplied context to
 *     fold into the caption prompt (e.g. "this was at the café with
 *     Sam"). When omitted, the row's persisted `userContext` is used.
 *
 * Returns JSON:
 *   {"ok": true, "lifeline_id": 1234, "caption": "...",
 *    "model": "gemini-2.0-flash", "took_ms": 2843}
 *   {"ok": false, "error": "not_found"|"no_pending_photos"|...,
 *    "detail": "..."}
 */
@Singleton
class CaptionLifelinePhotoTool @Inject constructor(
    private val repo: LifelineRepository,
    private val captioner: LifelineCaptioner,
) : Tool {
    override val name: String = "caption_lifeline_photo"

    override val description: String =
        "(Re-)caption a single photo from the user's Lifeline timeline using the " +
            "same vision cascade the automatic photo-arrival worker uses. " +
            "Pass `lifeline_id` from a prior search_photos result, OR `latest=true` " +
            "to caption the most recently added photo. Optional `context` injects " +
            "user-supplied detail into the caption prompt. Use this for ANY " +
            "\"caption that photo / tell me what's in this picture / re-caption with X\" request."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("lifeline_id", buildJsonObject {
                put("type", "integer")
                put("description", "Row id from a prior search_photos / lifeline result. Preferred over `latest`.")
            })
            put("latest", buildJsonObject {
                put("type", "boolean")
                put("description", "If true and lifeline_id is omitted, caption the most-recently added local photo.")
            })
            put("context", buildJsonObject {
                put("type", "string")
                put("description", "Optional user-supplied context to fold into the caption prompt (e.g. 'this was at the café with Sam').")
            })
        })
        // Neither lifeline_id nor latest are required individually —
        // the executor falls back to `latest=true` when both are
        // omitted so calling `caption_lifeline_photo()` with no
        // args still does the obvious thing.
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val explicitId = args["lifeline_id"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
        val latest = args["latest"]?.jsonPrimitive?.contentOrNull()?.toBooleanStrictOrNull() ?: false
        val ctxArg = args["context"]?.jsonPrimitive?.contentOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val row = try {
            when {
                explicitId != null -> repo.dao.byId(explicitId)
                    ?: return@withContext ToolResult.fail("not_found: no lifeline row with id=$explicitId")
                else -> {
                    // `latest` (or no args at all): pick the newest
                    // local row. We never auto-pick a remote (peer-
                    // synced) row because we can't read those bytes
                    // from MediaStore on this device.
                    val recent = repo.dao.listRecent(limit = 10)
                        .firstOrNull { !it.isRemote && !it.isDeleted }
                        ?: return@withContext ToolResult.fail(
                            "no_local_photos: no recent locally-captured lifeline rows found." +
                                if (latest) "" else " Pass `latest=true` or a specific lifeline_id.",
                        )
                    recent
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "lookup failed: ${e.message}")
            return@withContext ToolResult.fail("lookup_error: ${e.message ?: e.javaClass.simpleName}")
        }

        if (row.isRemote) {
            return@withContext ToolResult.fail(
                "remote_row: lifeline_id=${row.id} was synced from another device — its bytes aren't on this phone.",
            )
        }
        if (row.isDeleted) {
            return@withContext ToolResult.fail(
                "deleted_row: lifeline_id=${row.id} was deleted from gallery.",
            )
        }

        val start = System.currentTimeMillis()
        val ok = runCatching { captioner.captionOne(row, additionalContext = ctxArg) }
            .getOrElse {
                Log.w(TAG, "captionOne threw for id=${row.id}: ${it.message}")
                return@withContext ToolResult.fail(
                    "caption_error: ${it.message ?: it.javaClass.simpleName}",
                )
            }
        val took = System.currentTimeMillis() - start

        // captionOne mutates the row; re-read to surface the updated
        // caption + model + status to the agent in one round-trip.
        val updated = repo.dao.byId(row.id)
        val captionText = updated?.captionText.orEmpty()
        val captionModel = updated?.captionModel.orEmpty()
        val status = updated?.captionStatus.orEmpty()

        if (!ok || captionText.isBlank()) {
            return@withContext ToolResult.fail(
                """{"ok":false,"lifeline_id":${row.id},"status":"$status","took_ms":$took,"error":"caption_failed","detail":"vision cascade returned empty for ${row.uri}"}""",
            )
        }

        ToolResult.ok(
            """{"ok":true,"lifeline_id":${row.id},"status":"${LifelineCaptionStatus.CAPTIONED.name}","caption":${jsonString(captionText)},"model":"$captionModel","took_ms":$took${ctxArg?.let { ""","context_used":${jsonString(it)}""" } ?: ""}}""",
        )
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    companion object {
        private const val TAG = "Mythara/CaptionPhoto"
    }
}
