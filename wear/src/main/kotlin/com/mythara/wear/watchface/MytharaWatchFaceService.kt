package com.mythara.wear.watchface

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Minimal Mythara-branded digital watch face.
 *
 * Visual: Bg (#201F26) fill, big monospace HH:MM in the centre, the
 * MYTHARA wordmark below in a Charple → Bok horizontal gradient, a
 * tiny date below that in FgDim. Mirrors the splash + chat-header
 * brand of the mobile app so the wrist surface reads as "this is
 * still Mythara" at a glance.
 *
 * Why bare CanvasRenderer: nothing fancy — no complications, no
 * editor, no styles. A watch face should sip battery; this one only
 * paints text + one gradient stroke per frame. Updates once per
 * minute in ambient mode (the framework gates that), once per second
 * in interactive mode.
 *
 * Customisation / complications are out of scope for v1. The chat
 * surface + dashboard live in the phone-paired companion app — the
 * watch face is just "the wallpaper of the wrist."
 */
class MytharaWatchFaceService : WatchFaceService() {

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = MytharaRenderer(
            context = this,
            surfaceHolder = surfaceHolder,
            currentUserStyleRepository = currentUserStyleRepository,
            watchState = watchState,
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer)
    }
}

private class MytharaRenderer(
    context: android.content.Context,
    surfaceHolder: SurfaceHolder,
    currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
) : Renderer.CanvasRenderer2<MytharaRenderer.SharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    /* interactiveDrawModeUpdateDelayMillis = */ 1_000L,
    /* clearWithBackgroundTintBeforeRenderingHighlightLayer = */ false,
) {
    /** SharedAssets is rebuilt only on surface change. Cheap, but we
     *  cache paints / typefaces here to avoid per-frame allocation. */
    class SharedAssets(
        val bgPaint: Paint,
        val timePaint: Paint,
        val datePaint: Paint,
        val wordmarkPaint: Paint,
    ) : Renderer.SharedAssets {
        override fun onDestroy() {}
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE MMM d", Locale.getDefault())
    private val textBounds = Rect()

    override suspend fun createSharedAssets(): SharedAssets {
        val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val bg = Paint().apply { color = Color.parseColor("#FF201F26") }
        val time = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#FFDFDBDD")
            textAlign = Paint.Align.CENTER
            typeface = mono
        }
        val date = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#FF605F6B")
            textAlign = Paint.Align.CENTER
            typeface = mono
        }
        val wordmark = Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = mono
            letterSpacing = 0.25f
        }
        return SharedAssets(bg, time, date, wordmark)
    }

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: SharedAssets) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        // Background. In ambient mode the system shoulders the burn-in
        // tax — we still paint a flat fill rather than a wallpaper.
        canvas.drawColor(Color.parseColor("#FF201F26"))

        val isAmbient = renderParameters.drawMode == androidx.wear.watchface.DrawMode.AMBIENT
        // In ambient, drop colour saturation to greys to spare OLED.
        if (isAmbient) {
            sharedAssets.timePaint.color = Color.parseColor("#FFDFDBDD")
            sharedAssets.datePaint.color = Color.parseColor("#FF605F6B")
        }

        // Time — HH:MM, fills ~36% of the smaller dimension.
        val timeStr = timeFormat.format(java.util.Date(zonedDateTime.toInstant().toEpochMilli()))
        val timeSize = (minOf(w, h) * 0.36f)
        sharedAssets.timePaint.textSize = timeSize
        sharedAssets.timePaint.getTextBounds(timeStr, 0, timeStr.length, textBounds)
        val timeY = cy - textBounds.exactCenterY()
        canvas.drawText(timeStr, cx, timeY, sharedAssets.timePaint)

        // Wordmark — Charple → Bok gradient horizontally, ~12% size,
        // below the time. Wallpaper-quiet in ambient (dim grey).
        val wordSize = (minOf(w, h) * 0.105f)
        sharedAssets.wordmarkPaint.textSize = wordSize
        val wordY = timeY + textBounds.height() * 0.55f + wordSize * 1.4f
        if (isAmbient) {
            sharedAssets.wordmarkPaint.color = Color.parseColor("#FFA8A4AB")
            sharedAssets.wordmarkPaint.shader = null
        } else {
            // Width of the wordmark in pixels (rough — Paint.measureText
            // is cheap and the gradient just needs the right span).
            val wordWidth = sharedAssets.wordmarkPaint.measureText("MYTHARA")
            sharedAssets.wordmarkPaint.shader = LinearGradient(
                cx - wordWidth / 2, 0f,
                cx + wordWidth / 2, 0f,
                Color.parseColor("#FF6B50FF"), // Charple
                Color.parseColor("#FF68FFD6"), // Bok
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawText("MYTHARA", cx, wordY, sharedAssets.wordmarkPaint)

        // Date — small dim line under the wordmark.
        val dateSize = (minOf(w, h) * 0.065f)
        sharedAssets.datePaint.textSize = dateSize
        val dateStr = dateFormat.format(java.util.Date(zonedDateTime.toInstant().toEpochMilli()))
        val dateY = wordY + dateSize * 1.9f
        canvas.drawText(dateStr, cx, dateY, sharedAssets.datePaint)
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: SharedAssets,
    ) {
        // No complications + no interactive elements → highlight layer
        // is intentionally empty. The framework still calls this on
        // an editor session; leaving it blank is correct.
    }
}
