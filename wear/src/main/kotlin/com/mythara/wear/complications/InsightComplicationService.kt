package com.mythara.wear.complications

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.mythara.wear.InsightStore
import com.mythara.wear.MainActivity

/**
 * Watch-face complication that surfaces the latest Mythara insight.
 *
 * The phone composes a health-driven insight (or an important
 * notification line) and pushes it over the Wearable Data Layer;
 * [com.mythara.wear.MytharaWearDataReceiver] caches it via
 * [InsightStore] and asks the complication system to refresh this
 * service, so the watch face's insight slot updates in near-real-time.
 *
 * Tapping the complication opens the PTT app.
 */
class InsightComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complicationFor(type, "Mythara: cluster nominal")

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        val text = InsightStore.latest(this).ifBlank { "Mythara online" }
        listener.onComplicationData(complicationFor(request.complicationType, text))
    }

    private fun complicationFor(type: ComplicationType, text: String): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(20)).build(),
                contentDescription = PlainComplicationText.Builder(text).build(),
            ).setTapAction(tap).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(text.take(80)).build(),
                contentDescription = PlainComplicationText.Builder(text).build(),
            ).setTapAction(tap).build()

            else -> null
        }
    }
}
