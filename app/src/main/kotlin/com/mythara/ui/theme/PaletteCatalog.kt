package com.mythara.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette library — every (skin × brightness) combination resolves
 * here. Phase 3 ships the Spatial skin's light + dark palettes; the
 * Aurora / Rose / HUD skins reuse Spatial until their phases (6–8)
 * give them bespoke palettes, so the skin picker works end-to-end
 * while the distinct looks land incrementally.
 */
object PaletteCatalog {

    /** Dark Spatial — the original Charmtone Pantera values verbatim,
     *  so the default theme is pixel-identical to the pre-v6 app. */
    val SpatialDark = MythPalette(
        Bg = Color(0xFF201F26),
        Surface = Color(0xFF2D2C35),
        SurfaceMid = Color(0xFF3A3943),
        SurfaceHigh = Color(0xFF4D4C57),
        Fg = Color(0xFFDFDBDD),
        FgMute = Color(0xFFA8A4AB),
        FgDim = Color(0xFF605F6B),
        Charple = Color(0xFF6B50FF),
        Bok = Color(0xFF68FFD6),
        Sriracha = Color(0xFFEB4268),
        Mustard = Color(0xFFF5EF34),
        Citron = Color(0xFFE8FF27),
        Malibu = Color(0xFF00A4FF),
        Julep = Color(0xFF00FFB2),
    )

    /** Light Spatial — near-white surfaces with a faint violet cast,
     *  near-black text, and brand/semantic accents darkened so the
     *  neon mints + yellows stay legible on a light background. */
    val SpatialLight = MythPalette(
        Bg = Color(0xFFF5F3FA),         // near-white, faint violet cast
        Surface = Color(0xFFFFFFFF),    // cards
        SurfaceMid = Color(0xFFEAE7F1), // bubbles, hairline
        SurfaceHigh = Color(0xFFD7D2E2),// raised panels / borders
        Fg = Color(0xFF1B1A22),         // near-black primary
        FgMute = Color(0xFF565260),     // de-emphasis
        FgDim = Color(0xFF8E8A99),      // metadata
        Charple = Color(0xFF5A3FE0),    // slightly deeper violet for contrast
        Bok = Color(0xFF00A98C),        // mint darkened — pure #68FFD6 is invisible on white
        Sriracha = Color(0xFFD42F54),   // error
        Mustard = Color(0xFFB89500),    // warning — yellow darkened
        Citron = Color(0xFF7E9400),     // busy — darkened
        Malibu = Color(0xFF0083CC),     // info
        Julep = Color(0xFF00936E),      // success — darkened
    )

    /** Resolve the palette for a skin + brightness. */
    fun forSkin(skin: SkinId, dark: Boolean): MythPalette = when (skin) {
        SkinId.SpatialCards -> if (dark) SpatialDark else SpatialLight
        // Phases 6–8 replace these with bespoke palettes.
        SkinId.AuroraGlass -> if (dark) SpatialDark else SpatialLight
        SkinId.LivingRose -> if (dark) SpatialDark else SpatialLight
        SkinId.HolographicHud -> if (dark) SpatialDark else SpatialLight
    }
}
