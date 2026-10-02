package com.peaceantz.stagescope.shared.show

import kotlinx.serialization.Serializable

@Serializable
data class EmailAddress(val address: String, val name: String? = null) {
    /** `Name <addr>` or `addr`, for display and header building. */
    fun display(): String = if (name.isNullOrBlank()) address else "$name <$address>"
}

object EmailValidator {
    // Pragmatic, not RFC-complete: one @, a dotted domain, no spaces or angle brackets. This is a
    // guard against typos and injected header text, not a deliverability check.
    private val PATTERN = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$")

    fun isValid(address: String): Boolean =
        address.length in 6..254 && '\r' !in address && '\n' !in address && PATTERN.matches(address.trim())

    fun normalize(address: String): String = address.trim().lowercase()
}

/** A person StageScope may email. [verified] means the owner confirmed the address in setup. */
@Serializable
data class Contact(
    val id: String,
    val name: String,
    val email: String,
    val verified: Boolean = false,
    val role: String? = null,
)

@Serializable
data class RecipientGroup(
    val id: String,
    val name: String,
    val contactIds: List<String> = emptyList(),
)

/** A section of the performance report; [enabled] sections appear, empty ones are omitted or marked. */
@Serializable
data class ReportSection(val id: String, val title: String, val enabled: Boolean = true, val guidance: String = "")

@Serializable
enum class PerformanceStatus { UPCOMING, IN_PROGRESS, COMPLETED, CANCELLED }

@Serializable
data class WritingPreferences(
    val tone: String = "clear, factual, professional",
    val length: String = "concise",
    val signature: String? = null,
    val greeting: String = "Hi all,",
)

@Serializable
data class Production(
    val id: String,
    val name: String,
    val venue: String? = null,
    /** IANA zone id; null means "this phone's current zone" -- never silently UTC. */
    val timezoneId: String? = null,
    val equipmentNotes: String? = null,
    val reportRecipientGroupId: String? = null,
    val issueRecipientContactIds: List<String> = emptyList(),
    val reportSections: List<ReportSection> = DEFAULT_REPORT_SECTIONS,
    val writing: WritingPreferences = WritingPreferences(),
    val defaultKeepList: String? = null,
    val defaultCalendarId: String? = null,
) {
    companion object {
        val DEFAULT_REPORT_SECTIONS = listOf(
            ReportSection("overall", "Overall sound", guidance = "Only what the notes actually say about the sound."),
            ReportSection("incidents", "Incidents", guidance = "Logged issues for this performance."),
            ReportSection("fixes", "Fixes", guidance = "Fixes that were attempted or applied, as recorded."),
            ReportSection("unresolved", "Unresolved issues", guidance = "Open or reopened issues only."),
            ReportSection("next", "Next-performance actions", guidance = "Follow-ups the notes or issues call for."),
        )
    }
}

/**
 * One performance of a production -- separate from the production so each night has its own
 * issues and report. [localDate]/[localTime] are local to the production's zone (ISO strings).
 */
@Serializable
data class Performance(
    val id: String,
    val productionId: String,
    val localDate: String,
    val localTime: String? = null,
    val number: Int? = null,
    val label: String? = null,
    val status: PerformanceStatus = PerformanceStatus.UPCOMING,
    val notes: String? = null,
) {
    fun displayName(): String = buildString {
        label?.takeIf { it.isNotBlank() }?.let { append(it) }
            ?: number?.let { append("Performance #$it") }
            ?: append("Performance")
        append(" · ").append(localDate)
        localTime?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
    }
}

/** Everything show-related the phone owns. The watch only ever receives [WatchShowView]. */
@Serializable
data class ShowLibrary(
    val productions: List<Production> = emptyList(),
    val performances: List<Performance> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val groups: List<RecipientGroup> = emptyList(),
    val selectedProductionId: String? = null,
    val selectedPerformanceId: String? = null,
    /** Shown on a time prompt when AM/PM is missing -- the "clearly presented user-configured default". */
    val calendarDefaults: CalendarDefaults = CalendarDefaults(),
) {
    fun production(id: String?): Production? = productions.firstOrNull { it.id == id }
    fun performance(id: String?): Performance? = performances.firstOrNull { it.id == id }
    fun selectedPerformance(): Performance? = performance(selectedPerformanceId)
}

@Serializable
data class CalendarDefaults(
    val defaultDurationMinutes: Int = 60,
    /** What to assume for an hour 1..11 with no AM/PM said: "ASK" (default), "PM" or "AM". */
    val ambiguousHourBias: String = "ASK",
    val defaultCalendarLabel: String = "Primary calendar",
)

@Serializable
data class WatchProduction(val id: String, val name: String, val venue: String?)

@Serializable
data class WatchPerformance(val id: String, val productionId: String, val label: String, val status: PerformanceStatus)

/** The compact slice of [ShowLibrary] mirrored to the watch (names and ids only, no addresses). */
@Serializable
data class WatchShowView(
    val revision: Long,
    val productions: List<WatchProduction> = emptyList(),
    val performances: List<WatchPerformance> = emptyList(),
    val selectedProductionId: String? = null,
    val selectedPerformanceId: String? = null,
)

object ShowViews {
    fun toWatchView(library: ShowLibrary, revision: Long, maxPerformances: Int = 30): WatchShowView =
        WatchShowView(
            revision = revision,
            productions = library.productions.map { WatchProduction(it.id, it.name, it.venue) },
            performances = library.performances
                .sortedWith(compareByDescending<Performance> { it.localDate }.thenByDescending { it.localTime ?: "" })
                .take(maxPerformances)
                .map { WatchPerformance(it.id, it.productionId, it.displayName(), it.status) },
            selectedProductionId = library.selectedProductionId,
            selectedPerformanceId = library.selectedPerformanceId,
        )
}
