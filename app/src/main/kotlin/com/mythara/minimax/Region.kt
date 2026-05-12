package com.mythara.minimax

/**
 * MiniMax has two distinct deployments with non-interchangeable API keys.
 * Pick one explicitly in Settings — guessing is the #1 cause of bogus
 * "invalid api key" errors (code 2049).
 *
 * The OpenAI-compatible chat endpoint lives at `{baseUrl}chat/completions`
 * regardless of region. Speech (T2A / STT) and any future endpoints follow
 * the same `{baseUrl}<path>` shape.
 */
enum class Region(val label: String, val baseUrl: String) {
    Global("Global (minimax.io)", "https://api.minimax.io/v1/"),
    China ("China (minimaxi.com)", "https://api.minimaxi.com/v1/");

    companion object {
        val Default: Region = Global
        fun fromId(id: String?): Region = entries.firstOrNull { it.name == id } ?: Default
    }
}
