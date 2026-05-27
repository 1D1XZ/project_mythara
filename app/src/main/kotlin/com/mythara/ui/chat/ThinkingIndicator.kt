package com.mythara.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mythara.ui.amulet.RoseGeometry
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.JetBrainsMono
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.delay

/**
 * "Mythara is thinking…" indicator — Phase E redesign.
 *
 * New visual: a 16 dp mini-rose breathing in Charple ↔ Lavender at
 * 0.8 Hz (the brand heartbeat — same cadence as the watch face's
 * active-PTT pulse), followed by a rolodex phrase in JetBrainsMono
 * + a trailing ellipsis glyph. The mini-rose uses the same petal +
 * hex geometry as every other rose surface in the system
 * (watch face, splash, amulet, fold bloom) so the user instantly
 * recognises "Mythara is doing something" without a label.
 *
 * The rolodex phrase rotation is preserved from the previous
 * gradient-text implementation — it gives the indicator personality
 * beyond a static spinner, and matches the chat surface's "friend
 * keeping you in the loop" voice.
 *
 * Rendered in the chat surface between submit() and the first
 * streamed delta; suppressed once streaming text starts arriving.
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier,
) {
    // Phrase rolodex. Reads like a friend updating you on what
    // they're doing. ~1.6s per phrase — slow enough to read,
    // fast enough to feel responsive.
    var phraseIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PHRASE_INTERVAL_MS)
            phraseIdx = (phraseIdx + 1) % PHRASES.size
        }
    }
    val phrase = PHRASES[phraseIdx]

    // Pulse phase 0 → 1 → 0, 1250 ms period (0.8 Hz). Drives both
    // the petal colour blend AND a subtle scale wobble so the rose
    // visibly "breathes" through each cycle.
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-pulse-phase",
    )
    val petalColor = lerpColor(MytharaColors.Charple, RoseGeometry.Lavender, pulse)
    val scale = 1f + 0.06f * pulse

    // Continuous rotation — one revolution every SPIN_PERIOD_MS. We
    // drive this off withFrameNanos rather than animateFloat so the
    // angle wraps cleanly 360° → 0° without a perceptible reverse.
    var spinDegrees by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNs = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - startNs) / 1_000_000.0
                spinDegrees = ((elapsedMs / SPIN_PERIOD_MS.toDouble()) * 360.0 % 360.0).toFloat()
            }
        }
    }

    // Cycling gradient hue offset for the phrase text. Same
    // withFrameNanos source so the brush, the rose spin, and the
    // pulse all share the same clock and never visually desync.
    var hueOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNs = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedMs = (now - startNs) / 1_000_000.0
                // Range 0..1, wraps every HUE_CYCLE_MS.
                hueOffset = ((elapsedMs / HUE_CYCLE_MS.toDouble()) % 1.0).toFloat()
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(ROSE_SIZE_DP.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val targetRadius =
                (minOf(size.width, size.height) / 2f) * 0.9f * scale
            // RoseGeometry uses source units where the outer petal tip
            // sits at 30 units from centre — scale accordingly.
            val unitScale = targetRadius / RoseGeometry.OuterRadiusSourceUnits
            // Five big petals at the 5-fold rotation, blending Charple
            // ↔ Lavender per pulse phase. spinDegrees adds a global
            // rotation so the whole rose visibly turns — one full
            // revolution per SPIN_PERIOD_MS.
            for (deg in RoseGeometry.BigPetalAngles) {
                drawPath(
                    path = RoseGeometry.petalPath(
                        diamond = RoseGeometry.BigPetal,
                        angleDegrees = deg.toFloat() + spinDegrees,
                        cx = cx, cy = cy, scale = unitScale,
                    ),
                    color = petalColor.copy(alpha = 0.95f),
                )
            }
            // Five smaller lavender petals interleaved at 36° offsets.
            // Counter-rotate slightly for a layered "two wheels at
            // different speeds" effect — keeps the eye engaged on
            // longer "thinking…" stretches without being flashy.
            for (deg in RoseGeometry.SmallPetalAngles) {
                drawPath(
                    path = RoseGeometry.petalPath(
                        diamond = RoseGeometry.SmallPetal,
                        angleDegrees = deg.toFloat() - spinDegrees * 0.5f,
                        cx = cx, cy = cy, scale = unitScale,
                    ),
                    color = RoseGeometry.Lavender.copy(alpha = 0.85f),
                )
            }
            // Cyan hex nucleus pulses with the same phase.
            val hexAlpha = 0.55f + 0.45f * pulse
            drawPath(
                path = RoseGeometry.hexPath(cx = cx, cy = cy, scale = unitScale),
                color = com.mythara.ui.theme.MytharaColorsStatic.Bok.copy(alpha = hexAlpha),
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        // Cycling-gradient brush. We build a long colour ramp
        // (purple → pink → red → blue → violet → yellow → loop) and
        // shift its starting offset by hueOffset so the colour
        // appears to "flow" across the phrase. Brush is computed
        // every frame (cheap — six Color stops) and re-applied via
        // TextStyle.brush.
        val phraseBrush = remember(hueOffset) { buildCyclingBrush(hueOffset) }
        Text(
            text = phrase,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                brush = phraseBrush,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text = Glyph.Ellipsis,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 13.sp,
                brush = phraseBrush,
            ),
        )
    }
}

/** Six-colour cycling gradient — the user's spec was
 *  "purple, pink, red, blue, violet, yellow". We rotate the colour
 *  list by `offset` (0..1) so the gradient appears to scroll left
 *  across the text. Each stop sits at evenly-spaced t values so the
 *  transitions are smooth. */
