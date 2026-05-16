package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.MytharaColors
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/**
 * Pill that represents one collapsed day in the chat transcript.
 *
 * Behavior per user spec:
 *   - Chats older than the current day collapse into a date pill.
 *   - Pill is coloured deterministically from the app's accent
 *     palette (Charple / Bok / Mustard / Malibu / Julep / Citron /
 *     Sriracha) by hashing the date string — same date always
 *     gets the same colour, no flicker on recomposition.
 *   - Clicking the pill expands that day's transcript inline; a
 *     second click (or clicking a different pill) collapses it.
 *   - Only one day can be expanded at a time — the Transcript
 *     itself enforces single-open semantics via `expandedDay`
 *     state.
 *
 * Pill layout: pill BG = SurfaceMid; left dot + label text both
 * use the assigned random accent; trailing item count + "▾"
 * caret. Label format: "Wed, May 14 · 12 messages".
 */
@Composable
fun DayPill(
    dayLabel: String,
    iso: String,
    itemCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = colorFor(iso)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MytharaColors.SurfaceMid)
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = dayLabel,
            color = accent,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$itemCount",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (expanded) "▴" else "▾",
            color = accent,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * "It's a brand new day" placeholder shown at the top of the
 * transcript when the user has had ZERO chat activity today yet.
 *
 * Renders a soft system-style bubble with:
 *   - Date headline ("Wednesday, May 15")
 *   - A short greeting generated once per day via ModelRouter
 *     (light path). Cached in [BrandNewDayGreeter] keyed by ISO
 *     date so it stays the same all day; first paint shows a
 *     curated fallback while the LLM is in flight.
 *
 * NO interactive elements on the bubble — it's a system message
 * that disappears the moment the user types anything (which
 * creates today's first ChatItem and pushes the bubble out).
 */
@Composable
fun BrandNewDayBubble(
    todayLabel: String,
    greeting: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MytharaColors.Charple.copy(alpha = 0.18f),
                        MytharaColors.Bok.copy(alpha = 0.12f),
                    ),
                ),
            )
            .border(
                1.dp,
                MytharaColors.Charple.copy(alpha = 0.45f),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "it's a brand new day · $todayLabel",
            color = MytharaColors.Charple,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = greeting,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/* -------------------------------------------------- helpers */

/** Deterministic accent-colour pick from the Mythara palette,
 *  seeded on the ISO date string so the same day always gets
 *  the same pill colour across recompositions + sessions. */
private fun colorFor(iso: String): Color {
    val idx = (iso.hashCode().toLong() and 0x7fffffff).toInt() % ACCENT_PALETTE.size
    return ACCENT_PALETTE[idx]
}

private val ACCENT_PALETTE: List<Color> = listOf(
    MytharaColors.Charple,
    MytharaColors.Bok,
    MytharaColors.Mustard,
    MytharaColors.Malibu,
    MytharaColors.Julep,
    MytharaColors.Citron,
    MytharaColors.Sriracha,
)

/* -------------------------------------------------- date helpers */

/** Convert a ms timestamp to an ISO-format yyyy-MM-dd string
 *  in the device's default timezone — the grouping key the
 *  Transcript uses. */
fun toIsoDay(tsMillis: Long): String =
    Instant.ofEpochMilli(tsMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/** Today's ISO day. */
fun todayIso(): String =
    LocalDate.now(ZoneId.systemDefault()).toString()

/** Pretty label for a day pill — "Wed, May 14" / "Yesterday" /
 *  "Today". Today is special-cased because the current-day items
 *  render inline; pills only ever show OLDER days. Yesterday is
 *  the most-common pill so we give it a friendly label. */
fun prettyDayLabel(iso: String): String {
    val today = todayIso()
    if (iso == today) return "Today"
    val date = LocalDate.parse(iso)
    val daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now(ZoneId.systemDefault()))
    if (daysAgo == 1L) return "Yesterday"
    return PRETTY_FMT.format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()))
}

private val PRETTY_FMT = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

