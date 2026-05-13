package com.mythara.secret.observe.vosk

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Mythara's local copy of the Vosk small-English speech model.
 * The model isn't bundled in the APK (it's 40MB+); first time the user
 * enables Observe we fetch it from alphacephei.com over HTTPS, stream
 * to a temp zip, and extract into a stable known path.
 *
 * On-device path:
 *   filesDir/vosk-models/en-us-small/{am,conf,graph,ivector,README}
 *
 * Idempotent — call ensureReady() any number of times; if files are
 * already in place, the method short-circuits to Ready.
 *
 * Observable state is exposed via [state] so the UI can render a
 * progress bar during the download. Transitions:
 *   Missing → Downloading(bytes/total) → Extracting → Ready
 *   any → Failed(message)
 */
@Singleton
class VoskModelStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val bytes: Long, val total: Long) : State {
            val pct: Int get() = if (total > 0) ((bytes * 100) / total).toInt() else 0
        }
        data object Extracting : State
        data class Ready(val path: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(if (isExtracted()) State.Ready(modelDir.absolutePath) else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val modelsRoot: File get() = ctx.filesDir.resolve("vosk-models")
    val modelDir: File get() = modelsRoot.resolve("en-us-small")
    private val zipTmp: File get() = ctx.cacheDir.resolve("vosk-model-small-en-us-0.15.zip")

    fun isReady(): Boolean = _state.value is State.Ready
    fun isExtracted(): Boolean = modelDir.resolve("am").exists() && modelDir.resolve("conf").exists()
    fun pathOrNull(): String? = if (isExtracted()) modelDir.absolutePath else null

    suspend fun ensureReady(): State {
        if (isExtracted()) {
            _state.value = State.Ready(modelDir.absolutePath)
            return _state.value
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                download()
                extract()
                cleanupZip()
                State.Ready(modelDir.absolutePath).also { _state.value = it }
            }.getOrElse { e ->
                Log.e(TAG, "model fetch failed: ${e.message}", e)
                State.Failed(e.message ?: e.javaClass.simpleName).also { _state.value = it }
            }
        }
    }

    fun forgetModel() {
        runCatching {
            if (modelDir.exists()) modelDir.deleteRecursively()
            if (zipTmp.exists()) zipTmp.delete()
        }
        _state.value = State.Missing
    }

    private fun download() {
        if (zipTmp.exists() && zipTmp.length() > MIN_ZIP_BYTES) {
            Log.d(TAG, "cached zip already present (${zipTmp.length()}b); skipping download")
            return
        }
        modelsRoot.mkdirs()
        ctx.cacheDir.mkdirs()
        if (zipTmp.exists()) zipTmp.delete()

        val req = Request.Builder().url(MODEL_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} while fetching Vosk model")
            val body = resp.body ?: error("empty body fetching Vosk model")
            val total = body.contentLength().coerceAtLeast(0L)
            _state.value = State.Downloading(0, total)
            body.byteStream().use { input ->
                FileOutputStream(zipTmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReportMs = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastReportMs > PROGRESS_REPORT_MS) {
                            lastReportMs = now
                            _state.update { State.Downloading(read, total) }
                        }
                    }
                }
            }
            Log.d(TAG, "downloaded ${zipTmp.length()} bytes to ${zipTmp.absolutePath}")
        }
    }

    private fun extract() {
        _state.value = State.Extracting
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()

        ZipInputStream(zipTmp.inputStream().buffered()).use { zin ->
            // The zip contains a top-level folder like "vosk-model-small-en-us-0.15/".
            // We want to strip that prefix so files land under modelDir/ directly.
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                val stripped = name.substringAfter('/', missingDelimiterValue = name)
                if (stripped.isBlank()) {
                    zin.closeEntry(); continue
                }
                val out = modelDir.resolve(stripped)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
            }
        }
        Log.d(TAG, "extracted model to ${modelDir.absolutePath}")
    }

    private fun cleanupZip() {
        if (zipTmp.exists()) zipTmp.delete()
    }

    companion object {
        private const val TAG = "Mythara/Vosk"
        // Official small English model — ~40MB. Generated by Alpha Cephei,
        // released under Apache 2.0. We pin to a specific version so
        // unexpected schema changes don't break us.
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MIN_ZIP_BYTES = 20 * 1024 * 1024L // sanity floor; the file is ~40MB
        private const val PROGRESS_REPORT_MS = 250L
    }
}
