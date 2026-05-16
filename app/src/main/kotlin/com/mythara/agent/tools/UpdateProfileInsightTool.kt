package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `update_profile_insight` — lets the agent WRITE back to the
 * persona profile of either the user themselves or a named
 * contact. Companion to [ReadProfileTool].
 *
 * Why this exists:
 *   The user explicitly asked: "the agent should be able to ...
 *   update those [insights] based on ongoing interactions, same
 *   way for other contacts as well, and it must show up in
 *   contact learnings and be ingested into memory and learnings".
 *
 * Two-track write:
 *   1. **ContactProfileRepository** — the durable persona row the
 *      About-Me + People screens render. Updates land here so the
 *      next time the user opens those surfaces the agent's new
 *      observations are visible.
 *   2. **LearningVault** — every insight is ALSO appended to the
 *      vault as a Tier.Semantic row tagged
 *      `agent:profile-insight` + `contact:<key>`. That way the
 *      semantic-recall pipeline picks the insight up on future
 *      turns + the daily-summariser worker can fold it into the
 *      next day's review.
 *
 * Insight kinds:
 *   - `trait` — short adjective ("warm", "anxious", "values
 *     directness"). Appended to the row's notableTraits list, max
 *     20 entries (FIFO drop).
 *   - `key_point` — a SHORT actionable thing the agent should
 *     remember before the next conversation ("starting new job at
 *     Acme on Mon", "going through divorce — be gentle"). Appended
 *     to keyPoints, max 12 entries (FIFO drop).
 *   - `note` — free-form paragraph. Appended to relationshipSummary
 *     with a "\n\n— observed YYYY-MM-DD" bumper. The summary cap
 *     is the row's natural growth; the analytics builder will
 *     re-summarise periodically.
 *
 * Append-only: this tool never overwrites the analytics-builder's
 * fields wholesale — the builder runs its own Gemma pass + would
 * race the agent. Instead each call is additive, and the agent's
 * additions persist across the builder's rebuilds because the
 * builder reads the existing row before re-writing it.
 *
 * Read-only safety: no ConfirmationGate. Updates are minor + the
 * audit happens via the vault row.
 */
@Singleton
class UpdateProfileInsightTool @Inject constructor(
    private val repo: ContactProfileRepository,
    private val vault: LearningVault,
) : Tool {

    override val name: String = "update_profile_insight"
    override val description: String =
        "Update the persona profile of the user ('me') or a named contact with a new " +
            "observed insight. Kinds: 'trait' (short adjective), 'key_point' (actionable note), " +
            "or 'note' (free-form paragraph appended to relationship summary). Use after a " +
            "conversation reveals something durable about the person."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "who",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "'me' for the user's own profile, or a contact's nameKey/displayName " +
                                "(must already exist — call read_profile first to find it).",
                        )
                    },
                )
                put(
                    "kind",
                    buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add(JsonPrimitive("trait")); add(JsonPrimitive("key_point")); add(JsonPrimitive("note")) })
                        put("description", "What shape of insight to record.")
                    },
                )
                put(
                    "text",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The insight body. trait = ≤8 words, key_point = ≤120 chars, " +
                                "note = ≤400 chars (longer gets truncated).",
                        )
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("who")); add(JsonPrimitive("kind")); add(JsonPrimitive("text")) })
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val who = (args["who"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val kind = (args["kind"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
        val text = (args["text"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (who.isEmpty() || kind.isEmpty() || text.isEmpty()) {
            return ToolResult.fail(
                """{"error":"missing_field","detail":"who/kind/text are all required."}""",
            )
        }
        if (kind !in VALID_KINDS) {
            return ToolResult.fail(
                """{"error":"invalid_kind","detail":"kind must be one of trait | key_point | note."}""",
            )
        }

        // Resolve the row. "me" maps to the special SELF_KEY; any
        // other input is treated as either a nameKey lookup or a
        // displayName substring search.
        val isMe = who.equals("me", ignoreCase = true)
        val nameKey = if (isMe) ReadProfileTool.SELF_KEY else who.lowercase().trim()
        val existing = if (isMe) {
            repo.dao.byKey(ReadProfileTool.SELF_KEY) ?: createSelfRow()
        } else {
            repo.dao.byKey(nameKey)
                ?: repo.dao.listAll().firstOrNull { row ->
                    row.displayName.lowercase().contains(who.lowercase()) ||
                        row.nameKey.contains(who.lowercase())
                }
                ?: return ToolResult.fail(
                    """{"error":"contact_not_found","detail":"No contact matched '$who'. Call read_profile first to find the right name."}""",
                )
        }

        // Compute the updated row.
        val updated = applyInsight(existing, kind, text)
        runCatching { repo.dao.upsert(updated) }
            .onFailure {
                return ToolResult.fail("""{"error":"db_write_failed","detail":"${it.message}"}""")
            }

        // Mirror to the vault so the semantic-recall + daily-
        // summariser pipelines see the agent's new observation.
        runCatching {
            vault.add(
                content = "[$kind] $text",
                tier = Tier.Semantic,
                src = "agent:profile-insight",
                facets = listOf(
                    "agent:profile-insight",
                    "contact:${updated.nameKey}",
                    "kind:$kind",
                ),
                conf = 0.85,
            )
        }

        return ToolResult.ok(
            """{"ok":true,"contact":"${updated.nameKey}","kind":"$kind"}""",
        )
    }

    private fun applyInsight(row: ContactProfileRow, kind: String, text: String): ContactProfileRow {
        val now = System.currentTimeMillis()
        return when (kind) {
            "trait" -> {
                val list = decode(row.notableTraitsJson).toMutableList()
                val clean = text.take(60)
                if (list.none { it.equals(clean, ignoreCase = true) }) {
                    list += clean
                    while (list.size > MAX_TRAITS) list.removeAt(0)
                }
                row.copy(
                    notableTraitsJson = encode(list),
                    lastBuiltMs = now,
                )
            }
            "key_point" -> {
                val list = decode(row.keyPointsJson).toMutableList()
                val clean = text.take(120)
                if (list.none { it.equals(clean, ignoreCase = true) }) {
                    list += clean
                    while (list.size > MAX_KEY_POINTS) list.removeAt(0)
                }
                row.copy(
                    keyPointsJson = encode(list),
                    lastBuiltMs = now,
                )
            }
            "note" -> {
                val clean = text.take(400)
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
                val bumper = "$clean\n\n— observed $date"
                val merged = if (row.relationshipSummary.isNullOrBlank()) bumper
                else row.relationshipSummary + "\n\n" + bumper
                row.copy(
                    relationshipSummary = merged.takeLast(2000),
                    lastBuiltMs = now,
                )
            }
            else -> row
        }
    }

    /** Synthesise a baseline self-row when the user has none yet.
     *  Subsequent insight writes update this one in place. */
    private fun createSelfRow(): ContactProfileRow {
        val now = System.currentTimeMillis()
        return ContactProfileRow(
            nameKey = ReadProfileTool.SELF_KEY,
            displayName = "Me",
            phone = null,
            isFavorite = true,  // self is always pinned
            firstSeenMs = now,
            lastInteractionMs = now,
            messageCount = 0,
            isAutoAdded = false,
            lastBuiltMs = now,
        )
    }

    private fun decode(json: String): List<String> = runCatching {
        val arr = JSON.parseToJsonElement(json) as? JsonArray ?: return@runCatching emptyList()
        arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }.getOrDefault(emptyList())

    private fun encode(list: List<String>): String =
        JSON.encodeToString(JsonArray.serializer(), JsonArray(list.map { JsonPrimitive(it) }))

    companion object {
        private val VALID_KINDS = setOf("trait", "key_point", "note")
        private const val MAX_TRAITS = 20
        private const val MAX_KEY_POINTS = 12
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
