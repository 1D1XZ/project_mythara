package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.analytics.ContactProfileRepository
import com.mythara.analytics.ContactProfileRow
import com.mythara.me.MeProfileStore
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
 * `read_profile` — gives the agent first-class access to the
 * persona profiles Mythara has built for the user (themselves) and
 * for every learned contact.
 *
 * Reads the same row [com.mythara.ui.about.AboutMeScreen] +
 * [com.mythara.ui.analytics.PeopleScreen] render — Big Five scores,
 * notable traits, key points, relationship summary, personality
 * insights, user-authored notes, cross-app aliases, source apps,
 * favourite flag, message + image counts.
 *
 * Use cases (the agent should reach for this tool whenever):
 *   - The user asks introspective questions: "what does Mythara
 *     know about me?", "what's my personality profile?", "remind
 *     me of John's Big Five".
 *   - The user wants to compare or correlate: "who do I message
 *     at the same hour as me?", "which of my contacts are most
 *     similar to me on agreeableness?".
 *   - The agent itself needs persona context before drafting an
 *     auto-reply (today the AutoReplyDispatcher pre-injects this;
 *     this tool lets the chat agent ask for it on demand too).
 *
 * Two query shapes:
 *   - `who: "me"` returns the user's own profile (from
 *     MeProfileStore + the special "self" entry in the contact
 *     repo, when present).
 *   - `who: "<name>"` does a substring match against displayName
 *     and aliases of every contact row, returns up to MAX_RESULTS
 *     ranked by message-count.
 *
 * Companion to [com.mythara.agent.tools.UpdateProfileInsightTool]
 * which lets the agent WRITE traits + insights back to the
 * profile — both tools share a contract about what fields exist.
 */
@Singleton
class ReadProfileTool @Inject constructor(
    private val repo: ContactProfileRepository,
    private val me: MeProfileStore,
) : Tool {

    override val name: String = "read_profile"
    override val description: String =
        "Read the persona profile (Big Five, traits, key points, " +
            "relationship summary, cross-app aliases, message stats) for either " +
            "the user themselves or a named contact. Use 'me' to fetch the user's " +
            "own profile, or pass a contact name to search People."

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
                            "Either the literal string 'me' for the user's own profile, " +
                                "or a contact name fragment (case-insensitive substring match against " +
                                "display name + cross-app aliases).",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("who"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val who = (args["who"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (who.isEmpty()) {
            return ToolResult.fail(
                """{"error":"missing_who","detail":"Pass 'who' = 'me' or a contact name."}""",
            )
        }
        return if (who.equals("me", ignoreCase = true)) {
            ToolResult.ok(JSON.encodeToString(JsonObject.serializer(), buildSelfPayload()))
        } else {
            val rows = findContacts(who)
            ToolResult.ok(
                JSON.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("count", JsonPrimitive(rows.size))
                        put("contacts", JsonArray(rows.map { contactToJson(it) }))
                    },
                ),
            )
        }
    }

    private suspend fun buildSelfPayload(): JsonObject {
        val meProfile = me.snapshot()
        val selfRow = repo.dao.byKey(SELF_KEY)
        return buildJsonObject {
            put("kind", JsonPrimitive("self"))
            put("displayName", JsonPrimitive(meProfile.displayName.ifBlank { "Me" }))
            put("aliases", JsonArray(meProfile.aliases.map { JsonPrimitive(it) }))
            put("phones", JsonArray(meProfile.phones.map { JsonPrimitive(it) }))
            // The contact-repo "self" row is a side-channel for
            // the agent's running synthesis — the reflective insights
            // it accumulates over time. May or may not exist yet.
            if (selfRow != null) {
                put("profile", contactToJson(selfRow))
            } else {
                put(
                    "profile",
                    buildJsonObject {
                        put(
                            "note",
                            JsonPrimitive(
                                "No accumulated self-profile yet — call update_profile_insight " +
                                    "with who='me' to start writing reflective insights.",
                            ),
                        )
                    },
                )
            }
        }
    }

    private suspend fun findContacts(query: String): List<ContactProfileRow> {
        val all = runCatching { repo.dao.listAll() }.getOrDefault(emptyList())
        val q = query.lowercase()
        return all.filter { row ->
            row.displayName.lowercase().contains(q) ||
                aliasMatches(row.aliasesJson, q) ||
                row.nameKey.contains(q)
        }
            .sortedByDescending { it.messageCount }
            .take(MAX_RESULTS)
    }

    private fun aliasMatches(aliasesJson: String, q: String): Boolean = runCatching {
        val arr = JSON.parseToJsonElement(aliasesJson) as? JsonArray ?: return@runCatching false
        arr.any { (it as? JsonPrimitive)?.content?.lowercase()?.contains(q) == true }
    }.getOrDefault(false)

    private fun contactToJson(row: ContactProfileRow): JsonObject = buildJsonObject {
        put("nameKey", JsonPrimitive(row.nameKey))
        put("displayName", JsonPrimitive(row.displayName))
        row.phone?.let { put("phone", JsonPrimitive(it)) }
        put("isFavorite", JsonPrimitive(row.isFavorite))
        put("isAutoAdded", JsonPrimitive(row.isAutoAdded))
        put("messageCount", JsonPrimitive(row.messageCount))
        put("imageCount", JsonPrimitive(row.imageCount))
        put("firstSeenMs", JsonPrimitive(row.firstSeenMs))
        put("lastInteractionMs", JsonPrimitive(row.lastInteractionMs))
        // Cross-app discovery info (added in the v5→v6 schema bump).
        put("aliases", parseList(row.aliasesJson))
        put("sourceApps", parseList(row.sourceAppsJson))
        // Persona block — what the analytics builder + this very
        // agent's update tool have written.
        row.relationshipSummary?.let { put("relationshipSummary", JsonPrimitive(it)) }
        row.personalityInsights?.let { put("personalityInsights", JsonPrimitive(it)) }
        row.toneLabel?.let { put("toneLabel", JsonPrimitive(it)) }
        row.userNotes?.let { put("userNotes", JsonPrimitive(it)) }
        // Big Five — null when the sample is too small.
        if (row.openness != null) {
            put(
                "bigFive",
                buildJsonObject {
                    put("openness", JsonPrimitive(row.openness))
                    put("conscientiousness", JsonPrimitive(row.conscientiousness ?: 0.0))
                    put("extraversion", JsonPrimitive(row.extraversion ?: 0.0))
                    put("agreeableness", JsonPrimitive(row.agreeableness ?: 0.0))
                    put("neuroticism", JsonPrimitive(row.neuroticism ?: 0.0))
                    put("sampleSize", JsonPrimitive(row.bigFiveSampleSize))
                },
            )
        }
        put("topTopics", parseList(row.topTopicsJson))
        put("notableTraits", parseList(row.notableTraitsJson))
        put("keyPoints", parseList(row.keyPointsJson))
    }

    private fun parseList(json: String): JsonArray = runCatching {
        JSON.parseToJsonElement(json) as? JsonArray ?: JsonArray(emptyList())
    }.getOrDefault(JsonArray(emptyList()))

    companion object {
        /** Stable Room PK used for the user's own ContactProfileRow.
         *  Allows the agent to write reflective insights about the
         *  user using the same write path as for any other contact. */
        const val SELF_KEY = "self:me"
        private const val MAX_RESULTS = 5
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
