package com.peaceantz.stagescope.shared.issues

import com.peaceantz.stagescope.shared.util.StageScopeJson
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Export formats for the issue log (share sheet / file). Pure text; no I/O. */
object IssueExport {

    fun toJson(views: List<IssueView>): String =
        StageScopeJson.encodeToString(ListSerializer(IssueView.serializer()), views)

    fun toCsv(views: List<IssueView>, productionName: (String?) -> String? = { null }, performanceName: (String?) -> String? = { null }): String {
        val header = listOf(
            "id", "production", "performance", "occurred_at_utc", "status", "equipment", "channel", "severity",
            "description", "original_observation", "attempted_fix", "resolution_note", "has_conflict",
        )
        val rows = views.map { v ->
            listOf(
                v.id, productionName(v.productionId).orEmpty(), performanceName(v.performanceId).orEmpty(),
                v.occurredAtEpochMs?.let(::isoUtc).orEmpty(), v.statusLabel(), v.equipment.orEmpty(), v.channel.orEmpty(),
                v.severity?.name.orEmpty(), v.description, v.originalObservation, v.attemptedFix.orEmpty(),
                v.resolutionNote.orEmpty(), if (v.conflicts.isNotEmpty()) "yes" else "",
            )
        }
        return (listOf(header) + rows).joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } } + "\r\n"
    }

    fun toMarkdown(
        views: List<IssueView>,
        title: String = "Issue log",
        productionName: (String?) -> String? = { null },
        performanceName: (String?) -> String? = { null },
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        if (views.isEmpty()) appendLine("_No issues logged._")
        for (v in views) {
            appendLine("## ${v.description}")
            appendLine("- Status: ${v.statusLabel()}")
            productionName(v.productionId)?.let { appendLine("- Production: $it") }
            performanceName(v.performanceId)?.let { appendLine("- Performance: $it") }
            v.occurredAtEpochMs?.let { appendLine("- Occurred: ${isoUtc(it)} (UTC)") }
            v.equipment?.let { appendLine("- Equipment: $it") }
            v.channel?.let { appendLine("- Channel: $it") }
            v.severity?.let { appendLine("- Severity: ${it.name.lowercase()}") }
            v.attemptedFix?.let { appendLine("- Attempted fix: $it") }
            v.resolutionNote?.let { appendLine("- Resolution note: $it") }
            appendLine("- Original observation: \"${v.originalObservation}\"")
            if (v.conflicts.isNotEmpty()) appendLine("- ⚠ Conflicting edits need review: ${v.conflicts.joinToString { it.field.name.lowercase() }}")
            appendLine()
        }
    }

    private fun csvCell(raw: String): String {
        // Neutralise spreadsheet formula injection as well as quoting: a cell starting with = + - @
        // would otherwise be executed by Excel/Sheets when an exported log is opened.
        val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun isoUtc(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
}
