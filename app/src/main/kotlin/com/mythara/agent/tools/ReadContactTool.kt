package com.mythara.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `read_contact` — look up a saved contact by partial-name match.
 * Returns up to [MAX_RESULTS] matches with their phone numbers and
 * emails. Used by the model to resolve "text my mom" → an actual
 * number before the next step (which today opens the SMS composer).
 *
 * Permission: READ_CONTACTS. We return a structured permission_denied
 * error if not granted.
 *
 * Read-only — no ConfirmationGate needed.
 */
@Singleton
class ReadContactTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable
    data class Phone(val number: String, val type: String? = null)

    @Serializable
    data class Email(val address: String, val type: String? = null)

    @Serializable
    data class Contact(
        val displayName: String,
        val phones: List<Phone> = emptyList(),
        val emails: List<Email> = emptyList(),
    )

    @Serializable
    data class Response(val count: Int, val contacts: List<Contact>)

    override val name: String = "read_contact"
    override val description: String =
        "Look up a contact in the user's address book by name. " +
            "Returns up to 5 best-match contacts with their phone numbers and emails. " +
            "Use when the user asks 'text mom', 'call John', or any task that needs to resolve a name to a number/email."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Name fragment to search for. Case-insensitive substring match against display name (so 'mom' will find 'Mom' or 'Mom (Cell)').",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("name"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"Contacts permission isn't granted. Open Settings → Apps → Mythara → Permissions and allow Contacts."}""",
            )
        }
        val name = (args["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (name.isEmpty()) {
            return ToolResult(
                ok = false,
                output = """{"error":"missing_name","detail":"Pass a 'name' string to search for."}""",
            )
        }

        val contacts = withContext(Dispatchers.IO) { queryContacts(name) }
        return ToolResult(
            ok = true,
            output = JSON.encodeToString(
                Response.serializer(),
                Response(count = contacts.size, contacts = contacts),
            ),
        )
    }

    private fun queryContacts(name: String): List<Contact> {
        val cr = ctx.contentResolver
        // First find contact ids matching the name fragment, ordered by
        // display name. LIKE %name% is the simplest match — Contacts2's
        // own FILTER_URI would also work but its scoring vs visible
        // suggestions can surprise the model.
        val contactIds = mutableListOf<Pair<Long, String>>()
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ? COLLATE NOCASE",
            arrayOf("%$name%"),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $MAX_RESULTS",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (c.moveToNext()) {
                contactIds.add(c.getLong(idIdx) to (c.getString(nameIdx) ?: "(no name)"))
            }
        }
        if (contactIds.isEmpty()) return emptyList()

        return contactIds.map { (id, displayName) ->
            val phones = queryPhonesForContact(id)
            val emails = queryEmailsForContact(id)
            Contact(displayName = displayName, phones = phones, emails = emails)
        }
    }

    private fun queryPhonesForContact(contactId: Long): List<Phone> {
        val out = mutableListOf<Phone>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
            while (c.moveToNext()) {
                val type = when (c.getInt(typIdx)) {
                    ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                    ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                    ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "main"
                    else -> null
                }
                out.add(Phone(number = c.getString(numIdx).orEmpty(), type = type))
            }
        }
        return out
    }

    private fun queryEmailsForContact(contactId: Long): List<Email> {
        val out = mutableListOf<Email>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE,
            ),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val typIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE)
            while (c.moveToNext()) {
                val type = when (c.getInt(typIdx)) {
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "home"
                    ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "work"
                    else -> null
                }
                out.add(Email(address = c.getString(addrIdx).orEmpty(), type = type))
            }
        }
        return out
    }

    companion object {
        private const val MAX_RESULTS = 5
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
