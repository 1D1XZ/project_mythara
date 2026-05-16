package com.mythara.ui.settings

import android.app.Activity
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mythara.glasses.GlassesConnectionService
import com.mythara.glasses.GlassesConnectionState
import com.mythara.glasses.GlassesDatFacade
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors

/**
 * Settings panel for the Meta Display Glasses integration.
 *
 * Surfaces:
 *   - Current [GlassesConnectionState] live from the DAT façade.
 *   - "Register glasses" button — fires [GlassesDatFacade.startRegistration]
 *     which hands off to the Meta AI app. The user comes back into
 *     Mythara via the registered callback URI scheme.
 *   - "Start glasses session" button — kicks off [GlassesConnectionService]
 *     (the foreground service that holds the DAT session alive). The
 *     facade only starts the session once registration is REGISTERED.
 *   - "Unregister" button — calls [GlassesDatFacade.startUnregistration]
 *     so the user can disconnect Mythara from Meta AI.
 *
 * Mirrors the ShizukuPanel visual style — same card, same glyph
 * conventions, state-driven body copy.
 */
@Composable
fun GlassesPanel() {
    val ctx = LocalContext.current
    val state by GlassesDatFacade.connectionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} meta display glasses — POV photos, neural-band PTT, on-glasses cards",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(6.dp))

        val statusColor = when (state) {
            GlassesConnectionState.SessionActive -> MytharaColors.Bok
            GlassesConnectionState.Paired -> MytharaColors.Bok
            GlassesConnectionState.Error -> MytharaColors.Mustard
            GlassesConnectionState.Disconnected -> MytharaColors.Mustard
            else -> MytharaColors.Charple
        }
        Text(
            text = "${Glyph.Dot} state: ${state.name}",
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            GlassesConnectionState.NotInitialized -> Text(
                text = "${Glyph.AccentBar} The Meta AI app isn't installed or registration isn't available " +
                    "yet. Install Meta AI from Google Play, sign in with your Meta account, enable " +
                    "Developer Mode (Settings → About → tap version 5 times), then return here.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            GlassesConnectionState.Initialized -> {
                Text(
                    text = "${Glyph.AccentBar} Ready to pair Mythara with your glasses. " +
                        "Tap below — the Meta AI app will open to walk you through registration.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        (ctx as? Activity)?.let { GlassesDatFacade.startRegistration(it) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Bg,
                    ),
                ) { Text("register glasses") }
            }
            GlassesConnectionState.Paired -> {
                Text(
                    text = "${Glyph.AccentBar} Paired with Meta AI. Start a session to wake the glasses " +
                        "display + camera stream — Mythara will hold the session alive in the " +
                        "background until you stop it or disconnect the glasses.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { GlassesConnectionService.start(ctx) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple,
                            contentColor = MytharaColors.Bg,
                        ),
                    ) { Text("start session") }
                    OutlinedButton(
                        onClick = {
                            (ctx as? Activity)?.let { GlassesDatFacade.startUnregistration(it) }
                        },
                    ) { Text("unregister") }
                }
            }
            GlassesConnectionState.SessionActive -> {
                Text(
                    text = "${Glyph.AccentBar} Session live. Glasses display is rendering Mythara's " +
                        "Root card. Tap-tap on the neural band → photo. Press-and-hold → PTT.",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { GlassesConnectionService.stop(ctx) },
                    ) { Text("stop session") }
                    OutlinedButton(
                        onClick = {
                            (ctx as? Activity)?.let { GlassesDatFacade.startUnregistration(it) }
                        },
                    ) { Text("unregister") }
                }
            }
            GlassesConnectionState.Disconnected -> {
                Text(
                    text = "${Glyph.AccentBar} Session ended — usually because the glasses were folded, " +
                        "Bluetooth dropped, or another app took the device. Tap below to retry.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { GlassesConnectionService.start(ctx) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Bg,
                    ),
                ) { Text("restart session") }
            }
            GlassesConnectionState.Error -> Text(
                text = "${Glyph.AccentBar} The DAT SDK reported an error during initialization. " +
                    "Check that Meta AI is up to date and your phone has Bluetooth permission " +
                    "granted to Mythara.",
                color = MytharaColors.Mustard,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
