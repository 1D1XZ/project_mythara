package com.mythara.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Continuous pickup / orientation detector. Replaces the previous
 * one-shot TYPE_SIGNIFICANT_MOTION trigger because that fires once,
 * gives an 8 s window, then needs the user to physically jolt the
 * phone again before the camera re-activates. The new detector
 * keeps watching low-power motion sensors and the orientation
 * estimate so the camera is active **whenever the phone is in a
 * picked-up state** — held in hand, tilted toward the face — and
 * deactivates only after the phone has been at rest for
 * [REST_GRACE_MS] continuously.
 *
 * Signals fused:
 *
 *   - `TYPE_GRAVITY` (or `TYPE_ACCELEROMETER` fallback) → tilt angle
 *     of the phone from horizontal. Flat-on-a-desk reads ~0°;
 *     upright-in-hand reads 40-90°.
 *
 *   - `TYPE_GYROSCOPE` → rotational rate magnitude. > 0.10 rad/s
 *     for at least one sample within the last [MOTION_HOLD_MS]
 *     counts as "actively being moved".
 *
 * State:
 *   active ⇔ (tilt > [TILT_HELD_DEG]) OR (recent motion within
 *            [MOTION_HOLD_MS])
 *   at-rest ⇔ tilt ≤ TILT_HELD_DEG AND no recent motion
 *
 * Once at-rest persists for [REST_GRACE_MS], the activeWindow flips
 * false → false propagates to FaceMesh + the FaceTracker bind/unbind.
 * Tilting the phone back up or moving it immediately flips it true
 * again — no re-arm step.
 *
 * Sensor delay: `SENSOR_DELAY_NORMAL` (≈ 200 ms cadence). The
 * accelerometer + gyro at that cadence draw ≈ 1 mW combined — orders
 * of magnitude less than CameraX streaming, so leaving the listeners
 * registered while the screen is open costs essentially nothing.
 *
 * Falls back to "always-on" when neither sensor is available (rare
 * on modern hardware; old emulators sometimes lack one).
 */
