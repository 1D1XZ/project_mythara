package com.mythara.imports

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports recent SMS history via [Telephony.Sms] ContentProvider.
 * Mythara needs the `READ_SMS` runtime permission, which Play Store
 * gates behind "default SMS app" status — fine for us since we're
 * sideload-only.
 *
 * Reads both inbox + sent message tables; merges + sorts descending
 * by date. Caps at [MAX_MESSAGES] so a user with 10k SMS doesn't
 * pin Mythara for 30 seconds parsing.
 *
 * Phone numbers are best-effort resolved to contact display names
 * via ContactsContract.PhoneLookup so persona traits read as
 * "Mom" / "Pizza Place" rather than "+1415…".
 */
@Singleton
class SmsImporter @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    data class Outcome(val ok: Boolean, val messages: List<MessageRecord>, val detail: String? = null)

    suspend fun import(): Outcome = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Outcome(
                ok = false,
                messages = emptyList(),
                detail = "READ_SMS permission not granted",
            )
        }

        val rows = mutableListOf<MessageRecord>()
        rows.addAll(query(Telephony.Sms.Inbox.CONTENT_URI, isFromUser = false))
        rows.addAll(query(Telephony.Sms.Sent.CONTENT_URI, isFromUser = true))

        val capped = rows
            .sortedByDescending { it.tsMillis }
            .take(MAX_MESSAGES)
        Outcome(ok = true, messages = capped)
    }

    private fun query(uri: Uri, isFromUser: Boolean): List<MessageRecord> {
        val out = mutableListOf<MessageRecord>()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        ctx.contentResolver.query(uri, projection, null, null, "${Telephony.Sms.DATE} DESC LIMIT $PER_BOX_CAP")?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (c.moveToNext()) {
                val phone = c.getString(addrIdx).orEmpty()
                val body = c.getString(bodyIdx).orEmpty()
                if (body.isBlank()) continue
                val ts = c.getLong(dateIdx)
                val contact = resolveContact(phone).ifBlank { phone }
                out.add(
                    MessageRecord(
                        source = "sms",
                        tsMillis = ts,
                        isFromUser = isFromUser,
                        contact = contact.ifBlank { null },
                        text = body,
                    ),
                )
            }
        }
        return out
    }

    /** Best-effort phone-number → contact-name lookup. Empty on miss or no permission. */
    private fun resolveContact(phone: String): String {
        if (phone.isBlank()) return ""
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return ""
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phone),
        )
        ctx.contentResolver.query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                return c.getString(0).orEmpty()
            }
        }
        return ""
    }

    companion object {
        /** Per-box (inbox / sent) cap — caps total at 2 × this after merge. */
        private const val PER_BOX_CAP = 2_000
        /** Hard cap on what we hand to the persona extractor. */
        private const val MAX_MESSAGES = 3_000
    }
}
