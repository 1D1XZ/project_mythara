package com.mythara.imports

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses WhatsApp's "Export chat" `.txt` files.
 *
 * WhatsApp offers no public API for reading message history — the
 * on-disk SQLite database is encrypted with a key only the WhatsApp
 * process can read. The export feature is the only first-party path,
 * triggered by the user manually:
 *
 *   WhatsApp → open a chat → top-right kebab → more →
 *   Export chat → Without media → choose "Mythara" / Save to Files
 *
 * That produces a `.txt` like:
 *
 *   [12/3/2024, 10:42:15 AM] John Doe: hey
 *   [12/3/2024, 10:43:01 AM] You: not much
 *   12/3/24, 22:42 - John Doe: another format some locales use
 *
 * Two main formats:
 *   • bracketed: `[date, time] name: body`
 *   • dash:      `date, time - name: body`
 * Date/time layouts vary by locale; we try a handful of common ones
 * and skip lines we can't parse. The "You" sender marks the user's
 * own messages.
 */
@Singleton
class WhatsAppExportImporter @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    data class Outcome(val ok: Boolean, val messages: List<MessageRecord>, val detail: String? = null)

    suspend fun import(fileUri: Uri): Outcome = withContext(Dispatchers.IO) {
        val stream = runCatching { ctx.contentResolver.openInputStream(fileUri) }
            .getOrNull()
            ?: return@withContext Outcome(false, emptyList(), "Couldn't open the chosen file.")

        val out = mutableListOf<MessageRecord>()
        var skipped = 0

        try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                // WhatsApp wraps long messages over multiple lines —
                // a continuation line starts WITHOUT a date prefix.
                // We accumulate continuation text into the previous
                // message's body.
                var pending: MessageRecord? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val parsed = parseLine(line)
                    if (parsed != null) {
                        pending?.let { out.add(it) }
                        pending = parsed
                    } else if (pending != null && line.isNotBlank()) {
                        pending = pending.copy(text = pending.text + "\n" + line.trim())
                    } else {
                        skipped++
                    }
                    if (out.size + (pending?.let { 1 } ?: 0) >= MAX_MESSAGES) break
                }
                pending?.let { out.add(it) }
            }
        } catch (t: Throwable) {
            return@withContext Outcome(false, emptyList(), "Couldn't read the export file: ${t.message}")
        }

        Log.d(TAG, "imported ${out.size} messages (skipped $skipped unparseable lines)")
        Outcome(ok = true, messages = out)
    }

    /**
     * Try to parse a single WhatsApp export line. Returns null when
     * the line is a continuation, blank, or unparseable.
     */
    private fun parseLine(line: String): MessageRecord? {
        // Bracketed format: [12/3/2024, 10:42:15 AM] Sender: body
        BRACKETED.matchEntire(line)?.let { m ->
            val dateTime = m.groupValues[1]
            val sender = m.groupValues[2].trim()
            val body = m.groupValues[3].trim()
            val ts = parseDateTime(dateTime) ?: System.currentTimeMillis()
            return makeRecord(sender, body, ts)
        }
        // Dash format: 12/3/24, 22:42 - Sender: body
        DASH.matchEntire(line)?.let { m ->
            val dateTime = m.groupValues[1]
            val sender = m.groupValues[2].trim()
            val body = m.groupValues[3].trim()
            val ts = parseDateTime(dateTime) ?: System.currentTimeMillis()
            return makeRecord(sender, body, ts)
        }
        return null
    }

    private fun makeRecord(sender: String, body: String, ts: Long): MessageRecord {
        // System-event lines (encryption notice, "Messages and calls
        // are end-to-end encrypted") have no contact prefix — sender
        // is the whole line. We skip those.
        if (body.isBlank()) return MessageRecord(
            source = "whatsapp", tsMillis = ts, isFromUser = false,
            contact = null, text = "",
        )
        val isFromUser = sender.equals("you", ignoreCase = true)
        return MessageRecord(
            source = "whatsapp",
            tsMillis = ts,
            isFromUser = isFromUser,
            contact = sender.takeIf { !isFromUser },
            text = body,
        )
    }

    /**
     * Try a handful of date-time format strings in order. WhatsApp
     * adapts to the device locale at export time so we have to be
     * forgiving. Returns epoch ms or null when nothing matches.
     */
    private fun parseDateTime(s: String): Long? {
        for (fmt in DATE_FORMATS) {
            val ts = runCatching {
                val parser = SimpleDateFormat(fmt, Locale.US)
                parser.isLenient = true
                parser.parse(s)?.time
            }.getOrNull()
            if (ts != null) return ts
        }
        return null
    }

    companion object {
        private const val TAG = "Mythara/WAImport"
        private const val MAX_MESSAGES = 3_000

        /** `[12/3/2024, 10:42:15 AM] Name: body`  — Android / web format */
        private val BRACKETED = Regex("""^\[(.+?)] ([^:]+): (.*)$""")

        /** `12/3/24, 22:42 - Name: body`  — older format some locales still emit */
        private val DASH = Regex("""^([0-9/.,: APM-]+) - ([^:]+): (.*)$""")

        /** Common WhatsApp date/time formats. We try them in order. */
        private val DATE_FORMATS = listOf(
            "M/d/yyyy, h:mm:ss a",
            "M/d/yy, h:mm:ss a",
            "M/d/yy, HH:mm:ss",
            "M/d/yy, HH:mm",
            "M/d/yy, h:mm a",
            "d/M/yy, HH:mm",
            "d/M/yy, h:mm a",
            "d/M/yyyy, HH:mm",
            "yyyy-MM-dd, HH:mm:ss",
            "dd.MM.yy, HH:mm",
        )
    }
}