@Singleton
class PhonePickupDetector @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val sensorManager: SensorManager? =
        ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val supportsPickup: Boolean = gravitySensor != null || gyroSensor != null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var restGraceJob: Job? = null
    @Volatile private var enabled = false

    // Smoothed tilt angle in degrees (0 = flat, 90 = upright).
    @Volatile private var tiltDeg: Float = 0f
    // Timestamp of the last detected motion (gyro mag above threshold).
    @Volatile private var lastMotionMs: Long = 0L
    // Whether the held state was last evaluated true. Used to debounce
    // rapid flickers around the thresholds.
    @Volatile private var lastHeld: Boolean = false

    private val _activeWindow = MutableStateFlow(false)
    /** True while the phone is in a picked-up state. The camera path
     *  binds when this flips true, unbinds when it flips false. */
    val activeWindow: StateFlow<Boolean> = _activeWindow.asStateFlow()

    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            // TYPE_GRAVITY / TYPE_ACCELEROMETER returns m/s² along x, y, z.
            // Tilt from horizontal = angle between the gravity vector and
            // the phone's z-axis (which points out the back of the screen
            // when flat). 0° = flat-face-up or face-down; 90° = upright.
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]
            val horizMag = sqrt(gx * gx + gy * gy)
            val instantTilt = Math.toDegrees(
                atan2(horizMag.toDouble(), abs(gz.toDouble())),
            ).toFloat()
            // EMA-smooth so a quick wobble doesn't bounce the state.
            tiltDeg = tiltDeg * (1f - TILT_EMA) + instantTilt * TILT_EMA
            evaluateHeld()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val mag = sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2],
            )
            if (mag > GYRO_MOTION_THRESHOLD) {
                lastMotionMs = System.currentTimeMillis()
                evaluateHeld()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Start watching. Idempotent. Falls back to always-on when the
     *  hardware lacks both gravity + gyro. */
    fun enable() {
        if (enabled) return
        enabled = true
        if (!supportsPickup) {
            Log.w(TAG, "no orientation sensors — falling back to always-on")
            _activeWindow.value = true
            return
        }
        Log.d(TAG, "enabling continuous pickup detector (gravity=${gravitySensor?.name} gyro=${gyroSensor?.name})")
        // Seed in the active state so the first frame's camera bind
        // doesn't have to wait for the user to nudge the phone — most
        // of the time when this method is called, they're already
        // holding the phone (Home screen just opened).
        _activeWindow.value = true
        lastHeld = true
        lastMotionMs = System.currentTimeMillis()
        gravitySensor?.let {
            sensorManager?.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroSensor?.let {
            sensorManager?.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /** Stop watching. Idempotent. */
    fun disable() {
        if (!enabled) return
        enabled = false
        sensorManager?.unregisterListener(gravityListener)
        sensorManager?.unregisterListener(gyroListener)
        restGraceJob?.cancel()
        restGraceJob = null
        _activeWindow.value = false
        lastHeld = false
    }

    /** Hook left from the v6 / v7 API so callers that called this on
     *  every successful face detection still compile. With the new
     *  continuous detector the camera is bound whenever the phone is
     *  in a picked-up state, so we don't need an explicit "extend"
     *  signal anymore — but we do nudge the lastMotion timestamp so
     *  a sustained stare doesn't slip into at-rest while the
     *  accelerometer reads near-still. */
    fun extendWindow() {
        if (!enabled) return
        lastMotionMs = System.currentTimeMillis()
    }

    /** Combine tilt + recent-motion into the held verdict. Debounced
     *  via the [REST_GRACE_MS] timer so a brief flat moment doesn't
     *  immediately unbind the camera. */
    private fun evaluateHeld() {
        val now = System.currentTimeMillis()
        val recentMotion = (now - lastMotionMs) < MOTION_HOLD_MS
        val isUpright = tiltDeg > TILT_HELD_DEG
        val held = recentMotion || isUpright
        if (held && !lastHeld) {
            // Transition at-rest → held: flip immediately.
            lastHeld = true
            restGraceJob?.cancel()
            restGraceJob = null
            _activeWindow.value = true
            Log.d(TAG, "held=true (tilt=$tiltDeg, recentMotion=$recentMotion)")
        } else if (!held && lastHeld) {
            // Transition held → maybe-at-rest. Start the grace timer;
            // if motion picks up again before it fires, cancel.
            if (restGraceJob == null) {
                restGraceJob = scope.launch {
                    delay(REST_GRACE_MS)
                    lastHeld = false
                    _activeWindow.value = false
                    restGraceJob = null
                    Log.d(TAG, "held=false (rested for ${REST_GRACE_MS}ms)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "Mythara/Pickup"

        /** Tilt threshold (degrees from horizontal) above which the
         *  phone reads as "held in hand or propped up". 15° gives a
         *  comfortable margin — flat-on-a-table reads ~0°, even
         *  glance-angle on a desk wedge reads ~25°. */
        private const val TILT_HELD_DEG = 15f

        /** EMA smoothing for the tilt estimate. 0.18 gives a ~6-frame
         *  time constant at SENSOR_DELAY_NORMAL — fast enough to
         *  follow a hand pickup, slow enough to ignore single-sample
         *  noise. */
        private const val TILT_EMA = 0.18f

        /** Gyro magnitude (rad/s) above which we record "phone is
         *  actively being moved". 0.10 rad/s ≈ 5.7°/s — slow enough
         *  to catch a deliberate orientation change, fast enough to
         *  ignore micro-tremor from the user's pulse on a held phone. */
        private const val GYRO_MOTION_THRESHOLD = 0.10f

        /** How long after the last motion we still treat the phone as
         *  held. Sustained stares are common; 4 s tolerates that
         *  comfortably without forcing the camera off when the user
         *  just isn't moving. */
        private const val MOTION_HOLD_MS = 4_000L

        /** Grace period before the phone goes from held → at-rest.
         *  Prevents flicker around the tilt threshold. */
        private const val REST_GRACE_MS = 2_500L
    }
}
