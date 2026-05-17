package com.mythara.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text

/**
 * Stylised "M.Y.T.H.A.R.A" wordmark for the wear home.
 *
 * Per-letter colouring via AnnotatedString:
 *   • Letters M Y T H A R A — bold purple in monospace.
 *   • Separators .          — neon cyan (Mythara's brand accent).
 *
 * A soft purple glow surrounds the whole word via the TextStyle's
 * shadow — single blur, low offset, ~50% alpha — so the word reads
 * as illuminated without an expensive multi-layer text stack.
 *
 * Monospace + extra-bold + letter-spacing combine to give the
 * wordmark a tech / control-panel feel that complements the
 * geometric rose's vector-art aesthetic.
 */
@Composable
fun MytharaWordmark(
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
) {
    val styledText = buildAnnotatedString {
        val letters = "MYTHARA".toCharArray()
        for ((index, letter) in letters.withIndex()) {
            withStyle(SpanStyle(color = PURPLE, fontWeight = FontWeight.ExtraBold)) {
                append(letter.toString())
            }
            if (index < letters.lastIndex) {
                withStyle(SpanStyle(color = NEON_CYAN, fontWeight = FontWeight.Bold)) {
                    append(".")
                }
            }
        }
    }
    Text(
        text = styledText,
        modifier = modifier,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            fontSize = fontSize,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
            shadow = Shadow(
                color = PURPLE.copy(alpha = 0.85f),
                offset = Offset.Zero,
                blurRadius = 14f,
            ),
        ),
    )
}

private val PURPLE = Color(0xFF6B50FF)
private val NEON_CYAN = Color(0xFF68FFD6)
