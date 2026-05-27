package com.mythara.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Charmtone Pantera — the brand palette, now THEME-AWARE.
 *
 * Historically a static dark-only `object`. As of the v6 theme engine
 * these members are `@Composable @ReadOnlyComposable` accessors that
 * resolve to the ACTIVE [MythPalette] via [LocalMythPalette]. This is
 * the zero-churn trick: every existing `MytharaColors.Charple` read
 * inside a composable compiles unchanged and now tracks the live
 * theme (skin × light/dark), recomposing on theme switch.
 *
 * The raw colour values + light/dark variants live in
 * [PaletteCatalog]; the active one is provided by [MytharaTheme].
 *
 * NON-COMPOSABLE callers (a plain class building a notification colour,
 * a Canvas Paint outside Compose) CANNOT read these — use
 * [MytharaColorsStatic] instead, which returns the dark Spatial
 * palette. There are only a couple such call sites.
 */
object MytharaColors {
    // Surfaces
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Bg
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Surface
    val SurfaceMid: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.SurfaceMid
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.SurfaceHigh

    // Foreground / text
    val Fg: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Fg
    val FgMute: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.FgMute
    val FgDim: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.FgDim

    // Brand
    val Charple: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Charple
    val Bok: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Bok

    // Semantic
    val Sriracha: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Sriracha
    val Mustard: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Mustard
    val Citron: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Citron
    val Malibu: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Malibu
    val Julep: Color @Composable @ReadOnlyComposable get() = LocalMythPalette.current.Julep
}
