package com.mythara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mythara.ui.chat.ChatScreen
import com.mythara.ui.settings.SettingsScreen
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaTheme

/**
 * Compose root. Owns the theme + nav graph. Two routes for M2 — chat
 * (home) and settings — wire up via a NavHost so chat → settings → back
 * is a single navigation transaction with persisted state.
 *
 * Later routes (About → Secret unlock → Secret settings → Observe vault)
 * compose into the same NavHost in M8.
 */
@Composable
fun MytharaRoot() {
    val nav = rememberNavController()
    MytharaTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MytharaColors.Bg),
        ) {
            NavHost(navController = nav, startDestination = Routes.Chat) {
                composable(Routes.Chat) {
                    ChatScreen(onOpenSettings = { nav.navigate(Routes.Settings) })
                }
                composable(Routes.Settings) {
                    SettingsScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
}
