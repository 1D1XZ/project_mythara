package com.mythara.wear.complications

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.mythara.wear.PhoneBatteryStore

/**
 * Watch-face complication for the paired phone's battery level.
 *
 * The phone pushes its battery percent over the Wearable Data Layer;
 * [com.mythara.wear.MytharaWearDataReceiver] caches it via
 * [PhoneBatteryStore] and refreshes this service. Shown in the Mythara
 * Tactical face's top stat band next to the watch's own battery.
 */
class PhoneBatteryComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("87%") else null

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            listener.onComplicationData(null)
            return
        }
        val pct = PhoneBatteryStore.latest(this)
        listener.onComplicationData(shortText(if (pct in 0..100) "$pct%" else "--"))
    }

    private fun shortText(text: String): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("Phone battery $text").build(),
        ).build()
}
