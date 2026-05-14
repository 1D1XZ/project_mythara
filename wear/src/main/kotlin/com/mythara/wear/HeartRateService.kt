package com.mythara.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that samples the watch's heart-rate sensor and
 * pushes the latest reading to the phone every ~3 minutes over the
 * Wearable Data Layer ([WearPaths.HEART_RATE]).
 *
 * The phone files each reading into the health memory pipeline so the
 * "About Me" analytics can use it alongside the rest of the Health
 * Connect data. Runs foreground (minimal ongoing notification) so
 * sampling continues while the watch app isn't on screen — the point
 * is regular, spaced-out capture, not just app-session capture.
 */
class HeartRateService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sensorManager: SensorManager? = null

    /** Most recent valid bpm reading, or -1 until the sensor warms up. */
    @Volatile private var latestBpm: Int = -1

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        sensorManager = (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.also { sm ->
            val hr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (hr != null) {
                sm.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                Log.w(TAG, "no heart-rate sensor on this device")
            }
        }
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onSensorChanged(event: SensorEvent?) {
        val bpm = event?.values?.firstOrNull()?.toInt() ?: return
        if (bpm in VALID_BPM) latestBpm = bpm
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

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
        runCatching { sensorManager?.unregisterListener(this) }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "Mythara/HeartRate"
        private const val CHANNEL_ID = "mythara_heart_rate"
        private const val NOTIF_ID = 0x4842
        private const val PUSH_INTERVAL_MS = 3L * 60 * 1000
        private val VALID_BPM = 30..240

        fun start(ctx: Context) {
            val intent = Intent(ctx, HeartRateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
