package com.mythara.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Mythara's tool interface — the same shape Crush exposes to its agent
 * loop (and the same shape MiniMax / OpenAI function-calling expects).
 *
 * Each tool is a `suspend fun execute(JsonObject) -> ToolResult`. The
 * `parameters` JSON-schema describes its argument surface; that schema
 * goes out over the wire to MiniMax so the model knows how to invoke it.
 *
 * Tools should be pure observation or low-side-effect by default. Anything
 * destructive or sensitive (SMS, calls, payments, taps) must declare
 * `requiresConfirmation = true` so the ConfirmationGate prompts before
 * execution. Seed tools (time / battery / fetch) are all read-only.
 */
interface Tool {
    /** Stable identifier — the model addresses tools by this name. */
    val name: String

    /** One-line description shown to the model. Keep ≤ ~100 chars; tokens cost money. */
    val description: String

    /** JSON-schema for the arguments. `JsonElement` so callers can build it however they like. */
    val parameters: JsonElement

    /** True if a per-call confirmation prompt should fire before execute() runs. */
    val requiresConfirmation: Boolean get() = false

    /**
     * Run the tool. `args` is the JSON-parsed object passed by the model.
     * The result's `output` is what we hand back as a `role: tool` message —
     * the model sees it on its next turn. Truncate long outputs upstream;
     * the runtime caps strings at 8KB before sending to keep contexts sane.
     */
    suspend fun execute(args: JsonObject): ToolResult
}

/**
 * Result of one tool invocation.
 *
 * @param ok       Did the tool succeed?
 * @param output   String body — what gets surfaced both to the model
 *                 (as the `tool` message content) and to the UI
 *                 (truncated, as the preview in the ToolCallBubble).
 */
data class ToolResult(val ok: Boolean, val output: String) {
    companion object {
        fun ok(output: String) = ToolResult(ok = true, output = output)
        fun fail(reason: String) = ToolResult(ok = false, output = reason)
    }
}
