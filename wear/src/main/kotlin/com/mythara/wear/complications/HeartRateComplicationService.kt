package com.mythara.wear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.mythara.wear.MainActivity
import com.mythara.wear.WatchHrStore

/**
 * Watch-face complication for the wearer's most recent live heart-
 * rate sample.
 *
 * Reads from [WatchHrStore], which is updated every time
 * [com.mythara.wear.HeartRateService.onDataReceived] receives a
 * fresh Health Services BPM data point. Surface convention:
 *
 *   - fresh sample (within 3 min)  → "♥ 72"
 *   - no fresh sample              → "♥ --"
 *
 * The same store also feeds the in-app PttScreen HR readout so the
 * face + the app stay in lock-step.
 *
 * Self-refreshes every 60 s — slow enough to be battery-friendly,
 * fast enough that a noticeable BPM change (sit → stand → walk)
 * shows up on the next minute boundary.
 */
class HeartRateComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("♥ 72") else null

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            listener.onComplicationData(null)
            return
        }
        val bpm = if (WatchHrStore.isFresh(this)) WatchHrStore.latestBpm(this) else null
        val text = if (bpm != null) "♥ $bpm" else "♥ --"
        listener.onComplicationData(shortText(text))
    }

    private fun shortText(text: String): ComplicationData {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("Heart rate $text").build(),
        ).setTapAction(tap).build()
    }
}
