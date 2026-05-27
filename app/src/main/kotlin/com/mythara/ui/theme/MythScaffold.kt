package com.mythara.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lightweight per-screen wrapper. The app runs edge-to-edge with the
 * system status bar hidden (MainActivity), so screen content can slide
 * under the camera cutout / status region and become hard to tap (one
 * half of the user's "top elements not interactable" report — the
 * island overlay was the other half, fixed in P2).
 *
 * MythScaffold applies the safe-drawing inset (status bar + cutout +
 * nav bar) so content never collides with the cutout, and gives a
 * single seam where future skin chrome (header sliver, edge-glow)
 * can hang. Screens adopt it incrementally; un-wrapped screens keep
 * their existing behaviour.
 *
 * The skin BACKDROP is NOT drawn here — it's mounted once in
 * MytharaRoot ([MythBackdrop]) behind the whole NavHost. This wrapper
 * is transparent so the backdrop shows through.
 */
@Composable
fun MythScaffold(
    modifier: Modifier = Modifier,
    applySafeArea: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (applySafeArea) Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                else Modifier,
            ),
        content = content,
    )
}
