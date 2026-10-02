package com.peaceantz.stagescope.shared.actions

import com.peaceantz.stagescope.shared.show.EmailAddress
import com.peaceantz.stagescope.shared.util.Hashing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The canonical form of "what exactly will be done", hashed. A confirmation is only valid for the
 * hash it was given: changing the recipients, subject, body, account, calendar, time zone, start
 * or end -- anything an executor would send -- changes the hash and invalidates the approval.
 *
 * Informational fields (assumption notes, labels) are excluded on purpose: editing them cannot
 * change what is sent, so it must not silently void a review either way.
 */
object ConfirmationBinding {

    fun contentHash(draft: ActionDraft): String = Hashing.sha256Hex(canonical(draft).toString())

    fun canonical(draft: ActionDraft): JsonObject = when (draft) {
        is EmailDraft -> buildJsonObject {
            put("k", "email")
            put("purpose", draft.purpose.name)
            put("from", draft.senderAccount?.trim()?.lowercase() ?: "")
            put("to", addresses(draft.to))
            put("cc", addresses(draft.cc))
            put("bcc", addresses(draft.bcc))
            put("subject", draft.subject)
            put("body", draft.body)
        }
        is CalendarDraft -> buildJsonObject {
            put("k", "calendar")
            put("eventId", draft.eventId)
            put("account", draft.accountEmail?.trim()?.lowercase() ?: "")
            put("calendarId", draft.calendarId)
            put("title", draft.title)
            put("tz", draft.timezoneId)
            put("start", draft.startLocal)
            put("startOffset", draft.startOffset)
            put("end", draft.endLocal)
            put("endOffset", draft.endOffset)
            put("allDay", draft.allDay)
            put("location", draft.location ?: "")
            put("description", draft.description ?: "")
            put("invitees", addresses(draft.invitees))
        }
        is KeepDraft -> buildJsonObject {
            put("k", "keep")
            put("item", draft.itemText)
            put("list", draft.listName ?: "")
        }
        is IssueLogDraft -> buildJsonObject {
            put("k", "issue")
            put("issueId", draft.issueId)
        }
    }

    /** Case-insensitive on the address, sorted: reordering recipients is not a content change. */
    private fun addresses(list: List<EmailAddress>): JsonArray =
        JsonArray(list.map { it.address.trim().lowercase() }.distinct().sorted().map { JsonPrimitive(it) })

    /** True only if [confirmation] was issued for exactly this record as it stands now. */
    fun isValidFor(record: ActionRecord, confirmation: Confirmation?, nowEpochMs: Long): Boolean =
        confirmation != null &&
            confirmation.actionId == record.actionId &&
            confirmation.revision == record.revision &&
            confirmation.contentHash == record.contentHash &&
            nowEpochMs <= confirmation.expiresAtEpochMs
}
