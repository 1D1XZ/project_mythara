package com.mythara.minimax

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini vision backend. An optional alternative to MiniMax-VL-01 for
 * the `take_photo` tool's image-analysis pass.
 *
 * Why a separate service:
 *  - Gemini's wire format is its own thing (REST `:generateContent`,
 *    not the OpenAI-compatible `chat/completions`). Trying to share
 *    DTOs with the MiniMax path means polymorphism we don't need.
 *  - The auth model is different — query-param `?key=…` rather than
 *    a Bearer header.
 *  - Endpoint is fixed to `generativelanguage.googleapis.com`; there
 *    is no region toggle.
 *
 * The key itself is encrypted at rest via Tink in [SettingsStore]
 * exactly like the MiniMax key — same Keystore wrapping. The user
 * provides it through a separate Settings panel.
 *
 * Free tier note: Gemini API offers a generous free tier for personal
 * projects (rate-limited per-minute / per-day). The user creates a
 * key at https://aistudio.google.com/app/apikey and pastes it in.
 */
@Singleton
class GeminiVisionService @Inject constructor() {

    data class Outcome(val ok: Boolean, val text: String, val code: String? = null)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    suspend fun describeImage(
        imageFile: File,
        prompt: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
    ): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Outcome(false, "Gemini API key not set.", "missing_api_key")
        }
        if (!imageFile.exists() || imageFile.length() == 0L) {
            return@withContext Outcome(false, "Image file missing or empty.", "no_image")
        }
        // Downsample on decode + re-encode as JPEG so what we send
        // Gemini is bounded. Without this, every modern phone photo
        // (~8 MB raw JPEG) crashed the request against the size cap
        // and the bulk re-caption walker silently fell through to
        // MiniMax-VL — which usually didn't have a key either, so
        // the row was marked FAILED. Same pattern GemmaVisionService
        // already uses; we mirror it here so the cloud path enjoys
        // the same headroom.
        val bytes = runCatching { downsampleToJpeg(imageFile) }.getOrElse {
            return@withContext Outcome(false, "Couldn't decode image: ${it.message}", "decode_failed")
        } ?: return@withContext Outcome(false, "Couldn't decode image (null bitmap).", "decode_failed")
        if (bytes.size > MAX_BYTES) {
            // After downsampling, this should never trigger for a
            // normal phone photo — but keep the guard as belt-and-
            // suspenders for pathological inputs (panoramas etc.).
            return@withContext Outcome(
                false,
                "Image too large after downsample (${bytes.size} bytes), capped at $MAX_BYTES.",
                "image_too_large",
            )
        }
        Log.v(TAG, "downsampled ${imageFile.length()}B → ${bytes.size}B for Gemini")
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val body = GenerateContentRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = prompt),
                        GeminiPart(
                            inlineData = GeminiInlineData(mimeType = "image/jpeg", data = b64),
                        ),
                    ),
                ),
            ),
            generationConfig = GenerationConfig(
                temperature = 0.4,
                maxOutputTokens = MAX_RESPONSE_TOKENS,
            ),
        )
        val bodyJson = runCatching { json.encodeToString(GenerateContentRequest.serializer(), body) }
            .getOrElse {
                return@withContext Outcome(false, "Couldn't serialise request: ${it.message}", "serialise")
            }

        val url = "$BASE_URL/v1beta/models/$model:generateContent?key=$apiKey"
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val result = runCatching { http.newCall(req).execute() }
        if (result.isFailure) {
            val e = result.exceptionOrNull()
            Log.w(TAG, "Gemini call threw", e)
            return@withContext Outcome(false, e?.message ?: "network failure", "network")
        }
        val response = result.getOrThrow()
        response.use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                Log.w(TAG, "Gemini ${res.code}: ${raw.take(400)}")
                val parsedMsg = runCatching {
                    json.decodeFromString(GeminiErrorEnvelope.serializer(), raw).error?.message
                }.getOrNull()
                return@withContext Outcome(
                    false,
                    parsedMsg ?: "HTTP ${res.code}",
                    code = "http_${res.code}",
                )
            }
            val parsed = runCatching {
                json.decodeFromString(GenerateContentResponse.serializer(), raw)
            }.getOrNull()
            val text = parsed
                ?.candidates
                ?.firstOrNull()
                ?.content
                ?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                ?.trim()
                ?.ifBlank { null }
            if (text.isNullOrBlank()) {
                return@withContext Outcome(false, "Empty response.", "empty")
            }
            Outcome(ok = true, text = text)
        }
    }

    /**
     * Cheap one-shot key validity probe. Hits `:generateContent` with a
     * minimal prompt — same surface the vision path will use, so we
     * verify both that the key is valid AND that the model is
     * reachable for THIS account (some accounts gate vision behind
     * billing setup).
     */
    suspend fun validate(apiKey: String, model: String = DEFAULT_MODEL): Outcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Outcome(false, "Empty key.", "empty")
        val body = GenerateContentRequest(
            contents = listOf(
                GeminiContent(parts = listOf(GeminiPart(text = "Reply with the single word: ok"))),
            ),
            generationConfig = GenerationConfig(temperature = 0.0, maxOutputTokens = 8),
        )
        val bodyJson = json.encodeToString(GenerateContentRequest.serializer(), body)
        val req = Request.Builder()
            .url("$BASE_URL/v1beta/models/$model:generateContent?key=$apiKey")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val res = runCatching { http.newCall(req).execute() }.getOrElse {
            return@withContext Outcome(false, it.message ?: "network failure", "network")
        }
        res.use {
            if (it.isSuccessful) return@withContext Outcome(true, "key OK · model $model reachable")
            val raw = it.body?.string().orEmpty()
            val msg = runCatching {
                json.decodeFromString(GeminiErrorEnvelope.serializer(), raw).error?.message
            }.getOrNull()
            Outcome(false, msg ?: "HTTP ${it.code}", "http_${it.code}")
        }
    }

    // ---------- wire format DTOs (Gemini-specific) ----------

    @Serializable
    private data class GenerateContentRequest(
        val contents: List<GeminiContent>,
        val generationConfig: GenerationConfig? = null,
    )

    @Serializable
    private data class GeminiContent(
        val role: String? = null,
        val parts: List<GeminiPart>,
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null,
        val inlineData: GeminiInlineData? = null,
    )

    @Serializable
    private data class GeminiInlineData(
        val mimeType: String,
        val data: String,
    )

    @Serializable
    private data class GenerationConfig(
        val temperature: Double? = null,
        val maxOutputTokens: Int? = null,
    )

    @Serializable
    private data class GenerateContentResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null,
        val finishReason: String? = null,
    )

    @Serializable
    private data class GeminiErrorEnvelope(
        val error: GeminiError? = null,
    )

    @Serializable
    private data class GeminiError(
        val code: Int? = null,
        val message: String? = null,
        val status: String? = null,
    )

    companion object {
        private const val TAG = "Mythara/Gemini"
        private const val BASE_URL = "https://generativelanguage.googleapis.com"

        /**
         * Default to Gemini 2.5 Flash — fast, cheap, good vision, the
         * recommended target for free-tier vision workloads as of
         * May 2026. Users can change via the Settings UI once we
         * surface a model picker (out of scope for the first pass).
         */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Post-downsample size cap. 8 MB is generous given we
         *  downsample to MAX_LONG_EDGE_PX first — typical phone
         *  photos land at 100-200 KB after the resize. */
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_RESPONSE_TOKENS = 256

        /** Long edge of the downsampled JPEG we send to Gemini.
         *  Matches GemmaVisionService's choice — Gemini's vision
         *  tokeniser works well at ≤ 1024 px on the long edge and
         *  larger inputs waste tokens without improving the
         *  caption noticeably. */
        private const val MAX_LONG_EDGE_PX = 1024
        private const val JPEG_QUALITY = 85
    }

    /** Decode the source image, downsample so the long edge is
     *  ≤ [MAX_LONG_EDGE_PX], re-encode at [JPEG_QUALITY] JPEG. Bounds
     *  the byte buffer we ship as inline base64 in the Gemini request. */
    private fun downsampleToJpeg(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (srcLong <= 0) return null
        var sample = 1
        while (srcLong / sample > MAX_LONG_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}
