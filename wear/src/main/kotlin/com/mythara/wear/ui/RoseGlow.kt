package com.mythara.wear.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

/**
 * The Mythara rose surrounded by a particle glow that reacts to PTT
 * state:
 *
 *   IDLE     — 18 lavender/charple dots drifting slowly on Lissajous
 *              orbits at ~1× base speed.
 *   ACTIVE   — 36 neon-green dots drifting at ~2.5× base speed, hex
 *              pulse rate also bumped from 0.2 Hz to 0.8 Hz so the
 *              "halo brightness" rhythm matches the speed-up.
 *
 * Every transition between the two states is animated:
 *   • core + halo colour cross-fades over 400 ms (animateColorAsState)
 *   • speed multiplier eases from 1.0 → 2.5 over 500 ms
 *     (animateFloatAsState, applied to an integrated time variable so
 *     the drift accelerates smoothly instead of teleporting)
 *   • the EXTRA 18 particles (beyond the always-visible 18) fade in /
 *     out over 500 ms via an alpha multiplier
 *
 * The rose itself stays stationary (RoseWithGlow passes
 * `animated = false` to MytharaRose) — the only motion the user sees
 * is this particle field reacting to PTT events.
 */
@Composable
fun RoseWithGlow(
    modifier: Modifier = Modifier,
    roseSize: Dp = 120.dp,
    listening: Boolean = false,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ParticleGlow(
            modifier = Modifier.fillMaxSize(),
            listening = listening,
        )
        MytharaRose(
            modifier = Modifier.size(roseSize),
            listening = listening,
            showRing = false,
            animated = false,
        )
    }
}

/** Drifting particle layer — see file-level docstring. Drawn UNDER the
 *  rose so the particles read as "behind / around" the brand mark. */
@Composable
private fun ParticleGlow(modifier: Modifier, listening: Boolean) {
    val particles = remember { generateParticles(MAX_PARTICLE_COUNT) }

    // ── Animated state transitions ────────────────────────────────
    // Speed multiplier — applied to the integrated time variable so
    // particles smoothly accelerate / decelerate instead of teleporting
    // when listening flips.
    val speedTarget = if (listening) ACTIVE_SPEED_MUL else IDLE_SPEED_MUL
    val animatedSpeed by animateFloatAsState(
        targetValue = speedTarget,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "glow-speed",
    )
    // Extra-particle alpha — 0 when idle (only the first IDLE_PARTICLE_COUNT
    // visible), 1 when listening (all MAX_PARTICLE_COUNT visible). Fades
    // in/out over 500 ms so the count change doesn't pop.
    val extraAlpha by animateFloatAsState(
        targetValue = if (listening) 1.0f else 0.0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing),
        label = "glow-extra-alpha",
    )
    // Core + halo colours — cross-fade between purple-idle and neon-green-
    // active. Both move in lock-step so the perceived hue change is
    // unified rather than "core turns green first, halo still purple".
    val coreColor by animateColorAsState(
        targetValue = if (listening) GREEN_CORE else CHARPLE,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "glow-core-color",
    )
    val haloColor by animateColorAsState(
        targetValue = if (listening) GREEN_HALO else LAVENDER,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = "glow-halo-color",
    )

    // ── Integrated time + pulse drivers ───────────────────────────
    var tSec by remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(animatedSpeed)
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nowNs ->
                if (lastNanos != 0L) {
                    val deltaSec = (nowNs - lastNanos) / 1_000_000_000f
                    tSec += deltaSec * currentSpeed
                }
                lastNanos = nowNs
            }
        }
    }

    // Brightness pulse — uses an infinite transition (period is a state
    // value so it shortens / lengthens smoothly when listening flips).
    val transition = rememberInfiniteTransition(label = "glow-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (listening) 1_250 else 5_000,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow-pulse-phase",
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val side = min(size.width, size.height)
        val sizeScale = side / 240f
        val pulseMul = 0.7f + 0.3f * pulse

        for ((i, p) in particles.withIndex()) {
            // Skip drawing fully-faded extras to save the GPU some fill.
            val baseAlphaMul =
                if (i < IDLE_PARTICLE_COUNT) 1f else extraAlpha
            if (baseAlphaMul <= 0.01f) continue

            val dx = p.driftAmp * sizeScale *
                cos(tSec * (2f * PI.toFloat() / p.periodX) + p.phaseX)
            val dy = p.driftAmp * sizeScale *
                sin(tSec * (2f * PI.toFloat() / p.periodY) + p.phaseY)
            val ax = cx + p.baseRadius * sizeScale * cos(p.baseAngleRad)
            val ay = cy + p.baseRadius * sizeScale * sin(p.baseAngleRad)
            val x = ax + dx
            val y = ay + dy

            val r = p.coreRadius * sizeScale * pulseMul
            val haloR = r * 3.5f
            // Halo first (drawn under the core).
            drawCircle(
                color = haloColor.copy(alpha = HALO_ALPHA * pulseMul * baseAlphaMul),
                radius = haloR,
                center = Offset(x, y),
            )
            drawCircle(
                color = coreColor.copy(alpha = CORE_ALPHA * pulseMul * baseAlphaMul),
                radius = r,
                center = Offset(x, y),
            )
        }
    }
}

private data class Particle(
    val baseRadius: Float,   // distance from centre in 240-px units
    val baseAngleRad: Float, // angular anchor around centre
    val driftAmp: Float,     // lissajous drift amplitude
    val periodX: Float,      // seconds per x loop
    val periodY: Float,      // seconds per y loop
    val phaseX: Float,
    val phaseY: Float,
    val coreRadius: Float,   // core dot radius in 240-px units
)

private fun generateParticles(count: Int): List<Particle> {
    // Deterministic seed (0x526F7365 = ASCII "Rose") so the layout is
    // stable across recompositions — the same dot is always near the
    // same anchor angle.
    val rng = Random(0x526F7365L)
    return List(count) {
        Particle(
            // Particles ring the rose at radii 70..115 (just outside
            // the 60-px rose silhouette in 240-px units).
            baseRadius = 70f + rng.nextFloat() * 45f,
            baseAngleRad = rng.nextFloat() * 2f * PI.toFloat(),
            driftAmp = 8f + rng.nextFloat() * 18f,
            periodX = 6f + rng.nextFloat() * 12f,
            periodY = 6f + rng.nextFloat() * 12f,
            phaseX = rng.nextFloat() * 2f * PI.toFloat(),
            phaseY = rng.nextFloat() * 2f * PI.toFloat(),
            coreRadius = 1.4f + rng.nextFloat() * 1.8f,
        )
    }
}

private const val IDLE_PARTICLE_COUNT = 18
private const val MAX_PARTICLE_COUNT = 36
private const val IDLE_SPEED_MUL = 1.0f
private const val ACTIVE_SPEED_MUL = 2.5f
private const val HALO_ALPHA = 0.18f
private const val CORE_ALPHA = 0.65f

// Idle palette — same lavender/charple as the brand mark uses
// elsewhere (live wallpaper, splash, in-app amulet).
private val LAVENDER = Color(0xFF9B86FF)
private val CHARPLE = Color(0xFF6B50FF)

// Active palette — neon green that pops against the rose's purple
// petals while clearly signaling "captures live". GREEN_HALO is the
// same hue at a softer brightness so the aura reads as one colour
// rather than core / halo competing.
private val GREEN_CORE = Color(0xFF39FF7A)
private val GREEN_HALO = Color(0xFF7DFF9F)
