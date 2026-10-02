package com.peaceantz.stagescope.phone.google

import android.app.PendingIntent
import com.peaceantz.stagescope.phone.data.PhoneData

/** The Google features StageScope can be granted, each with only the scopes it needs. */
enum class GoogleFeature(val label: String, val scopes: List<String>) {
    GMAIL("Gmail (send only)", GoogleScopes.GMAIL_FEATURE),
    CALENDAR("Calendar (create events)", GoogleScopes.CALENDAR_FEATURE),
    /** Optional: lets the person pick which of their own calendars events go to. */
    CALENDAR_LIST("Calendar list (choose a calendar)", GoogleScopes.CALENDAR_PICKER + GoogleScopes.OPENID + GoogleScopes.EMAIL),
}

sealed interface ConnectResult {
    data class Connected(val email: String) : ConnectResult
    data class NeedsConsent(val pendingIntent: PendingIntent) : ConnectResult
    data class Failed(val message: String) : ConnectResult
}

/**
 * Connect / disconnect Gmail and Calendar. Authorization is requested per feature (incremental), and
 * the account a grant belongs to is recorded so every confirmation can be bound to it. StageScope
 * never stores an access token: Google's Authorization API caches and refreshes them itself.
 */
class GoogleService(
    private val data: PhoneData,
    private val authorizer: GoogleAuthorizer,
    private val calendar: CalendarClient,
    private val onChanged: suspend () -> Unit,
) {
    suspend fun connect(feature: GoogleFeature): ConnectResult {
        return when (val t = authorizer.accessToken(feature.scopes)) {
            is TokenResult.NeedsConsent ->
                t.pendingIntent?.let { ConnectResult.NeedsConsent(it) }
                    ?: ConnectResult.Failed("Google needs your permission but couldn't show its screen. Try again.")
            is TokenResult.Unavailable -> ConnectResult.Failed(t.reason)
            is TokenResult.Token -> {
                // Without a verified identity we couldn't bind confirmations to an account, so don't pretend it's connected.
                val id = t.identity ?: return ConnectResult.Failed("Google gave access but didn't say which account this is. Try connecting again.")
                data.settings.update { s ->
                    // A different account than before: its earlier grants don't carry over, so clear them rather
                    // than show "connected" for an account the next confirmation wouldn't use.
                    val switched = s.googleAccountId != null && s.googleAccountId != id.accountId
                    val base = if (switched) s.copy(gmailGranted = false, calendarGranted = false, calendarListGranted = false) else s
                    val withAccount = base.copy(googleAccountEmail = id.email, googleAccountId = id.accountId)
                    when (feature) {
                        GoogleFeature.GMAIL -> withAccount.copy(gmailGranted = true)
                        GoogleFeature.CALENDAR -> withAccount.copy(calendarGranted = true)
                        GoogleFeature.CALENDAR_LIST -> withAccount.copy(calendarListGranted = true)
                    }
                }
                onChanged()
                ConnectResult.Connected(id.email)
            }
        }
    }

    suspend fun disconnect(feature: GoogleFeature) {
        authorizer.revoke(feature.scopes)
        data.settings.update { s ->
            val n = when (feature) {
                GoogleFeature.GMAIL -> s.copy(gmailGranted = false)
                GoogleFeature.CALENDAR -> s.copy(calendarGranted = false)
                GoogleFeature.CALENDAR_LIST -> s.copy(calendarListGranted = false, defaultCalendarId = "primary", defaultCalendarLabel = "Primary calendar")
            }
            if (!n.gmailGranted && !n.calendarGranted && !n.calendarListGranted) n.copy(googleAccountEmail = null, googleAccountId = null) else n
        }
        onChanged()
    }

    /** Owned calendars, for the picker. Null when consent is still needed (call [connect] with CALENDAR_LIST). */
    suspend fun calendars(): List<CalendarInfo>? {
        val t = authorizer.accessToken(GoogleFeature.CALENDAR_LIST.scopes) as? TokenResult.Token ?: return null
        return runCatching { calendar.listCalendars(t.accessToken) }.getOrNull()
    }

    suspend fun chooseCalendar(info: CalendarInfo) {
        data.settings.update { it.copy(defaultCalendarId = info.id, defaultCalendarLabel = info.summary) }
    }
}
