package com.mythara.audit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.mythara.data.FavoritesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a phone number to a human-readable contact name so the
 * audit log can render "send_sms_direct → Mom (+15551234567)" instead
 * of the bare digit string.
 *
 * Two-step lookup with no-cost short-circuiting:
 *   1. FavoritesStore — the user's curated list lives in-process,
 *      no permission needed, no IPC. Match by canonicalised digits.
 *   2. ContactsContract.PhoneLookup — the system address book.
 *      Requires READ_CONTACTS (already granted for ReadContactTool);
 *      if not granted, this step is silently skipped.
 *
 * Returns null when neither source has a match. The audit logger
 * stores the result alongside the phone so renderers can decide
 * whether to show "Mom (+1…)" vs just "+1…".
 *
 * Lookup is cheap (small in-memory list + a single ContentProvider
 * query) and runs on the IO dispatcher inside AuditLogger.
 */
@Singleton
class ContactLookup @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val favorites: FavoritesStore,
) {
    /**
     * @return the display name matching this phone, or null when no
     *         match exists OR when the phone string is empty/non-digit.
     */
    suspend fun nameForPhone(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) return null

        // Step 1: favorites match. Cheapest path — in-process list.
        // Match strategy: digit-suffix (e.g. "5551234567" matches
        // "+15551234567" stored as the favorite phone) so country-
        // code differences don't miss obvious matches.
        runCatching { favorites.list() }.getOrDefault(emptyList()).forEach { fav ->
            val favDigits = fav.digits
            if (favDigits.isNotEmpty() && digitsMatch(digits, favDigits)) {
                return fav.name
            }
        }

        // Step 2: system address book. Requires READ_CONTACTS;
        // silently skipped when not granted so we don't crash the
        // log path on a permission gap.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phone),
        )
        return runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
            }
        }.getOrNull()
    }

    /**
     * Match digit strings either as exact equality or as
     * suffix-containment in either direction. Handles the common
     * country-code mismatch case: a saved contact "+1 555 123 4567"
     * (digits 15551234567) should match a tool call to "5551234567"
     * and vice versa.
     */
    private fun digitsMatch(a: String, b: String): Boolean {
        if (a == b) return true
        // Suffix containment: the shorter must be a suffix of the
        // longer, with a minimum overlap of 7 digits to avoid
        // false matches on short numbers (PIN codes etc.).
        val (longer, shorter) = if (a.length >= b.length) a to b else b to a
        if (shorter.length < 7) return false
        return longer.endsWith(shorter)
    }
}
