package com.mythara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Crush-style tool-call bubble. Translates the `● Reading file…` /
 * `✓ Reading file (0.4s)` rendering Crush uses on the terminal to a
 * mobile-friendly bordered card. The glyph + first-line are always
 * visible; the result preview shows below when present, monospaced.
 */
@Composable
fun ToolCallBubble(item: ChatViewModel.ChatItem.Tool) {
    val (glyph, accent) = when (item.state) {
        ChatViewModel.ToolState.Running -> Glyph.Dot to MytharaColors.Citron
        ChatViewModel.ToolState.Success -> Glyph.Check to MytharaColors.Julep
        ChatViewModel.ToolState.Failure -> Glyph.Cross to MytharaColors.Sriracha
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = glyph,
                color = accent,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 6.dp))
            Text(
                text = item.name,
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.labelLarge,
            )
            if (item.args.isNotBlank()) {
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = trimArgs(item.args),
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (item.durationMs != null) {
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = "${item.durationMs}ms",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item.output?.takeIf { it.isNotBlank() }?.let { preview ->
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = "${Glyph.DescendingArrow} ",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = preview.lines().take(MAX_PREVIEW_LINES).joinToString("\n").let {
                        if (preview.length > MAX_PREVIEW_CHARS) it.take(MAX_PREVIEW_CHARS) + "…" else it
                    },
                    color = MytharaColors.FgMute,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun trimArgs(raw: String): String {
    val cleaned = raw.replace(Regex("\\s+"), " ").trim()
    return if (cleaned.length > 60) cleaned.take(60) + "…" else cleaned
}

private const val MAX_PREVIEW_LINES = 4
private const val MAX_PREVIEW_CHARS = 240

private fun Color.copy(alpha: Float): Color = Color(red, green, blue, alpha)