private fun buildCyclingBrush(offset: Float): Brush {
    val palette = CYCLE_PALETTE
    // Compute a rotated list so the starting colour shifts with
    // the offset. The same palette is duplicated at the end so
    // wrap-around stays continuous.
    val shift = (offset * palette.size).toInt() % palette.size
    val rotated = palette.drop(shift) + palette.take(shift)
    val ramp = rotated + rotated.first()
    return Brush.horizontalGradient(colors = ramp)
}

private val CYCLE_PALETTE = listOf(
    Color(0xFF9B59FF), // purple
    Color(0xFFFF6FD8), // pink
    Color(0xFFFF4E50), // red
    Color(0xFF4FA8FF), // blue
    Color(0xFFB388FF), // violet
    Color(0xFFFFE066), // yellow
)

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val tt = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * tt,
        green = a.green + (b.green - a.green) * tt,
        blue = a.blue + (b.blue - a.blue) * tt,
        alpha = a.alpha + (b.alpha - a.alpha) * tt,
    )
}

private const val PHRASE_INTERVAL_MS = 1_600L
/** 1250 ms = 0.8 Hz — the brand heartbeat. Matches the watch
 *  face's active-PTT pulse so any pulsing surface across the
 *  Mythara ecosystem reads as one rhythm. */
private const val PULSE_PERIOD_MS = 1_250
/** Rose-spin period. 4 s for a full revolution is slow enough to
 *  feel meditative, fast enough to read as motion. The small
 *  petals counter-rotate at half this rate. */
private const val SPIN_PERIOD_MS = 4_000L
/** Text-colour-cycle period. 6 s lets the eye land on each
 *  individual colour for ~1 s before it shifts away — keeps the
 *  thinking phrase visually alive without feeling frantic. */
private const val HUE_CYCLE_MS = 6_000L
/** Mini-rose target size. Big enough to read as a rose, small
 *  enough to sit comfortably inline next to body text. */
private const val ROSE_SIZE_DP = 18

private val PHRASES = listOf(
    "Mythara is thinking…",
    "Reading the room…",
    "Composing a reply…",
    "Looking it up…",
    "Pulling things together…",
    "Just a sec…",
    "Mythara is working…",
)
