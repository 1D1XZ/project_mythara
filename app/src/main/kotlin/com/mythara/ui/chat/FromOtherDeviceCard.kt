package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a chat message that originated on a DIFFERENT device and
 * landed locally via memory-sync restore.
 *
 * Visually similar to the tool-call card (bordered, dim header line,
 * monospace body) so the reader instantly distinguishes it from
 * native-on-this-device bubbles. Carries:
 *   - the role chip (you / mythara)
 *   - the short device id ("dev:abc123")
 *   - timestamp
 *   - the message text
 *
 * Deliberately rendered FULL-WIDTH (not right- or left-aligned like
 * native bubbles) to underscore "this didn't happen here, just
 * showing it for context."
 */
@Composable
fun FromOtherDeviceCard(item: ChatViewModel.ChatItem.FromOtherDevice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Header: role + device id together so the reader sees
            // "who said this" and "from which device" in one chip.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${Glyph.ThinDivider} ${if (item.role == "user") "you" else "mythara"}",
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.padding(start = 6.dp))
                Text(
                    text = "dev:${item.deviceShortId}",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = formatTs(item.tsMillis),
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.text.ifBlank { "(empty message)" },
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val TS_FMT = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun formatTs(ts: Long): String = TS_FMT.format(Date(ts))
