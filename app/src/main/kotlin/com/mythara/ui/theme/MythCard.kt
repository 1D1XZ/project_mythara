package com.mythara.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Skin-aware surface card. Reads [LocalSkinSpec] so a single call site
 * renders correctly across all four skins:
 *   • Spatial      → solid Surface fill, hairline border, rounded corner
 *   • Aurora Glass → translucent fill + blur (P6)
 *   • Holographic  → transparent fill, glowing line-art border (P8)
 *   • Living Rose  → solid fill, organic radius (P7)
 *
 * Phase 4 ships the Spatial (Solid) treatment; the translucent/line-art
 * branches fill in with their skins. Screens adopt MythCard where card
 * treatment should track the skin; screens that want a fixed look keep
 * their own Box.
 */
@Composable
fun MythCard(
    modifier: Modifier = Modifier,
    spec: SkinSpec = LocalSkinSpec.current,
    palette: MythPalette = LocalMythPalette.current,
    contentPadding: Int = 14,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(spec.cornerRadius)
    val base = when (spec.surfaceTreatment) {
        SurfaceTreatment.Solid -> Modifier
            .clip(shape)
            .background(palette.Surface)
            .border(spec.hairlineWidth, palette.SurfaceHigh, shape)
        SurfaceTreatment.Translucent -> Modifier
            // P6 will add RenderEffect blur of the backdrop behind this.
            .clip(shape)
            .graphicsLayer { alpha = 0.92f }
            .background(palette.Surface.copy(alpha = 0.55f))
            .border(spec.hairlineWidth, palette.SurfaceHigh.copy(alpha = 0.6f), shape)
        SurfaceTreatment.LineArt -> Modifier
            .clip(shape)
            .background(palette.Bg.copy(alpha = 0.4f))
            .border(spec.hairlineWidth, palette.Charple, shape)
    }
    Column(
        modifier = modifier.then(base).padding(contentPadding.dp),
        content = content,
    )
}
