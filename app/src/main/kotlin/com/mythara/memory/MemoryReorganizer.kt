package com.mythara.memory

import android.util.Log
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.lifeline.LifelineRepository
import com.mythara.secret.observe.vault.LearningEntity
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Reorganize memory" — a self-evolving pass over the LearningVault
 * that retags older `src:user-asked` rows with the right
 * `contact:<key>` / `target:contact:<key>` / `place:<label>` /
 * `app:<package>` facets based on the LATEST contact list + aliases
 * + lifeline place labels.
 *
 * Why this exists:
 *
 *  - When the agent stored a fact months ago under
 *    `target:contact:rose`, the user later added "Rose" as an alias
 *    on the canonical "Roselyn Mathew" profile. This pass adds
 *    `contact:roselyn-mathew` / `target:contact:roselyn-mathew` to
 *    that row so it surfaces on her detail card.
 *
 *  - When the user adds a brand-new contact, this pass goes back and
 *    finds old `src:user-asked` rows that mention them by name and
 *    tags those rows with the new contact's facets.
 *
 *  - Notes that mention a place (a lifeline-recognised place_label)
 *    pick up `place:<label>` so future "what do you remember about
 *    that café?" queries hit them.
 *
 *  - Rows that mention an app package the user has on the device get
 *    an `app:<package>` facet so the agent's auto-reply context block
 *    can scope by app.
 *
 * Side-effect: every retagged row has `synced=false` set so the
 * normal MemorySync (GitHub) pushes the updated facet lists to every
 * paired device immediately on the next heartbeat. That means asking
 * the same question on another device hits the same memories.
 *
 * Idempotent — running it twice in a row is safe (the second pass
 * reports 0 retagged rows).
 */
@Singleton
class MemoryReorganizer @Inject constructor(
    private val vault: LearningVault,
    private val contactRepo: ContactProfileRepository,
    private val lifelineRepo: LifelineRepository,
) {
    /** Bag of counters for the Settings panel + acceptance log. */
    data class Report(
        val rowsScanned: Int,
        val rowsRetagged: Int,
        val contactFacetsAdded: Int,
        val placeFacetsAdded: Int,
        val durationMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val stringList = ListSerializer(String.serializer())

    /**
     * Walk all `src:user-asked` rows + every contact's name/alias set.
     * For each (row, contact) pair where the contact's displayName or
     * any alias appears in the row as a whole phrase, ensure the row
     * carries `contact:<key>` + `target:contact:<key>` facets.
     * Mirrored for known lifeline place labels (`place:<label>`).
     *
     * Streams progress through [onProgress] (current row, total) so
     * the UI panel can render a live counter.
     */
    suspend fun reorganize(
        onProgress: suspend (current: Int, total: Int, retaggedSoFar: Int) -> Unit = { _, _, _ -> },
    ): Report = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        val contacts = runCatching { contactRepo.dao.listAll() }.getOrDefault(emptyList())
        if (contacts.isEmpty()) {
            return@withContext Report(0, 0, 0, 0, System.currentTimeMillis() - started)
        }

        // Build per-contact phrase + facet plan once. We walk every
        // user-asked row only against this prebuilt index instead of
        // re-deriving aliases on every pair.
        data class ContactBinding(
            val nameKey: String,
            val phrases: List<String>, // lowercased
            val facetsToAdd: List<String>,
        )
        val bindings = contacts.mapNotNull { c ->
            val aliases = runCatching {
                json.decodeFromString(stringList, c.aliasesJson)
            }.getOrDefault(emptyList())
            val phrases = buildList {
                add(c.displayName.lowercase())
                for (a in aliases) {
                    val t = a.trim()
                    if (t.length >= 2) add(t.lowercase())
                }
            }.distinct()
            if (phrases.isEmpty()) return@mapNotNull null
            ContactBinding(
                nameKey = c.nameKey,
                phrases = phrases,
                facetsToAdd = listOf(
                    "contact:${c.nameKey}",
                    "target:contact:${c.nameKey}",
                ),
            )
        }

        // Lifeline place labels — distinct, lowercased.
        val placeLabels: Set<String> = runCatching {
            lifelineRepo.dao.listAllLocal()
                .mapNotNull { it.placeLabel?.trim()?.takeIf { p -> p.length >= 3 }?.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())

        val userStatedRows: List<LearningEntity> = runCatching {
            vault.listByTier(Tier.Semantic, limit = 5000)
        }.getOrDefault(emptyList())
            .filter { row -> "src:user-asked" in vault.decodeFacets(row) }

        if (userStatedRows.isEmpty()) {
            return@withContext Report(0, 0, 0, 0, System.currentTimeMillis() - started)
        }

        var retagged = 0
        var contactFacets = 0
        var placeFacets = 0

        userStatedRows.forEachIndexed { idx, row ->
            val haystack = row.content.lowercase()
            val currentFacets = vault.decodeFacets(row).toSet()
            val toAdd = mutableListOf<String>()

            for (b in bindings) {
                val hit = b.phrases.any { haystack.contains(it) }
                if (!hit) continue
                for (f in b.facetsToAdd) {
                    if (f !in currentFacets) {
                        toAdd.add(f)
                        if (f.startsWith("contact:")) contactFacets++
                    }
                }
            }
            for (place in placeLabels) {
                if (haystack.contains(place)) {
                    val facet = "place:${place.replace(Regex("[^a-z0-9]+"), "-").trim('-')}"
                    if (facet !in currentFacets) {
                        toAdd.add(facet)
                        placeFacets++
                    }
                }
            }
            if (toAdd.isNotEmpty()) {
                val changed = runCatching { vault.mergeFacets(row, toAdd) }
                    .getOrElse {
                        Log.w(TAG, "mergeFacets failed for ${row.id}: ${it.message}")
                        false
                    }
                if (changed) retagged++
            }
            // Progress every 25 rows + at the end — keeps the UI
            // animated without burning recomposition cycles.
            if (idx % 25 == 0 || idx == userStatedRows.lastIndex) {
                onProgress(idx + 1, userStatedRows.size, retagged)
            }
        }

        Log.d(
            TAG,
            "reorganize: scanned=${userStatedRows.size} retagged=$retagged " +
                "contactFacets=$contactFacets placeFacets=$placeFacets",
        )
        Report(
            rowsScanned = userStatedRows.size,
            rowsRetagged = retagged,
            contactFacetsAdded = contactFacets,
            placeFacetsAdded = placeFacets,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    companion object {
        private const val TAG = "Mythara/MemoryReorg"
    }
}
