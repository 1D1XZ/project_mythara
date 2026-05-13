package com.mythara.wake

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
 * Persistence for the "Listen for Lumi" toggle in main Settings.
 *
 * Plain DataStore (not Tink-encrypted) because the toggle itself isn't
 * a secret — it's user preference. The wake-word model file is bundled
 * in the APK assets, not user-provided at runtime.
 */
@Singleton
class WakeWordSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_wake_word")

    private val keyEnabled = booleanPreferencesKey("lumi.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}
