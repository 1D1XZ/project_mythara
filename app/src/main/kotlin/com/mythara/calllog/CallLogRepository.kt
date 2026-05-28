package com.mythara.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.analytics.interactions.ContactInteractionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Source of a captured call. */
enum class CallSource { PHONE, WHATSAPP }

/** Direction of a call. */
enum class CallKind { INCOMING, OUTGOING, MISSED }

/** One merged call-log entry. [name] is the system contact name when
 *  available, else the raw number; [number] is null for WhatsApp-
 *  captured entries that didn't carry a phone number. */
data class CallLogEntry(
    val tsMs: Long,
    val name: String,
    val number: String?,
    val kind: CallKind,
    val durationMs: Long,
    val source: CallSource,
)

/**
 * v7 P5 — single source of truth for the consolidated call log.
 *
 * Merges:
 *  - the SYSTEM phone log (`CallLog.Calls`, needs READ_CALL_LOG —
 *    declared in manifest, requested at runtime), which has full
 *    historical depth with cached names + durations.
 *  - WhatsApp / VoIP call notifications captured by
 *    [com.mythara.services.NotificationListener] into
 *    `contact_interactions` (kind = call_incoming / call_outgoing,
 *    source = notification). Forward-only — WhatsApp exposes no API
 *    for historical call list, so we only see calls captured since
 *    notification access was granted.
 *
 * Returns entries newest-first. Empty when no permission + no
 * notification-captured calls. Off-main thread.
 */
@Singleton
class CallLogRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val interactions: ContactInteractionRepository,
) {

    fun hasPhoneLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun loadAll(limit: Int = 400): List<CallLogEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<CallLogEntry>(limit)

        // 1. System phone call log.
        if (hasPhoneLogPermission()) {
            runCatching {
                val cols = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_NAME,
                )
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    cols,
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC LIMIT $limit",
                )?.use { c ->
                    val iNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val iDur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val iName = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    while (c.moveToNext()) {
                        val number = c.getString(iNum)
                        val name = c.getString(iName).takeUnless { it.isNullOrBlank() }
                            ?: number ?: "unknown"
                        val typeRaw = c.getInt(iType)
                        val kind = when (typeRaw) {
                            CallLog.Calls.INCOMING_TYPE -> CallKind.INCOMING
                            CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
                            CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
                            CallLog.Calls.REJECTED_TYPE -> CallKind.MISSED
                            CallLog.Calls.VOICEMAIL_TYPE -> CallKind.INCOMING
                            else -> CallKind.INCOMING
                        }
                        out += CallLogEntry(
                            tsMs = c.getLong(iDate),
                            name = name,
                            number = number,
                            kind = kind,
                            durationMs = c.getLong(iDur) * 1_000L,
                            source = CallSource.PHONE,
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "phone log query failed: ${it.message}") }
        }

        // 2. WhatsApp / notification-captured calls from
        //    contact_interactions (kind = call_incoming / call_outgoing).
        runCatching {
            val rows = interactions.dao.listAll(limit = 2_000)
            rows.asSequence()
                .filter { it.kind == "call_incoming" || it.kind == "call_outgoing" }
                .map { r ->
                    CallLogEntry(
                        tsMs = r.tsMs,
                        name = r.nameKey, // nameKey is a lowercase display name; UI title-cases
                        number = null,
                        kind = if (r.kind == "call_incoming") CallKind.INCOMING else CallKind.OUTGOING,
                        durationMs = 0L, // not captured from notifications
                        source = CallSource.WHATSAPP,
                    )
                }
                .forEach { out += it }
        }.onFailure { Log.w(TAG, "interactions query failed: ${it.message}") }

        // Newest first.
        out.sortByDescending { it.tsMs }
        if (out.size > limit) out.subList(limit, out.size).clear()
        Log.d(TAG, "loadAll: ${out.size} entries")
        out
    }

    companion object {
        private const val TAG = "Mythara/CallLog"
    }
}
