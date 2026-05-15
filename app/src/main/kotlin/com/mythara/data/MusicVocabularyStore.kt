package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent storage for the **Music Mode vocabulary** — the
 * accumulating map of `token → motif` that the agent and user build
 * together. Co-located in `data/` with the other DataStore singletons
 * so the live-state read path stays cheap, while the
 * [com.mythara.secret.observe.vault.LearningVault] is kept clean
 * (vocabulary is high-churn live state, not durable knowledge).
 *
 * The whole vocabulary is serialised as a single JSON blob keyed by
 * the lowercase-and-stripped token. Even at thousands of tokens this
 * stays under a few hundred KB and writes are batched by DataStore,
 * so the simplicity wins over a relational model.
 *
 * Schema (JSON):
 * ```
 * {
 *   "hello": {"n": [392.0, 523.25, 659.25], "h": 4, "m": 1, "g": 3},
 *   "watch": {"n": [261.63, 329.63, 440.0], "h": 0, "m": 2, "g": 1},
 *   ...
 * }
 * ```
 * where `n` = note frequencies in Hz, `h` = hits, `m` = misses,
 * `g` = generation (incremented on mutation when confidence drops).
 */
@Singleton
class MusicVocabularyStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_music_vocab")

    private val keyVocab = stringPreferencesKey("vocab_json")

    /** Raw JSON blob — observers can decode in-place. */
    fun vocabFlow(): Flow<String> = ctx.dataStore.data.map { it[keyVocab] ?: "{}" }

    /** Overwrite the entire vocabulary blob. Called by
     *  [com.mythara.music.MusicVocabulary] after batched mutations. */
    suspend fun setVocab(json: String) {
        ctx.dataStore.edit { it[keyVocab] = json }
    }
}
