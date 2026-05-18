package com.mythara.agent.tools

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_file` — read a file from one of Mythara's allowed
 * filesystem roots, OR a MediaStore `content://media/...` URI.
 *
 * Roots:
 *   - app `filesDir`  (private; user's notes the agent has written)
 *   - app `cacheDir`  (transient)
 *   - app `externalFilesDir(null)` (per-app external)
 *   - `/sdcard/Download/`, `/storage/emulated/0/Download/`
 *   - `/sdcard/DCIM/`, `/storage/emulated/0/DCIM/`     (camera roll)
 *   - `/sdcard/Pictures/`, `/storage/emulated/0/Pictures/`
 *   - `/sdcard/Movies/`, `/storage/emulated/0/Movies/`
 *
 * MediaStore URIs (`content://media/external/images/media/<id>`,
 * etc.) are resolved through the ContentResolver — covers
 * scoped-storage paths that aren't directly readable via
 * `File.inputStream()` on Android 11+.
 *
 * Binary files (images / audio / video, anything not UTF-8 text):
 *   - returned as `"content_b64": "<base64>"` instead of `"content"`.
 *   - `mime` tells the agent how to interpret it.
 *
 * Any path outside the allowed roots is rejected. Default max read
 * is 64 KB; agent can override up to 1 MB for binary content.
 *
 * Returns JSON: `{path, size, sha, mime, content | content_b64,
 *                 truncated, is_binary}` — the agent sees what it
 * just read and reasons about it.
 */
@Singleton
class ReadFileTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : Tool {
    override val name = "read_file"
    override val description =
        "Read a file from Mythara's allowed paths (filesDir, cacheDir, Downloads, DCIM, " +
            "Pictures, Movies) OR a content:// MediaStore URI. Returns text for UTF-8 " +
            "content, base64 for binaries (images, audio, video)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", "string")
                put("description", "Absolute file path or content:// URI. File paths must be under an allowed root.")
            })
            put("max_bytes", buildJsonObject {
                put("type", "integer")
                put("description", "Max bytes to read. Default 65536, max 1048576 (1 MB).")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val pathStr = args["path"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (pathStr.isBlank()) return ToolResult.fail("path must be non-empty")
        val maxBytes = (args["max_bytes"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 65_536)
            .coerceIn(1, 1_048_576)

        // content:// URI path — resolve via ContentResolver. The most
        // common shape the agent will see is a MediaStore image URI
        // it got back from `search_photos` / a lifeline row.
        if (pathStr.startsWith("content://")) {
            return readContentUri(Uri.parse(pathStr), maxBytes)
        }

        val file = File(pathStr).canonicalFile
        if (!isUnderAllowedRoot(file)) {
            return ToolResult.fail(
                "path_not_allowed: $pathStr — must be under filesDir, cacheDir, externalFilesDir, " +
                    "Downloads, DCIM, Pictures, or Movies. For arbitrary media URIs pass content://… instead.",
            )
        }
        if (!file.exists()) return ToolResult.fail("not_found: $pathStr")
        if (file.isDirectory) return ToolResult.fail("is_directory: use list_dir instead")
        if (!file.canRead()) return ToolResult.fail("not_readable: $pathStr")

        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
                val truncated = file.length() > maxBytes
                val mime = guessMime(file.name)
                ToolResult.ok(renderJson(file.absolutePath, file.length(), bytes, mime, truncated))
            }.getOrElse { ToolResult.fail("read_error: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    /** Read a `content://` URI via the ContentResolver. Used for
     *  MediaStore image / video URIs that don't have a directly-
     *  accessible filesystem path on Android 11+ (scoped storage). */
    private suspend fun readContentUri(uri: Uri, maxBytes: Int): ToolResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                val bytes = cr.openInputStream(uri)?.use { it.readNBytes(maxBytes) }
                    ?: return@withContext ToolResult.fail("not_readable: $uri")
                val sizeHint = runCatching {
                    cr.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: bytes.size.toLong()
                val truncated = sizeHint > 0 && sizeHint > maxBytes
                ToolResult.ok(renderJson(uri.toString(), sizeHint, bytes, mime, truncated))
            }.getOrElse {
                ToolResult.fail("read_error: ${it.message ?: it.javaClass.simpleName}")
            }
        }

    /** Format the JSON result. Text bodies go in `content`; binary
     *  bodies (image/, audio/, video/, anything non-UTF-8) go in
     *  `content_b64` so the model still gets the bytes. */
    private fun renderJson(
        path: String,
        size: Long,
        bytes: ByteArray,
        mime: String,
        truncated: Boolean,
    ): String {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val isBinary = isBinaryMime(mime) || !bytes.isLikelyUtf8()
        return if (isBinary) {
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            """{"path":"${path.escape()}","size":$size,"sha":"$sha","mime":"$mime","truncated":$truncated,"is_binary":true,"content_b64":"$b64"}"""
        } else {
            val content = String(bytes, Charsets.UTF_8)
            """{"path":"${path.escape()}","size":$size,"sha":"$sha","mime":"$mime","truncated":$truncated,"is_binary":false,"content":${jsonString(content)}}"""
        }
    }

    private fun isBinaryMime(mime: String): Boolean = when {
        mime.startsWith("image/") -> true
        mime.startsWith("audio/") -> true
        mime.startsWith("video/") -> true
        mime == "application/octet-stream" -> true
        mime == "application/pdf" -> true
        mime == "application/zip" -> true
        else -> false
    }

    /** Cheap UTF-8 sniff: any NUL byte in the first 1 KB makes us
     *  treat the file as binary even if the MIME claimed otherwise. */
    private fun ByteArray.isLikelyUtf8(): Boolean {
        val n = minOf(size, 1024)
        for (i in 0 until n) if (this[i] == 0.toByte()) return false
        return true
    }

    private fun isUnderAllowedRoot(file: File): Boolean {
        val roots = listOfNotNull(
            context.filesDir.canonicalFile,
            context.cacheDir.canonicalFile,
            context.getExternalFilesDir(null)?.canonicalFile,
            File("/sdcard/Download").canonicalFile,
            File("/storage/emulated/0/Download").canonicalFile,
            File("/sdcard/DCIM").canonicalFile,
            File("/storage/emulated/0/DCIM").canonicalFile,
            File("/sdcard/Pictures").canonicalFile,
            File("/storage/emulated/0/Pictures").canonicalFile,
            File("/sdcard/Movies").canonicalFile,
            File("/storage/emulated/0/Movies").canonicalFile,
        )
        val target = file.canonicalPath
        return roots.any { target.startsWith(it.canonicalPath) }
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "yaml", "yml" -> "application/yaml"
        "kt" -> "text/x-kotlin"
        "py" -> "text/x-python"
        "js" -> "application/javascript"
        else -> "application/octet-stream"
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")
    private fun jsonString(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
}
