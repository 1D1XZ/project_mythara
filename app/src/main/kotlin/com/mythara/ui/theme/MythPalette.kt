package com.mythara.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Immutable colour set for ONE (skin × brightness) combination.
 *
 * The field names match the legacy `MytharaColors` members 1:1 so the
 * ~1,873 existing `MytharaColors.Charple` call sites keep compiling
 * unchanged — those members are now `@Composable` accessors
 * (see [MytharaColors]) that read `LocalMythPalette.current.<field>`.
 *
 * Surfaces (Bg/Surface/SurfaceMid/SurfaceHigh) and foreground
 * (Fg/FgMute/FgDim) flip between light and dark. The brand + semantic
 * accents (Charple/Bok/Sriracha/Mustard/Citron/Malibu/Julep) are
 * tuned per-brightness for legibility — the neon mints/yellows are
 * darkened in light mode so they're readable on white.
 */
data class MythPalette(
    val Bg: Color,
    val Surface: Color,
    val SurfaceMid: Color,
    val SurfaceHigh: Color,
    val Fg: Color,
    val FgMute: Color,
    val FgDim: Color,
    val Charple: Color,
    val Bok: Color,
    val Sriracha: Color,
    val Mustard: Color,
    val Citron: Color,
    val Malibu: Color,
    val Julep: Color,
)

/**
 * The active palette. [MytharaTheme] provides it per (skin ×
 * brightness). Defaults to the dark Spatial palette so:
 *   • any composable read outside MytharaTheme still resolves, and
 *   • [MytharaColorsStatic] (the non-composable accessor) has a value.
 * staticCompositionLocalOf (not compositionLocalOf) because the
 * palette changes rarely — a full subtree recompose on theme switch
 * is exactly what we want, and reads stay cheap.
 */
val LocalMythPalette = staticCompositionLocalOf { PaletteCatalog.SpatialDark }

/**
 * Non-composable accessor for the handful of call sites that build
 * colours outside a composition (e.g. AgentRunner constructing a
 * DynamicIsland insight accent, notification builders). Returns the
 * dark Spatial palette — the brand accents are identical across
 * skins/brightness, and these surfaces are never user-facing in a
 * light context. Composable code should always go through
 * [MytharaColors] instead so it tracks the live theme.
 */
val MytharaColorsStatic: MythPalette get() = PaletteCatalog.SpatialDark
