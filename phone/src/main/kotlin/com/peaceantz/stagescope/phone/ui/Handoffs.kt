package com.peaceantz.stagescope.phone.ui

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.peaceantz.stagescope.shared.actions.CalendarDraft
import com.peaceantz.stagescope.shared.actions.EmailDraft
import com.peaceantz.stagescope.shared.actions.KeepDraft
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Prepared routes into other apps. None of these completes anything: they open the person's own
 * composer/editor with the text filled in, and the person finishes it there. StageScope then records
 * only "opened on phone" -- completion is something the person marks, never something inferred.
 */
object Handoffs {
    const val KEEP_PACKAGE = "com.google.android.keep"
    const val GEMINI_PACKAGE = "com.google.android.apps.bard"

    fun emailIntent(d: EmailDraft): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, d.to.map { it.address }.toTypedArray())
        if (d.cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, d.cc.map { it.address }.toTypedArray())
        if (d.bcc.isNotEmpty()) putExtra(Intent.EXTRA_BCC, d.bcc.map { it.address }.toTypedArray())
        putExtra(Intent.EXTRA_SUBJECT, d.subject)
        putExtra(Intent.EXTRA_TEXT, d.body)
    }

    /** Millis for the draft's start/end, or null if the persisted local time / offset can't be parsed. */
    fun calendarWindow(d: CalendarDraft): Pair<Long, Long>? = runCatching {
        if (d.allDay) {
            // Calendar's all-day events are UTC-midnight based.
            val start = LocalDate.parse(d.startLocal.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = LocalDate.parse(d.endLocal.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            start to end
        } else {
            OffsetDateTime.parse(d.startLocal + ":00" + d.startOffset).toInstant().toEpochMilli() to
                OffsetDateTime.parse(d.endLocal + ":00" + d.endOffset).toInstant().toEpochMilli()
        }
    }.getOrNull()

    fun calendarIntent(d: CalendarDraft): Intent? {
        val (begin, end) = calendarWindow(d) ?: return null
        return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, d.title)
            d.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            d.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, d.allDay)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, d.timezoneId)
            if (d.invitees.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, d.invitees.joinToString(",") { it.address })
        }
    }

    /** Shares the text to Keep as a *new note* -- Keep has no supported way to append to an existing list. */
    fun keepShareIntent(d: KeepDraft): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(KEEP_PACKAGE)
        putExtra(Intent.EXTRA_TEXT, d.itemText)
        d.listName?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }

    fun genericShare(text: String, subject: String? = null): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        },
        null,
    )
}
