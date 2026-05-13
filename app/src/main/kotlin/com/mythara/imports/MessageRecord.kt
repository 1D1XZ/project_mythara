package com.mythara.imports

/**
 * One imported message from a chat history dump (SMS provider scan,
 * WhatsApp `.txt` export, future Signal/Telegram backups). Used as
 * the intermediate format the persona extractor walks over.
 *
 * Not persisted directly — the extractor reduces a list of these
 * into a handful of persona-trait vault records (top contacts,
 * activity rhythm, common phrases, etc.). Raw messages stay on the
 * device only during the import run.
 *
 * Privacy stance: even though the raw messages don't get persisted
 * verbatim, the user's intent at import time is to teach Mythara
 * about themselves — extracted patterns (kind:persona facets) DO
 * sync to their GitHub backup like every other vault record. The
 * import panel makes this explicit.
 */
data class MessageRecord(
    /** Source identifier: "sms", "whatsapp", future "signal", "telegram", etc. */
    val source: String,

    /** Epoch ms the message was sent/received. */
    val tsMillis: Long,

    /** True if the user authored this message; false for inbound. */
    val isFromUser: Boolean,

    /**
     * Contact identifier — phone number for SMS, display name for
     * WhatsApp exports. Null when we couldn't resolve it.
     */
    val contact: String?,

    /** Message body. We never persist this verbatim; only patterns. */
    val text: String,
)
