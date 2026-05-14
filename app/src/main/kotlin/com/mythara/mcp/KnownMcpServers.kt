package com.mythara.mcp

/**
 * Curated list of MCP servers the user might want to add. The list is
 * static, baked at compile time — UI surfaces them as one-tap-add
 * entries in the McpServersPanel. The entries that require a token
 * are marked so the panel can prompt for it.
 *
 * Auto-seed policy: only entries marked [autoSeed] = true are added
 * to the user's config on first MCP-registry startup. Today that's
 * just one — the gitmcp.io proxy for the Mythara project's own repo,
 * a known-working HTTP MCP that needs no auth.
 */
data class KnownMcpServer(
    val name: String,
    val url: String,
    val description: String,
    val needsToken: Boolean,
    val autoSeed: Boolean = false,
)

object KnownMcpServers {

    /**
     * Stable id Mythara assigns to each catalog entry's user-added
     * config row. Same hash function as McpServersPanelViewModel.idFromUrl
     * so we can detect "is this catalog entry already configured?".
     */
    fun idFor(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.trim().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    val catalog: List<KnownMcpServer> = listOf(
        // Note: no autoSeed=true entries today. gitmcp.io + most
        // production MCP servers use Server-Sent Events instead of
        // plain HTTP POST JSON-RPC, which Mythara's McpClient doesn't
        // speak yet. When we add SSE support the gitmcp Mythara-docs
        // entry below becomes auto-seedable.
        //
        // For now: every catalog entry is suggestion-only — user
        // taps "add" to put it in their config. Token-required ones
        // are tagged so the panel can flag them. Suggestions are
        // shown sorted catalog order, not by relevance.
        KnownMcpServer(
            name = "Mythara docs (gitmcp.io)",
            url = "https://gitmcp.io/lumilyra2026-ces/project_mythara",
            description = "Search + fetch the Mythara repo via gitmcp.io. Currently uses SSE — adding support in a follow-up.",
            needsToken = false,
        ),
        KnownMcpServer(
            name = "Any GitHub repo (gitmcp.io)",
            url = "https://gitmcp.io/<owner>/<repo>",
            description = "Replace <owner>/<repo> with any GitHub repo to expose its docs as MCP tools. (SSE endpoint.)",
            needsToken = false,
        ),
        KnownMcpServer(
            name = "Linear",
            url = "https://mcp.linear.app/mcp",
            description = "Issue tracking — list/create/update Linear tickets. Requires Linear API token.",
            needsToken = true,
        ),
        KnownMcpServer(
            name = "Notion",
            url = "https://mcp.notion.com/mcp",
            description = "Read/write Notion pages + databases. Requires Notion integration token.",
            needsToken = true,
        ),
        KnownMcpServer(
            name = "GitHub",
            url = "https://api.githubcopilot.com/mcp/",
            description = "GitHub issues / PRs / files via the official Copilot MCP server. Requires GitHub PAT.",
            needsToken = true,
        ),
        KnownMcpServer(
            name = "Asana",
            url = "https://mcp.asana.com/sse",
            description = "Asana tasks + projects. Requires Asana PAT. Note: SSE endpoint — may need fallback.",
            needsToken = true,
        ),
        KnownMcpServer(
            name = "Atlassian (Jira / Confluence)",
            url = "https://mcp.atlassian.com/v1/sse",
            description = "Jira issues + Confluence pages. Requires Atlassian OAuth token.",
            needsToken = true,
        ),
        KnownMcpServer(
            name = "Slack",
            url = "https://slack.com/api/mcp",
            description = "Send + read Slack messages. Requires Slack user / bot token.",
            needsToken = true,
        ),
        KnownMcpServer(
            name = "PagerDuty",
            url = "https://mcp.pagerduty.com/mcp",
            description = "Incidents + escalations. Requires PagerDuty API key.",
            needsToken = true,
        ),
    )
}
