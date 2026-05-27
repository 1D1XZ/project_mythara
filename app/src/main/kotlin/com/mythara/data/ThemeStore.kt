package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythara.ui.theme.SkinId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How light/dark is chosen. User default = [TimeOfDay] (auto). */
enum class BrightnessMode { Light, Dark, System, TimeOfDay }

/**
 * Persists the user's theme choice: which visual [SkinId] + how
 * brightness is resolved ([BrightnessMode]). Mirrors the SettingsStore
 * DataStore idiom. Read by [com.mythara.ui.theme.MytharaTheme] via a
 * Hilt EntryPoint so it works in Activity AND Service compose hosts.
 */
@Singleton
class ThemeStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val keySkin = stringPreferencesKey("skin")
    private val keyBrightness = stringPreferencesKey("brightnessMode")

    fun skinFlow(): Flow<SkinId> = ctx.dataStore.data.map { prefs ->
        prefs[keySkin]?.let { runCatching { SkinId.valueOf(it) }.getOrNull() }
            ?: SkinId.SpatialCards   // default skin
    }

    fun brightnessModeFlow(): Flow<BrightnessMode> = ctx.dataStore.data.map { prefs ->
        prefs[keyBrightness]?.let { runCatching { BrightnessMode.valueOf(it) }.getOrNull() }
            ?: BrightnessMode.TimeOfDay   // user default: auto by time of day
    }

    suspend fun setSkin(skin: SkinId) {
        ctx.dataStore.edit { it[keySkin] = skin.name }
    }

    suspend fun setBrightnessMode(mode: BrightnessMode) {
        ctx.dataStore.edit { it[keyBrightness] = mode.name }
    }

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
            name = "mythara_theme",
        )
    }
}
