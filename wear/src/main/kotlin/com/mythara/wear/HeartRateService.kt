package com.mythara.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.SampleDataPoint
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that samples the watch's heart-rate sensor via the
 * Wear OS [androidx.health.services.client.HealthServices] API and pushes:
 *
 *  - the latest reading to the phone every ~3 minutes over
 *    [WearPaths.HEART_RATE] (slow baseline; fed into the phone's
 *    "About Me" analytics alongside the rest of Health Connect),
 *  - and, while a Resonance Mode session is active, the latest reading
 *    every ~1 s over [WearPaths.RESONANCE_HR] (fast stream; fed into
 *    the phone's `ResonanceHrStore` for the analyzer + closed loop).
 *
 * **Why Health Services and not SensorManager?** The legacy
 * `SensorManager.TYPE_HEART_RATE` path silently fires nothing on most
 * Samsung Galaxy Watches — `BODY_SENSORS` is granted, the sensor
 * exists, registration succeeds, and `onSensorChanged` is just never
 * called. The supported, Galaxy-Watch-compatible API is Wear OS Health
 * Services' [MeasureClient.registerMeasureCallback], which delivers
 * HR points through [MeasureCallback.onDataReceived] and is also the
 * API the system's HR complications use.
 *
 * Runs foreground (minimal ongoing notification) so sampling continues
 * while the watch app isn't on screen — the point is regular, spaced
 * capture, not just app-session capture.
 */
class HeartRateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Most recent valid bpm reading from Health Services, or -1 until
     *  the watch's sensor has produced its first sample. */
    @Volatile private var latestBpm: Int = -1

    /** Health Services measure-client — owns the HR subscription. */
    private var measureClient: MeasureClient? = null

    /** True while [measureCallback] is registered with [measureClient].
     *  Used to keep `register` / `unregister` calls balanced. */
    @Volatile private var measureRegistered: Boolean = false

    /** True while the Resonance fast-stream push loop is running. */
    @Volatile private var streaming: Boolean = false

    /** Fast push job — set while streaming, cancelled when streaming
     *  stops. The slow [PUSH_INTERVAL_MS] loop in onCreate keeps
     *  running independently. */
    private var streamJob: Job? = null

    /** The Health Services callback. We keep it as a single instance so
     *  unregister knows which subscription to drop. */
    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability,
        ) {
            // Galaxy Watch sometimes flips ACQUIRING → AVAILABLE only
            // after the user's wrist makes good contact. Logging this
            // makes "no readings yet" debuggable instead of silent.
            val s = (availability as? DataTypeAvailability)?.name ?: availability.toString()
            Log.d(TAG, "HR availability: $s")
        }

        override fun onDataReceived(data: DataPointContainer) {
            val points = data.getData(DataType.HEART_RATE_BPM)
            // We only care about the freshest sample; the API may
            // batch a few in one delivery.
            val freshest = points.maxByOrNull { it.timeDurationFromBoot.toMillis() }
                ?: return
            val bpm = freshest.value.toInt()
            if (bpm in VALID_BPM) {
                latestBpm = bpm
                latestSampleCount++
                // Persist for the in-app HR readout + the watch-face
                // HeartRateComplicationService. Same store the
                // PttScreen reads from, so the live BPM visible in
                // the app matches the complication on the face.
                WatchHrStore.save(this@HeartRateService, bpm)
                if (latestSampleCount % 5 == 1) {
                    Log.d(TAG, "HR reading $bpm bpm (sample #$latestSampleCount)")
                }
            }
        }
    }
    @Volatile private var latestSampleCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        measureClient = HealthServices.getClient(this).measureClient
        registerHrSubscriptionInternal()
        // Push the latest reading on a slow timer — spaced capture, not
        // a firehose of every sensor sample.
        scope.launch {
            while (true) {
                delay(PUSH_INTERVAL_MS)
                pushLatest()
            }
        }
    }

    // NOT sticky: a background auto-restart would hit the Android 12+
    // background-FGS-start ban and crash-loop. The activity re-starts
    // it from onResume() instead, which is a guaranteed-foreground point.
    //
    // The streaming state is a tri-state on the wire: extra absent →
    // "just keep me alive, don't touch the mode" (used by the
    // plain `start()` from MainActivity.onResume); extra present + true
    // → start streaming; extra present + false → stop streaming.
    // Without this, every onResume would kill an active Resonance HR
    // stream because `start()` was overloaded with a default-false flag.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_STREAMING) == true) {
            val wantStreaming = intent.getBooleanExtra(EXTRA_STREAMING, false)
            if (wantStreaming) startStreamingInternal() else stopStreamingInternal()
        }
        return START_NOT_STICKY
    }

    /**
     * Register the Health Services HR callback. Idempotent. The
     * subscription stays active for the lifetime of the service —
     * Health Services itself manages the underlying sensor's
     * duty-cycle, so leaving it on is fine for battery on Wear OS.
     */
    private fun registerHrSubscriptionInternal() {
        if (measureRegistered) return
        val client = measureClient ?: run {
            Log.w(TAG, "no Health Services MeasureClient; HR unavailable")
            return
        }
        runCatching {
            client.registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
        }.onSuccess {
            measureRegistered = true
            Log.d(TAG, "Health Services HR subscription registered")
        }.onFailure {
            Log.w(TAG, "Health Services HR registration failed: ${it.message}")
        }
    }

    private fun unregisterHrSubscriptionInternal() {
        if (!measureRegistered) return
        // Note: we deliberately do NOT call
        // `MeasureClient.unregisterMeasureCallbackAsync` here — its
        // return type is `ListenableFuture` which would drag in a
        // guava (or concurrent-futures) dep just for an unregister
        // we only need on process tear-down. Health Services drops
        // the subscription automatically when the registering
        // process dies; the foreground service is the only thing
        // holding it alive, and it stops with the process.
        measureRegistered = false
        Log.d(TAG, "Health Services HR subscription will drop with service")
    }

    /**
     * Start the Resonance fast-stream push loop. Idempotent. Health
     * Services delivers HR samples on its own schedule (~1 Hz on
     * recent Galaxy Watches); the loop just samples [latestBpm] and
     * pushes it over [WearPaths.RESONANCE_HR].
     */
    private fun startStreamingInternal() {
        if (streamJob?.isActive == true) {
            Log.d(TAG, "streaming already active; skipping")
            return
        }
        // Defensive: re-register in case the subscription was dropped
        // for any reason (Health Services unavailable at boot, etc.).
        registerHrSubscriptionInternal()
        streamPushCount = 0
        lastNoReadingLogMs = 0L
        lastNoNodesLogMs = 0L
        streaming = true
        Log.d(TAG, "resonance HR stream starting (latestBpm=$latestBpm, registered=$measureRegistered)")
        streamJob = scope.launch {
            while (isActive) {
                delay(STREAM_INTERVAL_MS)
                pushStreamSample()
            }
        }
    }

    /** Stop the fast push loop. Leaves the HR subscription active so
     *  the slow 3-min push keeps working. */
    private fun stopStreamingInternal() {
        if (streamJob?.isActive != true) return
        Log.d(TAG, "resonance HR stream stopping")
        streamJob?.cancel()
        streamJob = null
        streaming = false
    }

    private fun pushStreamSample() {
        val bpm = latestBpm
        if (bpm !in VALID_BPM) {
            // Surface this every ~5s so a "no readings yet" condition
            // is debuggable instead of silent.
            val now = System.currentTimeMillis()
            if (now - lastNoReadingLogMs > 5_000L) {
                Log.d(TAG, "stream tick — no valid HR yet (latest=$bpm, registered=$measureRegistered)")
                lastNoReadingLogMs = now
            }
            return
        }
        streamPushCount++
        if (streamPushCount % 10 == 1) {
            Log.d(TAG, "streaming HR $bpm bpm (#$streamPushCount)")
        }
        val payload = "$bpm|${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(this)
        val msgClient = Wearable.getMessageClient(this)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastNoNodesLogMs > 10_000L) {
                        Log.w(TAG, "stream tick — NO connected phone nodes")
                        lastNoNodesLogMs = now
                    }
                    return@addOnSuccessListener
                }
                for (node in nodes) msgClient.sendMessage(node.id, WearPaths.RESONANCE_HR, payload)
            }
            .addOnFailureListener { e -> Log.w(TAG, "stream HR push failed: ${e.message}") }
    }

    @Volatile private var streamPushCount = 0
    @Volatile private var lastNoReadingLogMs = 0L
    @Volatile private var lastNoNodesLogMs = 0L

    private fun pushLatest() {
        val bpm = latestBpm
        if (bpm !in VALID_BPM) {
            Log.d(TAG, "no valid HR reading yet; skipping push")
            return
        }
        val bytes = bpm.toString().toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(this)
        val msgClient = Wearable.getMessageClient(this)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) msgClient.sendMessage(node.id, WearPaths.HEART_RATE, bytes)
                if (nodes.isNotEmpty()) Log.d(TAG, "pushed HR $bpm bpm to ${nodes.size} node(s)")
            }
            .addOnFailureListener { e -> Log.w(TAG, "HR push failed: ${e.message}") }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Heart-rate monitor",
                        NotificationManager.IMPORTANCE_MIN,
                    ),
                )
            }
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mythara")
            .setContentText("monitoring heart rate")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterHrSubscriptionInternal()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Mythara/HeartRate"
        private const val CHANNEL_ID = "mythara_heart_rate"
        private const val NOTIF_ID = 0x4842
        private const val PUSH_INTERVAL_MS = 3L * 60 * 1000
        /** Resonance Mode fast-stream cadence — ~1Hz. */
        private const val STREAM_INTERVAL_MS = 1_000L
        private val VALID_BPM = 30..240

        /** Intent extra: when true, the service runs in fast-stream
         *  mode for a Resonance session (1Hz push on RESONANCE_HR). */
        private const val EXTRA_STREAMING = "stream"

        /** Start (or re-attach to) the slow 3-min HR push baseline.
         *  Does NOT touch the streaming mode — calling this from
         *  `MainActivity.onResume` must not knock an active Resonance
         *  HR stream back to the slow baseline. */
        fun start(ctx: Context) = launchSelf(ctx, streaming = null)

        /** Bump the running service into fast-stream mode for the
         *  duration of a Resonance session. Idempotent. */
        fun startStreaming(ctx: Context) = launchSelf(ctx, streaming = true)

        /** Drop fast-stream mode back to the slow baseline. */
        fun stopStreaming(ctx: Context) = launchSelf(ctx, streaming = false)

        private fun launchSelf(ctx: Context, streaming: Boolean?) {
            val intent = Intent(ctx, HeartRateService::class.java)
            if (streaming != null) intent.putExtra(EXTRA_STREAMING, streaming)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
