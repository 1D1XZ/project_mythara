package com.mythara.face

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling persistent history of face-detected mood sessions. Backs the
 * "shape evolves based on emotion + LEARNS from past expressions"
 * behaviour:
 *
 *   1. EmotionDetector publishes a (mood, intensity) reading per
 *      session (face-detect → 8s window → face-undetect).
 *   2. FaceMesh appends a [MoodSession] row to this store at the end
 *      of each session, capturing the dominant mood + intensity +
 *      which shape was actually rendered.
 *   3. The NEXT session, FaceMesh reads the recent list and feeds it
 *      to [ShapeMoodMapping.pickShape] which BIASES the random shape
 *      pick toward shapes that have matched the user's recurring
 *      moods.
 *
 * So a user who's been calm + contemplative all week starts seeing
 * tori and octahedrons more often; a high-energy week tilts toward
 * trefoil knots and icosahedrons; the catalogue isn't pre-assigned,
 * it's earned by the user's own emotional history.
 *
 * Capped at [HISTORY_LIMIT] rows so a one-time outlier doesn't shift
 * the steady-state weighting permanently. All local; never synced.
 */
@Singleton
class MoodHistoryStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    @Serializable
    data class MoodSession(
        val tsMs: Long,
        val mood: String,
        val intensity: Float,
        val durationMs: Long,
        val shapeKind: String,
    )

    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_mood_history")
    private val keyHistory = stringPreferencesKey("sessions.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(MoodSession.serializer())

    /** Append a session, trimming to the rolling window. */
    suspend fun record(session: MoodSession) {
        val current = list().toMutableList()
        current.add(session)
        save(current.takeLast(HISTORY_LIMIT))
    }

    /** Read all stored sessions, oldest first. */
    suspend fun list(): List<MoodSession> {
        val raw = ctx.dataStore.data.first()[keyHistory] ?: return emptyList()
        return runCatching { json.decodeFromString(ser, raw) }.getOrDefault(emptyList())
    }

    /** Just the recent mood labels — used by [ShapeMoodMapping] to
     *  bias the next shape pick. [limit] defaults to 7 which felt
     *  right in dev: long enough to remember "this user has been
     *  calm all evening", short enough to react to "today they're
     *  excited". */
    suspend fun recentMoods(limit: Int = 7): List<String> =
        list().takeLast(limit).map { it.mood }

    private suspend fun save(items: List<MoodSession>) {
        ctx.dataStore.edit { it[keyHistory] = json.encodeToString(ser, items) }
    }

    companion object {
        const val HISTORY_LIMIT = 50
    }
}
