package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent settings for **Music Mode** — a private, evolving
 * tone-based language the user and the agent build together. When
 * [enabled] is true, every agent reply is also encoded as a sequence
 * of motifs (short tone phrases, one per content word) played through
 * [com.mythara.music.MusicToneEngine]. Off by default; toggled from
 * the chat composer next to the STT button.
 *
 * Same SharedPreferences-via-DataStore pattern as `ResonanceSettings`
 * / `AutoReplyPrefixStore` / `SpeechMuteStore`.
 */
@Singleton
class MusicModeStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_music_mode")

    private val keyEnabled = booleanPreferencesKey("enabled")

    /** True while the user has opted in to tone-encoded agent replies. */
    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}
